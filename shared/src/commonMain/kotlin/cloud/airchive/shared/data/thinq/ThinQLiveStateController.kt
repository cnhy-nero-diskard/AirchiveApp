package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialized live ThinQ state for exactly one configured device (OpenSpec task
 * 4.3).
 *
 * This controller is **strictly live-state-only**. Every entry ([onDeviceOpened])
 * or explicit ([refresh]) read performs a direct `liveState(configuredDeviceId)`
 * request and republishes the adapter's response; it never reads a profile, never
 * issues a command, and never touches Firestore or telemetry sync metadata. The
 * controller keeps the AC's read timestamp (the adapter's
 * [ThinQLiveState.observedAtEpochMillis]) separate from any handset time: that
 * value is passed through unchanged and is never replaced with [nowEpochMillis].
 *
 * ## State contract
 *
 * The observable [state] is a [ThinQLiveRefreshState]:
 * - [ThinQLiveRefreshState.NotRead] — no read attempted yet.
 * - [ThinQLiveRefreshState.Loading] — a read is in flight (previous snapshot kept).
 * - [ThinQLiveRefreshState.Current] — the adapter's fresh read.
 * - [ThinQLiveRefreshState.Stale] — a newer read failed; the last successful
 *   read is retained, explicitly **not** as current, with a stable failure kind.
 * - [ThinQLiveRefreshState.Unavailable] — a read failed before anything was read.
 *
 * A retained state is never presented as current: after a success, a later
 * failure always downgrades to [ThinQLiveRefreshState.Stale], never back to
 * [ThinQLiveRefreshState.Current].
 *
 * ## Lifecycle fencing (OpenSpec `deliver-shared-instrument-ui` tasks 2.1/2.3)
 *
 * Every operation captures its [ThinQContextKey] at entry (or accepts one from
 * the caller that captured it earlier, e.g. the coordinator's Device entry) and
 * then:
 *
 *  1. rechecks it immediately before dispatching the LG request, so an
 *     operation whose context ended never sends — and never borrows a
 *     replacement credential;
 *  2. publishes every snapshot inside one [ThinQLifecycleBoundary] section with
 *     a current-key check, so a late old-context result is discarded instead of
 *     repopulating cleared presentation or enabling controls.
 *
 * [resetForContextChange] is the invalidation hook: a direct state write that
 * never takes this controller's read [Mutex], so invalidation completes even
 * while a live read is suspended in the network.
 *
 * ## Safety
 *
 * - One [Mutex] serializes entry/manual reads so concurrent calls never publish
 *   a mixed snapshot; a cancelled call releases the lock and rethrows
 *   [CancellationException] untouched (cancellation is never surfaced as an
 *   error state).
 * - Failures are reduced to the stable [ThinQRefreshFailureKind] categories via
 *   [classifyRefreshFailure]; a raw exception message or token is never kept or
 *   published.
 * - Command gating is left to the Device layer: [ThinQLiveRefreshState.canIssueCommands]
 *   is `false` for every non-current state, and for [ThinQLiveRefreshState.Current]
 *   only becomes `true` when the caller separately supplies a verified profile
 *   through [hasVerifiedProfile]. This controller never infers control capability
 *   from a live-state read alone.
 *
 * The `configuredDeviceId` is fixed at construction; callers cannot request any
 * other device, so unsupported/arbitrary ids are structurally impossible here.
 *
 * @param lifecycle shared lifecycle boundary; `null` (demo/preview graphs)
 *   means a single context that can never be invalidated.
 */
class ThinQLiveStateController(
    private val thinQ: ThinQPort,
    private val configuredDeviceId: String,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val hasVerifiedProfile: () -> Boolean = { false },
    private val lifecycle: ThinQLifecycleBoundary? = null,
) {

    init {
        require(ThinQPayloads.isSinglePathSegment(configuredDeviceId)) {
            "ThinQLiveStateController requires the exact configured ThinQ device id."
        }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ThinQLiveRefreshState>(ThinQLiveRefreshState.NotRead)

    /** Observable live-state; starts at [ThinQLiveRefreshState.NotRead]. */
    val state: StateFlow<ThinQLiveRefreshState> = mutableState.asStateFlow()

    /**
     * The owner opened the Device screen. Always performs a direct live read for
     * [configuredDeviceId]; the screen/platform graph owns any auto-sync policy,
     * not this controller.
     *
     * @param contextKey context captured by a caller (the Device entry captured
     *   it before starting); `null` captures the current context here, so a
     *   follow-up read can never borrow a replacement context's credential.
     */
    suspend fun onDeviceOpened(contextKey: ThinQContextKey? = null) = refresh(contextKey)

    /**
     * Explicit manual refresh. Performs a direct live read and **always** bypasses
     * any cached or freshness gate: even immediately after a recent
     * [ThinQLiveRefreshState.Current], an entirely new request reaches the adapter.
     * Serialized with any in-flight entry/refresh read by the internal [Mutex].
     *
     * Fenced against [contextKey] (default: captured at entry): dispatch is
     * refused when that context already ended, and every publication — Loading,
     * failure, success and the cancellation restore — only happens while it is
     * still current.
     */
    suspend fun refresh(contextKey: ThinQContextKey? = null) {
        val captured = contextKey ?: lifecycle?.current()
        mutex.withLock {
            val previous = mutableState.value
            // Fence: an operation whose context ended before dispatch never sends.
            if (!lifecycle.isCurrentContext(captured)) return@withLock
            val publishedLoading = lifecycle.publishIfCurrent(captured) {
                mutableState.value = ThinQLiveRefreshState.Loading(
                    previous = previous.takeUnless { it is ThinQLiveRefreshState.NotRead },
                )
            }
            if (!publishedLoading) return@withLock
            // Fence: recheck immediately before the LG request; no boundary is
            // ever held across the network call itself.
            if (!lifecycle.isCurrentContext(captured)) return@withLock

            val live = try {
                thinQ.liveState(configuredDeviceId).also { result ->
                    if (result.deviceId != configuredDeviceId) {
                        throw ThinQClientException.MalformedResponse
                    }
                }
            } catch (cancelled: CancellationException) {
                // Cancellation is not a read failure: restore the pre-flight
                // snapshot (never a phantom Loading or an error state) — but only
                // for the same context, so a late restore cannot repopulate an
                // invalidated one — and rethrow so the cancellation keeps propagating.
                withContext(NonCancellable) {
                    lifecycle.publishIfCurrent(captured) { mutableState.value = previous }
                }
                throw cancelled
            } catch (failure: Exception) {
                val kind = classifyRefreshFailure(failure)
                val lastKnown = lastKnownState(previous)
                val next = if (lastKnown != null) {
                    ThinQLiveRefreshState.Stale(
                        lastKnownState = lastKnown,
                        errorKind = kind,
                        failedAtEpochMillis = nowEpochMillis(),
                    )
                } else {
                    ThinQLiveRefreshState.Unavailable(
                        errorKind = kind,
                        failedAtEpochMillis = nowEpochMillis(),
                    )
                }
                // Fence: a late failure from a superseded context is discarded;
                // in the unchanged context it stays the explicitly labeled
                // stale/unavailable state with commands disabled.
                lifecycle.publishIfCurrent(captured) { mutableState.value = next }
                return@withLock
            }

            val profileVerified = try {
                hasVerifiedProfile()
            } catch (_: Exception) {
                false
            }
            // Fence: a late success from a superseded context never becomes
            // current state and never enables controls.
            lifecycle.publishIfCurrent(captured) {
                mutableState.value = ThinQLiveRefreshState.Current(
                    state = live,
                    profileVerified = profileVerified,
                )
            }
        }
    }

    /**
     * Invalidates presentation because the lifecycle context changed: resets to
     * [ThinQLiveRefreshState.NotRead] so no old-context reading can be shown,
     * even by a late completion (its publication is fenced by the same key).
     *
     * Called by the lifecycle owner **while holding the lifecycle boundary**;
     * deliberately a direct state write that never takes [mutex], so
     * invalidation never waits for a read suspended in the network.
     */
    fun resetForContextChange() {
        mutableState.value = ThinQLiveRefreshState.NotRead
    }

    /**
     * Reconciles the controller with the state read already carried by one
     * command outcome. [KtorThinQClient] performs its bounded confirmation GET
     * before returning; consuming that same read here prevents a redundant
     * second LG read after every command.
     *
     * A returned, device-matching [CommandOutcome.observedState] becomes the
     * current snapshot for CONFIRMED or UNCONFIRMED outcomes (a mismatched
     * command can still have a fresh, valid read of the actual device state).
     * ACCEPTED or UNCONFIRMED without a read downgrades the last-known state to
     * stale/unavailable, because the command may have changed the AC and the
     * controller has no fresh observation to publish. A definite FAILED command
     * leaves the prior live snapshot alone because the adapter says no command
     * was sent. This method issues no network request itself.
     *
     * Every publication is fenced by [contextKey] — pass the key captured when
     * the command was submitted so an outcome from a superseded context can
     * never become the active context's live state. `null` captures the current
     * context here.
     */
    suspend fun acceptCommandOutcome(outcome: CommandOutcome, contextKey: ThinQContextKey? = null) {
        val captured = contextKey ?: lifecycle?.current()
        mutex.withLock {
            if (outcome.deviceId != configuredDeviceId) return@withLock
            when (outcome.status) {
                CommandStatus.SENDING,
                CommandStatus.FAILED,
                -> Unit

                CommandStatus.ACCEPTED,
                CommandStatus.CONFIRMED,
                CommandStatus.UNCONFIRMED,
                -> {
                    val observed = outcome.observedState
                    if (observed != null && observed.deviceId == configuredDeviceId) {
                        val lastKnown = lastKnownState(mutableState.value)
                        // Both timestamps are LG read times; preserve an even
                        // newer, concurrently completed explicit refresh.
                        if (lastKnown == null || observed.observedAtEpochMillis >= lastKnown.observedAtEpochMillis) {
                            val profileVerified = try {
                                hasVerifiedProfile()
                            } catch (_: Exception) {
                                false
                            }
                            lifecycle.publishIfCurrent(captured) {
                                mutableState.value = ThinQLiveRefreshState.Current(
                                    state = observed,
                                    profileVerified = profileVerified,
                                )
                            }
                        }
                    } else {
                        val prior = mutableState.value
                        val errorKind = outcome.failure?.toRefreshFailureKind() ?: ThinQRefreshFailureKind.UNKNOWN
                        val lastKnown = lastKnownState(prior)
                        val next = if (lastKnown != null) {
                            ThinQLiveRefreshState.Stale(
                                lastKnownState = lastKnown,
                                errorKind = errorKind,
                                failedAtEpochMillis = nowEpochMillis(),
                            )
                        } else {
                            ThinQLiveRefreshState.Unavailable(
                                errorKind = errorKind,
                                failedAtEpochMillis = nowEpochMillis(),
                            )
                        }
                        lifecycle.publishIfCurrent(captured) { mutableState.value = next }
                    }
                }
            }
        }
    }

    private fun lastKnownState(snapshot: ThinQLiveRefreshState): ThinQLiveState? = when (snapshot) {
        is ThinQLiveRefreshState.Current -> snapshot.state
        is ThinQLiveRefreshState.Stale -> snapshot.lastKnownState
        else -> null
    }
}

/**
 * Observable live ThinQ state for the single configured device.
 *
 * [canIssueCommands] is `false` for every non-current snapshot: a failed read
 * means the current capability knowledge cannot be trusted. For
 * [Current], it is `true` only when the caller separately supplied a verified
 * profile ([Current.profileVerified]). Command gating always stays in the
 * Device layer; this controller never enables controls implicitly.
 */
sealed interface ThinQLiveRefreshState {

    val canIssueCommands: Boolean get() = false

    /** No live read has been attempted yet. */
    data object NotRead : ThinQLiveRefreshState

    /**
     * A live read is in flight. [previous] is the snapshot just before this read
     * started (or `null` when nothing was known yet), so a UI can keep showing a
     * last/older label without ever claiming fresh state.
     */
    data class Loading(val previous: ThinQLiveRefreshState? = null) : ThinQLiveRefreshState

    /**
     * A live read succeeded. [state] is the adapter's response unchanged —
     * [ThinQLiveState.observedAtEpochMillis] is exactly the time the ThinQ
     * adapter read it and is never replaced by this controller's handset clock.
     * [profileVerified] is supplied by the caller (Device layer) and is the only
     * thing that can make [canIssueCommands] true.
     */
    data class Current(
        val state: ThinQLiveState,
        val profileVerified: Boolean = false,
    ) : ThinQLiveRefreshState {
        override val canIssueCommands: Boolean get() = profileVerified
    }

    /**
     * The last successful read is retained — never as current — because the
     * newest read failed with [errorKind]. [lastKnownState] keeps the adapter's
     * original `observedAtEpochMillis` unchanged; [failedAtEpochMillis] is this
     * controller's handset clock labeling when the failure was noticed.
     */
    data class Stale(
        val lastKnownState: ThinQLiveState,
        val errorKind: ThinQRefreshFailureKind,
        val failedAtEpochMillis: Long,
    ) : ThinQLiveRefreshState

    /** No state was ever read successfully; the most recent read failed with [errorKind]. */
    data class Unavailable(
        val errorKind: ThinQRefreshFailureKind,
        val failedAtEpochMillis: Long,
    ) : ThinQLiveRefreshState
}

/**
 * Stable, secret-free failure category for a live-state read. Mirrors the
 * classified transport/credential/remote semantics of [ThinQClientException]
 * and [ThinQReadFailure]; it never carries a raw exception message or token.
 */
enum class ThinQRefreshFailureKind {
    /** No stored ThinQ credential (token or account country), so no request was possible. */
    NO_CREDENTIAL,

    /** The stored credential/token was locally invalid and rejected before the network. */
    INVALID_CREDENTIAL,

    /** The transport failed: unreachable host, DNS, connection, or socket. */
    NETWORK,

    /** The bounded request deadline elapsed. */
    TIMEOUT,

    /** The account country cannot be routed to an LG region. */
    UNSUPPORTED_COUNTRY,

    /** LG answered with a body that could not be parsed as expected. */
    MALFORMED_RESPONSE,

    /** LG rejected the token/account (read classification). */
    INVALID_TOKEN,

    /** The configured device is not reachable under this account/config. */
    WRONG_ACCOUNT,

    /** LG reports the configured device unavailable or offline. */
    DEVICE_UNAVAILABLE,

    /** LG rate-limited the request. */
    RATE_LIMITED,

    /** LG denies control authority for this token. */
    NO_CONTROL_AUTHORITY,

    /** LG returned a transient error. */
    TRANSIENT,

    /** LG rejected the requested command for the device's current state. */
    INVALID_REQUEST,

    /** Any other unexpected failure; the safe default. */
    UNKNOWN,
}

/**
 * Maps any caught failure onto a stable [ThinQRefreshFailureKind]. [CancellationException]
 * is rethrown untouched — cancellation is never classified as a read failure.
 * Every branch produces a fixed enum value; no raw exception message or token is
 * ever retained or surfaced.
 */
internal fun classifyRefreshFailure(cause: Throwable): ThinQRefreshFailureKind {
    if (cause is CancellationException) throw cause
    return when (cause) {
        is ThinQClientException.MissingCredential -> ThinQRefreshFailureKind.NO_CREDENTIAL
        is ThinQClientException.InvalidCredential -> ThinQRefreshFailureKind.INVALID_CREDENTIAL
        is ThinQClientException.Network -> ThinQRefreshFailureKind.NETWORK
        is ThinQClientException.Timeout -> ThinQRefreshFailureKind.TIMEOUT
        is ThinQClientException.UnsupportedCountry -> ThinQRefreshFailureKind.UNSUPPORTED_COUNTRY
        is ThinQClientException.MalformedResponse -> ThinQRefreshFailureKind.MALFORMED_RESPONSE
        is ThinQClientException.RemoteFailure -> cause.kind.toRefreshFailureKind()
        else -> ThinQRefreshFailureKind.UNKNOWN
    }
}

/** Projection of the public read-failure classification onto [ThinQRefreshFailureKind]. */
internal fun ThinQReadFailure.toRefreshFailureKind(): ThinQRefreshFailureKind = when (this) {
    ThinQReadFailure.INVALID_TOKEN -> ThinQRefreshFailureKind.INVALID_TOKEN
    ThinQReadFailure.WRONG_ACCOUNT -> ThinQRefreshFailureKind.WRONG_ACCOUNT
    ThinQReadFailure.DEVICE_UNAVAILABLE -> ThinQRefreshFailureKind.DEVICE_UNAVAILABLE
    ThinQReadFailure.RATE_LIMITED -> ThinQRefreshFailureKind.RATE_LIMITED
    ThinQReadFailure.NO_CONTROL_AUTHORITY -> ThinQRefreshFailureKind.NO_CONTROL_AUTHORITY
    ThinQReadFailure.TRANSIENT -> ThinQRefreshFailureKind.TRANSIENT
    ThinQReadFailure.INVALID_REQUEST -> ThinQRefreshFailureKind.INVALID_REQUEST
    ThinQReadFailure.UNKNOWN -> ThinQRefreshFailureKind.UNKNOWN
}

/** Projection of one safe command failure onto a live-state failure category. */
private fun CommandFailure.toRefreshFailureKind(): ThinQRefreshFailureKind = when (this) {
    CommandFailure.NO_CONTROL_AUTHORITY -> ThinQRefreshFailureKind.NO_CONTROL_AUTHORITY
    CommandFailure.RATE_LIMITED -> ThinQRefreshFailureKind.RATE_LIMITED
    CommandFailure.INVALID_REQUEST -> ThinQRefreshFailureKind.INVALID_REQUEST
    CommandFailure.TOKEN_REJECTED -> ThinQRefreshFailureKind.INVALID_TOKEN
    CommandFailure.NETWORK -> ThinQRefreshFailureKind.NETWORK
    CommandFailure.TIMEOUT -> ThinQRefreshFailureKind.TIMEOUT
    CommandFailure.DEVICE_UNAVAILABLE -> ThinQRefreshFailureKind.DEVICE_UNAVAILABLE
    CommandFailure.UNKNOWN -> ThinQRefreshFailureKind.UNKNOWN
}
