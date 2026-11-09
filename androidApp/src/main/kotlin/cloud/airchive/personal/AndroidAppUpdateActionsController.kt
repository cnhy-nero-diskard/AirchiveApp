package cloud.airchive.personal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import cloud.airchive.shared.data.update.AppUpdateActions
import cloud.airchive.shared.data.update.AppUpdateDownloadActions
import cloud.airchive.shared.data.update.AppUpdateInstallControllerPort
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Android install-permission gate and settings route for the verified update APK. */
class AndroidAppUpdateActionsController(
    context: Context,
    private val installController: AppUpdateInstallControllerPort,
    private val downloadActions: AppUpdateDownloadActions,
    private val packageInstaller: AndroidAppUpdatePackageInstaller?,
    private val appScope: CoroutineScope,
    private val canRequestPackageInstalls: () -> Boolean = {
        context.packageManager.canRequestPackageInstalls()
    },
    private val launchSettings: (Intent) -> Unit = { intent ->
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    },
) : AppUpdateActions {
    private val appContext = context.applicationContext

    override fun download(manifest: UpdateManifest) = downloadActions.download(manifest)

    override fun cancelDownload() = downloadActions.cancelDownload()

    override fun install(manifest: UpdateManifest) {
        appScope.launch {
            val allowed = canRequestPackageInstalls()
            installController.updateInstallPermission(
                manifest = manifest,
                allowed = allowed,
            )
            if (allowed) packageInstaller?.install(manifest)
        }
    }

    override fun requestInstallPermission(manifest: UpdateManifest) {
        val current = installController.state.value as? AppUpdateState.NeedsInstallPermission ?: return
        if (current.manifest != manifest) return

        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${appContext.packageName}".toUri(),
        )
        try {
            launchSettings(intent)
        } catch (_: ActivityNotFoundException) {
            // Keep the explanation and retry action visible if a device has no handler.
        }
    }

    override fun recheckInstallPermission() {
        val current = installController.state.value
        val manifest = when (current) {
            is AppUpdateState.ReadyToInstall -> current.manifest
            is AppUpdateState.NeedsInstallPermission -> current.manifest
            else -> return
        }
        appScope.launch {
            installController.updateInstallPermission(
                manifest = manifest,
                allowed = canRequestPackageInstalls(),
            )
        }
    }

    fun handleInstallerStatus(statusIntent: Intent) {
        packageInstaller?.handleStatusIntent(statusIntent)
    }
}
