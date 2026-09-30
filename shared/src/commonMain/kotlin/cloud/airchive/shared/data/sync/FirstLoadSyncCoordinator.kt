package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Orchestrates the bounded **first-load** synchronization for one
 * [HistoryPartition]: the latest 24 hours of five-minute observations (paginated
 * with an opaque, durable cursor and a page size that never exceeds 250), the
 * latest 30 available finalized daily totals, and the latest collector health.
 *
 * This is the *read-and-cache* path only. It never touches Firestore writes,
 * background work, or live ThinQ accounts — the underlying
 * [AirchiveTelemetryPort] is read-only by contract and the in-memory fakes are
 * deterministic for a fixed clock.
 *
 * Guarantees provided here (the strict atomic-watermark guarantees of the wider
 * refresh path land in task 2.7):
 *
 *  - **Bounded window.** Only observations overlapping the initial 24-hour
 *    window `(now - 24h, now]` are ever persisted. The window is computed from
 *    the explicit [nowEpochMillis] passed to [syncFirstLoad], so callers control
 *    the clock and tests are deterministic.
 *  - **Buffered pages.** Every observation page is buffered in memory and
 *    de-duplicated by `sampleId` before any local write, so a mid-paging failure
 *    can never leave a partial initial window behind. The complete window is
 *    persisted as one coherent [upsertObservations] call.
 *  - **No cursor loops.** Each opaque cursor is consumed at most once; a repeated
 *    cursor aborts the load with [IllegalStateException] instead of paging
 *    forever.
 *  - **Honest nulls.** Null interval energy stays null; it is never coerced to a
 *    measured zero anywhere in the projection.
 *  - **Durable bookkeeping.** A successful load records the full queried
 *    24-hour range (including gaps with no documents) plus sync metadata and
 *    the initial `persistedAt`/`reconciledAt` watermarks, capped by what the
 *    loaded samples actually carried.
 *  - **Actionable failed first loads.** On any non-cancellation failure the
 *    buffered pages guarantee no partial initial window is left behind, and the
 *    sync metadata records a safe stable [TelemetryReadFailure.code] with
 *    `lastErrorEpochMillis` so a fresh install shows an actionable error while
 *    no cache exists. A failed load never marks `lastSuccessfulSyncEpochMillis`
 *    or the watermarks, cancels only via [CancellationException] (which is never
 *    recorded as a sync error), and a failure of the error record itself never
 *    masks the original sync failure. A later successful load clears the prior
 *    error.
 *
 * Deterministic [FirstLoadProgress] reports are delivered through [onProgress]:
 * once after every observation page, before and after the daily-totals fetch,
 * after collector health, and once more when the initial window is fully cached
 * and ready.
 *
 * Older observations are intentionally out of scope here; an explicit range
 * fetch (task 2.5) is the only path that caches them.
 */
