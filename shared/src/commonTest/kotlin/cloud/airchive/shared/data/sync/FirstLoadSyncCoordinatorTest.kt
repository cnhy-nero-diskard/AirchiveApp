package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
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
 * Deterministic tests for the bounded first-load synchronization, driven by the
 * in-memory fake providers ([FakeAirchiveTelemetry], [FakeLocalHistory]) with a
 * fixed clock. No accounts, network, or Firebase.
 *
 * Proves the 288-sample fixture pages 250 + 38 with deterministic progress,
 * stores all 288 in the local partition alongside 30 totals, preserves null
 * intervals, keeps every persisted sample inside the 24-hour window, consumes
 * opaque cursors exactly once without ever exceeding page size 250, and leaves
 * no partial initial window behind when a page or cursor misbehaves.
 */
class FirstLoadSyncCoordinatorTest {

    private val nowEpochMillis = 1_750_000_000_000L

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-1",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    private fun coordinator(
        telemetry: AirchiveTelemetryPort,
        local: FakeLocalHistory = FakeLocalHistory(),
        totalsLimit: Int = AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT,
    ) = FirstLoadSyncCoordinator(telemetry, local, partition, totalsLimit = totalsLimit)

    // ------------------------------------------------------- 288-sample fixture

    @Test
    fun firstLoadOf288SampleFixturePages250Then38AndStoresEverything() = runBlocking {
        val telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)
        val local = FakeLocalHistory()
        val progress = mutableListOf<FirstLoadProgress>()

        val result = coordinator(telemetry, local)
            .syncFirstLoad(nowEpochMillis = nowEpochMillis, onProgress = progress::add)

        // Deterministic page progress: 250 seen after the first page, 288 after
        // the second (250 + 38).
        val observationPages = progress.filter { it.phase == FirstLoadPhase.OBSERVATION_PAGE }
        assertEquals(listOf(1, 2), observationPages.map { it.observationPagesFetched })
        assertEquals(listOf(250, 288), observationPages.map { it.observationsSeen })
        assertEquals(listOf(true, false), observationPages.map { it.hasMoreObservationPages })

        // Daily totals: one "during" report (no count yet) and one "after" report.
        val totalsReports = progress.filter { it.phase == FirstLoadPhase.DAILY_TOTALS }
        assertEquals(2, totalsReports.size)
        assertNull(totalsReports[0].dailyTotalsFetched)
        assertEquals(30, totalsReports[1].dailyTotalsFetched)

        // Collector health report precedes the ready report.
        val healthReport = progress.first { it.phase == FirstLoadPhase.COLLECTOR_HEALTH }
        assertTrue(healthReport.collectorHealthFetched)

        // The final report declares the initial window ready.
        val ready = progress.last()
        assertEquals(FirstLoadPhase.READY, ready.phase)
        assertTrue(ready.windowReady)
        assertEquals(2, ready.observationPagesFetched)

        // Summary as seen by the caller.
        assertEquals(2, result.observationPagesFetched)
        assertEquals(288, result.observationsPersisted)
        assertEquals(30, result.dailyTotalsPersisted)
        assertTrue(result.collectorHealthPersisted)

        // The local partition holds all 288 unique samples and 30 totals.
        val snapshot = local.observe(partition).first()
        assertEquals(288, snapshot.observations.size)
        assertEquals(288, snapshot.observations.map { it.sampleId }.toSet().size)
        assertEquals(30, snapshot.dailyTotals.size)
        assertNotNull(snapshot.collectorHealth)
        assertEquals(CollectorStatus.HEALTHY, snapshot.collectorHealth.status)

        // Null interval energy stayed null (never a measured zero).
        val missing = snapshot.observations.filter { it.energyValue == null }
        assertTrue(missing.isNotEmpty(), "fixture must contain missing intervals")
        missing.forEach { assertNull(it.energyValue) }

        // The full query window is covered, including slots with no documents.
        assertEquals(
            CoveredRange(
                fromEpochMillis = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS,
                toEpochMillis = nowEpochMillis,
            ),
            snapshot.coveredRanges.single(),
        )

        // Sync metadata + watermarks recorded for the successful first load.
        val sync = assertNotNull(snapshot.sync)
        assertEquals(nowEpochMillis, sync.lastSuccessfulSyncEpochMillis)
        assertEquals(nowEpochMillis, sync.persistedAtWatermarkEpochMillis)
        assertEquals(nowEpochMillis, sync.reconciledAtWatermarkEpochMillis)
    }

    // ------------------------------------------------------- cursor consumption

    @Test
    fun cursorsAreConsumedAndPagesNeverExceed250() = runBlocking {
        val recording = RecordingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        coordinator(recording).syncFirstLoad(nowEpochMillis = nowEpochMillis)

        // Exactly two pages, both requested at the default 250 bound.
        assertEquals(listOf(250, 250), recording.pageSizesRequested)
        assertEquals(2, recording.pagesReturned.size)
        assertEquals(250, recording.pagesReturned[0].items.size)
        assertEquals(38, recording.pagesReturned[1].items.size)

        // Every requested page size obeyed the 1..250 contract bound.
        assertTrue(recording.pageSizesRequested.all { it in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE })

        // The second call consumed the first page's opaque cursor; the final
        // page carried no next cursor, so paging terminated.
        assertEquals(listOf(null, recording.pagesReturned[0].nextCursor), recording.cursorRequests)
        assertNotNull(recording.cursorRequests[1])
        assertNull(recording.pagesReturned[1].nextCursor)
    }

    // ------------------------------------------------------------- de-duplication

    @Test
    fun rowsAreDeduplicatedBySampleIdKeepingTheFirstOccurrence() = runBlocking {
        val base = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS + 100L
        val telemetry = StubTelemetry(
            pages = listOf(
                TelemetryPage(
                    items = listOf(
                        sample("a", from = base),
                        sample("b", from = base + 300_000),
                        sample("b", from = base + 301_000), // duplicate within page
                    ),
                    nextCursor = "c1",
                ),
                TelemetryPage(
                    items = listOf(
                        sample("a", from = base), // duplicate across pages
                        sample("c", from = base + 600_000),
                    ),
                    nextCursor = null,
                ),
            ),
        )
        val local = FakeLocalHistory()
        val progress = mutableListOf<FirstLoadProgress>()
        val result = coordinator(telemetry, local).syncFirstLoad(
            nowEpochMillis = nowEpochMillis,
            pageSize = 250,
            onProgress = progress::add,
        )

        // 2 unique rows after page 1, 3 after page 2 (b's copy and a's repeat
        // are dropped).
        assertEquals(listOf(2, 3), progress.filter { it.phase == FirstLoadPhase.OBSERVATION_PAGE }
            .map { it.observationsSeen })
        assertEquals(3, result.observationsPersisted)

        val stored = local.observe(partition).first().observations
        assertEquals(setOf("a", "b", "c"), stored.map { it.sampleId }.toSet())
        assertEquals(base + 300_000, stored.single { it.sampleId == "b" }.intervalStartEpochMillis)
        assertEquals(base, stored.single { it.sampleId == "a" }.intervalStartEpochMillis)
    }

    // ------------------------------------------------------------- loop safety

    @Test
    fun repeatedCursorAbortsInsteadOfLoopingAndWritesNothing() = runBlocking {
        val base = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS + 100L
        val stuck = TelemetryPage(items = listOf(sample("a", from = base)), nextCursor = "stuck")
        val telemetry = StubTelemetry(pages = listOf(stuck, stuck))
        val local = FakeLocalHistory()

        val error = assertFailsWith<IllegalStateException> {
            coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        }
        assertTrue("cursor" in error.message.orEmpty())

        // null, then the returned "stuck" cursor; the guard fires the moment
        // that same cursor comes back instead of issuing a third request.
        assertEquals(listOf(null, "stuck"), telemetry.cursorRequests)
        // Nothing was persisted before the abort.
        val snapshot = local.observe(partition).first()
        assertTrue(snapshot.observations.isEmpty())
        assertNull(snapshot.sync?.lastSuccessfulSyncEpochMillis)
    }

    @Test
    fun pageFailureDuringBufferingLeavesNoPartialInitialWindow() = runBlocking {
        val base = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS + 100L
        val telemetry = StubTelemetry(
            pages = listOf(
                TelemetryPage(
                    items = listOf(sample("a", from = base), sample("b", from = base + 300_000)),
                    nextCursor = "c1",
                ),
                TelemetryPage(items = listOf(sample("c", from = base + 600_000)), nextCursor = null),
            ),
            failOnCallNumber = 2,
        )
        val local = FakeLocalHistory()

        assertFailsWith<IllegalStateException> {
            coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        }

        // Buffering means the failed second page prevented every local write.
        val snapshot = local.observe(partition).first()
        assertTrue(snapshot.observations.isEmpty(), "a failed page must not leave a partial initial window")
        assertEquals(emptyList(), snapshot.dailyTotals)
        assertNull(snapshot.sync?.lastSuccessfulSyncEpochMillis)
        // The failure is still recorded as a safe, actionable error code: a
        // fresh install shows an error while no cache exists, with no
        // last-success or watermark invented.
        assertEquals(nowEpochMillis, snapshot.sync?.lastErrorEpochMillis)
        assertEquals("REMOTE_FAILURE", snapshot.sync?.lastError)
        assertNull(snapshot.sync?.persistedAtWatermarkEpochMillis)
    }

    // -------------------------------- actionable failed first loads

    @Test
    fun accessDeniedDuringFirstLoadLeavesActionableErrorWithoutAnyCache() = runBlocking {
        val base = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS + 100L
        val telemetry = StubTelemetry(
            pages = listOf(
                TelemetryPage(items = listOf(sample("a", from = base)), nextCursor = null),
            ),
            failOnCallNumber = 1,
            failure = TelemetryReadFailure.AccessDenied(),
        )
        val local = FakeLocalHistory()

        val error = assertFailsWith<TelemetryReadFailure.AccessDenied> {
            coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        }
        assertEquals("ACCESS_DENIED", error.code)

        // No cache exists yet and none was invented: an actionable error state
        // (safe stable code + timestamp) with no last-success and no watermarks.
        val snapshot = local.observe(partition).first()
        assertTrue(snapshot.observations.isEmpty())
        assertEquals(emptyList(), snapshot.dailyTotals)
        assertNull(snapshot.collectorHealth)
        val sync = assertNotNull(snapshot.sync)
        assertNull(sync.lastSuccessfulSyncEpochMillis)
        assertNull(sync.persistedAtWatermarkEpochMillis)
        assertNull(sync.reconciledAtWatermarkEpochMillis)
        assertEquals(nowEpochMillis, sync.lastErrorEpochMillis)
        assertEquals("ACCESS_DENIED", sync.lastError)
    }

    @Test
    fun failedFirstLoadRetryConvergesAndClearsTheError() = runBlocking {
        val base = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS + 100L
        val page = TelemetryPage(
            items = listOf(sample("a", from = base), sample("b", from = base + 300_000)),
            nextCursor = null,
        )
        val telemetry = StubTelemetry(pages = listOf(page), failOnCallNumber = 1)
        val local = FakeLocalHistory()

        assertFailsWith<IllegalStateException> {
            coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        }
        // The failed attempt left an error but no partial cache.
        assertNotNull(local.observe(partition).first().sync?.lastError)
        assertTrue(local.observe(partition).first().observations.isEmpty())

        // A retry once the remote has recovered converges and clears the error.
        telemetry.failOnCallNumber = null
        telemetry.failure = null
        val result = coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        assertEquals(2, result.observationsPersisted)
        val snapshot = local.observe(partition).first()
        assertEquals(2, snapshot.observations.size)
        val sync = assertNotNull(snapshot.sync)
        assertEquals(nowEpochMillis, sync.lastSuccessfulSyncEpochMillis)
        assertEquals(nowEpochMillis, sync.persistedAtWatermarkEpochMillis)
        assertNull(sync.lastErrorEpochMillis)
        assertNull(sync.lastError)
    }

    // ------------------------------------------------------- window faithfulness

    @Test
    fun outOfWindowObservationsAreDroppedBeforePersisting() = runBlocking {
        val windowFrom = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS
        val telemetry = StubTelemetry(
            pages = listOf(
                TelemetryPage(
                    items = listOf(
                        sample("recent", from = windowFrom + 100, end = windowFrom + 400),
                        // Older than the window and start-at-or-after-window-end
                        // rows are injected by a "misbehaving" adapter view.
                        sample("too-old", from = windowFrom - 600_000, end = windowFrom - 300_000),
                        sample("too-future", from = nowEpochMillis + 100_000, end = nowEpochMillis + 400_000),
                    ),
                    nextCursor = null,
                ),
            ),
        )
        val local = FakeLocalHistory()

        val result = coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        val stored = local.observe(partition).first().observations

        assertEquals(1, result.observationsPersisted)
        assertEquals(setOf("recent"), stored.map { it.sampleId }.toSet())
    }

    @Test
    fun olderSampleIsNotStoredUntilAnExplicitRangeFetch() = runBlocking {
        val windowFrom = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS
        val olderSampleId = "old-outside-window"
        // A five-minute sample ending strictly before the initial window.
        val olderSample = sample(olderSampleId, from = windowFrom - 600_000, end = windowFrom - 300_000)

        val telemetry = FakeAirchiveTelemetry(
            nowEpochMillis = nowEpochMillis,
            observations = FakeAirchiveTelemetry.defaultObservations(nowEpochMillis) + olderSample,
        )
        val local = FakeLocalHistory()

        coordinator(telemetry, local).syncFirstLoad(nowEpochMillis = nowEpochMillis)
        val afterFirstLoad = local.observe(partition).first()
        assertEquals(288, afterFirstLoad.observations.size)
        assertTrue(afterFirstLoad.observations.none { it.sampleId == olderSampleId })

        // Task 2.5's explicit range fetch: select a window strictly before the
        // initial 24 hours, page it, and cache the result locally.
        val page = telemetry.observations(
            fromEpochMillis = windowFrom - 700_000,
            toEpochMillis = windowFrom - 200_000,
            pageSize = 250,
        )
        assertEquals(listOf(olderSampleId), page.items.map { it.sampleId })
        local.upsertObservations(partition, page.items)

        val afterExplicitFetch = local.observe(partition).first()
        assertEquals(289, afterExplicitFetch.observations.size)
        assertTrue(afterExplicitFetch.observations.any { it.sampleId == olderSampleId })
    }

    // --------------------------------------------------------------- validation

    @Test
    fun pageSizeAndTotalsLimitAreValidated(): Unit = runBlocking {
        val telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)
        // Legal values at both bounds run cleanly.
        coordinator(telemetry, totalsLimit = 30).syncFirstLoad(nowEpochMillis = nowEpochMillis, pageSize = 250)
        // Page size must stay within 1..250.
        assertFailsWith<IllegalArgumentException> {
            coordinator(telemetry).syncFirstLoad(nowEpochMillis = nowEpochMillis, pageSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(telemetry).syncFirstLoad(nowEpochMillis = nowEpochMillis, pageSize = 251)
        }
        // The first load never requests more than the 30 finalized totals.
        assertFailsWith<IllegalArgumentException> { coordinator(telemetry, totalsLimit = 0) }
        assertFailsWith<IllegalArgumentException> { coordinator(telemetry, totalsLimit = 31) }
    }

    // ------------------------------------------------------------------- helpers

    private fun sample(id: String, from: Long, end: Long = from + 300_000) = Observation(
        sampleId = id,
        intervalStartEpochMillis = from,
        intervalEndEpochMillis = end,
        energyValue = 1.25,
        recordedUnit = EnergyUnit.WH,
        intervalStatus = "OK",
        quality = ObservationQuality.NORMAL,
        persistedAtEpochMillis = nowEpochMillis,
        reconciledAtEpochMillis = nowEpochMillis,
        deviceId = partition.deviceId,
    )
}

