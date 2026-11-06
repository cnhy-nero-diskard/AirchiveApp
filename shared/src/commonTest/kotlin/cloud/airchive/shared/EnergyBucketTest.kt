package cloud.airchive.shared

import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.energy.EnergyPeriodType
import cloud.airchive.shared.data.energy.presentEnergyHistory
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class EnergyBucketTest {
    private val start = Instant.parse("2026-01-14T16:00:00Z").toEpochMilliseconds()
    private fun sample(slot: Int, value: Double?, unit: EnergyUnit?, quality: ObservationQuality = ObservationQuality.NORMAL) = Observation(
        sampleId = "s$slot", intervalStartEpochMillis = start + slot * 300_000L,
        intervalEndEpochMillis = start + (slot + 1) * 300_000L,
        energyValue = value, recordedUnit = unit, quality = quality,
    )
    private fun days() = presentEnergyHistory(
        observations = listOf(sample(0, 0.0, EnergyUnit.WH), sample(1, .01, EnergyUnit.KWH),
            sample(2, 8.0, null), sample(3, 999.0, EnergyUnit.WH, ObservationQuality.ANOMALOUS)),
        dailyTotals = emptyList(), coveredRanges = emptyList(), periodType = EnergyPeriodType.DAY,
        selectedDate = LocalDate.parse("2026-01-15"), nowEpochMillis = start + 6 * 300_000L,
    ).days

    @Test fun bucketsPreserveZeroUnknownUnitsAnomaliesAndGaps() {
        val fine = energyBuckets(days(), 5)
        assertEquals(0.0, fine[0].wh)
        assertEquals(10.0, fine[1].wh)
        assertNull(fine[2].wh)
        assertNull(fine[3].wh)
        assertNull(fine[4].wh)
        assertTrue(fine[0].complete)
        val coarse = energyBuckets(days(), 15)
        assertEquals(10.0, coarse[0].wh)
        assertEquals(2, coarse[0].usable)
        assertEquals(3, coarse[0].expected)
        assertFalse(coarse[0].complete)
        assertNull(coarse[1].wh)
        assertEquals(0, coarse[2].expected)
        assertFalse(coarse[2].complete)
    }

    @Test fun changingResolutionPreservesUsableEnergyAndDayBoundary() {
        for (minutes in listOf(5, 15, 30, 60, 360, 1440)) {
            val buckets = energyBuckets(days(), minutes)
            assertEquals(10.0, buckets.mapNotNull { it.wh }.sum())
            assertEquals(start, buckets.first().start)
            assertEquals(start + 86_400_000L, buckets.last().end)
        }
    }
}
