package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-backed Room KMP integration for the first-load sync coordinator.
 *
 * These run host-side on the JVM (`androidHostTest`) against real SQLite files
 * via the bundled driver, proving the coordinator persists the complete initial
 * window and bookkeeping into the durable [RoomLocalHistoryPort] — not just the
 * in-memory fake. The coordinator under test is the same commonMain class used
 * on every platform.
 */
class FirstLoadSyncRoomHostTest {

    private val nowEpochMillis = 1_750_000_000_000L

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-1",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    @Test
    fun firstLoadPersistsFullWindowWithTotalsHealthCoverageAndSync() = runBlocking {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val local = RoomLocalHistoryPort(database)
                val telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)

                val result = FirstLoadSyncCoordinator(telemetry, local, partition)
                    .syncFirstLoad(nowEpochMillis = nowEpochMillis)

                assertEquals(288, result.observationsPersisted)
                assertEquals(30, result.dailyTotalsPersisted)
                assertTrue(result.collectorHealthPersisted)
                assertNotNull(result.coveredRange)

                val snapshot = local.observe(partition).first()
                assertEquals(288, snapshot.observations.size)
                assertEquals(288, snapshot.observations.map { it.sampleId }.toSet().size)
                assertEquals(30, snapshot.dailyTotals.size)
                assertNotNull(snapshot.collectorHealth)
                assertEquals(CollectorStatus.HEALTHY, snapshot.collectorHealth.status)

                // Null interval energy survives the Room round trip as null.
                val missing = snapshot.observations.filter { it.energyValue == null }
                assertTrue(missing.isNotEmpty(), "fixture must contain missing intervals")
                missing.forEach { assertNull(it.energyValue) }

                assertEquals(1, snapshot.coveredRanges.size)
                val sync = assertNotNull(snapshot.sync)
                assertEquals(nowEpochMillis, sync.lastSuccessfulSyncEpochMillis)
                assertEquals(nowEpochMillis, sync.persistedAtWatermarkEpochMillis)
                assertEquals(nowEpochMillis, sync.reconciledAtWatermarkEpochMillis)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun firstLoadNeverStoresOutOfWindowRowsInRoom() = runBlocking {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val local = RoomLocalHistoryPort(database)
                val telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)

                FirstLoadSyncCoordinator(telemetry, local, partition)
                    .syncFirstLoad(nowEpochMillis = nowEpochMillis)

                val snapshot = local.observe(partition).first()
                val windowFrom = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS
                // Every persisted row overlaps the initial 24-hour window.
                snapshot.observations.forEach { observation ->
                    assertTrue(observation.intervalEndEpochMillis >= windowFrom)
                    assertTrue(observation.intervalStartEpochMillis < nowEpochMillis)
                }
                // Nothing older than the window sneaks into Room.
                assertTrue(snapshot.observations.none { it.sampleId == "old-outside-window" })
                assertEquals(
                    windowFrom,
                    snapshot.coveredRanges.single().fromEpochMillis,
                )
                assertEquals(
                    nowEpochMillis,
                    snapshot.coveredRanges.single().toEpochMillis,
                )
            } finally {
                database.close()
            }
        }
    }

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-sync-room-test").toFile()
        val dbFile = File(dir, "history.db")
        try {
            runBlocking { block(dbFile) }
        } finally {
            dbFile.delete()
            File(dir, "history.db-wal").delete()
            File(dir, "history.db-shm").delete()
            dir.delete()
        }
    }
}
