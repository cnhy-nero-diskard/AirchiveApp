package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Atomic incremental-refresh commit tests for the durable [RoomLocalHistoryPort]
 * and the in-memory [FakeLocalHistory].
 *
 * The Room test runs host-side on the JVM against a real SQLite file opened with
 * [openAirchiveDatabase], seeds prior observations + prior [SyncMetadata],
 * and injects a local SQL failure at the metadata write (a temporary
 * `BEFORE INSERT ON sync_metadata` trigger that `RAISE(ABORT, ...)`s) to prove
 * [LocalHistoryPort.upsertObservationsAndRecordSync] rolls back the observation
 * upserts too — neither new rows nor advanced watermarks survive a failed
 * commit — and that a retry after the trigger is removed commits both.
 */
class AtomicIncrementalRefreshRoomHostTest {

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-atomic",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    @Test
    fun atomicRefreshRollsBackObservationsAndWatermarksThenRetryCommitsBoth() = runBlocking {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(dbFile.absolutePath)
            val injection = BundledSQLiteDriver().open(dbFile.absolutePath)
            try {
                val local = RoomLocalHistoryPort(database)

                // ---- Seed prior observations + prior sync metadata. ----
                val prior = observation(sampleId = "prior-1", start = 1_000L, end = 1_300L)
                val priorSync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = 9_000L,
                    lastErrorEpochMillis = 8_000L,
                    lastError = "prior-outage",
                    persistedAtWatermarkEpochMillis = 8_500L,
                    reconciledAtWatermarkEpochMillis = 8_700L,
                )
                local.upsertObservations(partition, listOf(prior))
                local.recordSync(partition, priorSync)

                // ---- Inject a local SQL failure at the metadata write: any
                // INSERT into sync_metadata (the statement AFTER the
                // observation upsert in the atomic commit) aborts. ----
                injection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS fail_sync_metadata_write " +
                        "BEFORE INSERT ON `sync_metadata` " +
                        "BEGIN SELECT RAISE(ABORT, 'injected: sync_metadata write failure'); END",
                )

                val fresh = observation(sampleId = "fresh-1", start = 1_300L, end = 1_600L)
                val advanced = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = 9_500L,
                    persistedAtWatermarkEpochMillis = 9_200L,
                    reconciledAtWatermarkEpochMillis = 9_400L,
                )

                // ---- The atomic commit must fail... ----
                val failure = runCatching {
                    local.upsertObservationsAndRecordSync(partition, listOf(fresh), advanced)
                }.exceptionOrNull()
                assertNotNull(
                    failure,
                    "atomic refresh must throw while the sync_metadata write is blocked",
                )

                // ---- ...and leave BOTH the prior observation and the old
                // watermarks completely untouched. ----
                val afterFailure = local.observe(partition).first()
                assertEquals(listOf(prior), afterFailure.observations)
                assertEquals(priorSync, afterFailure.sync)

                // ---- Remove the failure injection and retry: both commit. ----
                injection.execSQL("DROP TRIGGER IF EXISTS fail_sync_metadata_write")
                local.upsertObservationsAndRecordSync(partition, listOf(fresh), advanced)

                val afterRetry = local.observe(partition).first()
                assertEquals(listOf(prior, fresh), afterRetry.observations)
                assertEquals(advanced, afterRetry.sync)
            } finally {
                injection.close()
                database.close()
            }
        }
    }

    @Test
    fun fakeAtomicRefreshThrowsWithoutTouchingRowsOrMetadata() = runBlocking {
        val local = FakeLocalHistory()
        val prior = observation(sampleId = "prior-1", start = 1_000L, end = 1_300L)
        val priorSync = SyncMetadata(lastSuccessfulSyncEpochMillis = 9_000L)

        local.upsertObservations(partition, listOf(prior))
        local.recordSync(partition, priorSync)

        // Inject a failure: the next atomic commit must throw with NO state change.
        local.injectAtomicRefreshFailure = IllegalStateException("injected atomic refresh failure")
        val failure = runCatching {
            local.upsertObservationsAndRecordSync(
                partition,
                listOf(observation(sampleId = "fresh-1", start = 1_300L, end = 1_600L)),
                SyncMetadata(lastSuccessfulSyncEpochMillis = 9_500L),
            )
        }.exceptionOrNull()
        assertNotNull(failure, "injected atomic refresh failure must propagate")

        val untouched = local.observe(partition).first()
        assertEquals(listOf(prior), untouched.observations)
        assertEquals(priorSync, untouched.sync)

        // Clear the injection and retry: both the observation and the metadata commit.
        local.injectAtomicRefreshFailure = null
        val fresh = observation(sampleId = "fresh-1", start = 1_300L, end = 1_600L)
        val advanced = SyncMetadata(lastSuccessfulSyncEpochMillis = 9_500L)
        local.upsertObservationsAndRecordSync(partition, listOf(fresh), advanced)

        val committed = local.observe(partition).first()
        assertEquals(listOf(prior, fresh), committed.observations)
        assertEquals(advanced, committed.sync)
    }

    private fun observation(sampleId: String, start: Long, end: Long) = Observation(
        sampleId = sampleId,
        deviceId = partition.deviceId,
        intervalStartEpochMillis = start,
        intervalEndEpochMillis = end,
        energyValue = 2.1,
    )

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-atomic-refresh-room-test").toFile()
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