/**
 * Delegating double that records every `latestObservations` call — the page
 * sizes requested, the opaque cursors fed in, and the pages returned — so tests
 * can prove consecutive cursor consumption and the 250-document page bound.
 */
private class RecordingTelemetry(
    private val delegate: AirchiveTelemetryPort,
) : AirchiveTelemetryPort {

    val pageSizesRequested = mutableListOf<Int>()
    val cursorRequests = mutableListOf<String?>()
    val pagesReturned = mutableListOf<TelemetryPage<Observation>>()

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        pageSizesRequested += pageSize
        cursorRequests += cursor
        return delegate.latestObservations(sinceEpochMillis, pageSize, cursor).also { pagesReturned += it }
    }

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)

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
 * Fully scripted double that serves a fixed page sequence to
 * `latestObservations` regardless of arguments, and can inject a failure on a
 * given call. Used to exercise de-duplication, repeated cursors, and failed
 * pages deterministically.
 */
private class StubTelemetry(
    private val pages: List<TelemetryPage<Observation>>,
    private val totals: List<DailyTotal> = defaultTotals(),
    var failOnCallNumber: Int? = null,
    var failure: Throwable? = null,
) : AirchiveTelemetryPort {

    val cursorRequests = mutableListOf<String?>()

    private var latestCallCount = 0

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        cursorRequests += cursor
        latestCallCount += 1
        if (failOnCallNumber == latestCallCount) {
            throw failure ?: IllegalStateException("injected page failure on call $latestCallCount")
        }
        return pages.getOrElse(latestCallCount - 1) { pages.last() }
    }

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
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

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = totals.take(limit)

    override suspend fun collectorHealth(): CollectorHealth = CollectorHealth(
        status = CollectorStatus.HEALTHY,
        lastPollEpochMillis = STUB_NOW,
        lastSuccessEpochMillis = STUB_NOW,
    )

    override suspend fun deviceIdentity(): DeviceIdentity = DeviceIdentity(deviceId = "stub-device")

    private companion object {
        const val STUB_NOW = 1_750_000_000_000L

        fun defaultTotals() = (0 until 30).map { d ->
            DailyTotal(
                totalId = "stub-total-$d",
                dayStartEpochMillis = 1000L * d,
                energyValue = 10.0 + d,
                recordedUnit = EnergyUnit.KWH,
                source = DailyTotalSource.LG_FINALIZED,
                finalized = true,
            )
        }
    }
}
