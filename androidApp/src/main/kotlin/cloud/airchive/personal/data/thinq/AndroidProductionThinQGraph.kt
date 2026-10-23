package cloud.airchive.personal.data.thinq

import android.content.Context
import cloud.airchive.personal.BuildConfig
import cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStore
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupCoordinator
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.KtorThinQClient
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import io.ktor.client.engine.cio.CIO
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android production ThinQ graph. The PAT enters only through
 * [setupCredential], which verifies the configured AC and stores the token in
 * Android Keystore-backed storage. It is never loaded from BuildConfig, Gradle
 * properties, or local.properties.
 *
 * This graph performs reads on explicit Device entry/refresh and sends commands
 * only when the UI calls [AcCommandController.submit] after an owner action.
 */
class AndroidProductionThinQGraph(
    context: Context,
    private val configuredDeviceId: String = BuildConfig.AIRCHIVE_DEVICE_ID,
) : AutoCloseable {

    val credentialStore = AndroidEncryptedThinQCredentialStore(context.applicationContext)

    private val client = KtorThinQClient(
        engine = CIO.create(),
        credentialStore = credentialStore,
        configuredDeviceId = configuredDeviceId,
        temperatureStepOverride = BuildConfig.AIRCHIVE_THINQ_TEMPERATURE_STEP_OVERRIDE.toDoubleOrNull(),
    )

    val credentialSetup = ThinQCredentialSetupCoordinator(
        credentialStore = credentialStore,
        verifier = client,
        configuredDeviceId = configuredDeviceId,
    )

    private val mutableDevice = MutableStateFlow<ThinQDevice?>(null)
    val device: StateFlow<ThinQDevice?> = mutableDevice.asStateFlow()

    private val mutableProfile = MutableStateFlow<ThinQDeviceProfile?>(null)
    val profile: StateFlow<ThinQDeviceProfile?> = mutableProfile.asStateFlow()

    private val profileMutex = Mutex()

    val liveStateController = ThinQLiveStateController(
        thinQ = client,
        configuredDeviceId = configuredDeviceId,
        hasVerifiedProfile = { mutableProfile.value?.deviceId == configuredDeviceId },
    )

    val commandController = AcCommandController(
        thinQ = client,
        configuredDeviceId = configuredDeviceId,
    )

    /** Verify-before-save setup; failed replacement leaves the previous PAT/profile intact. */
    suspend fun setupCredential(rawToken: String, country: String): ThinQCredentialSetupResult {
        val result = credentialSetup.setup(rawToken, country)
        if (result is ThinQCredentialSetupResult.Success) {
            profileMutex.withLock {
                mutableDevice.value = result.device
                mutableProfile.value = result.profile.takeIf { it.deviceId == configuredDeviceId }
            }
        }
        return result
    }

    /**
     * Device-route entry: load the exact account device and profile before
     * reading live state. If setup has not happened, this performs no LG call.
     */
    suspend fun onDeviceOpened() {
        profileMutex.withLock {
            if (credentialStore.readToken().isNullOrBlank()) {
                clearCachedDeviceProfileLocked()
                return
            }

            if (mutableDevice.value?.deviceId != configuredDeviceId ||
                mutableProfile.value?.deviceId != configuredDeviceId
            ) {
                try {
                    val configuredDevice = client.listDevices()
                        .firstOrNull { it.deviceId == configuredDeviceId }
                        ?: run {
                            clearCachedDeviceProfileLocked()
                            return
                        }
                    if (configuredDevice.online == false) {
                        mutableDevice.value = configuredDevice
                        mutableProfile.value = null
                        return
                    }
                    val verifiedProfile = client.profile(configuredDeviceId)
                    if (verifiedProfile.deviceId != configuredDeviceId) {
                        clearCachedDeviceProfileLocked()
                        return
                    }
                    mutableDevice.value = configuredDevice
                    mutableProfile.value = verifiedProfile
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The UI sees no verified capability and the live controller's
                    // safe error classification; raw LG/exception text is omitted.
                    clearCachedDeviceProfileLocked()
                    return
                }
            }
        }
        liveStateController.onDeviceOpened()
    }

    /** Explicit Device refresh; live-state failures downgrade to stale/unavailable. */
    suspend fun refreshLiveState() {
        if (mutableProfile.value?.deviceId == configuredDeviceId) {
            liveStateController.refresh()
        }
    }

    /** Explicit removal; account sign-out/change also clears this store through the account controller. */
    suspend fun removeCredential() {
        credentialStore.clear()
        clearCachedDeviceProfile()
    }

    fun bindings(): ProductionThinQBindings = ProductionThinQBindings(
        credentialState = credentialStore.observe(),
        clientId = credentialStore.clientId,
        device = device,
        profile = profile,
        liveStateController = liveStateController,
        commandController = commandController,
        setupCredential = ::setupCredential,
        onDeviceOpened = ::onDeviceOpened,
        refreshLiveState = ::refreshLiveState,
        removeCredential = ::removeCredential,
        clearCachedDeviceProfile = ::clearCachedDeviceProfile,
    )

    /** Call after confirmed PAT removal/sign-out so profile-derived controls disappear immediately. */
    fun clearCachedDeviceProfile() {
        mutableDevice.value = null
        mutableProfile.value = null
    }

    override fun close() {
        client.close()
    }

    private fun clearCachedDeviceProfileLocked() {
        mutableDevice.value = null
        mutableProfile.value = null
    }
}
