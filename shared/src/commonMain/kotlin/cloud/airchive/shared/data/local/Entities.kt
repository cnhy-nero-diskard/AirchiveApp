package cloud.airchive.shared.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Room storage projection of the owner-local Airchive history.
 *
 * Every table is partitioned by the exact (Firebase project, owner UID, device
 * ID) triple, which is always part of the record's primary key, so a signed-in
 * owner can never observe another owner's or another device's cache. Only
 * **normalized, whitelisted** typed values are stored: the Firestore `raw` and
 * `state` payload maps are discarded before anything reaches these entities.
 * The `raw_daily_total*` / `interval_value*` / `final_previous_day_total*`
 * columns are the authoritative *numeric/text mirrors* the parser extracts from
 * the document, not the opaque payload the collector stores.
 *
 * Set-valued normalized fields (`quality_flags`, `flag_texts`) are persisted as
 * [STRING_LIST_SEPARATOR]-joined TEXT columns and mapped back to typed
 * `Set<QualityFlag>`/`Set<String>` by the port. The separator is a control
 * character that cannot appear in source flag text, so unknown future flag
 * strings survive round trips verbatim. This intentionally avoids Room
 * `@TypeConverters`, whose default-value resolution still trips KSP2's analysis
 * API with a `[MissingType]` failure at this toolchain set (Room 3.0.3, KSP
 * 2.3.12, Kotlin 2.4.20).
 */

/** Delimiter for set-valued TEXT columns; control character never emitted by the source. */
internal const val STRING_LIST_SEPARATOR: String = "\u001F"

/** Ten: `devices/{deviceId}/telemetry/{sampleId}` samples. */
@Entity(
    tableName = "observations",
    primaryKeys = ["firebase_project_id", "owner_uid", "device_id", "sample_id"],
    indices = [
        Index(value = ["firebase_project_id", "owner_uid", "device_id", "interval_start_epoch_millis"]),
        Index(value = ["firebase_project_id", "owner_uid", "device_id", "interval_end_epoch_millis"]),
    ],
)
data class ObservationEntity(
    @ColumnInfo(name = "firebase_project_id") val firebaseProjectId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    /** Partition device id (the configured device path). */
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "sample_id") val sampleId: String,
    @ColumnInfo(name = "interval_start_epoch_millis") val intervalStartEpochMillis: Long,
    @ColumnInfo(name = "interval_end_epoch_millis") val intervalEndEpochMillis: Long,
    /** Null interval energy stays null — never stored as a fabricated zero. */
    @ColumnInfo(name = "energy_value") val energyValue: Double?,
    /** Stored as the enum name (`WH`/`KWH`); legacy rows stay `null`. */
    @ColumnInfo(name = "recorded_unit") val recordedUnit: String?,
    @ColumnInfo(name = "interval_status") val intervalStatus: String?,
    /** Stored as the enum name, e.g. `"NORMAL"`. */
    @ColumnInfo(name = "quality") val quality: String,
    /** [STRING_LIST_SEPARATOR]-joined enum names; empty text means no flags. */
    @ColumnInfo(name = "quality_flags") val qualityFlags: String,
    /** [STRING_LIST_SEPARATOR]-joined verbatim source flag strings. */
    @ColumnInfo(name = "flag_texts") val flagTexts: String,
    @ColumnInfo(name = "source_failure") val sourceFailure: String?,
    @ColumnInfo(name = "persisted_at_epoch_millis") val persistedAtEpochMillis: Long?,
    @ColumnInfo(name = "reconciled_at_epoch_millis") val reconciledAtEpochMillis: Long?,
    @ColumnInfo(name = "scheduled_at_epoch_millis") val scheduledAtEpochMillis: Long?,
    @ColumnInfo(name = "observed_at_epoch_millis") val observedAtEpochMillis: Long?,
    @ColumnInfo(name = "local_date") val localDate: String?,
    @ColumnInfo(name = "timezone") val timezone: String?,
    @ColumnInfo(name = "completeness") val completeness: Int?,
    @ColumnInfo(name = "metadata_version") val metadataVersion: String?,
    @ColumnInfo(name = "collector_version") val collectorVersion: String?,
    @ColumnInfo(name = "energy_property") val energyProperty: String?,
    @ColumnInfo(name = "raw_daily_total") val rawDailyTotal: String?,
    @ColumnInfo(name = "raw_daily_total_number") val rawDailyTotalNumber: Double?,
    @ColumnInfo(name = "interval_value") val intervalValue: String?,
    @ColumnInfo(name = "interval_value_number") val intervalValueNumber: Double?,
    @ColumnInfo(name = "interval_seconds") val intervalSeconds: Double?,
    @ColumnInfo(name = "final_previous_day_total") val finalPreviousDayTotal: String?,
    @ColumnInfo(name = "final_previous_day_total_number") val finalPreviousDayTotalNumber: Double?,
    @ColumnInfo(name = "unit_text") val unitText: String?,
    @ColumnInfo(name = "unit_source") val unitSource: String?,
    @ColumnInfo(name = "unit_source_text") val unitSourceText: String?,
    /** Nullable because a sample may have no energy source outcome at all. */
    @ColumnInfo(name = "energy_source_failure_ok") val energySourceFailureOk: Boolean?,
    @ColumnInfo(name = "energy_source_failure_class") val energySourceFailureClass: String?,
    @ColumnInfo(name = "energy_source_failure_error_code") val energySourceFailureErrorCode: String?,
    @ColumnInfo(name = "energy_source_failure_error_name") val energySourceFailureErrorName: String?,
    @ColumnInfo(name = "state_source_failure_ok") val stateSourceFailureOk: Boolean?,
    @ColumnInfo(name = "state_source_failure_class") val stateSourceFailureClass: String?,
    @ColumnInfo(name = "state_source_failure_error_code") val stateSourceFailureErrorCode: String?,
    @ColumnInfo(name = "state_source_failure_error_name") val stateSourceFailureErrorName: String?,
    /** Source-reported `deviceId` mirror, kept separate from the partition key. */
    @ColumnInfo(name = "model_device_id") val modelDeviceId: String?,
)

