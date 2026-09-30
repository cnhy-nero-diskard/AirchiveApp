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
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic common tests for the two incremental mutation streams
 * ([changedSincePersistedAt]/[changedSinceReconciledAt]) and the
 * [IncrementalSyncCoordinator] that consumes them, driven entirely by the
 * in-memory fake providers under a fixed clock. No Firebase, no Room, no
 * secrets.
 *
 * Proves: a new sample advances only the `persistedAt` watermark; a later
 * completeness upgrade replaces the cached row and advances the
 * `reconciledAt` watermark; an older rollover patch does the same; the overlap
 * (and the recent-24h fallback when no watermark exists) is applied to each
 * stream lower bound; a sample returned by both streams is merged once with the
 * freshest projection; a repeated cursor or an injected page failure aborts
 * before any local write and leaves the prior watermarks/cache recoverable;
 * and every advertised page size obeys the 1..250 contract bound.
 */
class IncrementalSyncCoordinatorTest {

    private val nowEpochMillis = 1_750_000_000_000L

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-incremental",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    private fun coordinator(
        telemetry: AirchiveTelemetryPort,
        local: FakeLocalHistory = FakeLocalHistory(),
    ) = IncrementalSyncCoordinator(telemetry, local, partition)

    // ---------------------------------- new sample advances persistedAt only

    @Test
    fun newSampleAdvancesOnlyThePersistedAtWatermark() = runBlocking {
        val persistedWatermark = nowEpochMillis - 1_000_000L
        val reconciledWatermark = nowEpochMillis - 1_000_000L
        val newPersistedAt = nowEpochMillis - 800_000L // newer than the watermark
        val server = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(
                nowEpochMillis = nowEpochMillis,
                observations = listOf(
                    sample(
                        id = "fresh",
                        persistedAt = newPersistedAt,
                        reconciledAt = null,
                    ),
                ),
            ),
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = persistedWatermark,
                        reconciledAtWatermarkEpochMillis = reconciledWatermark,
                    ),
                ),
            )
        }

        val result = coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        // The stream was queried from the watermark minus the overlap.
        assertEquals(listOf(persistedWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS), server.persistedSinceRequests)
        assertEquals(listOf(reconciledWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS), server.reconciledSinceRequests)

        val sync = assertNotNull(local.observe(partition).first().sync)
        // Only the persistedAt watermark advanced, from the returned server value.
        assertEquals(newPersistedAt, sync.persistedAtWatermarkEpochMillis)
        assertEquals(reconciledWatermark, sync.reconciledAtWatermarkEpochMillis)
        assertEquals(newPersistedAt, result.persistedAtWatermarkEpochMillis)
        assertEquals(reconciledWatermark, result.reconciledAtWatermarkEpochMillis)
        assertEquals(1, result.observationsMerged)

        val cached = local.observe(partition).first().observations
        assertEquals(setOf("fresh"), cached.map { it.sampleId }.toSet())
        assertEquals(newPersistedAt, cached.single().persistedAtEpochMillis)
        assertNull(cached.single().reconciledAtEpochMillis)
    }

    // --------------------------- completeness upgrade replaces cached row

    @Test
    fun completenessUpgradeReplacesTheCachedRow() = runBlocking {
        val persistedWatermark = nowEpochMillis - 800_000L
        val reconciledWatermark = nowEpochMillis - 900_000L
        val upgradedReconciledAt = nowEpochMillis - 500_000L
        // The same sample was cached earlier with a lower completeness.
        val server = FakeAirchiveTelemetry(
            nowEpochMillis = nowEpochMillis,
            observations = listOf(
                sample(
                    id = "upgrade",
                    persistedAt = nowEpochMillis - 800_000L,
                    reconciledAt = upgradedReconciledAt,
                    completeness = 2, // server now carries the completeness upgrade
                    intervalStatus = "OK",
                ),
            ),
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    observations = listOf(
                        sample(
                            id = "upgrade",
                            persistedAt = nowEpochMillis - 800_000L,
                            reconciledAt = nowEpochMillis - 900_000L,
                            completeness = 0, // stale cached projection
                            intervalStatus = "ENERGY_UNAVAILABLE",
                        ),
                    ),
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = persistedWatermark,
                        reconciledAtWatermarkEpochMillis = reconciledWatermark,
                    ),
                ),
            )
        }

        coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        val cached = local.observe(partition).first().observations
        // One row, replaced by the upgraded projection.
        assertEquals(listOf("upgrade"), cached.map { it.sampleId })
        assertEquals(2, cached.single().completeness)
        assertEquals("OK", cached.single().intervalStatus)
        assertEquals(upgradedReconciledAt, cached.single().reconciledAtEpochMillis)

        val sync = assertNotNull(local.observe(partition).first().sync)
        assertEquals(upgradedReconciledAt, sync.reconciledAtWatermarkEpochMillis)
        assertEquals(persistedWatermark, sync.persistedAtWatermarkEpochMillis)
    }

    // ------------------------- older reconciliation replaces the cached row

    @Test
    fun olderSampleReconciliationAdvancesReconciledAtAndReplacesTheRow() = runBlocking {
        val persistedWatermark = nowEpochMillis - 1_000_000L
        val reconciledWatermark = nowEpochMillis - 1_000_000L
        val patchedReconciledAt = nowEpochMillis - 500_000L
        // The old sample's persistedAt predates the persisted watermark (other,
        // newer documents advanced it), so only the reconciledAt stream returns
        // it — after the collector resolved its rollover.
        val server = FakeAirchiveTelemetry(
            nowEpochMillis = nowEpochMillis,
            observations = listOf(
                sample(
                    id = "rollover",
                    persistedAt = nowEpochMillis - 1_200_000L, // below persisted lower bound
                    reconciledAt = patchedReconciledAt,
                    intervalStatus = "NORMAL",
                    energyValue = 1.5,
                ),
            ),
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    observations = listOf(
                        sample(
                            id = "rollover",
                            persistedAt = nowEpochMillis - 1_200_000L,
                            reconciledAt = nowEpochMillis - 1_000_000L,
                            intervalStatus = "DAY_ROLLOVER_UNRESOLVED",
                            energyValue = null,
                        ),
                    ),
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = persistedWatermark,
                        reconciledAtWatermarkEpochMillis = reconciledWatermark,
                    ),
                ),
            )
        }

        coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        val cached = local.observe(partition).first().observations
        assertEquals(listOf("rollover"), cached.map { it.sampleId })
        assertEquals("NORMAL", cached.single().intervalStatus)
        assertEquals(1.5, cached.single().energyValue)
        assertEquals(patchedReconciledAt, cached.single().reconciledAtEpochMillis)

        val sync = assertNotNull(local.observe(partition).first().sync)
        // Reconciliation advanced the reconciledAt watermark only; the
        // persistedAt watermark stays where earlier newer documents left it.
        assertEquals(patchedReconciledAt, sync.reconciledAtWatermarkEpochMillis)
        assertEquals(persistedWatermark, sync.persistedAtWatermarkEpochMillis)
    }

    // ---------------------------------------------------- overlap and bounds

    @Test
    fun overlapStartIsAppliedToBothStreams() = runBlocking {
        val persistedWatermark = nowEpochMillis - 2_000_000L
        val reconciledWatermark = nowEpochMillis - 3_000_000L
        val server = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = emptyList()),
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = persistedWatermark,
                        reconciledAtWatermarkEpochMillis = reconciledWatermark,
                    ),
                ),
            )
        }

        coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        assertEquals(
            listOf(persistedWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS),
            server.persistedSinceRequests,
        )
        assertEquals(
            listOf(reconciledWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS),
            server.reconciledSinceRequests,
        )
        // Nothing returned -> the prior watermarks are preserved, not invented.
        val sync = assertNotNull(local.observe(partition).first().sync)
        assertEquals(persistedWatermark, sync.persistedAtWatermarkEpochMillis)
        assertEquals(reconciledWatermark, sync.reconciledAtWatermarkEpochMillis)
        assertTrue(local.observe(partition).first().observations.isEmpty())
    }

    @Test
    fun absentWatermarkUsesBoundedRecent24HourWindowNotAFullScan() = runBlocking {
        val server = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = emptyList()),
        )
        val local = FakeLocalHistory() // no sync metadata at all
        assertNull(local.observe(partition).first().sync?.persistedAtWatermarkEpochMillis)

        val result = coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        val recentBound = nowEpochMillis - IncrementalSyncCoordinator.RECENT_WINDOW_MILLIS
        assertEquals(
            listOf(recentBound - IncrementalSyncCoordinator.OVERLAP_MILLIS),
            server.persistedSinceRequests,
        )
        assertEquals(
            listOf(recentBound - IncrementalSyncCoordinator.OVERLAP_MILLIS),
            server.reconciledSinceRequests,
        )
        // Both watermarks stay null: nothing was returned and none existed before.
        assertNull(result.persistedAtWatermarkEpochMillis)
        assertNull(result.reconciledAtWatermarkEpochMillis)
        assertTrue(local.observe(partition).first().observations.isEmpty())
    }

    // --------------------- duplicate across streams merges to freshest once

    @Test
    fun duplicateSampleFromBothStreamsIsMergedOnceWithTheFreshestProjection() = runBlocking {
        // The same sample surfaces in both streams with different projections:
        // the persisted stream carries an older write, the reconciled stream the
        // later completeness upgrade.
        val staleProjection = sample(
            id = "dup",
            persistedAt = nowEpochMillis - 700_000L,
            reconciledAt = null,
            completeness = 1,
        )
        val freshProjection = sample(
            id = "dup",
            persistedAt = nowEpochMillis - 800_000L,
            reconciledAt = nowEpochMillis - 300_000L,
            completeness = 2,
        )
        val server = ScriptedIncrementalTelemetry(
            persistedPages = listOf(
                TelemetryPage(items = listOf(staleProjection), nextCursor = null),
            ),
            reconciledPages = listOf(
                TelemetryPage(items = listOf(freshProjection), nextCursor = null),
            ),
        )
        val local = FakeLocalHistory()

        val result = coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        // Merged exactly once, with the freshest (reconciled) projection.
        assertEquals(1, result.observationsMerged)
        val cached = local.observe(partition).first().observations
        assertEquals(listOf("dup"), cached.map { it.sampleId })
        assertEquals(freshProjection, cached.single())
        assertEquals(2, cached.single().completeness)
        assertEquals(nowEpochMillis - 300_000L, cached.single().reconciledAtEpochMillis)

        // Both streams contributed; each watermark advances only from its own stream.
        val sync = assertNotNull(local.observe(partition).first().sync)
        assertEquals(nowEpochMillis - 300_000L, sync.reconciledAtWatermarkEpochMillis)
        // Each high-water mark is derived only from its own stream's query
        // result. The persisted stream returned the stale projection at -700s;
        // the newer reconciled projection returned a different persistedAt but
        // must not move the persisted stream watermark.
        assertEquals(nowEpochMillis - 700_000L, sync.persistedAtWatermarkEpochMillis)
    }

    // ---------------------------------------- failure leaves state recoverable

    @Test
    fun injectedPageFailureLeavesWatermarksAndCacheUsable() = runBlocking {
        val persistedWatermark = nowEpochMillis - 25L * 60 * 60 * 1000
        // The default 288-sample fixture carries persistedAt = reconciledAt = now,
        // so once the watermark is 25h old every persisted page must page 250+38.
        val cached = sample(
            id = "cached-before-failure",
            persistedAt = persistedWatermark,
            reconciledAt = persistedWatermark,
        )
        val server = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
            failPersistedOnCall = 2, // second page of the persistedAt stream dies
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    observations = listOf(cached),
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = persistedWatermark,
                        reconciledAtWatermarkEpochMillis = persistedWatermark,
                    ),
                ),
            )
        }

        assertFailsWith<IllegalStateException> {
            coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)
        }

        // Both streams are read fully before any write: the failure left the
        // prior cache and watermarks untouched and therefore usable. The 288
        // fixture samples paged 250 + 38 before the injected failure, so the
        // persisted stream was requested twice from the same lower bound.
        val bound = persistedWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS
        assertEquals(listOf(bound, bound), server.persistedSinceRequests)
        var snapshot = local.observe(partition).first()
        assertEquals(listOf("cached-before-failure"), snapshot.observations.map { it.sampleId })
        assertEquals(persistedWatermark, snapshot.sync?.persistedAtWatermarkEpochMillis)
        assertEquals(persistedWatermark, snapshot.sync?.reconciledAtWatermarkEpochMillis)

        // A retry (no injected failure) converges: it re-reads the same bounded
        // window, merges idempotently, and advances the watermarks from the
        // returned server timestamps.
        server.failPersistedOnCall = null
        val result = coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        assertEquals(2, result.persistedPagesFetched)
        snapshot = local.observe(partition).first()
        assertEquals(289, snapshot.observations.size)
        assertEquals(nowEpochMillis, snapshot.sync?.persistedAtWatermarkEpochMillis)
        assertEquals(nowEpochMillis, snapshot.sync?.reconciledAtWatermarkEpochMillis)
    }

    @Test
    fun injectedAtomicLocalCommitFailureLeavesRowsAndWatermarksRecoverable() = runBlocking {
        val persistedWatermark = nowEpochMillis - 10 * 60 * 1000L
        val reconciledWatermark = nowEpochMillis - 12 * 60 * 1000L
        val prior = sample(
            id = "cached-before-local-failure",
            persistedAt = persistedWatermark,
            reconciledAt = reconciledWatermark,
        )
        val fresh = sample(
            id = "fresh-after-retry",
            persistedAt = nowEpochMillis - 5 * 60 * 1000L,
            reconciledAt = nowEpochMillis - 4 * 60 * 1000L,
        )
        val telemetry = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis, observations = listOf(fresh)),
        )
        val priorSync = SyncMetadata(
            lastSuccessfulSyncEpochMillis = nowEpochMillis - 20 * 60 * 1000L,
            lastErrorEpochMillis = nowEpochMillis - 15 * 60 * 1000L,
            lastError = "prior-sync-error",
            persistedAtWatermarkEpochMillis = persistedWatermark,
            reconciledAtWatermarkEpochMillis = reconciledWatermark,
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    observations = listOf(prior),
                    sync = priorSync,
                ),
            )
            injectAtomicRefreshFailure = IllegalStateException("injected atomic local commit failure")
        }

        assertFailsWith<IllegalStateException> {
            coordinator(telemetry, local).syncIncremental(nowEpochMillis = nowEpochMillis)
        }

        // Both remote streams completed, but the atomic local API failed. The
        // old cache and the complete old sync record—including prior error—stay
        // untouched, so retry reuses the same overlap lower bounds.
        assertEquals(listOf(persistedWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS), telemetry.persistedSinceRequests)
        assertEquals(listOf(reconciledWatermark - IncrementalSyncCoordinator.OVERLAP_MILLIS), telemetry.reconciledSinceRequests)
        var snapshot = local.observe(partition).first()
        assertEquals(listOf(prior), snapshot.observations)
        assertEquals(priorSync, snapshot.sync)

        local.injectAtomicRefreshFailure = null
        val result = coordinator(telemetry, local).syncIncremental(nowEpochMillis = nowEpochMillis)
        snapshot = local.observe(partition).first()
        assertEquals(1, result.observationsMerged)
        assertEquals(listOf(prior, fresh), snapshot.observations)
        assertEquals(nowEpochMillis, snapshot.sync?.lastSuccessfulSyncEpochMillis)
        assertNull(snapshot.sync?.lastErrorEpochMillis)
        assertNull(snapshot.sync?.lastError)
        assertEquals(fresh.persistedAtEpochMillis, snapshot.sync?.persistedAtWatermarkEpochMillis)
        assertEquals(fresh.reconciledAtEpochMillis, snapshot.sync?.reconciledAtWatermarkEpochMillis)
    }

    // ------------------------------------------------------------ page bound

    @Test
    fun pageBoundOfTwoHundredFiftyIsRespectedAcrossBothStreams() = runBlocking {
        val server = RecordingIncrementalTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
        )
        val local = FakeLocalHistory().apply {
            replace(
                partition,
                FakeLocalHistory.syncedSnapshotFor(
                    partition,
                    sync = SyncMetadata(
                        persistedAtWatermarkEpochMillis = nowEpochMillis - 25L * 60 * 60 * 1000,
                        reconciledAtWatermarkEpochMillis = nowEpochMillis - 25L * 60 * 60 * 1000,
                    ),
                ),
            )
        }

        val result = coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)

        // 288 samples -> exactly 250 + 38 pages per stream, each at the bound.
        assertEquals(2, result.persistedPagesFetched)
        assertEquals(2, result.reconciledPagesFetched)
        assertEquals(listOf(250, 250), server.persistedPageSizesRequested)
        assertEquals(listOf(250, 250), server.reconciledPageSizesRequested)
        assertTrue(server.persistedPageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })
        assertTrue(server.reconciledPageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })
        // Opaque cursors consumed exactly once per stream, oldest-first.
        assertEquals(listOf(null, "index:250"), server.persistedCursorRequests)
        assertEquals(listOf(null, "index:250"), server.reconciledCursorRequests)
        // Every fixture sample merged exactly once into the local store.
        assertEquals(288, result.observationsMerged)
        assertEquals(288, local.observe(partition).first().observations.size)
    }

    // ------------------------------------------------- repeat-cursor fail fast

    @Test
    fun repeatedCursorAbortsInsteadOfLoopingAndWritesNothing() = runBlocking {
        val server = ScriptedIncrementalTelemetry(
            persistedPages = listOf(
                TelemetryPage(items = listOf(sample("a", persistedAt = nowEpochMillis - 100_000L)), nextCursor = "stuck"),
                TelemetryPage(items = listOf(sample("b", persistedAt = nowEpochMillis - 90_000L)), nextCursor = "stuck"),
            ),
            reconciledPages = listOf(TelemetryPage(items = emptyList(), nextCursor = null)),
        )
        val local = FakeLocalHistory()

        val error = assertFailsWith<IllegalStateException> {
            coordinator(server, local).syncIncremental(nowEpochMillis = nowEpochMillis)
        }
        assertTrue("cursor" in error.message.orEmpty())

        val snapshot = local.observe(partition).first()
        assertTrue(snapshot.observations.isEmpty())
        assertNull(snapshot.sync?.lastSuccessfulSyncEpochMillis)
        assertNull(snapshot.sync?.persistedAtWatermarkEpochMillis)
    }

    // ----------------------------------------------------------------- helpers

    private fun sample(
        id: String,
        persistedAt: Long,
        reconciledAt: Long? = null,
        completeness: Int? = null,
        intervalStatus: String? = "OK",
        energyValue: Double? = 1.0,
    ) = Observation(
        sampleId = id,
        intervalStartEpochMillis = nowEpochMillis - 3_000_000L,
        intervalEndEpochMillis = nowEpochMillis - 2_700_000L,
        energyValue = energyValue,
        recordedUnit = EnergyUnit.WH,
        intervalStatus = intervalStatus,
        quality = ObservationQuality.NORMAL,
        persistedAtEpochMillis = persistedAt,
        reconciledAtEpochMillis = reconciledAt,
        completeness = completeness,
        deviceId = partition.deviceId,
    )
}