class FirstLoadSyncCoordinator(
    private val telemetry: AirchiveTelemetryPort,
    private val local: LocalHistoryPort,
    private val partition: HistoryPartition,
    private val totalsLimit: Int = AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT,
) {

    init {
        // The first load is deliberately capped at the 30 finalized totals the
        // contracting spec allows; it never grows toward the 250 read bound.
        require(totalsLimit in 1..AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT) {
            "First-load totalsLimit must be in 1..${AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT}, " +
                "got $totalsLimit."
        }
    }

    /**
     * Runs one bounded first-load synchronization.
     *
     * @param nowEpochMillis the caller's explicit clock; the initial window is
     *   `(nowEpochMillis - [INITIAL_WINDOW_MILLIS], nowEpochMillis]`.
     * @param pageSize observation page size, defaulting to (and capped at) the
     *   Airchive read contract's 250-document maximum.
     * @param onProgress deterministic hook called at every sync stage.
     * @return a summary of what the load persisted.
     * @throws IllegalArgumentException if [pageSize] is outside `1..250`.
     * @throws IllegalStateException if a page exceeds [pageSize] or the telemetry
     *   adapter repeats an opaque cursor (both would otherwise loop).
     * @throws Throwable any adapter or local-store failure; the buffered pages
     *   guarantee no partial initial window is written, the sync metadata
     *   records a safe stable error code (never `lastSuccess`/watermarks), and
     *   cancellation propagates without being recorded.
     */
    suspend fun syncFirstLoad(
        nowEpochMillis: Long,
        pageSize: Int = AirchiveTelemetryPort.MAX_PAGE_SIZE,
        onProgress: (FirstLoadProgress) -> Unit = {},
    ): FirstLoadResult {
        AirchiveTelemetryPort.requireValidPageSize(pageSize)

        // Captured before any read so an error record can preserve prior
        // last-success/watermarks (or leave them null on a true first attempt).
        val previous = local.observe(partition).first().sync
        return try {
        val windowFromEpochMillis = nowEpochMillis - INITIAL_WINDOW_MILLIS
        val windowToEpochMillis = nowEpochMillis

        // ---- 1. Buffer every observation page; nothing is written yet. ----
        val inWindowById = linkedMapOf<String, Observation>()
        val consumedCursors = HashSet<String>()
        var cursor: String? = null
        var pagesFetched = 0
        while (true) {
            if (cursor != null) {
                check(consumedCursors.add(cursor)) {
                    "Telemetry returned repeated cursor '$cursor'; " +
                        "aborting first-load instead of paging forever."
                }
            }
            val page = telemetry.latestObservations(
                sinceEpochMillis = windowFromEpochMillis,
                pageSize = pageSize,
                cursor = cursor,
            )
            check(page.items.size <= pageSize) {
                "Telemetry page exceeded the requested pageSize $pageSize (got ${page.items.size})."
            }
            pagesFetched += 1
            for (item in page.items) {
                // Misbehaving adapters can return rows outside the requested
                // window; they are dropped here and never persisted.
                if (item.intervalEndEpochMillis < windowFromEpochMillis ||
                    item.intervalStartEpochMillis >= windowToEpochMillis
                ) {
                    continue
                }
                inWindowById.putIfAbsent(item.sampleId, item)
            }
            val hasMore = page.nextCursor != null
            onProgress(
                FirstLoadProgress(
                    phase = FirstLoadPhase.OBSERVATION_PAGE,
                    observationPagesFetched = pagesFetched,
                    observationsSeen = inWindowById.size,
                    hasMoreObservationPages = hasMore,
                ),
            )
            if (!hasMore) break
            cursor = page.nextCursor
        }
        val observations = inWindowById.values.sortedBy { it.intervalStartEpochMillis }

        // ---- 2. Latest finalized daily totals (bounded), then health. ----
        onProgress(
            FirstLoadProgress(
                phase = FirstLoadPhase.DAILY_TOTALS,
                observationPagesFetched = pagesFetched,
                observationsSeen = observations.size,
            ),
        )
        val totals: List<DailyTotal> = telemetry.latestFinalizedTotals(limit = totalsLimit).take(totalsLimit)
        onProgress(
            FirstLoadProgress(
                phase = FirstLoadPhase.DAILY_TOTALS,
                observationPagesFetched = pagesFetched,
                observationsSeen = observations.size,
                dailyTotalsFetched = totals.size,
            ),
        )
        val health = telemetry.collectorHealth()
        onProgress(
            FirstLoadProgress(
                phase = FirstLoadPhase.COLLECTOR_HEALTH,
                observationPagesFetched = pagesFetched,
                observationsSeen = observations.size,
                dailyTotalsFetched = totals.size,
                collectorHealthFetched = true,
            ),
        )

        // ---- 3. Persist the complete initial window as one coherent write. ----
        local.upsertObservations(partition, observations)
        local.upsertDailyTotals(partition, totals)
        local.upsertCollectorHealth(partition, health)

        val coveredRange = CoveredRange(
            fromEpochMillis = windowFromEpochMillis,
            toEpochMillis = windowToEpochMillis,
        )
        local.upsertCoveredRange(partition, coveredRange)
        val syncMetadata = SyncMetadata(
            lastSuccessfulSyncEpochMillis = nowEpochMillis,
            // The first load establishes the durable watermarks from what was
            // actually observed; later incremental refreshes advance them.
            persistedAtWatermarkEpochMillis = observations.mapNotNull { it.persistedAtEpochMillis }.maxOrNull(),
            reconciledAtWatermarkEpochMillis = observations.mapNotNull { it.reconciledAtEpochMillis }.maxOrNull(),
        )
        local.recordSync(partition, syncMetadata)

        onProgress(
            FirstLoadProgress(
                phase = FirstLoadPhase.READY,
                observationPagesFetched = pagesFetched,
                observationsSeen = observations.size,
                dailyTotalsFetched = totals.size,
                collectorHealthFetched = true,
                windowReady = true,
            ),
        )

        FirstLoadResult(
            observationPagesFetched = pagesFetched,
            observationsPersisted = observations.size,
            dailyTotalsPersisted = totals.size,
            collectorHealthPersisted = true,
            coveredRange = coveredRange,
            syncMetadata = syncMetadata,
        )
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // A failed first load leaves no partial cache behind (buffered
            // pages) and records only a safe, stable error code — never a raw
            // exception message and never a last-success or watermark.
            recordSyncError(
                local = local,
                partition = partition,
                previous = previous ?: SyncMetadata(),
                failure = TelemetryReadFailure.classify(failure),
                nowEpochMillis = nowEpochMillis,
            )
            throw failure
        }
    }

    companion object {
        /** The bounded initial first-load window: the latest 24 hours. */
        const val INITIAL_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

/** The sync stage a [FirstLoadProgress] report describes. */
enum class FirstLoadPhase {
    /** Emitted once after every observation page was fetched and buffered. */
    OBSERVATION_PAGE,

    /** Emitted right before and right after the daily-totals fetch. */
    DAILY_TOTALS,

    /** Emitted right after the collector-health fetch. */
    COLLECTOR_HEALTH,

    /** Emitted once when the initial window is fully cached and ready. */
    READY,
}

/**
 * Deterministic progress report for [FirstLoadSyncCoordinator.syncFirstLoad].
 *
 * [dailyTotalsFetched] is `null` on the *before*-totals report and carries the
 * count on the *after*-totals report; [collectorHealthFetched] flips to `true`
 * once health has been read; [windowReady] becomes `true` only on the final
 * [FirstLoadPhase.READY] report.
 */
data class FirstLoadProgress(
    val phase: FirstLoadPhase,
    val observationPagesFetched: Int = 0,
    val observationsSeen: Int = 0,
    val hasMoreObservationPages: Boolean = false,
    val dailyTotalsFetched: Int? = null,
    val collectorHealthFetched: Boolean = false,
    val windowReady: Boolean = false,
)

/** Summary of one completed [FirstLoadSyncCoordinator.syncFirstLoad] call. */
data class FirstLoadResult(
    val observationPagesFetched: Int,
    val observationsPersisted: Int,
    val dailyTotalsPersisted: Int,
    val collectorHealthPersisted: Boolean,
    val coveredRange: CoveredRange? = null,
    val syncMetadata: SyncMetadata,
)
