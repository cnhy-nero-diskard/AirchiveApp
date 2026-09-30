package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.data.telemetry.TelemetryReadKind
import kotlinx.coroutines.flow.first

/**
 * Orchestrates the bounded **incremental** synchronization for one
 * [HistoryPartition] (task 2.6): it catches both new samples and later
 * revisions of already-cached samples by reading the two server-managed
 * mutation streams — observations whose `persistedAt` moved and observations
 * whose `reconciledAt` moved — and merging them into the local cache.
 *
 * This is the *read-and-cache* path only. It never writes through the read-only
 * [AirchiveTelemetryPort], never constructs a Firestore path, never schedules
 * background work (task 2.8 owns triggers), and commits observations plus their
 * sync metadata through one atomic [LocalHistoryPort] operation.
 *
 * ## Guarantees provided here
 *
 *  - **Bounded overlap, never a full scan.** Each stream is queried from a
 *    lower bound derived from the partition's durable watermark: the stored
 *    `persistedAt`/`reconciledAt` watermark minus the documented [OVERLAP_MILLIS]
 *    overlap. If no watermark exists yet, the lower bound is the bounded recent
 *    24-hour window (`now - [RECENT_WINDOW_MILLIS]`), also minus the overlap —
 *    never a full historical scan. The overlap guarantees a document whose
 *    mutation timestamp is slightly *before* the watermark (written between two
 *    pages of the previous sync) is still returned.
 *  - **Both streams fully read before any local write.** Every page of the
 *    `persistedAt` stream and every page of the `reconciledAt` stream is
 *    buffered in memory first. If any page fails, the sync aborts **before**
 *    touching the local store, so the previous cache and watermarks stay intact
 *    and a retry simply re-reads the same bounded window (idempotent upsert).
 *  - **De-duplication by `sampleId`, freshest projection wins.** The two
 *    streams are merged by `sampleId`; a sample returned by both streams (or
 *    twice within one) is stored exactly once, preferring the newer server
 *    mutation projection: higher `reconciledAt` wins first (a reconciliation —
 *    rollover patch or completeness upgrade — is the collector's later write
 *    stage and always wins), then higher `persistedAt`, then the reconciled
 *    stream projection on an exact tie.
 *  - **Server-timestamp high-water marks only.** The new `persistedAt` /
 *    `reconciledAt` watermarks are the maximum values actually observed on the
 *    returned rows — never the handset clock. [lastSuccessfulSyncEpochMillis]
 *    uses the caller's explicit [nowEpochMillis] (first-load convention), but
 *    the mutation watermarks never do. When no rows were returned, the previous
 *    watermarks are preserved.
 *  - **No cursor loops.** Each opaque cursor is consumed at most once; a
 *    repeated cursor aborts with [IllegalStateException] instead of paging
 *    forever, and an oversized page is rejected the same way.
 *
 * [ForegroundRefreshController] owns app-open/resume and explicit manual trigger
 * policy; this coordinator performs exactly one bounded sync attempt per call.
 */
