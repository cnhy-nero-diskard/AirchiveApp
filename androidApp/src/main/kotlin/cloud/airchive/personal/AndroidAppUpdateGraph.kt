package cloud.airchive.personal

import android.content.Context
import android.content.Intent
import android.os.StatFs
import cloud.airchive.shared.data.update.AndroidAppUpdateDownloader
import cloud.airchive.shared.data.update.AndroidAppUpdateVerifier
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdatePreferences
import cloud.airchive.shared.data.update.AppUpdateService
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.classifyAndroidAppUpdateNetworkFailure
import cloud.airchive.shared.data.update.createAndroidAppUpdateHttpClient
import cloud.airchive.shared.data.update.releaseOnlyAppUpdateBinding
import cloud.airchive.shared.data.update.resolvePendingInstallAtStartup
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope

/** Release-only process graph for fetching, verifying, and installing app updates. */
internal class AndroidAppUpdateGraph(
    context: Context,
    private val preferences: () -> AppUpdatePreferences,
    private val appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var cachedBindings: AppUpdateBindings? = null

    @Synchronized
    fun bindings(): AppUpdateBindings? = releaseOnlyAppUpdateBinding(BuildConfig.BUILD_TYPE == "release") {
        cachedBindings ?: createBindings().also { cachedBindings = it }
    }

    fun handleInstallStatus(statusIntent: Intent) {
        (bindings()?.actions as? AndroidAppUpdateActionsController)?.handleInstallerStatus(statusIntent)
    }

    private fun createBindings(): AppUpdateBindings {
        check(BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL.isNotBlank()) {
            "Release update manifest URL is not configured."
        }
        check(BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256.isNotBlank()) {
            "Release update signer pin is not configured."
        }

        val preferences = preferences()
        val packageInstaller = appContext.packageManager.packageInstaller
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
            networkFailureCause = ::classifyAndroidAppUpdateNetworkFailure,
        )
        val cacheDirectory = appContext.cacheDir
        val downloader = AndroidAppUpdateDownloader(
            httpClient = updateHttpClient,
            cacheDirectory = cacheDirectory,
            availableBytes = { StatFs(cacheDirectory.absolutePath).availableBytes },
        )
        val verifier = AndroidAppUpdateVerifier(
            context = appContext,
            expectedPackageName = BuildConfig.APPLICATION_ID,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            pinnedSignerSha256 = BuildConfig.AIRCHIVE_APP_UPDATE_SIGNER_SHA256,
        )
        downloader.cleanupPartialFiles()
        val downloadController = AndroidAppUpdateDownloadController(service, downloader, verifier, appScope)
        val installer = AndroidAppUpdatePackageInstaller(
            context = appContext,
            installController = service,
            apkForVersionCode = downloadController::verifiedApkForVersion,
            preferences = preferences,
            nowEpochMillis = System::currentTimeMillis,
            appScope = appScope,
        )
        val updateActions = AndroidAppUpdateActionsController(
            context = appContext,
            installController = service,
            downloadActions = downloadController,
            packageInstaller = installer,
            appScope = appScope,
        )
        return AppUpdateBindings(
            service = service,
            preferences = preferences,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            feedUrl = BuildConfig.AIRCHIVE_APP_UPDATE_MANIFEST_URL,
            actions = updateActions,
            downloadActions = downloadController,
        )
    }
}
