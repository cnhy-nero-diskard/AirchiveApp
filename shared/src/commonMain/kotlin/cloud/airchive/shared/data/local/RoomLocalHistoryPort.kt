package cloud.airchive.shared.data.local

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.EnergyUnitSource
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Durable [LocalHistoryPort] backed by [AirchiveDatabase] (Room 3.0 KMP with the
 * bundled SQLite driver). Every record carries the exact (project, owner UID,
 * device ID) partition in its primary key, coverage is merged before any write,
 * and only normalized typed values from [Observation]/[DailyTotal]/
 * [CollectorHealth] plus the sync metadata are stored — never `raw` payload
 * maps or credentials.
 */
class RoomLocalHistoryPort(private val database: AirchiveDatabase) : LocalHistoryPort {

    private val dao = database.localHistoryDao()

    override fun observe(
        partition: HistoryPartition,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
    ): Flow<HistorySnapshot> {
        val project = partition.firebaseProjectId
        val owner = partition.ownerUid
        val device = partition.deviceId
        return combine(
            observationsFlow(project, owner, device, fromEpochMillis, toEpochMillis)
                .map { rows -> rows.map { it.toDomain(partition) } },
            dao.observeDailyTotals(project, owner, device)
                .map { rows -> rows.map { it.toDomain(partition) } },
            dao.observeCoveredRanges(project, owner, device)
                .map { rows -> rows.map { it.toDomain() } },
            dao.observeSync(project, owner, device)
                .map { it?.toDomain() ?: SyncMetadata() },
            dao.observeCollectorHealth(project, owner, device)
                .map { it?.toDomain() },
        ) { observations, totals, ranges, sync, health ->
            HistorySnapshot(
                partition = partition,
                observations = observations,
                dailyTotals = totals,
                coveredRanges = ranges,
                sync = sync,
                collectorHealth = health,
            )
        }
    }

    override suspend fun upsertObservations(partition: HistoryPartition, observations: List<Observation>) {
        dao.replaceObservations(observations.map { it.toEntity(partition) })
    }

    override suspend fun upsertDailyTotals(partition: HistoryPartition, totals: List<DailyTotal>) {
        dao.replaceDailyTotals(totals.map { it.toEntity(partition) })
    }

    override suspend fun upsertCollectorHealth(partition: HistoryPartition, health: CollectorHealth) {
        dao.replaceCollectorHealth(health.toEntity(partition))
    }

    override suspend fun upsertCoveredRange(partition: HistoryPartition, range: CoveredRange) {
        dao.mergeCoveredRange(
            partition.firebaseProjectId,
            partition.ownerUid,
            partition.deviceId,
            range.fromEpochMillis,
            range.toEpochMillis,
        )
    }

    override suspend fun recordSync(partition: HistoryPartition, metadata: SyncMetadata) {
        dao.replaceSync(metadata.toEntity(partition))
    }

    override suspend fun clear(partition: HistoryPartition) {
        dao.clearPartition(partition.firebaseProjectId, partition.ownerUid, partition.deviceId)
    }

    private fun observationsFlow(
        project: String,
        owner: String,
        device: String,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
    ): Flow<List<ObservationEntity>> = when {
        fromEpochMillis != null && toEpochMillis != null ->
            dao.observeObservationsInWindow(project, owner, device, fromEpochMillis, toEpochMillis)
        fromEpochMillis != null ->
            dao.observeObservationsStartingAfter(project, owner, device, fromEpochMillis)
        toEpochMillis != null ->
            dao.observeObservationsEndingBefore(project, owner, device, toEpochMillis)
        else -> dao.observeObservations(project, owner, device)
    }
}

/**
 * Merges overlapping/touching ranges into a minimal, sorted coverage list so
 * repeated historical selections never lose or duplicate covered windows. Kept
 * free of database state so it is unit-testable and mirrors the in-memory fake.
 */
internal fun normalizeCoveredRanges(ranges: List<CoveredRange>): List<CoveredRange> {
    if (ranges.isEmpty()) return emptyList()
    val merged = mutableListOf<CoveredRange>()
    for (range in ranges.sortedBy { it.fromEpochMillis }) {
        val last = merged.lastOrNull()
        if (last != null && range.fromEpochMillis <= last.toEpochMillis) {
            merged[merged.size - 1] = CoveredRange(
                fromEpochMillis = last.fromEpochMillis,
                toEpochMillis = maxOf(last.toEpochMillis, range.toEpochMillis),
            )
        } else {
            merged += range
        }
    }
    return merged
}

// ------------------------------------------------------------------ mapping

