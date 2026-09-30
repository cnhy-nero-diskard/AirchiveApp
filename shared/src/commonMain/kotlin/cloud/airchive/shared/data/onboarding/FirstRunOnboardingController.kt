package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.data.sync.FirstLoadPhase
import cloud.airchive.shared.data.sync.FirstLoadProgress
import cloud.airchive.shared.data.sync.FirstLoadSyncCoordinator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-run onboarding state machine (task 3.2).
 *
 * Guides a fresh install through the four steps in order:
 *
 *  1. [FirstRunOnboardingStep.GOOGLE_ACCESS]  — Google sign-in plus the separate
 *     Firestore device-read authorization check. Sign-in alone never means
 *     history is authorized; that decision belongs to [OwnerAccessCoordinator],
 *     which reports [OwnerAccessState.AccessPending] until the operator enrolls
 *     the exact UID.
 *  2. [FirstRunOnboardingStep.THINQ_CREDENTIAL] — a separate, control-capable LG
 *     ThinQ Personal Access Token (never the collector's read-only token) plus
 *     an editable account country prefilled with [DEFAULT_COUNTRY] (`PH`). The
 *     portal to mint the token is [PAT_PORTAL_URL].
 *  3. [FirstRunOnboardingStep.INITIAL_HISTORY] — the bounded 24-hour first-load
 *     sync (via [FirstLoadSyncCoordinator]) with deterministic
 *     [FirstLoadProgress] reports.
 *  4. [FirstRunOnboardingStep.COMPLETE] — reached **only** after the initial
 *     sync succeeds.
 *
 * ## Secrets stay out of the observable surface
 *
 * The controller never calls [ThinQCredentialStore.readToken] and never holds
 * the plaintext token: [FirstRunOnboardingState.credential] carries only the
 * masked [ThinQCredentialState] from [ThinQCredentialStore.observe]. A raw PAT
 * can therefore never appear in state, a log line, or an exception message.
 * Sync failures are reduced to the stable [TelemetryReadFailure.code] — never
 * an exception message.
 *
 * ## Resume behavior
 *
 * [restore] checks the session/read access first, then the local credential and
 * cache, and picks the first step that still needs work: signed out or access
 * pending keeps [FirstRunOnboardingStep.GOOGLE_ACCESS]; authorized without a
 * saved credential lands on [FirstRunOnboardingStep.THINQ_CREDENTIAL];
 * authorized with a credential but no successful first-load falls to
 * [FirstRunOnboardingStep.INITIAL_HISTORY]; and a completed partition (authorized
 * + credential + durable full-24-hour initial coverage) resumes straight to
 * [FirstRunOnboardingStep.COMPLETE]. An incremental refresh's last-success
 * marker alone is not proof that the initial load completed.
 *
 * @param ownerAccess the Google-session + device-read authorization coordinator.
 * @param credentialStore protected ThinQ PAT storage (masked state only).
 * @param telemetry read-only Airchive telemetry feeding the first-load sync.
 * @param local the durable owner-local history projection.
 * @param firebaseProjectId the fixed, nonsecret project this build targets.
 * @param configuredDeviceId the nonsecret, configured AC device ID.
 * @param demoOnly when true the state carries a warning that the credential
 *   store is memory-only; the controller still accepts synthetic fixture tokens.
 */
class FirstRunOnboardingController(
    private val ownerAccess: OwnerAccessCoordinator,
    private val credentialStore: ThinQCredentialStore,
    private val credentialVerifier: ThinQCredentialVerifier,
    private val telemetry: AirchiveTelemetryPort,
    private val local: LocalHistoryPort,
    private val firebaseProjectId: String,
    private val configuredDeviceId: String,
    private val demoOnly: Boolean = false,
) {

    private val credentialSetup = ThinQCredentialSetupCoordinator(
        credentialStore = credentialStore,
        verifier = credentialVerifier,
        configuredDeviceId = configuredDeviceId,
    )

    private val _state = MutableStateFlow(
        FirstRunOnboardingState(
            step = FirstRunOnboardingStep.GOOGLE_ACCESS,
            ownerAccess = OwnerAccessState.SignedOut,
            demoOnly = demoOnly,
        ),
    )

    /** Observable onboarding state; the UI renders [FirstRunOnboardingState]. */
    val state: StateFlow<FirstRunOnboardingState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    /**
     * Restores the Firebase session, checks the device read, then consults the
     * local credential and cache to choose the next unfinished step.
     */
    suspend fun restore() = refreshFromAccess(ownerAccess.restore())

    /** Delegates to [OwnerAccessCoordinator.signIn]; sign-in alone never advances past Google access. */
    suspend fun signIn() = refreshFromAccess(ownerAccess.signIn())

    /** Retries the device-read authorization, e.g. after the operator enrolls the UID. */
    suspend fun retryAccess() = refreshFromAccess(ownerAccess.retryHistoryAccess())

    /**
     * Verifies and stores a ThinQ PAT. Only callable while
     * [OwnerAccessState.Authorized]; otherwise the call fails with
     * [IllegalStateException] (a UI/flow contract violation, not an owner error).
     *
     * The country is normalized to two uppercase letters (`ph` becomes `PH`);
     * a blank token or a country that is not exactly two alphabetic letters is
     * rejected with [ThinQCredentialSaveFailure.INVALID_INPUT] **before** the
     * verifier/store is touched. On success only the masked
     * [ThinQCredentialState] is published; the raw token never enters this
     * controller's state. A token is persisted only after the configured AC
     * identity and profile were read successfully; this read check does **not**
     * establish command/control authority.
     */
    suspend fun saveThinQCredential(rawToken: String, country: String) {
        val current = _state.value
        val access = current.ownerAccess
        check(access is OwnerAccessState.Authorized) {
            "ThinQ credential setup requires authorized owner access; current state is $access"
        }
        when (val result = credentialSetup.setup(rawToken, country)) {
            is ThinQCredentialSetupResult.Success -> {
                _state.value = current.copy(
                    step = deriveStep(access, result.credential, current.history),
                    credential = result.credential,
                    credentialFailure = null,
                    credentialSetupFailure = null,
                )
            }

            is ThinQCredentialSetupResult.Failure -> {
                // Every verifier/store failure preserves the previous masked
                // credential; the UI receives a typed reason, never a raw
                // token or adapter exception message.
                _state.value = current.copy(
                    credential = result.credential,
                    credentialFailure = when (result) {
                        is ThinQCredentialSetupResult.Failure.MalformedInput -> ThinQCredentialSaveFailure.INVALID_INPUT
                        is ThinQCredentialSetupResult.Failure.StoreKeyUnavailable -> ThinQCredentialSaveFailure.KEY_UNAVAILABLE
                        is ThinQCredentialSetupResult.Failure.StoreSaveFailure -> ThinQCredentialSaveFailure.UNKNOWN
                        else -> null
                    },
                    credentialSetupFailure = result,
                )
            }
        }
    }

    /**
     * Runs the bounded initial-history sync for the authorized owner's
     * partition. Requires [OwnerAccessState.Authorized] **and** a saved
     * credential (state preconditions; failing them throws
     * [IllegalStateException]). Mirrors every deterministic [FirstLoadProgress]
     * report into the state, then advances to [FirstRunOnboardingStep.COMPLETE]
     * only after the sync succeeds.
     *
     * On failure the state keeps [FirstRunOnboardingStep.INITIAL_HISTORY] with a
     * stable [TelemetryReadFailure.code] in [FirstRunOnboardingState.safeErrorCode]
     * and the refreshed local snapshot (the coordinators guarantee no partial
     * first-load rows and preserve prior success/watermarks). Cancellation
     * propagates untouched and is never recorded.
     */
    suspend fun syncInitialHistory(
        nowEpochMillis: Long,
        pageSize: Int = AirchiveTelemetryPort.MAX_PAGE_SIZE,
    ): Unit = syncMutex.withLock {
        val access = _state.value.ownerAccess
        val session = (access as? OwnerAccessState.Authorized)?.session
            ?: throw IllegalStateException("Initial history sync requires authorized owner access.")
        val credential = credentialStore.observe().first()
        check(credential.present && !credential.unlockFailed) {
            "Initial history sync requires a saved, decryptable ThinQ credential."
        }
        val partition = HistoryPartition(firebaseProjectId, session.uid, configuredDeviceId)
        val coordinator = FirstLoadSyncCoordinator(telemetry, local, partition)

        // A deterministically shaped "starting" report before the first page so
        // the UI renders an in-flight marker rather than stale progress.
        _state.value = _state.value.copy(
            firstLoadProgress = FirstLoadProgress(phase = FirstLoadPhase.OBSERVATION_PAGE),
        )
        try {
            coordinator.syncFirstLoad(
                nowEpochMillis = nowEpochMillis,
                pageSize = pageSize,
                onProgress = { progress ->
                    _state.value = _state.value.copy(firstLoadProgress = progress)
                },
            )
            val history = local.observe(partition).first()
            _state.value = _state.value.copy(
                step = FirstRunOnboardingStep.COMPLETE,
                history = history,
                safeErrorCode = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Safe classification only: a stable code, never an exception
            // message and never a raw token. The local snapshot preserves the
            // prior cache plus the coordinator's recorded error.
            val history = local.observe(partition).first()
            _state.value = _state.value.copy(
                history = history,
                safeErrorCode = TelemetryReadFailure.classify(failure).code,
            )
        }
    }

    /**
     * Recomputes the whole state from a fresh [OwnerAccessState] after
     * [restore]/[signIn]/[retryAccess]: masked credential read once, partition
     * history for the authorized owner, and the first unfinished step.
     */
    private suspend fun refreshFromAccess(access: OwnerAccessState) {
        val authorized = access as? OwnerAccessState.Authorized
        val session = authorized?.session
        // Do not even expose masked credential metadata while signed out or
        // access-pending; an account that has not passed the device read check
        // must not see a prior session's token suffix/country.
        val credential = if (authorized == null) {
            null
        } else {
            credentialStore.observe().first()
        }
        val stepCredential = credential ?: ThinQCredentialState(
            present = false,
            clientId = credentialStore.clientId,
        )
        val partition = session?.let { HistoryPartition(firebaseProjectId, it.uid, configuredDeviceId) }
        val history = partition?.let { local.observe(it).first() }
        _state.value = FirstRunOnboardingState(
            step = if (authorized == null) {
                FirstRunOnboardingStep.GOOGLE_ACCESS
            } else {
                deriveStep(access, stepCredential, history)
            },
            ownerAccess = access,
            credential = credential,
            history = history,
            safeErrorCode = when (access) {
                is OwnerAccessState.AccessPending -> access.safeErrorCode
                is OwnerAccessState.AccessCheckFailed -> access.safeErrorCode
                else -> null
            },
            credentialSetupFailure = null,
            demoOnly = demoOnly,
        )
    }

    /** The first step that still needs work, given access, credential, and cache. */
    private fun deriveStep(
        access: OwnerAccessState,
        credential: ThinQCredentialState,
        history: HistorySnapshot?,
    ): FirstRunOnboardingStep = when (access) {
        is OwnerAccessState.Authorized -> when {
            // Missing credential, or the protected key can no longer decrypt it:
            // request ThinQ setup again without ever exposing ciphertext.
            !credential.present || credential.unlockFailed -> FirstRunOnboardingStep.THINQ_CREDENTIAL
            // Incremental refresh can set last-success on a fresh install, so
            // require the durable full initial-window range before declaring
            // setup complete. Daily totals may be empty when none are available.
            history?.coveredRanges?.any { range ->
                range.toEpochMillis - range.fromEpochMillis >= FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS
            } == true -> FirstRunOnboardingStep.COMPLETE
            else -> FirstRunOnboardingStep.INITIAL_HISTORY
        }

        else -> FirstRunOnboardingStep.GOOGLE_ACCESS
    }

    companion object {
        /** Editable account-country prefill for ThinQ setup (Philippines). */
        const val DEFAULT_COUNTRY = "PH"

        /** Official LG ThinQ PAT portal the UI links to for minting a control-capable token. */
        const val PAT_PORTAL_URL = "https://connect-pat.lgthinq.com/"

        /** Copy shown only when [FirstRunOnboardingState.demoOnly] is true. */
        const val DEMO_MEMORY_ONLY_WARNING =
            "Demo build: the ThinQ credential store is memory-only and is wiped on restart. " +
                "Do NOT enter a real LG ThinQ Personal Access Token. " +
                "Use only the synthetic fixture tokens this demo accepts."

    }
}

/**
 * The four first-run steps, in order. The controller advances to
 * [FirstRunOnboardingStep.COMPLETE] only after the initial history sync succeeds.
 */
enum class FirstRunOnboardingStep {
    GOOGLE_ACCESS,
    THINQ_CREDENTIAL,
    INITIAL_HISTORY,
    COMPLETE,
}

/**
 * One renderable snapshot of the first-run flow.
 *
 * Only the masked [ThinQCredentialState] is ever published — the raw PAT is not
 * a field of this state and cannot reach the UI, logs, or exceptions.
 * [safeErrorCode] is always a stable [TelemetryReadFailure.code] or access code;
 * [credentialFailure] is the typed [ThinQCredentialSaveFailure] from the last
 * save attempt. [history] is the owner's cached partition snapshot (null until
 * an authorized owner session establishes the partition), and
 * [firstLoadProgress] mirrors the latest [FirstLoadProgress] from the initial
 * sync. [demoWarning] is populated only when [demoOnly] is set, reminding the
 * user that the demo credential store is memory-only.
 */
data class FirstRunOnboardingState(
    val step: FirstRunOnboardingStep,
    val ownerAccess: OwnerAccessState,
    val credential: ThinQCredentialState? = null,
    val history: HistorySnapshot? = null,
    val firstLoadProgress: FirstLoadProgress? = null,
    val safeErrorCode: String? = null,
    val credentialFailure: ThinQCredentialSaveFailure? = null,
    val credentialSetupFailure: ThinQCredentialSetupResult.Failure? = null,
    val demoOnly: Boolean = false,
    val demoWarning: String? = if (demoOnly) FirstRunOnboardingController.DEMO_MEMORY_ONLY_WARNING else null,
)
