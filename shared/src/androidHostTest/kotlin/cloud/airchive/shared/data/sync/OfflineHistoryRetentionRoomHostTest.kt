package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-backed Room KMP integration for task 2.9: cached history stays honest
 * offline.
 *
 * These run host-side on the JVM (`androidHostTest`) against a real SQLite file
 * via [openAirchiveDatabase] and exercise [IncrementalSyncCoordinator] against
 * the durable [RoomLocalHistoryPort]. They prove the offline stories the task
 * describes without any network or secrets:
 *
 *  1. A prior owner's partition is seeded with an [Observation] plus
 *     [SyncMetadata] (last-success + both mutation watermarks), the database is
 *     closed and reopened to simulate an offline app restart, and an
 *     incremental sync driven by an [AirchiveTelemetryPort] decorator that
 *     throws a typed [TelemetryReadFailure.Offline] before serving any page
 *     rethrows the failure while Room keeps the old observation, last-success,
 *     and both watermarks untouched — the only metadata change is the safe
 *     `OFFLINE` code plus the explicit attempt time.
 *  2. A second, never-touched owner partition stays completely empty/isolated
 *     afterwards: the failed sync wrote nothing and leaked no error metadata
 *     into another owner's cache.
 *  3. A separate fresh install partition fails offline with no observations,
 *     totals, or collector health, null last-success and watermarks, and the
 *     `OFFLINE` timestamp/code recorded — exactly the durable state the UI
 *     needs to render an actionable empty state instead of fake data.
 */
class OfflineHistoryRetentionRoomHostTest {

    /** Clock used when the prior owner's cache was last successfully synced. */
    private val seedEpochMillis = 1_750_000_000_000L

    /**
     * The explicit attempt time of the offline restart, passed to
     * [IncrementalSyncCoordinator.syncIncremental] as `nowEpochMillis`; the
     * coordinator records it as the sync-error timestamp.
     */
    private val attemptEpochMillis = seedEpochMillis + 15L * 60 * 1000

    private val persistedWatermarkEpochMillis = seedEpochMillis - 120_000L
    private val reconciledWatermarkEpochMillis = seedEpochMillis - 60_000L
    private val lastSuccessfulSyncEpochMillis = seedEpochMillis - 300_000L

    private val priorOwner = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-prior",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    /** A different signed-in owner; its cache must stay empty and isolated. */
    private val otherOwner = priorOwner.copy(ownerUid = "owner-uid-other")

    /** A brand-new installation partition with no prior cache at all. */
    private val freshInstall = priorOwner.copy(ownerUid = "owner-uid-fresh")