internal fun Observation.toEntity(partition: HistoryPartition) = ObservationEntity(
    firebaseProjectId = partition.firebaseProjectId,
    ownerUid = partition.ownerUid,
    deviceId = partition.deviceId,
    sampleId = sampleId,
    intervalStartEpochMillis = intervalStartEpochMillis,
    intervalEndEpochMillis = intervalEndEpochMillis,
    energyValue = energyValue,
    recordedUnit = recordedUnit?.name,
    intervalStatus = intervalStatus,
    quality = quality.name,
    qualityFlags = qualityFlags.mapToSet { it.name }.joinToString(STRING_LIST_SEPARATOR),
    flagTexts = flagTexts.joinToString(STRING_LIST_SEPARATOR),
    sourceFailure = sourceFailure,
    persistedAtEpochMillis = persistedAtEpochMillis,
    reconciledAtEpochMillis = reconciledAtEpochMillis,
    scheduledAtEpochMillis = scheduledAtEpochMillis,
    observedAtEpochMillis = observedAtEpochMillis,
    localDate = localDate,
    timezone = timezone,
    completeness = completeness,
    metadataVersion = metadataVersion,
    collectorVersion = collectorVersion,
    energyProperty = energyProperty,
    rawDailyTotal = rawDailyTotal,
    rawDailyTotalNumber = rawDailyTotalNumber,
    intervalValue = intervalValue,
    intervalValueNumber = intervalValueNumber,
    intervalSeconds = intervalSeconds,
    finalPreviousDayTotal = finalPreviousDayTotal,
    finalPreviousDayTotalNumber = finalPreviousDayTotalNumber,
    unitText = unitText,
    unitSource = unitSource?.name,
    unitSourceText = unitSourceText,
    energySourceFailureOk = energySourceFailure?.ok,
    energySourceFailureClass = energySourceFailure?.failureClass,
    energySourceFailureErrorCode = energySourceFailure?.errorCode,
    energySourceFailureErrorName = energySourceFailure?.errorName,
    stateSourceFailureOk = stateSourceFailure?.ok,
    stateSourceFailureClass = stateSourceFailure?.failureClass,
    stateSourceFailureErrorCode = stateSourceFailure?.errorCode,
    stateSourceFailureErrorName = stateSourceFailure?.errorName,
    modelDeviceId = deviceId,
)

internal fun ObservationEntity.toDomain(partition: HistoryPartition) = Observation(
    sampleId = sampleId,
    deviceId = modelDeviceId,
    intervalStartEpochMillis = intervalStartEpochMillis,
    intervalEndEpochMillis = intervalEndEpochMillis,
    energyValue = energyValue,
    recordedUnit = recordedUnit.toEnergyUnit(),
    intervalStatus = intervalStatus,
    quality = quality.toObservationQuality(),
    qualityFlags = qualityFlags.split(STRING_LIST_SEPARATOR)
        .filter { it.isNotEmpty() }
        .mapToSet { flag -> runCatching { QualityFlag.valueOf(flag) }.getOrNull() }
        .filterNotNullToSet(),
    sourceFailure = sourceFailure,
    persistedAtEpochMillis = persistedAtEpochMillis,
    reconciledAtEpochMillis = reconciledAtEpochMillis,
    scheduledAtEpochMillis = scheduledAtEpochMillis,
    observedAtEpochMillis = observedAtEpochMillis,
    localDate = localDate,
    timezone = timezone,
    completeness = completeness,
    metadataVersion = metadataVersion,
    collectorVersion = collectorVersion,
    energyProperty = energyProperty,
    rawDailyTotal = rawDailyTotal,
    rawDailyTotalNumber = rawDailyTotalNumber,
    intervalValue = intervalValue,
    intervalValueNumber = intervalValueNumber,
    intervalSeconds = intervalSeconds,
    finalPreviousDayTotal = finalPreviousDayTotal,
    finalPreviousDayTotalNumber = finalPreviousDayTotalNumber,
    unitText = unitText,
    unitSource = unitSource.toEnergyUnitSource(),
    unitSourceText = unitSourceText,
    flagTexts = flagTexts.split(STRING_LIST_SEPARATOR).filter { it.isNotEmpty() }.toSet(),
    energySourceFailure = energySourceFailureOk?.let { ok ->
        SourceReadFailure(
            ok = ok,
            failureClass = energySourceFailureClass,
            errorCode = energySourceFailureErrorCode,
            errorName = energySourceFailureErrorName,
        )
    },
    stateSourceFailure = stateSourceFailureOk?.let { ok ->
        SourceReadFailure(
            ok = ok,
            failureClass = stateSourceFailureClass,
            errorCode = stateSourceFailureErrorCode,
            errorName = stateSourceFailureErrorName,
        )
    },
)

internal fun DailyTotal.toEntity(partition: HistoryPartition) = DailyTotalEntity(
    firebaseProjectId = partition.firebaseProjectId,
    ownerUid = partition.ownerUid,
    deviceId = partition.deviceId,
    totalId = totalId,
    dayStartEpochMillis = dayStartEpochMillis,
    energyValue = energyValue,
    recordedUnit = recordedUnit?.name,
    source = source.name,
    finalized = finalized,
    localDate = localDate,
    totalText = totalText,
    totalNumber = totalNumber,
    unitText = unitText,
    fetchedAtEpochMillis = fetchedAtEpochMillis,
    modelDeviceId = deviceId,
)

