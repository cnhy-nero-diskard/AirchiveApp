package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.ThinQPort
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlin.time.Clock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One-at-a-time AC command submission and per-command UI state for exactly one
 * configured device (OpenSpec task 4.4).
 *
 * ## Serialization contract
 *
 * [submit] is **non-queueing**: at most one command is ever in flight. A
 * submission grabs the internal [Mutex] with `tryLock`; if another submission
 * already holds it, the call returns [AcCommandSubmitResult.IgnoredWhilePending]
 * immediately **without touching the port**, so a duplicate tap can never invoke
 * [ThinQPort.setState] twice. The lock is always released in `finally`; a
 * concurrent submission is deterministically the one that wins the lock and the
 * rest are rejected, never queued or interleaved.
 *
 * ## Publish order and terminal mapping
 *
 * [submit] publishes [AcCommandUiState.Sending] **before** the port call, then
 * maps every returned [CommandStatus] exactly once to its terminal state:
 *
 * - [CommandStatus.ACCEPTED] → [AcCommandUiState.Accepted] — stays accepted (no
 *   automatic follow-up; [KtorThinQClient] has already performed its own bounded
 *   fresh confirmation inside [ThinQPort.setState] before returning, and any
 *   extra confirmation path is task 4.5, not this controller).
 * - [CommandStatus.CONFIRMED] → [AcCommandUiState.Confirmed].
 * - [CommandStatus.FAILED] → [AcCommandUiState.Failed].
 * - [CommandStatus.UNCONFIRMED] → [AcCommandUiState.Unconfirmed].
 * - [CommandStatus.SENDING] returned by the port is a protocol violation (the
 *   port contract only ever returns ACCEPTED/CONFIRMED/FAILED/UNCONFIRMED) and
 *   fails safely to [AcCommandUiState.Failed] with
 *   [CommandFailure.UNKNOWN] plus a fixed safe message.
 *
 * The controller **never retries or resends** a command for any reason, and an
 * [AcCommandUiState.Accepted] is retained until the caller explicitly submits a
 * new command or a later task wires up its own confirmation refresh.
 *
 * ## Failure and cancellation safety
 *
 * - [CancellationException] during the port call is rethrown, but **first** the
 *   in-flight [AcCommandUiState.Sending] is replaced with
 *   [AcCommandUiState.Unconfirmed] with [CommandFailure.UNKNOWN] and a fixed
 *   message asking the owner to refresh state before retrying — the command may
 *   already have gone out, so it is never auto-resent.
 * - [ThinQClientException.Network] / [ThinQClientException.Timeout] are
 *   ambiguous (the request may or may not have reached LG): they become
 *   [AcCommandUiState.Unconfirmed] with [CommandFailure.NETWORK] /
 *   [CommandFailure.TIMEOUT] and fixed safe text.
 * - Other explicit [ThinQClientException] subtypes map to
 *   [AcCommandUiState.Failed] with a stable [CommandFailure] (see
 *   [ThinQClientException.thinQCommandFailure]); a generic exception becomes
 *   [AcCommandUiState.Unconfirmed] with [CommandFailure.UNKNOWN], conservative
 *   about a possible send. No raw exception message or token text is ever
 *   retained or published.
     * - A returned [CommandOutcome] whose [CommandOutcome.deviceId] is not the
     *   configured device or whose [CommandOutcome.command] differs from the
     *   submitted command is rejected and replaced with a fresh FAILED /
     *   [CommandFailure.UNKNOWN] outcome bound to the configured device/request.
 *
 * ## Lifecycle fencing (OpenSpec `deliver-shared-instrument-ui` tasks 2.1/2.3)
 *
 * [submit] captures its [ThinQContextKey] (from the tap-time caller or at
 * entry) and fences the whole operation with it: a superseded context is
 * refused before the slot is taken and again immediately before dispatch, and
 * every state publication happens inside one [ThinQLifecycleBoundary] section
 * with a current-key check. An already-dispatched command whose context ends
 * mid-flight keeps its honest outcome path (it may have gone out and is never
 * resent), but its late result is discarded instead of becoming the new
 * context's feedback.
 *
 * ## Non-goals
 *
 * This controller issues no Firestore calls, no profile reads, and no
 * historical-telemetry writes; it only serializes UI submissions and reports
 * their outcome. Commands are all-or-nothing: the requested [AcCommand] is
 * passed to [ThinQPort.setState] **exactly once** (or zero times when
 * [AcCommandSubmitResult.IgnoredWhilePending] or
 * [AcCommandSubmitResult.IgnoredStaleContext] is returned) and never retried.
 *
 * @param lifecycle shared lifecycle boundary; `null` (demo/preview graphs)
 *   means a single context that can never be invalidated.
 */
