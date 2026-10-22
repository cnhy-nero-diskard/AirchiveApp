package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Production account actions for Settings: confirmed sign-out, account change,
 * and cache clear. It deliberately has **no** ThinQ credential surface — all
 * ThinQ control setup is deferred in production, so no PAT is stored, read,
 * replaced, or cleared here.
 *
 * Order-of-operations mirrors [OwnerSettingsController] so every destructive
 * transition is safe against an active history refresh:
 *
 *  - [signOutConfirmed] / [changeAccountConfirmed] / [clearCacheConfirmed] run
 *    inside a caller-supplied [serializedAction] (MainActivity routes this
 *    through the [cloud.airchive.shared.data.sync.ProductionHistoryCoordinator]
 *    operation lock) so a foreground/manual refresh can never re-populate a
 *    partition that is being removed.
 *  - Each confirmed action first clears the **current exact** (project, UID,
 *    device) partition; a clear failure aborts before any identity change so
 *    the prior session and rows stay intact and the owner can retry.
 *  - After a confirmed account change, a dismissed or failed new sign-in ends
 *    the prior session and stays signed out and clear.
 *
 * Only the active owner's partition is ever read or cleared; another account's
 * cache is never surfaced here because the caller observes the partition
 * derived from the live [identity] session.
 *
 * @param serializedAction wrapper that locks destructive transitions against
 *   concurrent sync runs (default: no locking, used by tests and simple graphs).
 */