internal fun DailyTotalEntity.toDomain(partition: HistoryPartition) = DailyTotal(
    totalId = totalId,
    dayStartEpochMillis = dayStartEpochMillis,
    energyValue = energyValue,
    recordedUnit = recordedUnit.toEnergyUnit(),
    source = source.toDailyTotalSource(),
    finalized = finalized,
    localDate = localDate,
    totalText = totalText,
    totalNumber = totalNumber,
    unitText = unitText,
    deviceId = modelDeviceId,
    fetchedAtEpochMillis = fetchedAtEpochMillis,
)

internal fun CoveredRange.toEntity(partition: HistoryPartition) = CoveredRangeEntity(
    firebaseProjectId = partition.firebaseProjectId,
    ownerUid = partition.ownerUid,
    deviceId = partition.deviceId,
    fromEpochMillis = fromEpochMillis,
    toEpochMillis = toEpochMillis,
)

internal fun CoveredRangeEntity.toDomain() = CoveredRange(
    fromEpochMillis = fromEpochMillis,
    toEpochMillis = toEpochMillis,
)

internal fun SyncMetadata.toEntity(partition: HistoryPartition) = SyncEntity(
    firebaseProjectId = partition.firebaseProjectId,
    ownerUid = partition.ownerUid,
    deviceId = partition.deviceId,
    lastSuccessfulSyncEpochMillis = lastSuccessfulSyncEpochMillis,
    lastErrorEpochMillis = lastErrorEpochMillis,
    lastError = lastError,
    persistedAtWatermarkEpochMillis = persistedAtWatermarkEpochMillis,
    reconciledAtWatermarkEpochMillis = reconciledAtWatermarkEpochMillis,
)

internal fun SyncEntity.toDomain() = SyncMetadata(
    lastSuccessfulSyncEpochMillis = lastSuccessfulSyncEpochMillis,
    lastErrorEpochMillis = lastErrorEpochMillis,
    lastError = lastError,
    persistedAtWatermarkEpochMillis = persistedAtWatermarkEpochMillis,
    reconciledAtWatermarkEpochMillis = reconciledAtWatermarkEpochMillis,
)

internal fun CollectorHealth.toEntity(partition: HistoryPartition) = CollectorHealthEntity(
    firebaseProjectId = partition.firebaseProjectId,
    ownerUid = partition.ownerUid,
    deviceId = partition.deviceId,
    status = status.name,
    lastPollEpochMillis = lastPollEpochMillis,
    lastSuccessEpochMillis = lastSuccessEpochMillis,
    lastFailureEpochMillis = lastFailureEpochMillis,
    lastFailureReason = lastFailureReason,
    lastSampleId = lastSampleId,
    lastSamplePath = lastSamplePath,
    lastErrorClass = lastErrorClass,
    consecutiveFailures = consecutiveFailures,
    consecutiveRateLimits = consecutiveRateLimits,
    rateLimitedUntilEpochMillis = rateLimitedUntilEpochMillis,
    collectorVersion = collectorVersion,
)

internal fun CollectorHealthEntity.toDomain() = CollectorHealth(
    status = status.toCollectorStatus(),
    lastPollEpochMillis = lastPollEpochMillis,
    lastSuccessEpochMillis = lastSuccessEpochMillis,
    lastFailureEpochMillis = lastFailureEpochMillis,
    lastFailureReason = lastFailureReason,
    lastSampleId = lastSampleId,
    lastSamplePath = lastSamplePath,
    lastErrorClass = lastErrorClass,
    consecutiveFailures = consecutiveFailures,
    consecutiveRateLimits = consecutiveRateLimits,
    rateLimitedUntilEpochMillis = rateLimitedUntilEpochMillis,
    collectorVersion = collectorVersion,
)

// ---------------------------------------------------------------- enum helpers

private fun String?.toEnergyUnit(): EnergyUnit? = enumOrNull<EnergyUnit>(this)

private fun String?.toEnergyUnitSource(): EnergyUnitSource? = enumOrNull<EnergyUnitSource>(this)

private fun String.toObservationQuality(): ObservationQuality =
    enumOrNull<ObservationQuality>(this) ?: ObservationQuality.UNAVAILABLE

private fun String.toDailyTotalSource(): DailyTotalSource =
    enumOrNull<DailyTotalSource>(this) ?: DailyTotalSource.LG_FINALIZED

private fun String.toCollectorStatus(): CollectorStatus =
    enumOrNull<CollectorStatus>(this) ?: CollectorStatus.UNKNOWN

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

private fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): Set<R> {
    val destination = LinkedHashSet<R>()
    for (item in this) destination += transform(item)
    return destination
}

private fun <T> Iterable<T?>.filterNotNullToSet(): Set<T> {
    val destination = LinkedHashSet<T>()
    for (item in this) if (item != null) destination += item
    return destination
}
