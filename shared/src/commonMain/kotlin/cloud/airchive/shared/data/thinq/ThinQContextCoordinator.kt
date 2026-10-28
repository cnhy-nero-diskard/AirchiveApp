package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupCoordinator
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle/context core for exactly one configured ThinQ device (OpenSpec
 * `deliver-shared-instrument-ui` tasks 2.1–2.3, design D2).
 *
 * Owns:
 *
 *  - the [ThinQLifecycleBoundary] (owner/project/device + credential generation);
 *  - the atomically published device/profile snapshot;
 *  - the fenced [ThinQLiveStateController] and [AcCommandController], which
 *    share the same boundary so every live/command publication is checked
 *    against the captured context;
 *  - the restructured Device-entry read ([onDeviceOpened]).
 *
 * ## Lock order (design D2)
 *
 * **Lifecycle boundary → profile mutex**, never the reverse. Network work
 * (list/profile/live/setup verification) runs with **no** lock held, so
 * invalidation never waits for an in-flight LG request; locks are only
 * re-acquired for local, conditional publication. The boundary may cover a
 * protected-store read/clear when that is necessary to keep a context snapshot
 * atomic with credential availability; it is also the home of the short
 * protected-store setup commit (task 2.4), which runs only after the
 * current-attempt gate in the same boundary section. It is never held across
 * LG I/O or while waiting for an in-flight command.
 *
 * ## Invalidation triggers (task 2.1)
 *
 *  - owner switch / confirmed sign-out → [onOwnerChanged];
 *  - credential removal → [removeCredential] or [clearCachedDeviceProfile];
 *  - verified replacement (even with an identical mask/country/timestamp) →
 *    [setupCredential] success, whose protected-store commit and generation
 *    advance are one atomic transition (task 2.4);
 *  - protected-key loss → [onCredentialState] / the entry prelude;
 *  - a **failed** same-owner replacement invalidates nothing, so the working
 *    credential context survives it.
 */