class IncrementalSyncCoordinator(
    private val telemetry: AirchiveTelemetryPort,
    private val local: LocalHistoryPort,
    private val partition: HistoryPartition,
) {

    /**
     * Runs one bounded incremental synchronization.
     *
     * @param nowEpochMillis the caller's explicit clock; used only for the
     *   absent-watermark recent window and the recorded last-success timestamp,
     *   never for the mutation watermarks.
     * @param pageSize observation page size, defaulting to (and capped at) the
     *   Airchive read contract's 250-document maximum.
     * @return a summary of the streams read, the merged rows, and the watermarks
     *   recorded.
     * @throws IllegalArgumentException if [pageSize] is outside `1..250`.
     * @throws IllegalStateException if a page exceeds [pageSize] or the telemetry
     *   adapter repeats an opaque cursor (both would otherwise loop).
     * @throws Throwable any adapter or local-store failure; because both streams
     *   are fully read before the first local write, such a failure leaves the
     *   previous cache and watermarks untouched.
     */
    suspend fun syncIncremental(
        nowEpochMillis: Long,
        pageSize: Int = AirchiveTelemetryPort.MAX_PAGE_SIZE,
    ): IncrementalSyncResult {
        AirchiveTelemetryPort.requireValidPageSize(pageSize)

        // ---- 1. Durable watermarks -> bounded, overlapped lower bounds. ----
        val previous = local.observe(partition).first().sync ?: SyncMetadata()
        val persistedLowerBound = lowerBound(previous.persistedAtWatermarkEpochMillis, nowEpochMillis)
        val reconciledLowerBound = lowerBound(previous.reconciledAtWatermarkEpochMillis, nowEpochMillis)

        // ---- 2. Read BOTH streams fully before any local write. ----
        val persistedStream = readStream(
            telemetry = {
                telemetry.changedSincePersistedAt(persistedLowerBound, pageSize, it)
            },
            kind = TelemetryReadKind.PERSISTED_AT,
            pageSize = pageSize,
        )
        val reconciledStream = readStream(
            telemetry = {
                telemetry.changedSinceReconciledAt(reconciledLowerBound, pageSize, it)
            },
            kind = TelemetryReadKind.RECONCILED_AT,
            pageSize = pageSize,
        )

        val mergedById = linkedMapOf<String, Observation>()
        for (item in persistedStream.observations) {
            mergedById[item.sampleId] = item
        }
        for (item in reconciledStream.observations) {
            val incumbent = mergedById[item.sampleId]
            if (incumbent == null || fresherThan(item, incumbent, TelemetryReadKind.RECONCILED_AT)) {
                mergedById[item.sampleId] = item
            }
        }

        // ---- 3. High-water marks derive ONLY from returned server timestamps.
        //      They are monotonic: a previously recorded watermark (from other
        //      rows in an earlier sync) is never lowered by a returned row that
        //      carries an older timestamp, and no watermark ever comes from the
        //      handset clock. ----
        val observations = mergedById.values.sortedBy { it.intervalStartEpochMillis }
        val newPersistedAt = nextWatermark(
            previous = previous.persistedAtWatermarkEpochMillis,
            returned = persistedStream.observations.mapNotNull { it.persistedAtEpochMillis }.maxOrNull(),
        )
        val newReconciledAt = nextWatermark(
            previous = previous.reconciledAtWatermarkEpochMillis,
            returned = reconciledStream.observations.mapNotNull { it.reconciledAtEpochMillis }.maxOrNull(),
        )
        val updatedMetadata = SyncMetadata(
            lastSuccessfulSyncEpochMillis = nowEpochMillis,
            lastErrorEpochMillis = null,
            lastError = null,
            persistedAtWatermarkEpochMillis = newPersistedAt,
            reconciledAtWatermarkEpochMillis = newReconciledAt,
        )

        // ---- 4. Commit rows and watermarks atomically. ----
        local.upsertObservationsAndRecordSync(partition, observations, updatedMetadata)

        return IncrementalSyncResult(
            persistedPagesFetched = persistedStream.pagesFetched,
            reconciledPagesFetched = reconciledStream.pagesFetched,
            observationsMerged = observations.size,
            persistedLowerBoundEpochMillis = persistedLowerBound,
            reconciledLowerBoundEpochMillis = reconciledLowerBound,
            persistedAtWatermarkEpochMillis = updatedMetadata.persistedAtWatermarkEpochMillis,
            reconciledAtWatermarkEpochMillis = updatedMetadata.reconciledAtWatermarkEpochMillis,
        )
    }

    /**
     * Consumes every page of one mutation stream, merging rows by `sampleId`
     * into [mergedById] so a duplicate is stored once with the freshest
     * projection. Returns the number of pages fetched.
     */
    private suspend fun readStream(
        telemetry: suspend (cursor: String?) -> TelemetryPage<Observation>,
        kind: TelemetryReadKind,
        pageSize: Int,
    ): MutationStreamRead {
        var pages = 0
        val observationsById = linkedMapOf<String, Observation>()
        val consumedCursors = HashSet<String>()
        var cursor: String? = null
        while (true) {
            if (cursor != null) {
                check(consumedCursors.add(cursor)) {
                    "Telemetry returned repeated $kind cursor '$cursor'; " +
                        "aborting incremental sync instead of paging forever."
                }
            }
            val page = telemetry(cursor)
            check(page.items.size <= pageSize) {
                "Telemetry page exceeded the requested pageSize $pageSize (got ${page.items.size})."
            }
            pages += 1
            for (item in page.items) {
                val incumbent = observationsById[item.sampleId]
                if (incumbent == null || fresherThan(item, incumbent, kind)) {
                    observationsById[item.sampleId] = item
                }
            }
            if (page.nextCursor == null) break
            cursor = page.nextCursor
        }
        return MutationStreamRead(
            pagesFetched = pages,
            observations = observationsById.values.toList(),
        )
    }

    companion object {
        /**
         * Overlap subtracted from every stream lower bound, so a document whose
         * mutation timestamp lands just *before* a stored watermark (written
         * between two pages of the previous sync) is still re-returned and
         * re-upserted idempotently.
         */
        const val OVERLAP_MILLIS: Long = 60 * 1000L

        /** Bounded recent window used when no durable watermark exists yet. */
        const val RECENT_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

private data class MutationStreamRead(
    val pagesFetched: Int,
    val observations: List<Observation>,
)

/**
 * True when [candidate] is the fresher server mutation projection of the same
 * sample and must replace [incumbent].
 *
 * A higher `reconciledAt` always wins first — a reconciliation (rollover patch
 * or completeness upgrade) is the collector's later write stage, so its
 * projection must win over whatever the older persisted projection carries.
 * Ties fall back to the higher `persistedAt`, and an exact tie on both server
 * timestamps prefers the reconciled-stream projection ([candidateKind] ==
 * [TelemetryReadKind.RECONCILED_AT]) for the same reason. `Long.MIN_VALUE`
 * stands in for a missing timestamp so a row that carries a mutation timestamp
 * always beats one that still has none.
 */
private fun fresherThan(
    candidate: Observation,
    incumbent: Observation,
    candidateKind: TelemetryReadKind,
): Boolean {
    val incumbentReconciled = incumbent.reconciledAtEpochMillis ?: Long.MIN_VALUE
    val candidateReconciled = candidate.reconciledAtEpochMillis ?: Long.MIN_VALUE
    if (candidateReconciled != incumbentReconciled) return candidateReconciled > incumbentReconciled

    val incumbentPersisted = incumbent.persistedAtEpochMillis ?: Long.MIN_VALUE
    val candidatePersisted = candidate.persistedAtEpochMillis ?: Long.MIN_VALUE
    if (candidatePersisted != incumbentPersisted) return candidatePersisted > incumbentPersisted

    return candidateKind == TelemetryReadKind.RECONCILED_AT
}

/** Bounded, overlapped query bound: watermark (or recent window) minus overlap. */
private fun lowerBound(watermarkEpochMillis: Long?, nowEpochMillis: Long): Long {
    val base = watermarkEpochMillis ?: (nowEpochMillis - IncrementalSyncCoordinator.RECENT_WINDOW_MILLIS)
    return base - IncrementalSyncCoordinator.OVERLAP_MILLIS
}

/**
 * Monotonic next high-water mark: the larger of the previously recorded
 * watermark and the largest returned server timestamp, or whichever of the two
 * exists. `null` only when neither exists (nothing returned, no prior sync), so
 * a silent empty refresh never fabricates a watermark from the handset clock.
 */
private fun nextWatermark(previous: Long?, returned: Long?): Long? = when {
    returned == null -> previous
    previous == null -> returned
    else -> maxOf(previous, returned)
}

/**
 * Summary of one [IncrementalSyncCoordinator.syncIncremental] call.
 *
 * [observationsMerged] counts the unique `sampleId`s stored (each page's rows
 * were de-duplicated against both the other stream and itself); the new high
 * watermarks are `null` only when nothing was returned and no watermark existed
 * before.
 */
data class IncrementalSyncResult(
    val persistedPagesFetched: Int,
    val reconciledPagesFetched: Int,
    val observationsMerged: Int,
    val persistedLowerBoundEpochMillis: Long,
    val reconciledLowerBoundEpochMillis: Long,
    val persistedAtWatermarkEpochMillis: Long?,
    val reconciledAtWatermarkEpochMillis: Long?,
)