/**
 * Delegating double around a real [FakeAirchiveTelemetry] that records every
 * incremental read (lower bound, page size, cursor) and can inject a failure on
 * a given 1-based call of either stream. Used to prove the overlap/fallback
 * bounds, the 1..250 page bound, and that a mid-stream failure leaves local
 * state untouched.
 */
private class RecordingIncrementalTelemetry(
    private val delegate: AirchiveTelemetryPort,
    var failPersistedOnCall: Int? = null,
    var failReconciledOnCall: Int? = null,
) : AirchiveTelemetryPort {

    val persistedSinceRequests = mutableListOf<Long>()
    val reconciledSinceRequests = mutableListOf<Long>()
    val persistedPageSizesRequested = mutableListOf<Int>()
    val reconciledPageSizesRequested = mutableListOf<Int>()
    val persistedCursorRequests = mutableListOf<String?>()
    val reconciledCursorRequests = mutableListOf<String?>()

    private var persistedCall = 0
    private var reconciledCall = 0

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        persistedSinceRequests += sinceEpochMillis
        persistedPageSizesRequested += pageSize
        persistedCursorRequests += cursor
        persistedCall += 1
        if (failPersistedOnCall == persistedCall) {
            throw IllegalStateException("injected persistedAt stream failure on call $persistedCall")
        }
        return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
    }

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        reconciledSinceRequests += sinceEpochMillis
        reconciledPageSizesRequested += pageSize
        reconciledCursorRequests += cursor
        reconciledCall += 1
        if (failReconciledOnCall == reconciledCall) {
            throw IllegalStateException("injected reconciledAt stream failure on call $reconciledCall")
        }
        return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
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

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = delegate.latestFinalizedTotals(limit)

    override suspend fun collectorHealth(): CollectorHealth = delegate.collectorHealth()

    override suspend fun deviceIdentity(): DeviceIdentity = delegate.deviceIdentity()
}

/**
 * Fully scripted double for the two incremental streams: serves fixed page
 * sequences regardless of arguments so tests can serve deliberately different
 * projections of the same sample to each stream, or repeat a cursor
 * deterministically. All other reads delegate to a default fake.
 */
private class ScriptedIncrementalTelemetry(
    private val persistedPages: List<TelemetryPage<Observation>>,
    private val reconciledPages: List<TelemetryPage<Observation>>,
) : AirchiveTelemetryPort {

    private val delegate = FakeAirchiveTelemetry(
        nowEpochMillis = SCRIPT_NOW,
        observations = emptyList(),
    )

    private var persistedCall = 0
    private var reconciledCall = 0

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        persistedCall += 1
        return persistedPages.getOrElse(persistedCall - 1) { persistedPages.last() }
    }

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        reconciledCall += 1
        return reconciledPages.getOrElse(reconciledCall - 1) { reconciledPages.last() }
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

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = delegate.latestFinalizedTotals(limit)

    override suspend fun collectorHealth(): CollectorHealth = delegate.collectorHealth()

    override suspend fun deviceIdentity(): DeviceIdentity = delegate.deviceIdentity()

    private companion object {
        const val SCRIPT_NOW: Long = 1_750_000_000_000L
    }
}