class ProductionAccountController(
    private val identity: OwnerIdentityPort,
    private val local: LocalHistoryPort,
    private val firebaseProjectId: String,
    private val configuredDeviceId: String,
    private val serializedAction: suspend (suspend () -> OwnerSettingsActionResult) -> OwnerSettingsActionResult = { action -> action() },
) {

    private val _state = MutableStateFlow(ProductionAccountState(session = identity.currentSession))

    /** Observable account state; the Settings UI renders [ProductionAccountState]. */
    val state: StateFlow<ProductionAccountState> = _state.asStateFlow()

    private var confirmationOwnerUid: String? = null

    // ------------------------------------------------- confirmation gating

    /** Publishes [ProductionAccountConfirmStep.SIGN_OUT] so the UI can confirm first. */
    fun requestSignOut() {
        confirmationOwnerUid = identity.currentSession?.uid
        _state.value = _state.value.copy(confirmStep = ProductionAccountConfirmStep.SIGN_OUT)
    }

    /** Publishes [ProductionAccountConfirmStep.CHANGE_ACCOUNT] so the UI can confirm first. */
    fun requestChangeAccount() {
        confirmationOwnerUid = identity.currentSession?.uid
        _state.value = _state.value.copy(confirmStep = ProductionAccountConfirmStep.CHANGE_ACCOUNT)
    }

    /** Publishes [ProductionAccountConfirmStep.CLEAR_CACHE] so the UI can confirm first. */
    fun requestClearCache() {
        confirmationOwnerUid = identity.currentSession?.uid
        _state.value = _state.value.copy(confirmStep = ProductionAccountConfirmStep.CLEAR_CACHE)
    }

    /** Cancels a pending confirmation. */
    fun dismissConfirmation() {
        confirmationOwnerUid = null
        _state.value = _state.value.copy(confirmStep = ProductionAccountConfirmStep.NONE)
    }

    // ------------------------------------------------------------ status

    /** Re-emits the current session and partition cache; call whenever Settings is shown. */
    suspend fun refresh() {
        _state.value = snapshot()
    }

    // ------------------------------------------------------------ actions

    /**
     * Confirmed sign-out. Clears the active UID's exact partition, then ends
     * the Google session. A clear failure refuses the sign-out and keeps the
     * session and rows intact.
     */
    suspend fun signOutConfirmed(): OwnerSettingsActionResult = serializedAction {
        checkConfirmStep(ProductionAccountConfirmStep.SIGN_OUT)
        val session = identity.currentSession
        val result: OwnerSettingsActionResult = if (clearPartitionSafely(partitionFor(session)) != null) {
            OwnerSettingsActionResult.Failure(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED)
        } else {
            signOutSafely()
                ?.let { OwnerSettingsActionResult.Failure(it) }
                ?: OwnerSettingsActionResult.Success(session = null)
        }
        publishAfter(result)
        result
    }

    /**
     * Confirmed cache-only clear: removes only the active owner's exact
     * partition, keeping the Google session and any other owner's rows intact.
     */
    suspend fun clearCacheConfirmed(): OwnerSettingsActionResult = serializedAction {
        checkConfirmStep(ProductionAccountConfirmStep.CLEAR_CACHE)
        val session = identity.currentSession
        // A stale confirmation must not report success after an external sign-out.
        val result = if (session == null) {
            OwnerSettingsActionResult.Failure(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED)
        } else {
            clearPartitionSafely(partitionFor(session))
                ?.let { OwnerSettingsActionResult.Failure(it) }
                ?: OwnerSettingsActionResult.Success(session)
        }
        publishAfter(result)
        result
    }

    /**
     * Confirmed Google account change. Clears the prior UID's exact partition,
     * then starts the new sign-in; a dismissed/failed dialog ends the prior
     * session so the install stays signed out and clear.
     */
    suspend fun changeAccountConfirmed(): OwnerSettingsActionResult = serializedAction {
        checkConfirmStep(ProductionAccountConfirmStep.CHANGE_ACCOUNT)
        val priorSession = identity.currentSession
        val result: OwnerSettingsActionResult = if (clearPartitionSafely(partitionFor(priorSession)) != null) {
            OwnerSettingsActionResult.Failure(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED)
        } else {
            when (val signIn = signInSafely()) {
                is SignInResult.Success -> OwnerSettingsActionResult.Success(signIn.session)
                SignInResult.Cancelled -> {
                    endSessionAfterCleanup()
                    OwnerSettingsActionResult.AccountChangeCancelled
                }
                is SignInResult.Failure -> {
                    endSessionAfterCleanup()
                    OwnerSettingsActionResult.AccountChangeSignInFailed(signIn.reason)
                }
            }
        }
        publishAfter(result)
        result
    }

    // ------------------------------------------------------------ helpers

    private fun checkConfirmStep(expected: ProductionAccountConfirmStep) {
        check(identity.currentSession?.uid == confirmationOwnerUid && confirmationOwnerUid != null) {
            "The account changed while confirmation was open; request confirmation again."
        }
        check(_state.value.confirmStep == expected) {
            "The destructive account action requires its explicit UI confirmation step; " +
                "call request*() first. Expected $expected but was ${_state.value.confirmStep}."
        }
    }

    private fun partitionFor(session: OwnerSession?): HistoryPartition? =
        session?.let { HistoryPartition(firebaseProjectId, it.uid, configuredDeviceId) }

    private suspend fun snapshot(): ProductionAccountState {
        val current = _state.value
        val session = identity.currentSession
        val partition = partitionFor(session)
        return ProductionAccountState(
            session = session,
            history = if (partition == null) null else observeHistorySafely(partition),
            confirmStep = current.confirmStep,
            lastActionResult = current.lastActionResult,
        )
    }

    private suspend fun observeHistorySafely(partition: HistoryPartition): HistorySnapshot? = try {
        local.observe(partition).first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun clearPartitionSafely(partition: HistoryPartition?): OwnerSettingsActionFailure? =
        if (partition == null) null
        else try {
            local.clear(partition)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED
        }

    private suspend fun signOutSafely(): OwnerSettingsActionFailure? = try {
        identity.signOut()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        OwnerSettingsActionFailure.SIGN_OUT_FAILED
    }

    private suspend fun signInSafely(): SignInResult = try {
        identity.signIn()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        SignInResult.Failure(SignInFailure.UNKNOWN)
    }

    private suspend fun endSessionAfterCleanup() {
        try {
            identity.signOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort; never record the exception text.
        }
    }

    private suspend fun publishAfter(actionResult: OwnerSettingsActionResult) {
        confirmationOwnerUid = null
        _state.value = snapshot().copy(
            confirmStep = ProductionAccountConfirmStep.NONE,
            lastActionResult = actionResult,
        )
    }
}

/**
 * One renderable snapshot of the production account surface. [session] is the
 * signed-in owner (or `null`), [history] its partition cache (null while signed
 * out so no other account's rows are ever shown), [confirmStep] drives the
 * confirmation dialog, and [lastActionResult] retains the latest outcome.
 */
data class ProductionAccountState(
    val session: OwnerSession? = null,
    val history: HistorySnapshot? = null,
    val confirmStep: ProductionAccountConfirmStep = ProductionAccountConfirmStep.NONE,
    val lastActionResult: OwnerSettingsActionResult? = null,
)

/** Which destructive account action currently awaits explicit UI confirmation. */
enum class ProductionAccountConfirmStep {
    NONE,
    SIGN_OUT,
    CHANGE_ACCOUNT,
    CLEAR_CACHE,
}