class AcCommandController(
    private val thinQ: ThinQPort,
    private val configuredDeviceId: String,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val lifecycle: ThinQLifecycleBoundary? = null,
) {

    init {
        require(ThinQPayloads.isSinglePathSegment(configuredDeviceId)) {
            "AcCommandController requires the exact configured ThinQ device id."
        }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AcCommandUiState>(AcCommandUiState.Idle)
    private val mutableSlotState = MutableStateFlow<AcCommandSlotState>(AcCommandSlotState.Idle)

    /** Observable command state; starts at [AcCommandUiState.Idle]. */
    val state: StateFlow<AcCommandUiState> = mutableState.asStateFlow()

    /** Device-global slot state, retained across context invalidation. */
    val slotState: StateFlow<AcCommandSlotState> = mutableSlotState.asStateFlow()

    /**
     * Submits one command for exactly [configuredDeviceId].
     *
     * Non-queueing: if another submission is in flight this returns
     * [AcCommandSubmitResult.IgnoredWhilePending] immediately and
     * [ThinQPort.setState] is **not** invoked. Otherwise [AcCommandUiState.Sending]
     * is published, the port is called exactly once for [command], the returned
     * [CommandOutcome] (or a safe failure state) is published, and
     * [AcCommandSubmitResult.Submitted] is returned. A new submission is always
     * allowed once the previous one is terminal; only the in-flight window is
     * exclusive.
     *
     * ## Lifecycle fencing (tasks 2.1/2.3)
     *
     * [contextKey] is the context captured when the owner tapped the control
     * (`null` captures the current context here). It is checked before taking
     * the submission slot, rechecked immediately before dispatch, and used to
     * fence the [AcCommandUiState.Sending] and terminal publications:
     *
     *  - a command whose context ended **before dispatch** returns
     *    [AcCommandSubmitResult.IgnoredStaleContext] without ever reaching the
     *    port, so scheduled stale work can never use a replacement credential;
     *  - an already-dispatched command keeps its honest path (it may have been
     *    sent and is never resent), but its late outcome is discarded when the
     *    context ended, so it cannot become the new context's feedback.
     */
    suspend fun submit(
        command: AcCommand,
        contextKey: ThinQContextKey? = null,
        afterSubmitted: suspend () -> Unit = {},
    ): AcCommandSubmitResult {
        val captured = contextKey ?: lifecycle?.current()
        // Dispatch fence — a superseded operation never sends.
        if (!lifecycle.isCurrentContext(captured)) return AcCommandSubmitResult.IgnoredStaleContext
        if (!mutex.tryLock()) return AcCommandSubmitResult.IgnoredWhilePending
        mutableSlotState.value = AcCommandSlotState.Occupied(captured)
        return try {
            submitWithHeldSlot(command, captured, afterSubmitted)
        } finally {
            releaseSlot()
        }
    }

    /**
     * Synchronously reserves the same internal slot, then launches the command
     * body in a graph-lifetime [scope]. This is the production route-independent
     * entry point (task 2.5): no second mutex or channel exists, and a busy tap
     * is rejected before any coroutine can wait and later become a queued send.
     */
    fun dispatch(
        command: AcCommand,
        contextKey: ThinQContextKey?,
        scope: CoroutineScope,
        afterSubmitted: suspend () -> Unit = {},
    ): AcCommandDispatchResult {
        if (!scope.isActive) return AcCommandDispatchResult.ScopeClosed
        val captured = contextKey ?: lifecycle?.current()
        if (!lifecycle.isCurrentContext(captured)) return AcCommandDispatchResult.IgnoredStaleContext
        if (!mutex.tryLock()) return AcCommandDispatchResult.IgnoredWhilePending
        mutableSlotState.value = AcCommandSlotState.Occupied(captured)

        var started = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            started = true
            try {
                submitWithHeldSlot(command, captured, afterSubmitted)
            } finally {
                releaseSlot()
            }
        }
        if (!started) {
            releaseSlot()
            return AcCommandDispatchResult.ScopeClosed
        }
        return AcCommandDispatchResult.Scheduled
    }

    /** Runs the accepted request while the one device-global mutex is held. */
    private suspend fun submitWithHeldSlot(
        command: AcCommand,
        captured: ThinQContextKey?,
        afterSubmitted: suspend () -> Unit,
    ): AcCommandSubmitResult {
        val publishedSending = lifecycle.publishIfCurrent(captured) {
            mutableState.value = AcCommandUiState.Sending(command = command)
        }
        if (!publishedSending) return AcCommandSubmitResult.IgnoredStaleContext
        // Recheck right before the port call (no boundary is held across it).
        if (!lifecycle.isCurrentContext(captured)) return AcCommandSubmitResult.IgnoredStaleContext

        val outcome = try {
            // The port may suspend while reading the protected credential.
            // Capture one opaque operation-bound port first, then recheck the
            // tap-time context before the actual dispatch. The bound port keeps
            // POST + bounded confirmation on the captured PAT.
            // Capture credentials atomically with the lifecycle key. The
            // capture is a protected-store read only (never LG I/O); holding
            // the short boundary section prevents a replacement from mixing
            // old token/new country before the final dispatch fence.
            val operationPort = if (lifecycle == null) {
                thinQ.captureOperationContext()
            } else {
                lifecycle.withBoundary {
                    if (lifecycle.isCurrent(captured)) thinQ.captureOperationContext() else null
                } ?: return AcCommandSubmitResult.IgnoredStaleContext
            }
            if (!lifecycle.isCurrentContext(captured)) return AcCommandSubmitResult.IgnoredStaleContext
            operationPort.setState(command)
        } catch (cancelled: CancellationException) {
            // The command may have gone out before cancellation was observed:
            // replace Sending with Unconfirmed (unless the context ended —
            // then the invalidation already reset the state) and keep
            // cancellation propagating.
            withContext(NonCancellable) {
                publishTerminal(
                    captured,
                    AcCommandUiState.Unconfirmed(
                        outcome = controllerOutcome(
                            command, CommandStatus.UNCONFIRMED, CommandFailure.UNKNOWN, MESSAGE_CANCELLED,
                        ),
                    ),
                )
            }
            throw cancelled
        } catch (network: ThinQClientException.Network) {
            publishTerminal(
                captured,
                AcCommandUiState.Unconfirmed(
                    outcome = controllerOutcome(
                        command, CommandStatus.UNCONFIRMED, CommandFailure.NETWORK, MESSAGE_AMBIGUOUS_NETWORK,
                    ),
                ),
            )
            afterSubmitted()
            return AcCommandSubmitResult.Submitted
        } catch (timeout: ThinQClientException.Timeout) {
            publishTerminal(
                captured,
                AcCommandUiState.Unconfirmed(
                    outcome = controllerOutcome(
                        command, CommandStatus.UNCONFIRMED, CommandFailure.TIMEOUT, MESSAGE_AMBIGUOUS_TIMEOUT,
                    ),
                ),
            )
            afterSubmitted()
            return AcCommandSubmitResult.Submitted
        } catch (typed: ThinQClientException) {
            publishTerminal(
                captured,
                AcCommandUiState.Failed(
                    outcome = controllerOutcome(
                        command, CommandStatus.FAILED, typed.thinQCommandFailure(), MESSAGE_TYPED_FAILURE,
                    ),
                ),
            )
            afterSubmitted()
            return AcCommandSubmitResult.Submitted
        } catch (generic: Exception) {
            // Conservative about a possible send: UNCONFIRMED/UNKNOWN, and the
            // throwable's text never reaches a published outcome or state.
            publishTerminal(
                captured,
                AcCommandUiState.Unconfirmed(
                    outcome = controllerOutcome(
                        command, CommandStatus.UNCONFIRMED, CommandFailure.UNKNOWN, MESSAGE_AMBIGUOUS_GENERIC,
                    ),
                ),
            )
            afterSubmitted()
            return AcCommandSubmitResult.Submitted
        }

        val validated = outcome.ifUnexpectedDeviceUseFailure(command)
        val terminal = when (validated.status) {
            CommandStatus.CONFIRMED -> AcCommandUiState.Confirmed(validated)
            CommandStatus.ACCEPTED -> AcCommandUiState.Accepted(validated)
            CommandStatus.FAILED -> AcCommandUiState.Failed(validated.ensureFailure())
            CommandStatus.UNCONFIRMED -> AcCommandUiState.Unconfirmed(validated.ensureFailure())
            CommandStatus.SENDING -> AcCommandUiState.Failed(
                outcome = controllerOutcome(
                    command, CommandStatus.FAILED, CommandFailure.UNKNOWN, MESSAGE_UNEXPECTED_SENDING,
                ),
            )
        }
        publishTerminal(captured, terminal)
        afterSubmitted()
        return AcCommandSubmitResult.Submitted
    }

    /** Release the slot and its neutral UI projection before a new tap can acquire it. */
    private fun releaseSlot() {
        mutableSlotState.value = AcCommandSlotState.Idle
        mutex.unlock()
    }

    /**
     * Publishes a terminal/starting command state only while [captured] is
     * still the current context. When the context ended, the invalidation has
     * already reset presentation and this write is dropped.
     */
    private suspend fun publishTerminal(captured: ThinQContextKey?, state: AcCommandUiState) {
        lifecycle.publishIfCurrent(captured) { mutableState.value = state }
    }

    /**
     * Invalidates presentation because the lifecycle context changed: resets to
     * [AcCommandUiState.Idle] so no superseded command feedback survives.
     *
     * Called by the lifecycle owner **while holding the lifecycle boundary**;
     * deliberately a direct state write that never takes [mutex], so
     * invalidation never waits for a command suspended in the port. A still
     * in-flight submission keeps its slot (it is never resent) and its late
     * outcome is discarded by the same fence.
     */
    fun resetForContextChange() {
        mutableState.value = AcCommandUiState.Idle
    }

    /** Rejects a returned outcome for any other device or any other command. */
    private fun CommandOutcome.ifUnexpectedDeviceUseFailure(requested: AcCommand): CommandOutcome =
        if (deviceId == configuredDeviceId && command == requested) {
            this
        } else {
            controllerOutcome(
                requested, CommandStatus.FAILED, CommandFailure.UNKNOWN, MESSAGE_UNEXPECTED_OUTCOME,
            )
        }

    /**
     * A FAILED/UNCONFIRMED outcome must always carry a stable failure;
     * an outcome that omitted one is graded [CommandFailure.UNKNOWN] with a
     * fixed safe message rather than surfacing `null`.
     */
    private fun CommandOutcome.ensureFailure(): CommandOutcome =
        if ((status == CommandStatus.FAILED || status == CommandStatus.UNCONFIRMED) && failure == null) {
            copy(failure = CommandFailure.UNKNOWN, message = MESSAGE_MISSING_FAILURE)
        } else {
            this
        }

    /**
     * A controller-generated outcome — used for exception paths, the
     * unexpected-SENDING violation, and wrong-device outcomes — bound to the
     * configured device and carrying only fixed, secret-free content. The
     * handset clock [nowEpochMillis] is stamped only when the command carried
     * no requested timestamp.
     */
    private fun controllerOutcome(
        command: AcCommand,
        status: CommandStatus,
        failure: CommandFailure?,
        message: String?,
    ): CommandOutcome = CommandOutcome(
        commandId = "ac-cmd-${ThinQMessageId.next()}",
        deviceId = configuredDeviceId,
        command = command,
        status = status,
        submittedAtEpochMillis = command.requestedAtEpochMillis.takeIf { it > 0L } ?: nowEpochMillis(),
        failure = failure,
        message = message,
    )
}

