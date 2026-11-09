package cloud.airchive.personal

import android.app.Application
import android.content.Intent
import android.os.StatFs
import cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStore
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import cloud.airchive.personal.data.thinq.AndroidProductionThinQGraph
import cloud.airchive.shared.data.update.AndroidAppUpdateDownloader
import cloud.airchive.shared.data.update.AndroidAppUpdateVerifier
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdateService
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.createAndroidAppUpdateHttpClient
import cloud.airchive.shared.data.update.releaseOnlyAppUpdateBinding
import cloud.airchive.shared.data.update.resolvePendingInstallAtStartup
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Process-lifetime scope keeps an explicit update download alive across Activity recreation. */
    private val appUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val packageInstaller = packageManager.packageInstaller
                val pendingInstall = preferences.appUpdateState.value.pendingInstall
                val hasActiveInstallSession = if (
                    pendingInstall != null && BuildConfig.VERSION_CODE.toLong() < pendingInstall.targetVersionCode
                ) {
                    try {
                        packageInstaller.mySessions.any { session ->
                            session.appPackageName == BuildConfig.APPLICATION_ID && session.isActive
                        }
                    } catch (_: Exception) {
                        // Defer failure reporting when Android cannot confirm that the session is gone.
                        true
                    }
                } else {
                    false
                }
                val startupState = resolvePendingInstallAtStartup(
                    preferences = preferences,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    installedVersionName = BuildConfig.VERSION_NAME,
                    hasActiveInstallSession = hasActiveInstallSession,
                )
                val updateHttpClient = createAndroidAppUpdateHttpClient(BuildConfig.VERSION_NAME)
                val service = AppUpdateService(
                    httpClient = updateHttpClient,
                    feedUrl = BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL,
                    expectedPackageName = BuildConfig.APPLICATION_ID,
                    pinnedSignerSha256 = BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    preferences = preferences,
                    nowEpochMillis = System::currentTimeMillis,
                    initialState = startupState ?: AppUpdateState.Idle,
                )
                val downloader = AndroidAppUpdateDownloader(
                    httpClient = updateHttpClient,
                    cacheDirectory = cacheDir,
                    availableBytes = { StatFs(cacheDir.absolutePath).availableBytes },
                )
                val verifier = AndroidAppUpdateVerifier(
                    context = applicationContext,
                    expectedPackageName = BuildConfig.APPLICATION_ID,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    pinnedSignerSha256 = BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256,
                )
                downloader.cleanupPartialFiles()
                val downloadController = AndroidAppUpdateDownloadController(service, downloader, verifier, appUpdateScope)
                val installer = AndroidAppUpdatePackageInstaller(
                    context = applicationContext,
                    installController = service,
                    apkForVersionCode = downloadController::verifiedApkForVersion,
                    preferences = preferences,
                    nowEpochMillis = System::currentTimeMillis,
                    appScope = appUpdateScope,
                )
                val updateActions = AndroidAppUpdateActionsController(
                    context = applicationContext,
                    installController = service,
                    downloadActions = downloadController,
                    packageInstaller = installer,
                    appScope = appUpdateScope,
                )
                AppUpdateBindings(
                    service = service,
                    preferences = preferences,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    feedUrl = BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL,
                    actions = updateActions,
                    downloadActions = downloadController,
                ).also { cachedAppUpdateBindings = it }
            }
        }
    }

    fun handleAppUpdateInstallStatus(statusIntent: Intent) {
        (appUpdateBindings()?.actions as? AndroidAppUpdateActionsController)?.handleInstallerStatus(statusIntent)
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
