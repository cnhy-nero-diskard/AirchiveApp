package cloud.airchive.shared.data.update

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatePendingInstallOutcomeTest {
    private val pendingRecord = PendingInstallRecord(targetVersionCode = 42L, startedAtEpochMillis = 100L)

    @Test
    fun successfulUpdateRemainsRecoverableUntilItsResultIsAcknowledged() {
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
        assertEquals(pendingRecord, preferences.appUpdateState.value.pendingInstall)

        val nextStartBeforeAcknowledgement = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 42L,
            installedVersionName = "2.4.0",
            hasActiveInstallSession = false,
        )
        assertEquals(AppUpdateState.Updated("2.4.0"), nextStartBeforeAcknowledgement)
        assertFalse(acknowledgePendingInstallOutcome(preferences, 41L, firstStart!!))
        assertEquals(pendingRecord, preferences.appUpdateState.value.pendingInstall)
        assertTrue(acknowledgePendingInstallOutcome(preferences, 42L, firstStart))
        assertNull(preferences.appUpdateState.value.pendingInstall)

        val nextStartAfterAcknowledgement = resolvePendingInstallAtStartup(
            preferences = preferences,
            installedVersionCode = 42L,
            installedVersionName = "2.4.0",
            hasActiveInstallSession = false,
        )
        assertNull(nextStartAfterAcknowledgement)
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
        assertEquals(pendingRecord, preferences.appUpdateState.value.pendingInstall)
        assertTrue(acknowledgePendingInstallOutcome(preferences, 41L, state!!))
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
    fun serviceAcknowledgesPresentedStartupOutcomeAndClearsDurableRecord() = runBlocking {
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(pendingInstall = pendingRecord),
        )
        val service = AppUpdateService(
            httpClient = AppUpdateHttpClient(
                engine = MockEngine { error("Acknowledging a startup result must not make a request.") },
                userAgent = "Airchive/2.4.0",
            ),
            feedUrl = "https://github.com/example/airchive/manifest.json",
            expectedPackageName = "cloud.airchive.personal",
            pinnedSignerSha256 = "c".repeat(64),
            installedVersionCode = pendingRecord.targetVersionCode,
            preferences = preferences,
            nowEpochMillis = { 200L },
            initialState = AppUpdateState.Updated("2.4.0"),
        )

        try {
            service.acknowledgeStartupOutcome()

            assertEquals(AppUpdateState.Idle, service.state.value)
            assertNull(preferences.appUpdateState.value.pendingInstall)
        } finally {
            service.close()
        }
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
