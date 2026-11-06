package cloud.airchive.shared

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistorySelectionPartitionTest {

    @Test
    fun oldSampleIdentifierCannotSelectAnotherOwnersRowWithTheSameId() {
        val ownerAPartition = HistoryPartition("project-1", "owner-a", "device-1")
        val ownerBPartition = HistoryPartition("project-1", "owner-b", "device-1")
        val ownerASample = sample("shared-sample-id", energy = 12.0)
        val ownerBSample = sample("shared-sample-id", energy = 99.0)
        val ownerASnapshot = snapshot(ownerAPartition, ownerASample)
        val ownerBSnapshot = snapshot(ownerBPartition, ownerBSample)
        val priorSelection = HistorySampleSelection(ownerAPartition, ownerASample.sampleId)

        assertEquals(ownerASample, selectedHistoryObservation(priorSelection, ownerASnapshot))
        assertNull(
            selectedHistoryObservation(priorSelection, ownerBSnapshot),
            "a matching sampleId in a different owner partition cannot reopen stale detail",
        )
    }

    @Test
    fun credentialGenerationIsNotPartOfHistorySelectionPartition() {
        val partition = HistoryPartition("project-1", "owner-a", "device-1")
        val sample = sample("sample-1", energy = 12.0)
        val selection = HistorySampleSelection(partition, sample.sampleId)

        assertEquals(sample, selectedHistoryObservation(selection, snapshot(partition, sample)),
            "PAT replacement does not alter the owner/project/device history partition")
    }

    private fun snapshot(partition: HistoryPartition, sample: Observation) = HistorySnapshot(
        partition = partition,
        observations = listOf(sample),
        dailyTotals = emptyList(),
        coveredRanges = emptyList(),
        sync = SyncMetadata(),
    )

    private fun sample(sampleId: String, energy: Double) = Observation(
        sampleId = sampleId,
        intervalStartEpochMillis = 1L,
        intervalEndEpochMillis = 2L,
        energyValue = energy,
        recordedUnit = EnergyUnit.WH,
    )
}