/**
 * Result of one [AcCommandController.submit] call.
 *
 * [Submitted] means the submission was accepted by the controller (it was
 * passed to the port, or the port call has completed with a published terminal
 * state). [IgnoredWhilePending] means another submission was already in flight
 * and this tap was rejected without any port interaction.
 */
sealed interface AcCommandSubmitResult {
    data object Submitted : AcCommandSubmitResult
    data object IgnoredWhilePending : AcCommandSubmitResult

    /**
     * The command's originating lifecycle context ended before dispatch:
     * nothing was sent, no state was published for it, and the replacement
     * context's credential is never borrowed for it (task 2.3).
     */
    data object IgnoredStaleContext : AcCommandSubmitResult
}

/** Immediate outcome when a graph-owned command job is requested from a tap. */
sealed interface AcCommandDispatchResult {
    /** The graph-owned job acquired the existing device-global slot. */
    data object Scheduled : AcCommandDispatchResult

    /** The existing slot was occupied; this tap was dropped, never queued. */
    data object IgnoredWhilePending : AcCommandDispatchResult

    /** The tap's context had already ended, so it was not sent. */
    data object IgnoredStaleContext : AcCommandDispatchResult

    /** The owning graph is closing; nothing was dispatched. */
    data object ScopeClosed : AcCommandDispatchResult
}