class ThinQContextCoordinator(
    private val credentialStore: ThinQCredentialStore,
    private val thinQ: ThinQPort,
    verifier: ThinQCredentialVerifier,
    private val configuredDeviceId: String,
    ownerUid: String? = null,
    projectId: String = "",
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /** Lifecycle boundary: the single source of truth for the active context. */
    val lifecycle = ThinQLifecycleBoundary(ownerUid, projectId, configuredDeviceId)

    /** Current context key; collected by the UI to fence entry and rendering. */
    val contextKey: StateFlow<ThinQContextKey> get() = lifecycle.key

    /**
     * Serializes device/profile publication with its clearing. Only ever taken
     * **while the lifecycle boundary is already held** (boundary → profile).
     */
    private val profileMutex = Mutex()

    private val mutableDeviceProfile = MutableStateFlow<ThinQDeviceProfilePublication?>(null)

    /** Device/profile published atomically together with their context key. */
    val deviceProfile: StateFlow<ThinQDeviceProfilePublication?> = mutableDeviceProfile.asStateFlow()

    val liveStateController = ThinQLiveStateController(
        thinQ = thinQ,
        configuredDeviceId = configuredDeviceId,
        nowEpochMillis = nowEpochMillis,
        hasVerifiedProfile = { mutableDeviceProfile.value?.profile?.deviceId == configuredDeviceId },
        lifecycle = lifecycle,
    )

    val commandController = AcCommandController(
        thinQ = thinQ,
        configuredDeviceId = configuredDeviceId,
        nowEpochMillis = nowEpochMillis,
        lifecycle = lifecycle,
    )

    /** Verify-before-save setup; a failure never advances the generation. */
    val credentialSetup = ThinQCredentialSetupCoordinator(
        credentialStore = credentialStore,
        verifier = verifier,
        configuredDeviceId = configuredDeviceId,
    )

    // ------------------------------------------------------------------
    // Device entry (task 2.2): snapshot → unlocked network → conditional
    // publication, with every step fenced by the captured context.
    // ------------------------------------------------------------------

    /**
     * Device-route entry: load the exact account device and profile before
     * reading live state, then perform the live read for the **captured**
     * context. If setup has not happened, no LG call is made.
     *
     *  1. Snapshot: the context key and credential presence are read in one
     *     boundary section, so a removal/replacement cannot interleave between
     *     them (no replacement token can be borrowed).
     *  2. List/profile/live requests run with no lifecycle or profile mutex
     *     held; each is re-authorized against the captured key immediately
     *     before dispatch.
     *  3. Publication re-acquires boundary → profile mutex and happens only if
     *     the captured key is still current; otherwise the result is dropped.
     *
     * Same-context network failure clears the verified presentation and
     * returns [ThinQEntryResult.Failed] while the live controller keeps only
     * explicitly-labeled old state with commands disabled; a superseded read
     * returns [ThinQEntryResult.Superseded] and publishes nothing.
     */
    suspend fun onDeviceOpened(): ThinQEntryResult {
        val snapshot = lifecycle.withBoundary {
            val contextKey = lifecycle.current()
            EntrySnapshot(
                contextKey = contextKey,
                // During a confirmed sign-out/account transition the old
                // identity can remain observable while its cleanup suspends.
                // The transition first moves this key to ownerUid=null; never
                // dispatch the outgoing owner's PAT from that unowned context.
                hasCredential = contextKey.ownerUid != null &&
                    !credentialStore.readToken().isNullOrBlank(),
            )
        }

        if (snapshot.contextKey.ownerUid == null) return ThinQEntryResult.Unavailable

        if (!snapshot.hasCredential) {
            // Distinguish "no credential yet" from "the protected key is lost":
            // the latter invalidates so no in-flight old-context result can land.
            val state = credentialStore.observe().first()
            if (state.unlockFailed || state.present) {
                invalidateForProtectedKeyLoss()
                return ThinQEntryResult.Unavailable
            }
            clearDeviceProfileIfCurrent(snapshot.contextKey)
            return ThinQEntryResult.Unavailable
        }

        val captured = snapshot.contextKey
        if (!lifecycle.isCurrent(captured)) return ThinQEntryResult.Superseded

        val publication = mutableDeviceProfile.value
        val needsDeviceProfileFetch = publication == null ||
            publication.contextKey != captured ||
            publication.device.deviceId != configuredDeviceId ||
            publication.profile?.deviceId != configuredDeviceId

        if (needsDeviceProfileFetch) {
            // --- LG network work: no lifecycle/profile mutex held. ---
            val device = try {
                thinQ.listDevices().firstOrNull { it.deviceId == configuredDeviceId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The UI sees no verified capability and the live controller's
                // safe error classification; raw LG/exception text is omitted.
                clearDeviceProfileIfCurrent(captured)
                return ThinQEntryResult.Failed
            }
            if (!lifecycle.isCurrent(captured)) return ThinQEntryResult.Superseded
            if (device == null) {
                clearDeviceProfileIfCurrent(captured)
                return ThinQEntryResult.Unavailable
            }
            if (device.online == false) {
                if (!publishDeviceProfile(captured, device, profile = null)) {
                    return ThinQEntryResult.Superseded
                }
                return ThinQEntryResult.Unavailable
            }

            val profile = try {
                thinQ.profile(configuredDeviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearDeviceProfileIfCurrent(captured)
                return ThinQEntryResult.Failed
            }
            if (!lifecycle.isCurrent(captured)) return ThinQEntryResult.Superseded
            if (profile.deviceId != configuredDeviceId) {
                clearDeviceProfileIfCurrent(captured)
                return ThinQEntryResult.Unavailable
            }
            if (!publishDeviceProfile(captured, device, profile)) {
                return ThinQEntryResult.Superseded
            }
        }

        // The live read is a follow-up of this entry: it is dispatched with the
        // captured key, so a replacement credential can never be borrowed for it.
        liveStateController.onDeviceOpened(captured)

        // Classify against observable state — a returned callback alone is
        // never a successful read.
        if (!lifecycle.isCurrent(captured)) return ThinQEntryResult.Superseded
        val verifiedProfile = mutableDeviceProfile.value
            ?.takeIf { it.contextKey == captured }
            ?.profile
        val live = liveStateController.state.value
        return when {
            verifiedProfile != null &&
                live is ThinQLiveRefreshState.Current &&
                live.state.deviceId == verifiedProfile.deviceId ->
                ThinQEntryResult.Success

            live is ThinQLiveRefreshState.Stale ||
                live is ThinQLiveRefreshState.Unavailable ->
                ThinQEntryResult.Failed

            else -> ThinQEntryResult.Unavailable
        }
    }

    /** Explicit Device refresh; live-state failures downgrade to stale/unavailable. */
    suspend fun refreshLiveState() {
        val captured = lifecycle.current()
        val publication = mutableDeviceProfile.value
        if (
            publication == null ||
            publication.contextKey != captured ||
            publication.profile?.deviceId != configuredDeviceId
        ) {
            return
        }
        liveStateController.refresh(captured)
    }

    // ------------------------------------------------------------------
    // Credential lifecycle (task 2.1)
    // ------------------------------------------------------------------

    /**
     * Fenced verify-before-save setup (task 2.4, design D2).
     *
     * Each call begins its own [ThinQSetupAttempt] (monotonic attempt id +
     * captured owner/project/device + credential-generation context). LG
     * verification runs with **no** lock held; the verified candidate's
     * protected-store save then runs inside the boundary's single
     * commit transition ([ThinQLifecycleBoundary.commitSetupAttemptIfCurrent]):
     *
     *  - a **current** attempt atomically saves the verified candidate,
     *    advances the credential generation (even when the observable
     *    mask/country/timestamp is byte-identical), resets live/command
     *    presentation to unread/idle, and publishes the verified device/profile
     *    **under the resulting key** — the success's masked credential is read
     *    from the store in that same section, so it always agrees with the new
     *    context;
     *  - an owner transition, credential removal/replacement, protected-key
     *    loss, a newer attempt, or an explicit/coroutine cancellation
     *    supersedes the attempt: no store save, no generation advance, no
     *    profile publication, and [ThinQCredentialSetupResult.Superseded] — no
     *    success feedback;
     *  - a **failed** same-owner replacement (verification or save) advances
     *    nothing: the working credential context and its stored token stay
     *    intact.
     */
    suspend fun setupCredential(rawToken: String, country: String): ThinQCredentialSetupResult {
        val attempt = lifecycle.beginSetupAttempt()
        return credentialSetup.setup(rawToken, country) { commitVerified ->
            val outcome = lifecycle.commitSetupAttemptIfCurrent(attempt) { advanceGeneration ->
                val result = commitVerified()
                if (result is ThinQCredentialSetupResult.Success) {
                    val resultingKey = advanceGeneration()
                    liveStateController.resetForContextChange()
                    commandController.resetForContextChange()
                    profileMutex.withLock {
                        mutableDeviceProfile.value = ThinQDeviceProfilePublication(
                            contextKey = resultingKey,
                            device = result.device,
                            profile = result.profile.takeIf { it.deviceId == configuredDeviceId },
                        )
                    }
                }
                result
            }
            when (outcome) {
                is ThinQSetupCommit.Committed -> outcome.result
                ThinQSetupCommit.Superseded -> ThinQCredentialSetupResult.Superseded
            }
        }
    }

    /**
     * Explicit cancel-attempt hook (task 2.4) for setup-form and route
     * cancellation: supersedes **only** the in-flight setup attempt, which can
     * then never save its candidate or advance/publish anything. It never
     * advances the generation, never touches the protected store, and never
     * affects a newer or later attempt.
     */
    suspend fun cancelSetupAttempt() {
        lifecycle.cancelSetupAttempt()
    }

    /**
     * Confirmed credential removal: stops exposing the old context first
     * (generation advance + presentation clear under the boundary), then clears
     * the protected store while the boundary is still held, so no concurrent
     * entry can capture the new key and still read the old token.
     */
    suspend fun removeCredential() {
        lifecycle.invalidateCredential {
            clearPresentationUnderBoundary()
            credentialStore.clear()
        }
    }

    /**
     * Clears the cached device/profile presentation for an absent credential
     * (Settings actions after sign-out/removal) and invalidates the context so
     * late old-context work cannot repopulate it.
     */
    suspend fun clearCachedDeviceProfile() {
        lifecycle.invalidateCredential { clearPresentationUnderBoundary() }
    }

    /**
     * Owner switch or confirmed sign-out (partition change). Idempotent: an
     * unchanged partition does not advance the generation.
     */
    suspend fun onOwnerChanged(ownerUid: String?, projectId: String) {
        val current = lifecycle.current()
        if (current.ownerUid == ownerUid && current.projectId == projectId) return
        lifecycle.invalidateOwner(ownerUid, projectId) { clearPresentationUnderBoundary() }
    }

    /**
     * Protected-key loss detected from the observable credential state. Only
     * `unlockFailed` is handled here: removal/replacement/sign-out always go
     * through the explicit methods above (which also clear the store), so a
     * credential-state collector can never re-enter an invalidation that is
     * currently holding the boundary.
     */
    suspend fun onCredentialState(state: ThinQCredentialState) {
        if (state.unlockFailed) invalidateForProtectedKeyLoss()
    }

    /**
     * Observes the credential store for protected-key loss. Call with a scope
     * that cannot resume inline on the store's emitting thread (production
     * uses a `Dispatchers.Default` scope): a synchronous store emission must
     * never run an invalidation on a thread that already holds the boundary.
     */
    fun startCredentialStateObservation(scope: CoroutineScope): Job =
        scope.launch {
            credentialStore.observe().collect { state -> onCredentialState(state) }
        }

    // ------------------------------------------------------------------
    // Presentation publication/clearing (all under the lifecycle boundary)
    // ------------------------------------------------------------------

    private suspend fun invalidateForProtectedKeyLoss() {
        lifecycle.invalidateCredential { clearPresentationUnderBoundary() }
    }

    /**
     * Clears live, command and device/profile presentation. Must run inside an
     * invalidation (lifecycle boundary already held); the profile mutex is
     * taken afterwards, honoring boundary → profile order. The controller
     * resets are direct state writes and never take the controllers' own
     * mutexes, so invalidation never waits for an in-flight read or command.
     */
    private suspend fun clearPresentationUnderBoundary() {
        liveStateController.resetForContextChange()
        commandController.resetForContextChange()
        profileMutex.withLock { mutableDeviceProfile.value = null }
    }

    private suspend fun publishDeviceProfile(
        captured: ThinQContextKey,
        device: ThinQDevice,
        profile: ThinQDeviceProfile?,
    ): Boolean = lifecycle.publishIfCurrent(captured) {
        profileMutex.withLock {
            mutableDeviceProfile.value = ThinQDeviceProfilePublication(
                contextKey = captured,
                device = device,
                profile = profile,
            )
        }
    }

    private suspend fun clearDeviceProfileIfCurrent(captured: ThinQContextKey) {
        lifecycle.publishIfCurrent(captured) {
            profileMutex.withLock { mutableDeviceProfile.value = null }
        }
    }

    /** Platform bindings over this coordinator; see [ProductionThinQBindings]. */
    fun bindings(): ProductionThinQBindings = ProductionThinQBindings(
        credentialState = credentialStore.observe(),
        clientId = credentialStore.clientId,
        contextKey = contextKey,
        deviceProfile = mutableDeviceProfile,
        liveStateController = liveStateController,
        commandController = commandController,
        setupCredential = ::setupCredential,
        cancelSetupAttempt = ::cancelSetupAttempt,
        onDeviceOpened = ::onDeviceOpened,
        refreshLiveState = ::refreshLiveState,
        removeCredential = ::removeCredential,
        clearCachedDeviceProfile = ::clearCachedDeviceProfile,
    )

    private data class EntrySnapshot(
        val contextKey: ThinQContextKey,
        val hasCredential: Boolean,
    )
}
