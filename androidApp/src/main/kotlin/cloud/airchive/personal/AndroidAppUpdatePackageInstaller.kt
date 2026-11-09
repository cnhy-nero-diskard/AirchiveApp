package cloud.airchive.personal

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import cloud.airchive.shared.data.update.AppUpdateInstallControllerPort
import cloud.airchive.shared.data.update.AppUpdateInstallOutcome
import cloud.airchive.shared.data.update.AppUpdateInstallSessionPolicy
import cloud.airchive.shared.data.update.AppUpdateInstallStatus
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateFailureReason
import cloud.airchive.shared.data.update.UpdateManifest
import cloud.airchive.shared.data.update.appUpdateInstallSessionPolicy
import cloud.airchive.shared.data.update.mapAppUpdateInstallStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Writes a verified APK to a full-install session and forwards PackageInstaller callbacks. */
class AndroidAppUpdatePackageInstaller(
    context: Context,
    private val installController: AppUpdateInstallControllerPort,
    private val apkForVersionCode: (Long) -> File?,
    private val appScope: CoroutineScope,
    private val launchConfirmation: (Intent) -> Unit = { intent ->
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    },
    private val packageInstaller: PackageInstaller = context.packageManager.packageInstaller,
) {
    private val appContext = context.applicationContext

    suspend fun install(manifest: UpdateManifest) {
        if (!installController.startInstall(manifest)) return
        val apk = apkForVersionCode(manifest.versionCode)
        if (apk == null || !apk.isFile) {
            installController.reportInstallOutcome(
                manifest,
                AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_ERROR),
            )
            return
        }

        var sessionId: Int? = null
        try {
            sessionId = packageInstaller.createSession(
                createAppUpdateSessionParams(
                    packageName = manifest.packageName,
                    apiLevel = Build.VERSION.SDK_INT,
                    policy = appUpdateInstallSessionPolicy(Build.VERSION.SDK_INT),
                ),
            )
            packageInstaller.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(APK_SESSION_ENTRY, 0L, apk.length()).use { output ->
                        input.copyTo(output, COPY_BUFFER_BYTES)
                        session.fsync(output)
                    }
                }
                session.commit(statusPendingIntent(sessionId).intentSender)
            }
        } catch (cancelled: CancellationException) {
            sessionId?.let(::abandonSessionSafely)
            throw cancelled
        } catch (failure: Exception) {
            sessionId?.let(::abandonSessionSafely)
            val reason = if (failure is IOException) UpdateFailureReason.INSTALL_STORAGE
            else UpdateFailureReason.INSTALL_ERROR
            installController.reportInstallOutcome(manifest, AppUpdateInstallOutcome.Failed(reason))
        }
    }

    fun handleStatusIntent(statusIntent: Intent) {
        val status = statusIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val outcome = mapAppUpdateInstallStatus(status.toAppUpdateInstallStatus())
        if (outcome == AppUpdateInstallOutcome.AwaitingUserAction) {
            val confirmationIntent = statusIntent.confirmationIntent()
            if (confirmationIntent == null) {
                reportFailure(UpdateFailureReason.INSTALL_ERROR)
                return
            }
            try {
                launchConfirmation(confirmationIntent)
            } catch (_: ActivityNotFoundException) {
                reportFailure(UpdateFailureReason.INSTALL_ERROR)
            } catch (_: SecurityException) {
                reportFailure(UpdateFailureReason.INSTALL_ERROR)
            }
            return
        }

        val manifest = (installController.state.value as? AppUpdateState.Installing)?.manifest ?: return
        appScope.launch { installController.reportInstallOutcome(manifest, outcome) }
    }

    private fun statusPendingIntent(sessionId: Int): PendingIntent {
        val statusIntent = Intent(appContext, AndroidAppUpdateInstallStatusReceiver::class.java)
            .setAction("${appContext.packageName}$STATUS_ACTION_SUFFIX")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            appContext,
            sessionId,
            statusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    private fun reportFailure(reason: UpdateFailureReason) {
        val manifest = (installController.state.value as? AppUpdateState.Installing)?.manifest ?: return
        appScope.launch {
            installController.reportInstallOutcome(manifest, AppUpdateInstallOutcome.Failed(reason))
        }
    }

    private fun abandonSessionSafely(sessionId: Int) {
        try {
            packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {
            // Preserve the original write/commit failure; any remaining session expires in Android.
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

    private fun Int.toAppUpdateInstallStatus(): AppUpdateInstallStatus = when (this) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> AppUpdateInstallStatus.PENDING_USER_ACTION
        PackageInstaller.STATUS_SUCCESS -> AppUpdateInstallStatus.SUCCESS
        PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdateInstallStatus.FAILURE_ABORTED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> AppUpdateInstallStatus.FAILURE_CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> AppUpdateInstallStatus.FAILURE_INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_STORAGE -> AppUpdateInstallStatus.FAILURE_STORAGE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> AppUpdateInstallStatus.FAILURE_BLOCKED
        PackageInstaller.STATUS_FAILURE_INVALID -> AppUpdateInstallStatus.FAILURE_INVALID
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> AppUpdateInstallStatus.FAILURE_TIMEOUT
        PackageInstaller.STATUS_FAILURE -> AppUpdateInstallStatus.FAILURE
        else -> AppUpdateInstallStatus.FAILURE
    }

    companion object {
        private const val APK_SESSION_ENTRY = "base.apk"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val STATUS_ACTION_SUFFIX = ".APP_UPDATE_INSTALL_STATUS"
    }
}

internal fun createAppUpdateSessionParams(
    packageName: String,
    apiLevel: Int,
    policy: AppUpdateInstallSessionPolicy = appUpdateInstallSessionPolicy(apiLevel),
): PackageInstaller.SessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    .apply {
        setAppPackageName(packageName)
        if (policy.requireUserAction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
    }

class AndroidAppUpdateInstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? AirchiveApplication)?.handleAppUpdateInstallStatus(intent)
    }
}