/** One device-global submission slot, intentionally retained across generations. */
sealed interface AcCommandSlotState {
    data object Idle : AcCommandSlotState

    /** Opaque context owner only; never contains the command target or outcome. */
    data class Occupied(val contextKey: ThinQContextKey?) : AcCommandSlotState
}

/**
 * Observable command submission state for one configured device.
 *
 * Every non-idle state carries the requested [CommandOutcome] (which in turn
 * contains the exact [AcCommand] and only safe, stable metadata); commands and
 * outcomes never contain a secret. [Sending] is published before the port is
 * reached, so the UI can disable the control while it is in flight.
 */
sealed interface AcCommandUiState {

    /** No command has been submitted yet. */
    data object Idle : AcCommandUiState

    /**
     * One command is in flight at the port; further taps are ignored until a
     * terminal state is published.
     */
    data class Sending(val command: AcCommand) : AcCommandUiState

    /**
     * LG accepted the command. It stays accepted until the caller explicitly
     * submits a new command or a later task's confirmation refresh changes it;
     * this controller never auto-resends or re-checks acceptance.
     */
    data class Accepted(val outcome: CommandOutcome) : AcCommandUiState

    /** The port accepted the command and its bounded fresh state read matched it. */
    data class Confirmed(val outcome: CommandOutcome) : AcCommandUiState

