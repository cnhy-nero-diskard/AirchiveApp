package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Common account/credential settings state controller (OpenSpec task 3.5).
 *
 * Owns the Settings surface for the single owner: the signed-in Google
 * session, the masked ThinQ credential status, the owner-local cache + sync
 * metadata, and the confirmed destructive actions (remove credential, clear the
 * local cache, sign out, change account). It builds on the same boundaries as
 * onboarding —
 * [OwnerIdentityPort] for the session, [ThinQCredentialStore] for protected PAT
 * storage, [LocalHistoryPort] for owner-scoped rows, and
 * [ThinQCredentialSetupCoordinator] for verified replacement — but never holds
 * or publishes a raw token or an exception message.
 *
 * ## Secrets stay out of the observable surface
 *
 * [OwnerSettingsState.credential] carries only the masked [ThinQCredentialState]
 * from [ThinQCredentialStore.observe]; the controller never calls
 * [ThinQCredentialStore.readToken] and never stores the plaintext PAT in state.
 * [OwnerSettingsState.history] carries the cache projection whose
 * [HistorySnapshot.sync] exposes last-success/last-error metadata. Protected-key
 * loss surfaces through [ThinQCredentialState.unlockFailed] without ever
 * touching ciphertext. Every captured failure is a stable typed
 * [OwnerSettingsActionFailure] or the coordinator's typed [ThinQCredentialSetupResult];
 * an exception message can never appear in state, a result, or a debug string.
 *
 * ## Explicit confirmation before destructive actions
 *
 * The UI must not call the destructive `*Confirmed()` methods directly. It
 * calls [requestRemoveCredential], [requestClearCache], [requestSignOut], or
 * [requestChangeAccount] first, which publishes the matching
 * [OwnerSettingsConfirmStep]; the UI shows its confirmation dialog from that
 * step and only when the owner confirms calls the matching `*Confirmed()`
 * method. A call whose step does not match is
 * rejected with [IllegalStateException] (a UI/flow contract violation, matching
 * the onboarding controller's precondition checks). [dismissConfirmation] (or
 * any completed action) returns the state to [OwnerSettingsConfirmStep.NONE].
 *
 * ## Order-of-operations guarantees
 *
 * Every transition runs under a single [Mutex] so remove/sign-out/account-
 * change/replace/refresh serialize. The two account transitions always clean up
 * the **current exact** (project, UID, device) partition and the PAT **before**
 * any identity change:
 *
 *  - [removeCredentialConfirmed] deletes only the PAT and preserves the owner's
 *    rows and sync metadata.
 *  - [clearCacheConfirmed] deletes only the active UID's partition rows and sync
 *    markers and preserves the Google session, the PAT, and its client ID.
 *  - [signOutConfirmed] clears the active UID's partition + PAT, then calls
 *    [OwnerIdentityPort.signOut].
 *  - [changeAccountConfirmed] clears the prior UID's partition + PAT, then
 *    starts the new Google sign-in. A dismissed dialog is **not** a failure:
 *    the owner already confirmed switching, so the install remains signed out
 *    and clear.
 *
 * A cache-clear failure aborts before any identity change (the old session,
 * PAT, and rows stay intact so the owner can retry). A PAT-clear failure that
 * happens after the rows were cleared also aborts before any identity change
 * and returns a safe typed failure. Another UID's partition is never read or
 * cleared — only the active session's exact partition is touched. Coroutine
 * [CancellationException] always propagates and is never recorded.
 *
 * @param identity the owner's Google sign-in/session port.
 * @param credentialStore protected ThinQ PAT storage (masked state only).
 * @param credentialVerifier the task-4.1 LG read-verification adapter boundary.
 * @param local the durable owner-local history projection (Room-backed).
 * @param firebaseProjectId the fixed, nonsecret project this build targets.
 * @param configuredDeviceId the nonsecret, configured AC device ID.
 */
