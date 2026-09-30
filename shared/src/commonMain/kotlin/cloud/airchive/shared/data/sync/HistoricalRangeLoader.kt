package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.Observation
import kotlinx.coroutines.flow.first

/**
 * Loads an explicitly selected historical observation range into the local
 * [LocalHistoryPort] cache on demand (task 2.5).
 *
 * This is the *read-and-cache* path for older five-minute history: the caller
 * supplies a concrete `[fromEpochMillis, toEpochMillis)` selection (from a
 * calendar picker, for example) and the loader turns it into a bounded,
 * re-runnable page through [AirchiveTelemetryPort.observations]. It never
 * constructs a Firestore path, never writes through the read-only telemetry
 * port, and never schedules background work — the whole load is one suspend
 * call on the caller's context.
 *
 * Guarantees provided here:
 *
 *  - **Cache first, remote only for gaps.** The partition's current snapshot is
 *    read up front. Uncovered subranges are computed purely from the durable
 *    [HistorySnapshot.coveredRanges] (which stay separate from incremental
 *    watermarks), so revisiting a covered range performs **zero** remote reads
 *    and returns the cached projection.
 *  - **Gap-scoped paging.** Only the missing gaps are queried, oldest first,
 *    in pages that never exceed [AirchiveTelemetryPort.MAX_PAGE_SIZE] via
 *    opaque cursors. Every page is buffered and de-duplicated by `sampleId`;
 *    misbehaving rows outside the queried gap are dropped.
 *  - **Atomic per gap.** A gap's observations are persisted and its
 *    [CoveredRange] recorded only after **all** pages for that gap succeed. A
 *    failed page therefore never marks the gap covered; because upserts merge
 *    by `sampleId`, a later retry re-fetches exactly the uncovered gaps and
 *    converges without duplicating rows.
 *  - **No cursor loops.** Each opaque cursor is consumed at most once; a
 *    repeated cursor aborts with [IllegalStateException] instead of paging
 *    forever, and an oversized page is rejected the same way.
 *  - **Honest nulls.** Null interval energy stays null; it is never coerced to
 *    a measured zero anywhere in the projection.
 */