    /** The port rejected the command with a stable, secret-free [CommandFailure]. */
    data class Failed(val outcome: CommandOutcome) : AcCommandUiState

    /**
     * The command may or may not have taken effect (timeout, network ambiguity,
     * cancellation, or a non-matching fresh state read). Never auto-resent;
     * the owner is asked to refresh state before acting again.
     */
    data class Unconfirmed(val outcome: CommandOutcome) : AcCommandUiState
}

// ------------------------------------------------------------------ messages

/**
 * Fixed, secret-free literals for every state the controller itself generates.
 * These are the only texts [AcCommandController] ever publishes; a raw
 * exception message, a PAT, or LG's raw server text can never appear.
 */
internal const val MESSAGE_CANCELLED =
    "The command was interrupted; it may have been sent. It was not resent; refresh state before retrying."
internal const val MESSAGE_AMBIGUOUS_NETWORK =
    "ThinQ did not answer the control request; the command may have been accepted. It was not resent; refresh state before acting again."
internal const val MESSAGE_AMBIGUOUS_TIMEOUT =
    "ThinQ did not answer the control request before the deadline; the command may have been accepted. It was not resent; refresh state before acting again."
internal const val MESSAGE_AMBIGUOUS_GENERIC =
    "ThinQ did not confirm the command; it may have been accepted. It was not resent; refresh state before acting again."
internal const val MESSAGE_TYPED_FAILURE =
    "ThinQ rejected the command; it was not sent. Check the token or device and try again."
internal const val MESSAGE_UNEXPECTED_SENDING =
    "ThinQ reported the command as still sending; the outcome is unknown. It was not resent; refresh state before acting again."
internal const val MESSAGE_UNEXPECTED_OUTCOME =
    "ThinQ returned an outcome that did not match the submitted device or command. The outcome could not be trusted; nothing was resent."
internal const val MESSAGE_MISSING_FAILURE =
    "The command could not be completed; refresh state before acting again."

/**
 * Stable, secret-free [CommandFailure] for an explicit [ThinQClientException]
 * on the command path.
 *
 * [ThinQClientException.Network] and [ThinQClientException.Timeout] are
 * ambiguous (a send may or may not have occurred) and are handled by
 * [AcCommandController] as UNCONFIRMED before this mapping is reached; the
 * remaining typed errors always mean the request did **not** complete, so they
 * map to [AcCommandUiState.Failed] with a stable category.
 */
internal fun ThinQClientException.thinQCommandFailure(): CommandFailure = when (this) {
    is ThinQClientException.MissingCredential,
    is ThinQClientException.InvalidCredential,
    -> CommandFailure.TOKEN_REJECTED

    is ThinQClientException.UnsupportedCountry,
    is ThinQClientException.MalformedResponse,
    -> CommandFailure.UNKNOWN

    is ThinQClientException.RemoteFailure -> error.toCommandFailure()

    is ThinQClientException.Network,
    is ThinQClientException.Timeout,
    -> CommandFailure.UNKNOWN
}