    @Test
    fun offlineRestartRethrowsOfflineAndRetainsPriorRowsLastSuccessAndWatermarks() = runBlocking {
        withTempDb { dbFile ->
            // ---- Session 1: seed the prior owner's durable cache. ----
            val firstSession = openAirchiveDatabase(dbFile.absolutePath)
            val cached = cachedObservation()
            val priorSync = SyncMetadata(
                lastSuccessfulSyncEpochMillis = lastSuccessfulSyncEpochMillis,
                lastErrorEpochMillis = lastSuccessfulSyncEpochMillis - 60_000L,
                lastError = "REMOTE_FAILURE", // stale pre-offline error, must be replaced
                persistedAtWatermarkEpochMillis = persistedWatermarkEpochMillis,
                reconciledAtWatermarkEpochMillis = reconciledWatermarkEpochMillis,
            )
            try {
                val firstLocal = RoomLocalHistoryPort(firstSession)
                firstLocal.upsertObservations(priorOwner, listOf(cached))
                firstLocal.recordSync(priorOwner, priorSync)
            } finally {
                firstSession.close()
            }

            // ---- Session 2: offline app restart on the same file. ----
            val reopened = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val local = RoomLocalHistoryPort(reopened)
                val offline = OfflineIncrementalTelemetry()

                val error = assertFailsWith<TelemetryReadFailure.Offline> {
                    IncrementalSyncCoordinator(offline, local, priorOwner)
                        .syncIncremental(nowEpochMillis = attemptEpochMillis)
                }
                assertEquals("OFFLINE", error.code)
                // The typed Offline threw on the very first incremental stream
                // read, before a single page of either stream was served.
                assertEquals(0, offline.incrementalPagesServed)
                assertEquals(1, offline.persistedCalls)
                assertEquals(0, offline.reconciledCalls)

                // The old observation survived the failed sync exactly as seeded.
                val snapshot = local.observe(priorOwner).first()
                assertEquals(listOf(cached), snapshot.observations)

                // last-success and both watermarks unchanged; only the safe
                // OFFLINE code and the explicit attempt time were written.
                val sync = assertNotNull(snapshot.sync)
                assertEquals(lastSuccessfulSyncEpochMillis, sync.lastSuccessfulSyncEpochMillis)
                assertEquals(persistedWatermarkEpochMillis, sync.persistedAtWatermarkEpochMillis)
                assertEquals(reconciledWatermarkEpochMillis, sync.reconciledAtWatermarkEpochMillis)
                assertEquals(attemptEpochMillis, sync.lastErrorEpochMillis)
                assertEquals("OFFLINE", sync.lastError)

                // The second owner partition remains completely empty/isolated:
                // the failed sync wrote nothing and leaked no error metadata
                // into another owner's cache.
                val isolated = local.observe(otherOwner).first()
                assertTrue(isolated.observations.isEmpty())
                assertTrue(isolated.dailyTotals.isEmpty())
                assertTrue(isolated.coveredRanges.isEmpty())
                assertNull(isolated.collectorHealth)
                assertEquals(SyncMetadata(), isolated.sync)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun freshOfflineInstallRecordsActionableOfflineEmptyState() = runBlocking {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val local = RoomLocalHistoryPort(database)
                val offline = OfflineIncrementalTelemetry()

                val error = assertFailsWith<TelemetryReadFailure.Offline> {
                    IncrementalSyncCoordinator(offline, local, freshInstall)
                        .syncIncremental(nowEpochMillis = attemptEpochMillis)
                }
                assertEquals("OFFLINE", error.code)
                assertEquals(0, offline.incrementalPagesServed)
                assertEquals(1, offline.persistedCalls)
                assertEquals(0, offline.reconciledCalls)

                // Nothing fake was ever written: no observations, totals,
                // coverage, or health — and no fabricated last-success or
                // watermarks. Only the OFFLINE empty-state bookkeeping exists,
                // with the explicit attempt time, so the UI can render an
                // actionable empty state ("can't reach Airchive, try again").
                val snapshot = local.observe(freshInstall).first()
                assertTrue(snapshot.observations.isEmpty())
                assertTrue(snapshot.dailyTotals.isEmpty())
                assertTrue(snapshot.coveredRanges.isEmpty())
                assertNull(snapshot.collectorHealth)
                val sync = assertNotNull(snapshot.sync)
                assertNull(sync.lastSuccessfulSyncEpochMillis)
                assertNull(sync.persistedAtWatermarkEpochMillis)
                assertNull(sync.reconciledAtWatermarkEpochMillis)
                assertEquals(attemptEpochMillis, sync.lastErrorEpochMillis)
                assertEquals("OFFLINE", sync.lastError)
            } finally {
                database.close()
            }
        }
    }

    private fun cachedObservation() = Observation(
        sampleId = "cached-offline-sample",
        deviceId = priorOwner.deviceId,
        intervalStartEpochMillis = seedEpochMillis - 20L * 60 * 1000,
        intervalEndEpochMillis = seedEpochMillis - 15L * 60 * 1000,
        energyValue = 2.1,
        recordedUnit = EnergyUnit.WH,
        intervalStatus = "OK",
        quality = ObservationQuality.NORMAL,
        persistedAtEpochMillis = persistedWatermarkEpochMillis,
        reconciledAtEpochMillis = reconciledWatermarkEpochMillis,
    )

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-offline-retention-room-test").toFile()
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

/**
 * Delegating double around a real [FakeAirchiveTelemetry] whose two incremental
 * streams ([changedSincePersistedAt]/[changedSinceReconciledAt]) throw a typed
 * [TelemetryReadFailure.Offline] before serving any page, simulating an app
 * restart with no connectivity. The first-load/range/totals/health/identity
 * reads are irrelevant to [IncrementalSyncCoordinator] and delegate untouched.
 */
private class OfflineIncrementalTelemetry : AirchiveTelemetryPort {

    private val delegate = FakeAirchiveTelemetry(nowEpochMillis = SEED_NOW)

    /** Number of times the persistedAt stream threw (coordinator aborts here). */
    var persistedCalls: Int = 0
        private set

    /** Number of times the reconciledAt stream was reached after a failure. */
    var reconciledCalls: Int = 0
        private set

    /** Incremental pages actually served — always 0 while the device is offline. */
    var incrementalPagesServed: Int = 0
        private set

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        persistedCalls += 1
        throw TelemetryReadFailure.Offline()
    }

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        reconciledCalls += 1
        throw TelemetryReadFailure.Offline()
    }

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.latestObservations(sinceEpochMillis, pageSize, cursor)

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> =
        delegate.latestFinalizedTotals(limit)

    override suspend fun collectorHealth(): CollectorHealth = delegate.collectorHealth()

    override suspend fun deviceIdentity(): DeviceIdentity = delegate.deviceIdentity()

    private companion object {
        const val SEED_NOW: Long = 1_750_000_000_000L
    }
}