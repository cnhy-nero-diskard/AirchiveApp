package cloud.airchive.shared.contract.history

import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.Observation
import kotlinx.coroutines.flow.Flow

/**
 * Durable, owner-local history projection (Room on Android/iOS, in-memory fake
 * elsewhere).
 *
 * Every record and sync marker is scoped by an exact
 * (Firebase project, owner UID, device ID) [HistoryPartition], so switching
 * Google accounts can never surface another owner's cache and the configured
 * device's rows never mix with another device's. This is the *local* write
 * surface; the read-only boundary lives on [AirchiveTelemetryPort]. No raw
 * payloads enter this store — only normalized [Observation]/[DailyTotal]
 * projections.
 */
interface LocalHistoryPort {

    /**
     * Observes the partition's projection, optionally windowed to observations
     * overlapping `(fromEpochMillis, toEpochMillis)`. Emits immediately with the
     * cached contents so the UI renders before any network work.
     */
    fun observe(
        partition: HistoryPartition,
        fromEpochMillis: Long? = null,
        toEpochMillis: Long? = null,
    ): Flow<HistorySnapshot>

    /** Inserts or replaces [observations] in [partition], merged by `sampleId`. */
    suspend fun upsertObservations(partition: HistoryPartition, observations: List<Observation>)

    /**
     * Commits one bounded incremental refresh atomically: [observations] are
     * upserted (merged by `sampleId`) and [metadata] — last-success age plus
     * both mutation watermarks — is recorded in the same durable commit.
     * Either both land or neither does, so the watermarks can never advance
     * past observation rows that were not written. Prefer this over a
     * [upsertObservations] + [recordSync] pair for incremental refresh; the
     * separate methods stay for every other workflow.
     */
    suspend fun upsertObservationsAndRecordSync(
        partition: HistoryPartition,
        observations: List<Observation>,
        metadata: SyncMetadata,
    )

    /** Inserts or replaces [totals] in [partition], merged by `totalId`. */
    suspend fun upsertDailyTotals(partition: HistoryPartition, totals: List<DailyTotal>)

    /** Stores the freshest collector health for the partition. */
    suspend fun upsertCollectorHealth(partition: HistoryPartition, health: CollectorHealth)

    /**
     * Persists a freshly covered observation range, merging it with existing
     * [CoveredRange]s so range coverage stays durable (an uncached historical
     * selection is re-fetched even if the incremental watermark is current).
     */
    suspend fun upsertCoveredRange(partition: HistoryPartition, range: CoveredRange)

    /**
     * Persists sync metadata (last-success age, watermarks, last error) for the
     * partition. Watermarks advance only after a successful bounded sync.
     */
    suspend fun recordSync(partition: HistoryPartition, metadata: SyncMetadata)

    /** Removes every projection and sync marker for the partition. */
    suspend fun clear(partition: HistoryPartition)
}

/** Exact owner/device cache key. All three fields are always present. */
data class HistoryPartition(
    val firebaseProjectId: String,
    val ownerUid: String,
    val deviceId: String,
)

/** A contiguously cached observation range, kept separate from mutation freshness. */
data class CoveredRange(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
)

/** Durable refresh bookkeeping for one partition. */
data class SyncMetadata(
    val lastSuccessfulSyncEpochMillis: Long? = null,
    val lastErrorEpochMillis: Long? = null,
    val lastError: String? = null,
    val persistedAtWatermarkEpochMillis: Long? = null,
    val reconciledAtWatermarkEpochMillis: Long? = null,
)

/** One renderable view of a partition's local projection. */
data class HistorySnapshot(
    val partition: HistoryPartition,
    val observations: List<Observation>,
    val dailyTotals: List<DailyTotal>,
    val coveredRanges: List<CoveredRange>,
    val sync: SyncMetadata? = null,
    val collectorHealth: CollectorHealth? = null,
)