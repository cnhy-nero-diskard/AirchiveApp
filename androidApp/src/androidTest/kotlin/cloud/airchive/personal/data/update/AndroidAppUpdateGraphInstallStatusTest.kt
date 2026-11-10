package cloud.airchive.personal.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import cloud.airchive.personal.AndroidAppUpdateGraph
import cloud.airchive.shared.data.update.AppUpdatePreferencesSnapshot
import cloud.airchive.shared.data.update.InMemoryAppUpdatePreferences
import cloud.airchive.shared.data.update.PendingInstallRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidAppUpdateGraphInstallStatusTest {
    @Test
    fun coldFinalStatusLeavesPendingRecordForForegroundStartup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = PendingInstallRecord(targetVersionCode = 6L, startedAtEpochMillis = 100L)
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(pendingInstall = pending),
        )
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var preferencesRead = false
        val graph = AndroidAppUpdateGraph(
            context = context,
            preferences = { preferencesRead = true; preferences },
            appScope = appScope,
            isReleaseBuild = true,
        )

        try {
            graph.handleInstallStatus(
                Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS),
            )

            assertFalse(preferencesRead)
            assertEquals(pending, preferences.appUpdateState.value.pendingInstall)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun coldPendingUserActionLaunchesConfirmationWithoutBuildingUpdaterGraph() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = InMemoryAppUpdatePreferences()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var preferencesRead = false
        var launchedIntent: Intent? = null
        val confirmationIntent = Intent("cloud.airchive.test.CONFIRM_INSTALL")
        val graph = AndroidAppUpdateGraph(
            context = context,
            preferences = { preferencesRead = true; preferences },
            appScope = appScope,
            isReleaseBuild = true,
            launchColdConfirmation = { launchedIntent = it },
        )

        try {
            graph.handleInstallStatus(
                Intent()
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                    .putExtra(Intent.EXTRA_INTENT, confirmationIntent),
            )

            assertFalse(preferencesRead)
            assertNotNull(launchedIntent)
            assertEquals(confirmationIntent.action, launchedIntent?.action)
            assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK,
                launchedIntent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } finally {
            appScope.cancel()
        }
    }
}
