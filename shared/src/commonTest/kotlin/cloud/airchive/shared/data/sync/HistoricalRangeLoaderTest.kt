package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic tests for the on-demand historical range loader
 * ([HistoricalRangeLoader]), driven by a counting telemetry decorator and the
 * in-memory [FakeLocalHistory] with a fixed selection. No accounts, network, or
 * Firebase.
 *
 * Proves a covered revisit makes zero remote reads, an uncached selection is
 * paged/cached and then served locally, a partially covered selection fetches
 * only the missing gaps, recorded coverage merges and stays partition-scoped,
 * a failed page leaves its gap uncovered so a retry converges, page sizes never
 * exceed 250, repeated cursors abort instead of looping, invalid ranges are
 * rejected, and null intervals stay null.
 */
class HistoricalRangeLoaderTest {

    private val partitionA = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-1",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    private val partitionB = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-2",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    private fun loader(
        telemetry: AirchiveTelemetryPort,
        local: FakeLocalHistory,
        partition: HistoryPartition = partitionA,
    ) = HistoricalRangeLoader(telemetry, local, partition)

    // ------------------------------------------------- covered revisit: zero reads

    @Test
    fun coveredRevisitReadsLocalDataAndMakesNoRangeQuery() = runBlocking {
        val seeded = listOf(
            sample("cached-0", from = 1_200_000L),
            sample("cached-1", from = 1_800_000L),
        )
        val local = FakeLocalHistory()
        local.upsertObservations(partitionA, seeded)
        local.upsertCoveredRange(partitionA, CoveredRange(1_000_000L, 3_500_000L))

        val counting = CountingTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = listOf(sample("remote", from = 1_500_000L))),
        )

        val result = loader(counting, local).loadHistoricalRange(
            fromEpochMillis = 1_000_000L,
            toEpochMillis = 3_500_000L,
        )

        // The range is fully covered, so the loader never touched the remote.
        assertTrue(counting.rangeQueries.isEmpty())
        assertEquals(0, result.remotePagesFetched)
        assertTrue(result.fullyCovered)
        assertEquals(emptyList(), result.newlyCoveredRanges)
        assertEquals(seeded.map { it.sampleId }, result.observations.map { it.sampleId })
    }

    // ---------------------------------------- uncached: paged, cached, revisited

    @Test
    fun uncachedSelectionIsPagedCachedAndThenRevisitedLocally() = runBlocking {
        val step = 300_000L
        val dataset = (0 until 600).map { i ->
            val start = i * step
            sample("hist-$i", from = start, end = start + step)
        }
        val remote = FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset)
        val local = FakeLocalHistory()

        // First visit: the whole selection is one gap and pages 250 + 250 + 100.
        val firstCounting = CountingTelemetry(remote)
        val first = loader(firstCounting, local).loadHistoricalRange(
            fromEpochMillis = 0L,
            toEpochMillis = 600L * step,
        )
        assertEquals(3, first.remotePagesFetched)
        assertEquals(listOf(250, 250, 250), firstCounting.pageSizesRequested)
        assertTrue(firstCounting.pageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })
        assertEquals(600, first.observations.size)
        assertTrue(first.fullyCovered)

        val snapshot = local.observe(partitionA).first()
        assertEquals(listOf(CoveredRange(0L, 600L * step)), snapshot.coveredRanges)
        assertEquals(600, snapshot.observations.size)

        // Revisit with a fresh counting decorator: zero remote reads, same rows.
        val revisitCounting = CountingTelemetry(remote)
        val revisit = loader(revisitCounting, local).loadHistoricalRange(
            fromEpochMillis = 0L,
            toEpochMillis = 600L * step,
        )
        assertTrue(revisitCounting.rangeQueries.isEmpty())
        assertEquals(0, revisit.remotePagesFetched)
        assertEquals(first.observations.map { it.sampleId }, revisit.observations.map { it.sampleId })
    }

    // -------------------------------------------------- partially covered selection

    @Test
    fun partiallyCoveredSelectionFetchesOnlyTheMissingGaps() = runBlocking {
        // Regions: left [500k, 1M), middle [1M, 2M) (already covered), right [2M, 3.5M).
        val left = listOf(
            sample("left-0", from = 500_000L),
            sample("left-1", from = 600_000L),
        )
        val middle = listOf(
            sample("mid-0", from = 1_000_000L),
            sample("mid-1", from = 1_100_000L),
            sample("mid-2", from = 1_200_000L),
        )
        val right = listOf(
            sample("right-0", from = 2_000_000L),
            sample("right-1", from = 2_100_000L),
            sample("right-2", from = 2_200_000L),
        )
        val local = FakeLocalHistory()
        local.upsertObservations(partitionA, middle)
        local.upsertCoveredRange(partitionA, CoveredRange(1_000_000L, 2_000_000L))

        val counting = CountingTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = left + middle + right),
        )

        val result = loader(counting, local).loadHistoricalRange(
            fromEpochMillis = 500_000L,
            toEpochMillis = 3_500_000L,
        )

        // Exactly the two missing gaps were queried, oldest first; the covered
        // middle was never re-fetched.
        assertEquals(
            listOf(500_000L to 1_000_000L, 2_000_000L to 3_500_000L),
            counting.rangeQueries.map { it.fromEpochMillis to it.toEpochMillis },
        )
        assertEquals(2, result.gapsFetched)

        // The store merged the touching coverage into one durable range. The
        // merged result is sorted oldest first; the store itself keeps upsert
        // order, so compare it as a set of the same 8 rows.
        val snapshot = local.observe(partitionA).first()
        assertEquals(listOf(CoveredRange(500_000L, 3_500_000L)), snapshot.coveredRanges)
        assertEquals(8, snapshot.observations.size)
        assertEquals(
            (left + middle + right).map { it.sampleId },
            result.observations.map { it.sampleId },
        )
        assertEquals(
            (left + middle + right).map { it.sampleId }.toSet(),
            snapshot.observations.map { it.sampleId }.toSet(),
        )
    }

    // --------------------------------------------- coverage merges; partition scope

    @Test
    fun persistedCoverageMergesAndRemainsPartitionScoped() = runBlocking {
        val step = 250_000L
        val dataset = (0 until 12).map { i ->
            val start = 1_000_000L + i * step
            sample("day-$i", from = start, end = start + step)
        }
        val remote = FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset)
        val local = FakeLocalHistory()

        // Partition A: two adjacent historical selections merge into one range.
        loader(remote, local, partitionA).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 2_500_000L)
        loader(remote, local, partitionA).loadHistoricalRange(fromEpochMillis = 2_500_000L, toEpochMillis = 4_000_000L)

        val snapshotA = local.observe(partitionA).first()
        assertEquals(listOf(CoveredRange(1_000_000L, 4_000_000L)), snapshotA.coveredRanges)
        assertEquals(12, snapshotA.observations.size)

        // Partition B has its own empty cache: selecting the same range must
        // read remotely even though partition A covers it.
        val countingB = CountingTelemetry(remote)
        loader(countingB, local, partitionB).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 4_000_000L)

        val snapshotB = local.observe(partitionB).first()
        assertEquals(listOf(CoveredRange(1_000_000L, 4_000_000L)), snapshotB.coveredRanges)
        assertEquals(12, snapshotB.observations.size)
        assertTrue(countingB.rangeQueries.isNotEmpty(), "partition B must fetch its own cache from scratch")
    }

    // ------------------------------------------------- page failure: retry converges

    @Test
    fun pageFailureLeavesGapUncoveredSoRetryConverges() = runBlocking {
        val pageOne = TelemetryPage(
            items = listOf(sample("retry-0", from = 1_000_000L), sample("retry-1", from = 1_300_000L)),
            nextCursor = "c1",
        )
        val pageTwo = TelemetryPage(items = listOf(sample("retry-2", from = 1_600_000L)), nextCursor = null)
        val local = FakeLocalHistory()

        // First attempt: the second page fails, so the gap must stay uncovered
        // and nothing may be persisted for it (per-gap buffering).
        val failing = ScriptedHistoricTelemetry(listOf(pageOne, pageTwo)).apply { throwOnCallNumber = 2 }
        assertFailsWith<IllegalStateException> {
            loader(failing, local).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 2_200_000L)
        }
        assertEquals(emptyList(), local.observe(partitionA).first().observations)
        assertEquals(emptyList(), local.observe(partitionA).first().coveredRanges)

        // Retry with a healthy adapter converges through idempotent upserts.
        val healthy = ScriptedHistoricTelemetry(listOf(pageOne, pageTwo))
        val result = loader(healthy, local).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 2_200_000L)

        assertTrue(result.fullyCovered)
        assertEquals(listOf(CoveredRange(1_000_000L, 2_200_000L)), local.observe(partitionA).first().coveredRanges)
        assertEquals(
            setOf("retry-0", "retry-1", "retry-2"),
            local.observe(partitionA).first().observations.map { it.sampleId }.toSet(),
        )
    }

    // ------------------------------------------------- repeated cursor: no looping

    @Test
    fun repeatedCursorFailsInsteadOfLoopingAndWritesNothing() = runBlocking {
        val stuck = TelemetryPage(
            items = listOf(sample("loop-0", from = 1_000_000L)),
            nextCursor = "stuck",
        )
        val local = FakeLocalHistory()
        val telemetry = ScriptedHistoricTelemetry(listOf(stuck, stuck))

        val error = assertFailsWith<IllegalStateException> {
            loader(telemetry, local).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 2_200_000L)
        }
        assertTrue("cursor" in error.message.orEmpty())

        // Nothing was persisted before the abort and the gap is not covered.
        val snapshot = local.observe(partitionA).first()
        assertTrue(snapshot.observations.isEmpty())
        assertEquals(emptyList(), snapshot.coveredRanges)
        assertEquals(listOf(null, "stuck"), telemetry.cursorRequests)
    }

    // ----------------------------------------------------- page bound + validation

    @Test
    fun pageSizesNeverExceed250AndRangesAreValidated() = runBlocking {
        val step = 300_000L
        val dataset = (0 until 600).map { i ->
            val start = i * step
            sample("bound-$i", from = start, end = start + step)
        }
        val local = FakeLocalHistory()

        // Default page size stays at the 250 bound across a multi-page load.
        val first = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset))
        loader(first, local).loadHistoricalRange(fromEpochMillis = 0L, toEpochMillis = 600L * step)
        assertEquals(3, first.pageSizesRequested.size)
        assertTrue(first.pageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })
        assertEquals(600, local.observe(partitionA).first().observations.size)

        // A custom page size is honored on a fresh cache and never exceeds 250.
        val counting = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset))
        loader(counting, FakeLocalHistory()).loadHistoricalRange(
            fromEpochMillis = 0L,
            toEpochMillis = 600L * step,
            pageSize = 50,
        )
        assertEquals(12, counting.pageSizesRequested.size)
        assertTrue(counting.pageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })

        // Invalid ranges and page sizes are rejected up front without reads.
        val rejection = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset))
        val rejecting = loader(rejection, FakeLocalHistory())
        assertFailsWith<IllegalArgumentException> {
            rejecting.loadHistoricalRange(fromEpochMillis = 2_000L, toEpochMillis = 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            rejecting.loadHistoricalRange(fromEpochMillis = -1L, toEpochMillis = 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            rejecting.loadHistoricalRange(fromEpochMillis = 0L, toEpochMillis = 1_000L, pageSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            rejecting.loadHistoricalRange(fromEpochMillis = 0L, toEpochMillis = 1_000L, pageSize = 251)
        }
        assertTrue(rejection.rangeQueries.isEmpty())
    }

    // ----------------------------------------------------- honest null intervals

    @Test
    fun nullIntervalsStayNullThroughLoadingAndCaching() = runBlocking {
        val dataset = listOf(
            sample("null-0", from = 1_000_000L, energy = null),
            sample("null-1", from = 1_300_000L, energy = 2.5),
            sample("null-2", from = 1_600_000L, energy = null),
        )
        val local = FakeLocalHistory()

        val result = loader(
            CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = NOW, observations = dataset)),
            local,
        ).loadHistoricalRange(fromEpochMillis = 1_000_000L, toEpochMillis = 2_200_000L)

        assertEquals(3, result.observations.size)
        val stored = local.observe(partitionA).first().observations
        assertEquals(3, stored.size)
        // Missing intervals stay null — never a measured zero.
        assertNull(stored.single { it.sampleId == "null-0" }.energyValue)
        assertNull(stored.single { it.sampleId == "null-2" }.energyValue)
        assertEquals(2.5, stored.single { it.sampleId == "null-1" }.energyValue)
    }

    // ------------------------------------------------------------------- helpers

    private fun sample(id: String, from: Long, end: Long = from + 300_000L, energy: Double? = 1.0) = Observation(
        sampleId = id,
        intervalStartEpochMillis = from,
        intervalEndEpochMillis = end,
        energyValue = energy,
        recordedUnit = EnergyUnit.WH,
        intervalStatus = "OK",
        quality = ObservationQuality.NORMAL,
        deviceId = partitionA.deviceId,
    )

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}

