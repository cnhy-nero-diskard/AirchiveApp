package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Exact event-trigger tests for the ten-minute foreground/manual refresh policy. */
class ForegroundRefreshControllerTest {

    private val nowEpochMillis = 1_750_000_000_000L
    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-refresh-trigger",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    @Test
    fun openAndResumeRefreshOnlyAtTenMinutesButManualAlwaysRefreshes() = runBlocking {
        val local = FakeLocalHistory()
        val telemetry = CountingMutationTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = emptyList()),
        )
        val incremental = IncrementalSyncCoordinator(telemetry, local, partition)
        val controller = ForegroundRefreshController(local, partition, incremental::syncIncremental)

        // First app open has no prior success, so it refreshes once.
        val firstOpen = controller.onForeground(nowEpochMillis)
        assertIs<ForegroundRefreshOutcome.Refreshed>(firstOpen)
        assertEquals(1, telemetry.persistedCalls)
        assertEquals(1, telemetry.reconciledCalls)
        assertEquals(nowEpochMillis, local.observe(partition).first().sync?.lastSuccessfulSyncEpochMillis)

        // A resume just under the threshold does no remote work.
        val earlyResume = controller.onForeground(
            nowEpochMillis + ForegroundRefreshController.MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS - 1,
        )
        assertEquals(
            ForegroundRefreshOutcome.SkippedFresh(nowEpochMillis),
            earlyResume,
        )
        assertEquals(1, telemetry.persistedCalls)
        assertEquals(1, telemetry.reconciledCalls)

        // At exactly ten minutes, a foreground resume queries both streams.
        val dueResumeTime = nowEpochMillis + ForegroundRefreshController.MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS
        val dueResume = controller.onForeground(dueResumeTime)
        assertIs<ForegroundRefreshOutcome.Refreshed>(dueResume)
        assertEquals(2, telemetry.persistedCalls)
        assertEquals(2, telemetry.reconciledCalls)
        assertEquals(dueResumeTime, local.observe(partition).first().sync?.lastSuccessfulSyncEpochMillis)

        // Manual refresh bypasses the age gate and calls both streams again.
        val manualTime = dueResumeTime + 1
        val manual = controller.onManualRefresh(manualTime)
        // Empty streams cannot invent server mutation watermarks from the
        // handset clock, even though this manual refresh updates last-success.
        assertNull(manual.persistedAtWatermarkEpochMillis)
        assertNull(manual.reconciledAtWatermarkEpochMillis)
        assertEquals(3, telemetry.persistedCalls)
        assertEquals(3, telemetry.reconciledCalls)
        assertEquals(manualTime, local.observe(partition).first().sync?.lastSuccessfulSyncEpochMillis)
    }

    @Test
    fun concurrentForegroundCallbacksRecheckAgeAfterTheFirstCommit() = runBlocking {
        val local = FakeLocalHistory()
        val telemetry = CountingMutationTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = emptyList()),
        )
        val incremental = IncrementalSyncCoordinator(telemetry, local, partition)
        val controller = ForegroundRefreshController(local, partition, incremental::syncIncremental)

        val first = controller.onForeground(nowEpochMillis)
        assertIs<ForegroundRefreshOutcome.Refreshed>(first)
        val second = controller.onForeground(nowEpochMillis)
        assertEquals(
            ForegroundRefreshOutcome.SkippedFresh(nowEpochMillis),
            second,
        )
        assertEquals(1, telemetry.persistedCalls)
        assertEquals(1, telemetry.reconciledCalls)
    }

    @Test
    fun clockMovingBackwardDoesNotCreateAnEarlyForegroundRefresh() = runBlocking {
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    sync = SyncMetadata(lastSuccessfulSyncEpochMillis = nowEpochMillis),
                ),
            )
        }
        val telemetry = CountingMutationTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = emptyList()),
        )
        val incremental = IncrementalSyncCoordinator(telemetry, local, partition)
        val controller = ForegroundRefreshController(local, partition, incremental::syncIncremental)

        val outcome = controller.onForeground(nowEpochMillis - 60_000L)

        assertEquals(ForegroundRefreshOutcome.SkippedFresh(nowEpochMillis), outcome)
        assertEquals(0, telemetry.persistedCalls)
        assertEquals(0, telemetry.reconciledCalls)
        assertNull(local.observe(partition).first().sync?.lastError)
    }

    private class CountingMutationTelemetry(
        private val delegate: AirchiveTelemetryPort,
    ) : AirchiveTelemetryPort by delegate {

        var persistedCalls: Int = 0
            private set
        var reconciledCalls: Int = 0
            private set

        override suspend fun changedSincePersistedAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            persistedCalls += 1
            return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSinceReconciledAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            reconciledCalls += 1
            return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
        }
    }
}
