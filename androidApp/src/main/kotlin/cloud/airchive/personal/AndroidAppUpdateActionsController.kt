package cloud.airchive.personal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import cloud.airchive.shared.data.update.AppUpdateActions
import cloud.airchive.shared.data.update.AppUpdateDownloadActions
import cloud.airchive.shared.data.update.AppUpdateInstallPermissionPort
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Android install-permission gate and settings route for the verified update APK. */
class AndroidAppUpdateActionsController(
    context: Context,
    private val installPermissionState: AppUpdateInstallPermissionPort,
    private val downloadActions: AppUpdateDownloadActions,
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
            installPermissionState.updateInstallPermission(
                manifest = manifest,
                allowed = canRequestPackageInstalls(),
            )
        }
    }

    override fun requestInstallPermission(manifest: UpdateManifest) {
        val current = installPermissionState.state.value as? AppUpdateState.NeedsInstallPermission ?: return
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
        val current = installPermissionState.state.value
        val manifest = when (current) {
            is AppUpdateState.ReadyToInstall -> current.manifest
            is AppUpdateState.NeedsInstallPermission -> current.manifest
            else -> return
        }
        appScope.launch {
            installPermissionState.updateInstallPermission(
                manifest = manifest,
                allowed = canRequestPackageInstalls(),
            )
        }
    }
}
