package cloud.airchive.shared

import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.energy.EnergyPeriodType
import cloud.airchive.shared.data.energy.presentEnergyHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class EnergySummaryLabelTest {
    private val start = Instant.parse("2026-01-14T16:00:00Z").toEpochMilliseconds()
    private fun reading(slot: Int, quality: ObservationQuality = ObservationQuality.NORMAL) = Observation(
        sampleId = "s$slot", intervalStartEpochMillis = start + slot * 300_000L,
        intervalEndEpochMillis = start + (slot + 1) * 300_000L,
        energyValue = 10.0, recordedUnit = EnergyUnit.WH, quality = quality,
    )
    private fun presentation(readings: List<Observation>, now: Long = start + 43_200_000L) = presentEnergyHistory(
        observations = readings, dailyTotals = emptyList(), coveredRanges = emptyList(),
        periodType = EnergyPeriodType.DAY, selectedDate = LocalDate.parse("2026-01-15"), nowEpochMillis = now,
        timeZoneId = "Asia/Manila",
    )

    @Test fun asOfUsesLatestUsableIntervalRatherThanCurrentTimeOrAnomaly() {
        assertEquals("As of 12:05 AM", energySummaryAsOf(presentation(listOf(reading(0), reading(1, ObservationQuality.ANOMALOUS)))))
    }

    @Test fun historicalReadingIncludesItsLocalDate() {
        assertEquals("As of 2026-01-15 · 12:05 AM", energySummaryAsOf(presentation(listOf(reading(0)), start + 86_400_000L)))
    }

    @Test fun missingDataHasNoInventedTimestamp() {
        assertEquals("No readings yet", energySummaryAsOf(presentation(emptyList())))
    }
}