class OwnerSettingsController(
    private val identity: OwnerIdentityPort,
    private val credentialStore: ThinQCredentialStore,
    private val credentialVerifier: ThinQCredentialVerifier,
    private val local: LocalHistoryPort,
    private val firebaseProjectId: String,
    private val configuredDeviceId: String,
) {

    private val credentialSetup = ThinQCredentialSetupCoordinator(
        credentialStore = credentialStore,
        verifier = credentialVerifier,
        configuredDeviceId = configuredDeviceId,
    )

    /** Serializes every remove/sign-out/account-change/replace/refresh transition. */
    private val actionMutex = Mutex()

    private val _state = MutableStateFlow(OwnerSettingsState(session = null))

    /** Observable settings state; the UI renders [OwnerSettingsState]. */
    val state: StateFlow<OwnerSettingsState> = _state.asStateFlow()

    // ------------------------------------------------- confirmation gating

    /**
     * Publishes [OwnerSettingsConfirmStep.REMOVE_CREDENTIAL] so the UI can show
     * a "remove the ThinQ PAT?" dialog before [removeCredentialConfirmed].
     */
    fun requestRemoveCredential() {
        _state.value = _state.value.copy(confirmStep = OwnerSettingsConfirmStep.REMOVE_CREDENTIAL)
    }

    /**
     * Publishes [OwnerSettingsConfirmStep.SIGN_OUT] so the UI can show a
     * "sign out and clear this device's history?" dialog before [signOutConfirmed].
     */
    fun requestSignOut() {
        _state.value = _state.value.copy(confirmStep = OwnerSettingsConfirmStep.SIGN_OUT)
    }

    /**
     * Publishes [OwnerSettingsConfirmStep.CHANGE_ACCOUNT] so the UI can show a
     * "switch Google account?" dialog before [changeAccountConfirmed].
     */
    fun requestChangeAccount() {
        _state.value = _state.value.copy(confirmStep = OwnerSettingsConfirmStep.CHANGE_ACCOUNT)
    }

    /**
     * Publishes [OwnerSettingsConfirmStep.CLEAR_CACHE] so the UI can show a
     * "clear this account's local cache?" dialog before [clearCacheConfirmed].
     * The Google session and the ThinQ PAT are explicitly **not** touched.
     */
    fun requestClearCache() {
        _state.value = _state.value.copy(confirmStep = OwnerSettingsConfirmStep.CLEAR_CACHE)
    }

    /** Cancels a pending confirmation; returns to [OwnerSettingsConfirmStep.NONE]. */
    fun dismissConfirmation() {
        _state.value = _state.value.copy(confirmStep = OwnerSettingsConfirmStep.NONE)
    }

    // ------------------------------------------------------------ status

    /**
     * Re-emits the current session, masked credential, and owner partition cache
     * and sync metadata. The UI should call this whenever Settings becomes
     * visible so the state reflects any external session/data changes; every
     * controller action also re-snapshots the state afterward.
     */
    suspend fun refresh() = actionMutex.withLock {
        _state.value = snapshot()
    }

    // ------------------------------------------------------------ actions

    /**
     * Replaces the ThinQ PAT through [ThinQCredentialSetupCoordinator.setup].
     * Requires a signed-in owner session (a precondition [IllegalStateException]
     * otherwise). The prior PAT survives every verification/storage failure and
     * the local history is never touched. Returns the coordinator's safe typed
     * [ThinQCredentialSetupResult]; only the masked credential is reflected back
     * into state. Cancellation propagates and is never recorded.
     */
    suspend fun replaceCredential(rawToken: String, country: String): ThinQCredentialSetupResult = actionMutex.withLock {
        check(identity.currentSession != null) {
            "ThinQ credential replacement requires a signed-in owner session."
        }
        val result = try {
            credentialSetup.setup(rawToken, country)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Defensive: setup already classifies every verifier/store failure
            // into its typed reason; only an unexpected path could reach here,
            // and it must still become a stable typed failure carrying the
            // preserved masked credential — never an exception message.
            ThinQCredentialSetupResult.Failure.RemoteFailure(observeCredentialSafely())
        }
        _state.value = snapshot()
        result
    }

    /**
     * Removes **only** the stored PAT (confirmed). The owner's local rows,
     * totals, and sync metadata are preserved untouched. The signed-in session
     * is unaffected. A protected-store failure returns
     * [OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED] so the owner
     * can retry; nothing else changes.
     */
    suspend fun removeCredentialConfirmed(): OwnerSettingsActionResult = actionMutex.withLock {
        checkConfirmStep(OwnerSettingsConfirmStep.REMOVE_CREDENTIAL)
        val failure = clearCredentialSafely()
        val result = if (failure == null) {
            OwnerSettingsActionResult.Success(identity.currentSession)
        } else {
            OwnerSettingsActionResult.Failure(failure)
        }
        publishAfter(result)
        result
    }

    /**
     * Confirmed sign-out. Clears the active UID's exact (project, UID, device)
     * partition and the PAT, then calls [OwnerIdentityPort.signOut]. If the
     * partition cannot be cleared the sign-out is refused and the session, PAT,
     * and rows stay intact for a retry; if the PAT cannot be cleared after the
     * rows are gone the sign-out is still refused with a safe typed failure.
     */
    suspend fun signOutConfirmed(): OwnerSettingsActionResult = actionMutex.withLock {
        checkConfirmStep(OwnerSettingsConfirmStep.SIGN_OUT)
        val session = identity.currentSession
        val result: OwnerSettingsActionResult
        val localFailure = clearPartitionSafely(partitionFor(session))
        if (localFailure != null) {
            // Rows could not be removed: do NOT sign out; the prior session,
            // PAT, and rows remain intact so the owner can retry.
            result = OwnerSettingsActionResult.Failure(localFailure)
        } else {
            val storeFailure = clearCredentialSafely()
            result = if (storeFailure != null) {
                // Rows are gone but the PAT survives: still do NOT sign out; a
                // typed failure lets the owner retry instead of silently
                // switching identities.
                OwnerSettingsActionResult.Failure(storeFailure)
            } else {
                val signOutFailure = signOutSafely()
                if (signOutFailure != null) {
                    OwnerSettingsActionResult.Failure(signOutFailure)
                } else {
                    OwnerSettingsActionResult.Success(session = null)
                }
            }
        }
        publishAfter(result)
        result
    }

    /**
     * Confirmed cache-only clear. Removes **only** the active owner's exact
     * (project, UID, device) partition from [LocalHistoryPort] — observations,
     * daily totals, covered ranges, and sync markers — while the signed-in
     * Google session and the ThinQ PAT (and its client ID) stay completely
     * untouched. A local clear failure leaves the session, PAT, and rows intact
     * and returns [OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED]; no
     * identity switch or account change ever happens and no exception text is
     * exposed.
     */
    suspend fun clearCacheConfirmed(): OwnerSettingsActionResult = actionMutex.withLock {
        checkConfirmStep(OwnerSettingsConfirmStep.CLEAR_CACHE)
        val session = identity.currentSession
        // A stale confirmation must not be reported as success if the owner was
        // signed out while the dialog was open. No partition is cleared without
        // an active session.
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
     * Confirmed Google account change. Performs the **same cleanup** as
     * [signOutConfirmed] — clears the current exact partition + PAT — and only
     * then starts the new Google sign-in flow. A cleanup failure aborts before
     * the new sign-in starts and the prior identity is retained. A dismissed
     * dialog after cleanup is allowed (the owner explicitly confirmed
     * switching): the install remains signed out and clear.
     */
    suspend fun changeAccountConfirmed(): OwnerSettingsActionResult = actionMutex.withLock {
        checkConfirmStep(OwnerSettingsConfirmStep.CHANGE_ACCOUNT)
        val priorSession = identity.currentSession
        val result: OwnerSettingsActionResult
        val localFailure = clearPartitionSafely(partitionFor(priorSession))
        if (localFailure != null) {
            // No identity switch: never start the new sign-in while the prior
            // owner's rows could not be cleared.
            result = OwnerSettingsActionResult.Failure(localFailure)
        } else {
            val storeFailure = clearCredentialSafely()
            result = if (storeFailure != null) {
                // No identity switch: never start the new sign-in with a stale
                // PAT still present.
                OwnerSettingsActionResult.Failure(storeFailure)
            } else {
                when (val signIn = signInSafely()) {
                    is SignInResult.Success -> OwnerSettingsActionResult.Success(signIn.session)
                    SignInResult.Cancelled -> {
                        // The owner confirmed switching but dismissed the new
                        // account dialog: end the prior session and remain
                        // signed out and clear.
                        endSessionAfterCleanup()
                        OwnerSettingsActionResult.AccountChangeCancelled
                    }
                    is SignInResult.Failure -> {
                        endSessionAfterCleanup()
                        OwnerSettingsActionResult.AccountChangeSignInFailed(signIn.reason)
                    }
                }
            }
        }
        publishAfter(result)
        result
    }

    // ------------------------------------------------------------ helpers

    private fun checkConfirmStep(expected: OwnerSettingsConfirmStep) {
        check(_state.value.confirmStep == expected) {
            "The destructive action requires its explicit UI confirmation step; " +
                "call request*() first. Expected $expected but was ${_state.value.confirmStep}."
        }
    }

    /** The exact cache key of a session; `null` when nobody is signed in. */
    private fun partitionFor(session: OwnerSession?): HistoryPartition? =
        session?.let { HistoryPartition(firebaseProjectId, it.uid, configuredDeviceId) }

    /**
     * Rebuilds the renderable state from the identity port, the masked
     * credential store, and the active partition's cache. Only the active
     * session's partition is ever read; while signed out no credential or cache
     * detail is published. [OwnerSettingsConfirmStep] and the last action
     * result are preserved so a confirmation or error survives a refresh.
     */
    private suspend fun snapshot(): OwnerSettingsState {
        val current = _state.value
        val session = identity.currentSession
        val partition = partitionFor(session)
        return OwnerSettingsState(
            session = session,
            credential = if (session == null) null else observeCredentialSafely(),
            history = if (session == null || partition == null) null else observeHistorySafely(partition),
            confirmStep = current.confirmStep,
            lastActionResult = current.lastActionResult,
        )
    }

    private suspend fun publishAfter(actionResult: OwnerSettingsActionResult) {
        _state.value = snapshot().copy(
            confirmStep = OwnerSettingsConfirmStep.NONE,
            lastActionResult = actionResult,
        )
    }

    private suspend fun observeCredentialSafely(): ThinQCredentialState = try {
        credentialStore.observe().first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A read hiccup is never credential evidence; the owner sees the absent
        // state and can retry, and no exception text or raw token is stored.
        ThinQCredentialState(present = false, clientId = credentialStore.clientId)
    }

    private suspend fun observeHistorySafely(partition: HistoryPartition): HistorySnapshot? = try {
        local.observe(partition).first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    /** Removes the active partition only; `null` partitions (signed out) are skipped. */
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

    private suspend fun clearCredentialSafely(): OwnerSettingsActionFailure? = try {
        credentialStore.clear()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED
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

    /**
     * Ends the prior session after a confirmed account-change cleanup when the
     * new sign-in did not succeed, so the identity port matches the advertised
     * signed-out-and-clear state. A failure here is swallowed (the port is
     * already past the cleaned data and a later [refresh] re-reads it); nothing
     * about an exception is stored.
     */
    private suspend fun endSessionAfterCleanup() {
        try {
            identity.signOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort only; never record the exception text.
        }
    }
}

/**
 * One renderable snapshot of the account/credential Settings surface.
 *
 * [session] is the signed-in owner, or `null` when signed out. [credential] is
 * **only** the masked [ThinQCredentialState] (its [ThinQCredentialState.unlockFailed]
 * surfaces a lost protected key); it is `null` while signed out so a prior
 * session's credential metadata is never shown. [history] is the active owner's
 * partition cache whose [HistorySnapshot.sync] carries the sync last-success
 * and last-error metadata — also `null` while signed out. [confirmStep] drives
 * the explicit-confirmation dialog, and [lastActionResult] retains the outcome
 * of the most recent confirmed action so the UI can render it.
 *
 * No field of this state, nor its `toString()`, contains a raw PAT or an
 * exception message.
 */
data class OwnerSettingsState(
    val session: OwnerSession? = null,
    val credential: ThinQCredentialState? = null,
    val history: HistorySnapshot? = null,
    val confirmStep: OwnerSettingsConfirmStep = OwnerSettingsConfirmStep.NONE,
    val lastActionResult: OwnerSettingsActionResult? = null,
)

/** Which destructive action currently awaits explicit UI confirmation. */
enum class OwnerSettingsConfirmStep {
    /** No confirmation is pending. */
    NONE,

    /** The owner is being asked before [OwnerSettingsController.removeCredentialConfirmed]. */
    REMOVE_CREDENTIAL,

    /** The owner is being asked before [OwnerSettingsController.signOutConfirmed]. */
    SIGN_OUT,

    /** The owner is being asked before [OwnerSettingsController.changeAccountConfirmed]. */
    CHANGE_ACCOUNT,

    /** The owner is being asked before [OwnerSettingsController.clearCacheConfirmed]. */
    CLEAR_CACHE,
}

/**
 * Outcome of one confirmed destructive settings action, safe by construction:
 * it carries only a resulting [OwnerSession], a cancellation marker, or a
 * stable [OwnerSettingsActionFailure] — never an exception message or a raw
 * credential.
 */
sealed interface OwnerSettingsActionResult {

    /**
     * The action completed. [session] is the session active afterwards: the
     * same owner after [OwnerSettingsController.removeCredentialConfirmed] or
     * [OwnerSettingsController.clearCacheConfirmed], the new owner after a
     * successful [OwnerSettingsController.changeAccountConfirmed], and `null`
     * after a successful [OwnerSettingsController.signOutConfirmed].
     */
    data class Success(val session: OwnerSession? = null) : OwnerSettingsActionResult

    /**
     * A confirmed account change: cleanup ran and the new Google sign-in dialog
     * was dismissed afterward; the install stays signed out and clear.
     */
    data object AccountChangeCancelled : OwnerSettingsActionResult

    /**
     * A confirmed account change: cleanup ran but the new Google sign-in failed
     * with a stable [SignInFailure]; the install stays signed out and clear.
     */
    data class AccountChangeSignInFailed(val reason: SignInFailure) : OwnerSettingsActionResult

    /**
     * The action stopped before any identity switch with a stable
     * [OwnerSettingsActionFailure]; the owner can retry. After a
     * [OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED] the local rows
     * are already cleared but the PAT still requires removal.
     */
    data class Failure(val reason: OwnerSettingsActionFailure) : OwnerSettingsActionResult
}

/** Stable, safe reason a confirmed settings action could not complete. */
enum class OwnerSettingsActionFailure {
    /** The active owner's partition rows could not be removed; no identity switch happened. */
    LOCAL_CACHE_CLEAR_FAILED,

    /** The protected store could not remove the PAT; no identity switch happened. */
    CREDENTIAL_STORE_CLEAR_FAILED,

    /** The identity port could not end the session after its data was cleared. */
    SIGN_OUT_FAILED,
}
