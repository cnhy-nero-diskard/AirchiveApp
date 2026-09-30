package cloud.airchive.shared.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import cloud.airchive.shared.contract.history.CoveredRange
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO over the partitioned local projection. Every query scopes rows by the
 * exact (firebase project, owner UID, device ID) partition, so reads can never
 * surface another owner/device's cache. All DAO methods are `suspend` or return
 * `Flow`, which is what Room KMP requires for non-Android platforms.
 */
@Dao
interface LocalHistoryDao {

    // ---------------------------------------------------------------- observations

    @Query(
        "SELECT * FROM observations " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "ORDER BY interval_start_epoch_millis ASC",
    )
    fun observeObservations(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): Flow<List<ObservationEntity>>

    @Query(
        "SELECT * FROM observations " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "AND interval_start_epoch_millis < :toEpochMillis " +
            "ORDER BY interval_start_epoch_millis ASC",
    )
    fun observeObservationsEndingBefore(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
        toEpochMillis: Long,
    ): Flow<List<ObservationEntity>>

    @Query(
        "SELECT * FROM observations " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "AND interval_end_epoch_millis > :fromEpochMillis " +
            "ORDER BY interval_start_epoch_millis ASC",
    )
    fun observeObservationsStartingAfter(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
        fromEpochMillis: Long,
    ): Flow<List<ObservationEntity>>

    @Query(
        "SELECT * FROM observations " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "AND interval_end_epoch_millis > :fromEpochMillis AND interval_start_epoch_millis < :toEpochMillis " +
            "ORDER BY interval_start_epoch_millis ASC",
    )
    fun observeObservationsInWindow(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ): Flow<List<ObservationEntity>>

    /**
     * Replaces matching rows (merged by primary key) instead of using
     * `@Upsert`: the exception-based insert-then-update fallback of `@Upsert`
     * relies on the Android framework's `SQLException` type, which the bundled
     * driver's error type does not match on the host JVM. `INSERT OR REPLACE` is
     * deterministic on every platform and keeps the merge semantics intact.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceObservations(rows: List<ObservationEntity>)

    @Query(
        "DELETE FROM observations " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    suspend fun clearObservations(firebaseProjectId: String, ownerUid: String, deviceId: String)

    // -------------------------------------------------------------- daily totals

    @Query(
        "SELECT * FROM daily_totals " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "ORDER BY day_start_epoch_millis ASC",
    )
    fun observeDailyTotals(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): Flow<List<DailyTotalEntity>>

    /** Same replace-by-primary-key strategy as [replaceObservations]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceDailyTotals(rows: List<DailyTotalEntity>)

    @Query(
        "DELETE FROM daily_totals " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    suspend fun clearDailyTotals(firebaseProjectId: String, ownerUid: String, deviceId: String)

    // ------------------------------------------------------------- covered ranges

    @Query(
        "SELECT * FROM covered_ranges " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "ORDER BY from_epoch_millis ASC",
    )
    fun observeCoveredRanges(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): Flow<List<CoveredRangeEntity>>

    @Query(
        "SELECT * FROM covered_ranges " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId " +
            "ORDER BY from_epoch_millis ASC",
    )
    suspend fun coveredRanges(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): List<CoveredRangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoveredRanges(rows: List<CoveredRangeEntity>)

    @Query(
        "DELETE FROM covered_ranges " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    suspend fun clearCoveredRanges(firebaseProjectId: String, ownerUid: String, deviceId: String)

    /** Replaces a partition's coverage with [ranges] in one write transaction. */
    @Transaction
    suspend fun replaceCoveredRanges(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
        ranges: List<CoveredRangeEntity>,
    ) {
        clearCoveredRanges(firebaseProjectId, ownerUid, deviceId)
        if (ranges.isNotEmpty()) insertCoveredRanges(ranges)
    }

    /** Reads, merges, and replaces coverage atomically to prevent concurrent range-loss. */
    @Transaction
    suspend fun mergeCoveredRange(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ) {
        val partition = cloud.airchive.shared.contract.history.HistoryPartition(
            firebaseProjectId = firebaseProjectId,
            ownerUid = ownerUid,
            deviceId = deviceId,
        )
        val range = CoveredRange(fromEpochMillis, toEpochMillis)
        val existing = coveredRanges(firebaseProjectId, ownerUid, deviceId).map { it.toDomain() }
        val merged = normalizeCoveredRanges(existing + range).map { it.toEntity(partition) }
        clearCoveredRanges(firebaseProjectId, ownerUid, deviceId)
        if (merged.isNotEmpty()) insertCoveredRanges(merged)
    }

    // ---------------------------------------------------------------- sync state

    @Query(
        "SELECT * FROM sync_metadata " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    fun observeSync(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): Flow<SyncEntity?>

    /** One row per partition, replaced wholesale by default. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceSync(row: SyncEntity)

    @Query(
        "DELETE FROM sync_metadata " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    suspend fun clearSync(firebaseProjectId: String, ownerUid: String, deviceId: String)

    // ----------------------------------------------------------- collector health

    @Query(
        "SELECT * FROM collector_health " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    fun observeCollectorHealth(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ): Flow<CollectorHealthEntity?>

    /** One row per partition, replaced wholesale by default. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCollectorHealth(row: CollectorHealthEntity)

    @Query(
        "DELETE FROM collector_health " +
            "WHERE firebase_project_id = :firebaseProjectId AND owner_uid = :ownerUid AND device_id = :deviceId",
    )
    suspend fun clearCollectorHealth(firebaseProjectId: String, ownerUid: String, deviceId: String)

    // -------------------------------------------------------------- clear (whole)

    /** Removes every projection and sync marker for one partition atomically. */
    @Transaction
    suspend fun clearPartition(
        firebaseProjectId: String,
        ownerUid: String,
        deviceId: String,
    ) {
        clearObservations(firebaseProjectId, ownerUid, deviceId)
        clearDailyTotals(firebaseProjectId, ownerUid, deviceId)
        clearCoveredRanges(firebaseProjectId, ownerUid, deviceId)
        clearSync(firebaseProjectId, ownerUid, deviceId)
        clearCollectorHealth(firebaseProjectId, ownerUid, deviceId)
    }
}