/**
 * Delegating double that counts every [AirchiveTelemetryPort.observations] call
 * — the exact (from, to) range, page size, and cursor — so tests can prove
 * zero-read revisits, gap-only selection, and the 1..250 page bound.
 */
private class CountingTelemetry(
    private val delegate: AirchiveTelemetryPort,
) : AirchiveTelemetryPort {

    data class RangeQuery(
        val fromEpochMillis: Long,
        val toEpochMillis: Long,
        val pageSize: Int,
        val cursor: String?,
    )

    val rangeQueries = mutableListOf<RangeQuery>()
    val pageSizesRequested = mutableListOf<Int>()

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        rangeQueries += RangeQuery(fromEpochMillis, toEpochMillis, pageSize, cursor)
        pageSizesRequested += pageSize
        return delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
    }

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.latestObservations(sinceEpochMillis, pageSize, cursor)

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = delegate.latestFinalizedTotals(limit)

    override suspend fun collectorHealth(): CollectorHealth = delegate.collectorHealth()

    override suspend fun deviceIdentity(): DeviceIdentity = delegate.deviceIdentity()
}

/**
 * Fully scripted double for [AirchiveTelemetryPort.observations]: serves a fixed
 * page sequence regardless of arguments and can inject a failure at a given
 * call. Used to prove per-gap atomicity after a page failure, and to serve
 * deliberately repeated cursors deterministically. Other read methods return
 * inert defaults.
 */