class HistoricalRangeLoader(
    private val telemetry: AirchiveTelemetryPort,
    private val local: LocalHistoryPort,
    private val partition: HistoryPartition,
) {

    /**
     * Loads the explicitly selected half-open range `[fromEpochMillis,
     * toEpochMillis)`.
     *
     * @param fromEpochMillis inclusive start of the requested range (>= 0).
     * @param toEpochMillis exclusive end of the requested range; must be
     *   strictly greater than [fromEpochMillis].
     * @param pageSize observation page size, defaulting to (and capped at) the
     *   Airchive read contract's 250-document maximum.
     * @return a summary with the merged in-range observations and the coverage
     *   recorded for the fetched gaps.
     * @throws IllegalArgumentException if the range is invalid or [pageSize] is
     *   outside `1..250`.
     * @throws IllegalStateException if a page exceeds [pageSize] or the
     *   telemetry adapter repeats an opaque cursor (both would otherwise loop).
     * @throws Throwable any adapter failure, leaving the in-flight gap
     *   uncovered so a retry converges.
     */
    suspend fun loadHistoricalRange(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int = AirchiveTelemetryPort.MAX_PAGE_SIZE,
    ): HistoricalRangeResult {
        require(fromEpochMillis >= 0L) {
            "Historical range fromEpochMillis must be non-negative, got $fromEpochMillis."
        }
        require(toEpochMillis > fromEpochMillis) {
            "Historical range must be a non-empty half-open [from, to) interval, " +
                "got [$fromEpochMillis, $toEpochMillis)."
        }
        AirchiveTelemetryPort.requireValidPageSize(pageSize)

        // ---- 1. Read the partition's cache first; never re-query it. ----
        val snapshot = local.observe(partition).first()
        val localInRange = snapshot.observations
            .filter { it.intervalEndEpochMillis > fromEpochMillis && it.intervalStartEpochMillis < toEpochMillis }
            .sortedBy { it.intervalStartEpochMillis }

        // ---- 2. Compute the uncovered subranges from durable coverage. ----
        val gaps = uncoveredRanges(fromEpochMillis, toEpochMillis, snapshot.coveredRanges)
        if (gaps.isEmpty()) {
            // Fully covered: return local data and make zero remote reads.
            return HistoricalRangeResult(
                fromEpochMillis = fromEpochMillis,
                toEpochMillis = toEpochMillis,
                observations = localInRange,
                fullyCovered = true,
                remotePagesFetched = 0,
                gapsFetched = 0,
                newlyCoveredRanges = emptyList(),
            )
        }

        // ---- 3. Query only the missing gaps, page by page. ----
        val fetchedById = linkedMapOf<String, Observation>()
        val newlyCovered = mutableListOf<CoveredRange>()
        var remotePagesFetched = 0

        for (gap in gaps) {
            val gapById = linkedMapOf<String, Observation>()
            val consumedCursors = HashSet<String>()
            var cursor: String? = null
            while (true) {
                if (cursor != null) {
                    check(consumedCursors.add(cursor)) {
                        "Telemetry returned repeated cursor '$cursor' while loading " +
                            "historical gap [${gap.fromEpochMillis},${gap.toEpochMillis}); " +
                            "aborting instead of paging forever."
                    }
                }
                val page = telemetry.observations(
                    fromEpochMillis = gap.fromEpochMillis,
                    toEpochMillis = gap.toEpochMillis,
                    pageSize = pageSize,
                    cursor = cursor,
                )
                check(page.items.size <= pageSize) {
                    "Telemetry page exceeded the requested pageSize $pageSize (got ${page.items.size})."
                }
                remotePagesFetched += 1
                for (item in page.items) {
                    // Misbehaving adapters can return rows outside the queried
                    // gap; they are dropped here and never persisted.
                    if (item.intervalEndEpochMillis <= gap.fromEpochMillis ||
                        item.intervalStartEpochMillis >= gap.toEpochMillis
                    ) {
                        continue
                    }
                    gapById.putIfAbsent(item.sampleId, item)
                }
                if (page.nextCursor == null) break
                cursor = page.nextCursor
            }

            // Every page for this gap succeeded: the buffered observations and
            // the coverage range become durable together. A failure anywhere in
            // the loop above left the gap uncovered, so a retry re-fetches it.
            fetchedById.putAll(gapById)
            local.upsertObservations(partition, gapById.values.toList())
            val coveredRange = CoveredRange(
                fromEpochMillis = gap.fromEpochMillis,
                toEpochMillis = gap.toEpochMillis,
            )
            local.upsertCoveredRange(partition, coveredRange)
            newlyCovered += coveredRange
        }

        // ---- 4. Merge the fetched rows with what was already cached. ----
        val allById = linkedMapOf<String, Observation>()
        fetchedById.values.forEach { allById[it.sampleId] = it }
        localInRange.forEach { allById.putIfAbsent(it.sampleId, it) }

        return HistoricalRangeResult(
            fromEpochMillis = fromEpochMillis,
            toEpochMillis = toEpochMillis,
            observations = allById.values.sortedBy { it.intervalStartEpochMillis },
            fullyCovered = true,
            remotePagesFetched = remotePagesFetched,
            gapsFetched = gaps.size,
            newlyCoveredRanges = newlyCovered,
        )
    }

    companion object {
        /**
         * Pure, deterministic split of `[fromEpochMillis, toEpochMillis)` into
         * the subranges not yet covered by [coveredRanges], oldest first.
         *
         * Coverage is half-open (`[from, to)`); ranges that merely touch the
         * request boundary or one another never overlap, so a range ending at
         * `from` does not cover the request's first instant and is re-fetched.
         * [coveredRanges] need not be sorted or normalized — they are merged as
         * they are walked — and a fully covered request yields an empty list.
         */
        fun uncoveredRanges(
            fromEpochMillis: Long,
            toEpochMillis: Long,
            coveredRanges: List<CoveredRange>,
        ): List<CoveredRange> {
            if (toEpochMillis <= fromEpochMillis) return emptyList()
            val gaps = mutableListOf<CoveredRange>()
            var cursor = fromEpochMillis
            for (range in coveredRanges.sortedBy { it.fromEpochMillis }) {
                // Entirely before the current gap start, or entirely after the
                // requested range — in the latter case nothing later can help.
                if (range.toEpochMillis <= cursor) continue
                if (range.fromEpochMillis >= toEpochMillis) break
                if (range.fromEpochMillis > cursor) {
                    gaps += CoveredRange(
                        fromEpochMillis = cursor,
                        toEpochMillis = minOf(range.fromEpochMillis, toEpochMillis),
                    )
                }
                cursor = maxOf(cursor, range.toEpochMillis)
                if (cursor >= toEpochMillis) break
            }
            if (cursor < toEpochMillis) {
                gaps += CoveredRange(
                    fromEpochMillis = cursor,
                    toEpochMillis = toEpochMillis,
                )
            }
            return gaps
        }
    }
}

/**
 * Summary of one [HistoricalRangeLoader.loadHistoricalRange] call.
 *
 * [observations] are the merged in-range rows (cached plus freshly fetched,
 * de-duplicated by `sampleId`) sorted oldest first; [fullyCovered] is `true`
 * whenever the whole requested range is durable — either because it already was
 * (with [remotePagesFetched] == 0) or because every gap fetched successfully;
 * [newlyCoveredRanges] lists the gaps whose coverage was recorded on this call.
 */
data class HistoricalRangeResult(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val observations: List<Observation>,
    val fullyCovered: Boolean,
    val remotePagesFetched: Int,
    val gapsFetched: Int,
    val newlyCoveredRanges: List<CoveredRange>,
)