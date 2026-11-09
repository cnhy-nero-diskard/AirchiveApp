package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdatePendingInstallOutcomeTest {
    private val pendingRecord = PendingInstallRecord(targetVersionCode = 42L, startedAtEpochMillis = 100L)

    @Test
    fun successfulUpdateIsShownOnceAndClearsItsRecord() {
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(pendingInstall = pendingRecord),
        )

        val firstStart = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 42L,
            installedVersionName = "2.4.0",
            hasActiveInstallSession = false,
        )

        assertEquals(AppUpdateState.Updated("2.4.0"), firstStart)
        assertNull(preferences.appUpdateState.value.pendingInstall)

        val nextStart = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 42L,
            installedVersionName = "2.4.0",
            hasActiveInstallSession = false,
        )
        assertNull(nextStart)
    }

    @Test
    fun lowerInstalledCodeWithoutAnActiveSessionOffersRetry() {
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(pendingInstall = pendingRecord),
        )

        val state = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 41L,
            installedVersionName = "2.3.9",
            hasActiveInstallSession = false,
        )

        assertEquals(AppUpdateState.NotCompleted("2.3.9"), state)
        assertNull(preferences.appUpdateState.value.pendingInstall)
    }

    @Test
    fun activeSessionKeepsThePendingRecordForALaterStart() {
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(pendingInstall = pendingRecord),
        )

        val state = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 41L,
            installedVersionName = "2.3.9",
            hasActiveInstallSession = true,
        )

        assertNull(state)
        assertEquals(pendingRecord, preferences.appUpdateState.value.pendingInstall)
    }

    @Test
    fun pendingRecordIsWrittenBeforeSessionCommit() {
        val preferences = InMemoryAppUpdatePreferences()
        var recordWasPresentWhenCommitted = false

        recordPendingInstallBeforeSessionCommit(preferences, pendingRecord) {
            recordWasPresentWhenCommitted = preferences.appUpdateState.value.pendingInstall == pendingRecord
        }

        assertEquals(true, recordWasPresentWhenCommitted)
    }
}