private class ScriptedHistoricTelemetry(
    private val pages: List<TelemetryPage<Observation>>,
) : AirchiveTelemetryPort {

    /** When non-null, the `observations` call at this 1-based count throws. */
    var throwOnCallNumber: Int? = null

    val cursorRequests = mutableListOf<String?>()
    private var observationsCallCount = 0

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        cursorRequests += cursor
        observationsCallCount += 1
        if (throwOnCallNumber == observationsCallCount) {
            throw IllegalStateException("injected page failure on call $observationsCallCount")
        }
        return pages.getOrElse(observationsCallCount - 1) { pages.last() }
    }

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = TelemetryPage(items = emptyList(), nextCursor = null)

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = TelemetryPage(items = emptyList(), nextCursor = null)

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = TelemetryPage(items = emptyList(), nextCursor = null)

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = emptyList()

    override suspend fun collectorHealth(): CollectorHealth = CollectorHealth(
        status = CollectorStatus.HEALTHY,
        lastPollEpochMillis = SCRIPT_NOW,
        lastSuccessEpochMillis = SCRIPT_NOW,
    )

    override suspend fun deviceIdentity(): DeviceIdentity = DeviceIdentity(deviceId = "stub-device")

    private companion object {
        const val SCRIPT_NOW = 1_750_000_000_000L
    }
}
