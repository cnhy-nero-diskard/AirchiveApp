package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.Observation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic, in-memory owner-local history store keyed by the exact
 * (project, owner UID, device ID) partition. No network or database; it backs
 * the same [LocalHistoryPort] contract the Room KMP adapter will implement.
 */
class FakeLocalHistory : LocalHistoryPort {

    private val mutex = Mutex()
    private val snapshots = mutableMapOf<HistoryPartition, MutableStateFlow<HistorySnapshot>>()

    override fun observe(
        partition: HistoryPartition,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
    ): Flow<HistorySnapshot> = flowFor(partition).map { snapshot ->
        if (fromEpochMillis == null && toEpochMillis == null) {
            snapshot
        } else {
            snapshot.copy(
                observations = snapshot.observations.filter { o ->
                    (fromEpochMillis == null || o.intervalEndEpochMillis > fromEpochMillis) &&
                        (toEpochMillis == null || o.intervalStartEpochMillis < toEpochMillis)
                },
            )
        }
    }

    override suspend fun upsertObservations(partition: HistoryPartition, observations: List<Observation>) =
        mutex.withLock {
            val current = flowFor(partition).value
            val merged = current.observations
                .map { existing -> observations.firstOrNull { it.sampleId == existing.sampleId } ?: existing } +
                observations.filter { new -> current.observations.none { it.sampleId == new.sampleId } }
            publish(partition, current.copy(observations = merged))
        }

    override suspend fun upsertDailyTotals(partition: HistoryPartition, totals: List<DailyTotal>) =
        mutex.withLock {
            val current = flowFor(partition).value
            val merged = current.dailyTotals
                .map { existing -> totals.firstOrNull { it.totalId == existing.totalId } ?: existing } +
                totals.filter { new -> current.dailyTotals.none { it.totalId == new.totalId } }
            publish(partition, current.copy(dailyTotals = merged))
        }

    override suspend fun upsertCollectorHealth(partition: HistoryPartition, health: CollectorHealth) =
        mutex.withLock {
            val current = flowFor(partition).value
            publish(partition, current.copy(collectorHealth = health))
        }

    override suspend fun upsertCoveredRange(partition: HistoryPartition, range: CoveredRange) =
        mutex.withLock {
            val current = flowFor(partition).value
            publish(partition, current.copy(coveredRanges = normalizeCoverage(current.coveredRanges + range)))
        }

    override suspend fun recordSync(partition: HistoryPartition, metadata: SyncMetadata) =
        mutex.withLock {
            val current = flowFor(partition).value
            publish(partition, current.copy(sync = metadata))
        }

    override suspend fun clear(partition: HistoryPartition) =
        mutex.withLock {
            flowFor(partition).value = emptySnapshot(partition)
        }

    /** Manual override used by fixture/smoke flows (e.g. simulating prior owner data). */
    suspend fun replace(partition: HistoryPartition, snapshot: HistorySnapshot): Unit = mutex.withLock {
        publish(partition, snapshot)
    }

    private fun flowFor(partition: HistoryPartition): MutableStateFlow<HistorySnapshot> =
        snapshots.getOrPut(partition) { MutableStateFlow(emptySnapshot(partition)) }

    /**
     * Merges overlapping/touching ranges into a minimal, sorted coverage list so
     * repeated historical selections never lose or duplicate covered windows.
     */
    private fun normalizeCoverage(ranges: List<CoveredRange>): List<CoveredRange> {
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

    private fun publish(partition: HistoryPartition, snapshot: HistorySnapshot) {
        flowFor(partition).value = snapshot
    }

    private fun emptySnapshot(partition: HistoryPartition) = HistorySnapshot(
        partition = partition,
        observations = emptyList(),
        dailyTotals = emptyList(),
        coveredRanges = emptyList(),
        sync = SyncMetadata(),
        collectorHealth = null,
    )

    companion object {
        fun syncedSnapshotFor(
            partition: HistoryPartition,
            observations: List<Observation> = emptyList(),
            dailyTotals: List<DailyTotal> = emptyList(),
            coveredRanges: List<CoveredRange> = emptyList(),
            sync: SyncMetadata = SyncMetadata(lastSuccessfulSyncEpochMillis = FAKE_EPOCH_MILLIS),
            collectorHealth: CollectorHealth? = null,
        ): HistorySnapshot = HistorySnapshot(
            partition = partition,
            observations = observations,
            dailyTotals = dailyTotals,
            coveredRanges = coveredRanges,
            sync = sync,
            collectorHealth = collectorHealth,
        )
    }
}