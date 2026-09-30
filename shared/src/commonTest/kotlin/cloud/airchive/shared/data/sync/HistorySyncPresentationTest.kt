package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cache-first status copy keeps offline/denied/error states distinguishable by text. */
class HistorySyncPresentationTest {

    private val now = 1_750_000_000_000L
    private val partition = HistoryPartition("demo-project", "owner-uid", "demo-device")

    @Test
    fun freshOfflineInstallGetsActionableEmptyStateAndFailureAge() {
        val presentation = presentHistorySync(
            snapshot = HistorySnapshot(
                partition = partition,
                observations = emptyList(),
                dailyTotals = emptyList(),
                coveredRanges = emptyList(),
                sync = SyncMetadata(lastErrorEpochMillis = now - 2 * 60_000L, lastError = "OFFLINE"),
            ),
            nowEpochMillis = now,
        )

        assertFalse(presentation.hasCachedHistory)
        assertTrue(presentation.message.contains("No history is cached yet"))
        assertTrue(presentation.message.contains("Connect to the internet"))
        assertEquals(2 * 60_000L, presentation.lastErrorAgeMillis)
        assertNull(presentation.lastSuccessfulAgeMillis)
        assertTrue(presentation.shouldAnnounceUpdate)
        assertEquals("Retry history sync", presentation.retryLabel)
    }

    @Test
    fun deniedRefreshKeepsCachedRowsAndShowsBothSuccessAndErrorAges() {
        val cached = observation()
        val presentation = presentHistorySync(
            snapshot = HistorySnapshot(
                partition = partition,
                observations = listOf(cached),
                dailyTotals = emptyList(),
                coveredRanges = emptyList(),
                sync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = now - 35 * 60_000L,
                    lastErrorEpochMillis = now - 3 * 60_000L,
                    lastError = "ACCESS_DENIED",
                    persistedAtWatermarkEpochMillis = now - 40 * 60_000L,
                    reconciledAtWatermarkEpochMillis = now - 45 * 60_000L,
                ),
            ),
            nowEpochMillis = now,
        )

        assertTrue(presentation.hasCachedHistory)
        assertTrue(presentation.message.contains("access is pending or denied"))
        assertTrue(presentation.message.contains("Showing cached history"))
        assertEquals(35 * 60_000L, presentation.lastSuccessfulAgeMillis)
        assertEquals(3 * 60_000L, presentation.lastErrorAgeMillis)
        assertEquals("Retry history sync", presentation.retryLabel)
        // The presentation is read-only: a denied refresh does not clear data.
        assertEquals("cached-sample", cached.sampleId)
    }

    @Test
    fun cachedOfflineStateAndStalenessRemainExplicit() {
        val offline = presentHistorySync(
            snapshot = HistorySnapshot(
                partition = partition,
                observations = listOf(observation()),
                dailyTotals = emptyList(),
                coveredRanges = emptyList(),
                sync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = now - 12 * 60_000L,
                    lastErrorEpochMillis = now - 1_000L,
                    lastError = "OFFLINE",
                ),
            ),
            nowEpochMillis = now,
        )

        assertTrue(offline.message.contains("could not be reached"))
        assertTrue(offline.message.contains("stays available offline"))
        assertEquals(12 * 60_000L, offline.lastSuccessfulAgeMillis)
        assertTrue(offline.shouldAnnounceUpdate)

        val staleWithoutError = presentHistorySync(
            snapshot = HistorySnapshot(
                partition = partition,
                observations = listOf(observation()),
                dailyTotals = emptyList(),
                coveredRanges = emptyList(),
                sync = SyncMetadata(lastSuccessfulSyncEpochMillis = now - 11 * 60_000L),
            ),
            nowEpochMillis = now,
        )
        assertTrue(staleWithoutError.message.contains("at least ten minutes old"))
        assertFalse(staleWithoutError.shouldAnnounceUpdate)
    }

    private fun observation() = Observation(
        sampleId = "cached-sample",
        deviceId = partition.deviceId,
        intervalStartEpochMillis = now - 300_000L,
        intervalEndEpochMillis = now,
        energyValue = 2.5,
        recordedUnit = EnergyUnit.WH,
    )
}
