package cloud.airchive.personal.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import cloud.airchive.personal.AndroidAppUpdateActionsController
import cloud.airchive.shared.data.update.AppUpdateDownloadActions
import cloud.airchive.shared.data.update.AppUpdateInstallPermissionPort
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateApk
import cloud.airchive.shared.data.update.UpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppUpdateActionsControllerTest {
    @Test
    fun returningWithoutPermissionStaysInNeedsInstallPermission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = testManifest()
        val permissionState = FakeInstallPermissionState(AppUpdateState.ReadyToInstall(manifest))
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var launchedIntent: Intent? = null
        val actions = AndroidAppUpdateActionsController(
            context = context,
            installPermissionState = permissionState,
            downloadActions = NoopDownloadActions,
            appScope = appScope,
            canRequestPackageInstalls = { false },
            launchSettings = { launchedIntent = it },
        )

        try {
            actions.install(manifest)
            assertEquals(AppUpdateState.NeedsInstallPermission(manifest), permissionState.state.value)

            actions.requestInstallPermission(manifest)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launchedIntent?.action)
            assertEquals(Uri.parse("package:${context.packageName}"), launchedIntent?.data)

            actions.recheckInstallPermission()
            assertEquals(AppUpdateState.NeedsInstallPermission(manifest), permissionState.state.value)
        } finally {
            appScope.cancel()
        }
    }

    private fun testManifest() = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = "cloud.airchive.personal",
        versionCode = 1_001L,
        versionName = "10.0.1",
        commit = "a1b2c3d4e5f6",
        publishedAt = "2026-11-09T00:00:00Z",
        releaseNotes = "Permission test",
        apk = UpdateApk(
            url = "https://github.com/example/AirchiveApp/releases/download/v10.0.1/app.apk",
            sha256 = "ab".repeat(32),
            size = 1_024_000L,
            signingCertSha256 = "cd".repeat(32),
        ),
    )

    private class FakeInstallPermissionState(initialState: AppUpdateState) : AppUpdateInstallPermissionPort {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<AppUpdateState> = mutableState

        override suspend fun updateInstallPermission(manifest: UpdateManifest, allowed: Boolean) {
            when (val current = mutableState.value) {
                is AppUpdateState.ReadyToInstall -> if (current.manifest == manifest && !allowed) {
                    mutableState.value = AppUpdateState.NeedsInstallPermission(manifest)
                }
                is AppUpdateState.NeedsInstallPermission -> if (current.manifest == manifest && allowed) {
                    mutableState.value = AppUpdateState.ReadyToInstall(manifest)
                }
                else -> Unit
            }
        }
    }

    private object NoopDownloadActions : AppUpdateDownloadActions {
        override fun download(manifest: UpdateManifest) = Unit
        override fun cancelDownload() = Unit
    }
}