/** One finalized (or locally computed) daily energy figure. */
@Entity(
    tableName = "daily_totals",
    primaryKeys = ["firebase_project_id", "owner_uid", "device_id", "total_id"],
)
data class DailyTotalEntity(
    @ColumnInfo(name = "firebase_project_id") val firebaseProjectId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "total_id") val totalId: String,
    @ColumnInfo(name = "day_start_epoch_millis") val dayStartEpochMillis: Long,
    @ColumnInfo(name = "energy_value") val energyValue: Double,
    @ColumnInfo(name = "recorded_unit") val recordedUnit: String?,
    /** Enum name: `LG_FINALIZED` / `LOCAL_INTERVAL_SUM`. */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "finalized") val finalized: Boolean,
    @ColumnInfo(name = "local_date") val localDate: String?,
    @ColumnInfo(name = "total_text") val totalText: String?,
    @ColumnInfo(name = "total_number") val totalNumber: Double?,
    @ColumnInfo(name = "unit_text") val unitText: String?,
    @ColumnInfo(name = "fetched_at_epoch_millis") val fetchedAtEpochMillis: Long?,
    /** Source-reported `deviceId` mirror, kept separate from the partition key. */
    @ColumnInfo(name = "model_device_id") val modelDeviceId: String?,
)

/** A durably covered observation range, kept separate from mutation watermarks. */
@Entity(
    tableName = "covered_ranges",
    primaryKeys = ["firebase_project_id", "owner_uid", "device_id", "from_epoch_millis"],
)
data class CoveredRangeEntity(
    @ColumnInfo(name = "firebase_project_id") val firebaseProjectId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "from_epoch_millis") val fromEpochMillis: Long,
    @ColumnInfo(name = "to_epoch_millis") val toEpochMillis: Long,
)

/** One row per partition holding durable refresh bookkeeping. */
@Entity(
    tableName = "sync_metadata",
    primaryKeys = ["firebase_project_id", "owner_uid", "device_id"],
)
data class SyncEntity(
    @ColumnInfo(name = "firebase_project_id") val firebaseProjectId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_successful_sync_epoch_millis") val lastSuccessfulSyncEpochMillis: Long?,
    @ColumnInfo(name = "last_error_epoch_millis") val lastErrorEpochMillis: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "persisted_at_watermark_epoch_millis") val persistedAtWatermarkEpochMillis: Long?,
    /** Added in schema v2 via an explicit migration. */
    @ColumnInfo(name = "reconciled_at_watermark_epoch_millis") val reconciledAtWatermarkEpochMillis: Long?,
)

/** The freshest sanitized collector health record per partition. */
@Entity(
    tableName = "collector_health",
    primaryKeys = ["firebase_project_id", "owner_uid", "device_id"],
)
data class CollectorHealthEntity(
    @ColumnInfo(name = "firebase_project_id") val firebaseProjectId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    /** Enum name: `UNKNOWN`/`HEALTHY`/`DEGRADED`/`OFFLINE`. */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "last_poll_epoch_millis") val lastPollEpochMillis: Long?,
    @ColumnInfo(name = "last_success_epoch_millis") val lastSuccessEpochMillis: Long?,
    @ColumnInfo(name = "last_failure_epoch_millis") val lastFailureEpochMillis: Long?,
    @ColumnInfo(name = "last_failure_reason") val lastFailureReason: String?,
    @ColumnInfo(name = "last_sample_id") val lastSampleId: String?,
    @ColumnInfo(name = "last_sample_path") val lastSamplePath: String?,
    @ColumnInfo(name = "last_error_class") val lastErrorClass: String?,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int?,
    @ColumnInfo(name = "consecutive_rate_limits") val consecutiveRateLimits: Int?,
    @ColumnInfo(name = "rate_limited_until_epoch_millis") val rateLimitedUntilEpochMillis: Long?,
    @ColumnInfo(name = "collector_version") val collectorVersion: String?,
)