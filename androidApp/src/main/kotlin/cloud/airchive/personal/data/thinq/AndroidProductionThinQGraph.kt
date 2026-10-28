package cloud.airchive.personal.data.thinq

import android.content.Context
import cloud.airchive.personal.BuildConfig
import cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStore
import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.KtorThinQClient
import cloud.airchive.shared.data.thinq.ThinQContextCoordinator
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.thinq.ThinQSetupOutcome
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android production ThinQ graph. The PAT enters only through
 * [setupCredential], which verifies the configured AC and stores the token in
 * Android Keystore-backed storage. It is never loaded from BuildConfig, Gradle
 * properties, or local.properties.
 *
 * This graph performs reads on explicit Device entry/refresh and sends commands
 * only when the UI calls [AcCommandController.submit] after an owner action.
 *
 * ## Lifecycle (OpenSpec `deliver-shared-instrument-ui` tasks 2.1–2.4)
 *
 * All context-sensitive behavior lives in the shared
 * [ThinQContextCoordinator]: the owner/project/device + credential-generation
 * boundary, the snapshot → unlocked-network → conditional-publication entry
 * read, the fenced live/command controllers, and the setup-attempt fence whose
 * protected-store commit and generation advance are one atomic boundary
 * transition. This class only wires the
 * Android adapters (Keystore store, Ktor client) into it and observes the
 * credential store for protected-key loss on a background scope, so a
 * synchronous store emission can never re-enter an invalidation on the thread
 * that holds the boundary. [onOwnerChanged] is the owner switch/sign-out hook
 * (MainActivity calls it from its session collector).
 */
class AndroidProductionThinQGraph(
    context: Context,
    private val configuredDeviceId: String = BuildConfig.AIRCHIVE_DEVICE_ID,
    ownerUid: String? = null,
    projectId: String = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
) : AutoCloseable {

    val credentialStore = AndroidEncryptedThinQCredentialStore(context.applicationContext)

    private val client = KtorThinQClient(
        engine = CIO.create(),
        credentialStore = credentialStore,
        configuredDeviceId = configuredDeviceId,
        temperatureStepOverride = BuildConfig.AIRCHIVE_THINQ_TEMPERATURE_STEP_OVERRIDE.toDoubleOrNull(),
    )

    /** Shared lifecycle/context core: boundary, fenced entry, live/command controllers. */
    val coordinator = ThinQContextCoordinator(
        credentialStore = credentialStore,
        thinQ = client,
        verifier = client,
        configuredDeviceId = configuredDeviceId,
        ownerUid = ownerUid,
        projectId = projectId,
    )

    val liveStateController: ThinQLiveStateController get() = coordinator.liveStateController

    val commandController: AcCommandController get() = coordinator.commandController

    // Protected-key loss detection; Default guarantees the collector never
    // resumes inline on the store's emitting thread (see startCredentialStateObservation).
    private val credentialObservationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        coordinator.startCredentialStateObservation(credentialObservationScope)
    }

    /** Verify-before-save setup; the result carries its exact resulting context key for feedback. */
    suspend fun setupCredential(rawToken: String, country: String): ThinQSetupOutcome =
        coordinator.setupCredentialDetailed(rawToken, country)

    /**
     * Explicit cancel-attempt hook (task 2.4) for setup-form/route
     * cancellation: supersedes only the in-flight setup attempt, which can
     * then never save its verified candidate or advance/publish anything.
     */
    suspend fun cancelSetupAttempt() {
        coordinator.cancelSetupAttempt()
    }

    /** Device-route entry; see [ThinQContextCoordinator.onDeviceOpened]. */
    suspend fun onDeviceOpened() {
        coordinator.onDeviceOpened()
    }

    /** Explicit Device refresh; live-state failures downgrade to stale/unavailable. */
    suspend fun refreshLiveState() {
        coordinator.refreshLiveState()
    }

    /** Explicit removal; account sign-out/change also clears this store through the account controller. */
    suspend fun removeCredential() {
        coordinator.removeCredential()
    }

    /**
     * Owner switch or confirmed sign-out: invalidates the previous
     * owner/project partition so its readings, profile and command feedback
     * can never reappear, even if the same owner signs back in.
     */
    suspend fun onOwnerChanged(ownerUid: String?, projectId: String) {
        coordinator.onOwnerChanged(ownerUid, projectId)
    }

    fun bindings(): ProductionThinQBindings = coordinator.bindings()

    /** Call after confirmed PAT removal/sign-out so profile-derived controls disappear immediately. */
    suspend fun clearCachedDeviceProfile() {
        coordinator.clearCachedDeviceProfile()
    }

    override fun close() {
        credentialObservationScope.cancel()
        client.close()
    }
}
