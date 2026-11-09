package cloud.airchive.personal

import android.app.Application
import cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStore
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import cloud.airchive.personal.data.thinq.AndroidProductionThinQGraph
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdateService
import cloud.airchive.shared.data.update.createAndroidAppUpdateHttpClient
import cloud.airchive.shared.data.update.releaseOnlyAppUpdateBinding
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process owner for command work that must survive Activity recreation. */
class AirchiveApplication : Application() {

    val installationPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidInterfaceDetailPreferences(applicationContext)
    }

    private val credentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidEncryptedThinQCredentialStore(applicationContext)
    }

    private val _thinQGraph = MutableStateFlow<AndroidProductionThinQGraph?>(null)
    val thinQGraph: StateFlow<AndroidProductionThinQGraph?> = _thinQGraph.asStateFlow()

    private var cachedAppUpdateBindings: AppUpdateBindings? = null

    /** Debug builds never create the updater or its HTTP client. */
    @Synchronized
    fun appUpdateBindings(): AppUpdateBindings? {
        return releaseOnlyAppUpdateBinding(BuildConfig.BUILD_TYPE == "release") {
            cachedAppUpdateBindings ?: run {
                check(BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL.isNotBlank()) {
                    "Release update manifest URL is not configured."
                }
                check(BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256.isNotBlank()) {
                    "Release update signer pin is not configured."
                }
                val preferences = installationPreferences
                val service = AppUpdateService(
                    httpClient = createAndroidAppUpdateHttpClient(BuildConfig.VERSION_NAME),
                    feedUrl = BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL,
                    expectedPackageName = BuildConfig.APPLICATION_ID,
                    pinnedSignerSha256 = BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    preferences = preferences,
                    nowEpochMillis = System::currentTimeMillis,
                )
                AppUpdateBindings(
                    service = service,
                    preferences = preferences,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                ).also { cachedAppUpdateBindings = it }
            }
        }
    }

    @Synchronized
    fun getOrCreateThinQGraph(ownerUid: String?): AndroidProductionThinQGraph {
        return _thinQGraph.value ?: newThinQGraph(ownerUid).also { _thinQGraph.value = it }
    }

    /** Replaces a graph only after OwnerTransition has invalidated and cleared its owner. */
    @Synchronized
    fun replaceThinQGraph(
        invalidatedGraph: AndroidProductionThinQGraph,
        ownerUid: String?,
    ): AndroidProductionThinQGraph? {
        if (_thinQGraph.value !== invalidatedGraph) return _thinQGraph.value
        val replacement = newThinQGraph(ownerUid)
        _thinQGraph.value = replacement
        invalidatedGraph.close()
        return replacement
    }

    private fun newThinQGraph(ownerUid: String?) = AndroidProductionThinQGraph(
        context = applicationContext,
        configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
        ownerUid = ownerUid,
        projectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
        credentialStore = credentialStore,
        engine = CIO.create(),
    )
}
