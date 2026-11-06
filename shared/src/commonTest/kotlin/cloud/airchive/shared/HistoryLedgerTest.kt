package cloud.airchive.shared

import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import cloud.airchive.shared.data.energy.EnergyPeriodType
import cloud.airchive.shared.data.energy.energyPeriodRange
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The History ledger's pure presentation model: days, hours, labels, and compact row summaries. */
class HistoryLedgerTest {
    private val date = LocalDate.parse("2026-10-24")
    private val dayStart = energyPeriodRange(EnergyPeriodType.DAY, date).startEpochMillis
    private val dayEnd = energyPeriodRange(EnergyPeriodType.DAY, date).endExclusiveEpochMillis
    private val slot = 300_000L

    private fun reading(
        id: String,
        slotIndex: Int,
        value: Double? = 10.0,
        unit: EnergyUnit? = EnergyUnit.WH,
        quality: ObservationQuality = ObservationQuality.NORMAL,
        dayOffsetMillis: Long = 0L,
    ) = Observation(
        sampleId = id,
        intervalStartEpochMillis = dayStart + dayOffsetMillis + slotIndex * slot,
        intervalEndEpochMillis = dayStart + dayOffsetMillis + (slotIndex + 1) * slot,
        energyValue = value,
        recordedUnit = unit,
        quality = quality,
    )

    /** One full hour: twelve usable 10 Wh readings (120 Wh). */
    private fun fullHour(hour: Int, prefix: String = "h", dayOffsetMillis: Long = 0L) =
        (0 until 12).map { reading("$prefix$hour-$it", hour * 12 + it, dayOffsetMillis = dayOffsetMillis) }

    // --- days and hours ---------------------------------------------------

    @Test fun daysAreNewestFirstAndEachDayTotalIsTheSumOfItsHours() {
        val samples = fullHour(10) + fullHour(11) + fullHour(5, "y", dayOffsetMillis = -24 * 3_600_000L)
        val days = historyDays(samples, dayEnd)

        assertEquals(listOf(date, LocalDate.parse("2026-10-23")), days.map { it.date })
        assertEquals(listOf(11, 10), days[0].hours.map { it.group.hour })
        assertEquals(240.0, days[0].energyWh)
        assertEquals(120.0, days[1].energyWh)
        assertEquals(days[0].energyWh, days[0].hours.sumOf { it.group.energyWh ?: 0.0 })
    }

    @Test fun historyHoursStaysTheFlatNewestFirstProjectionOfHistoryDays() {
        val samples = fullHour(10) + fullHour(11) + fullHour(5, "y", dayOffsetMillis = -24 * 3_600_000L)
        assertEquals(
            historyDays(samples, dayEnd).flatMap { it.hours }.map { it.key },
            historyHours(samples, dayEnd).map { it.key },
        )
    }

    @Test fun aFullyRecordedPastHourIsCompleteAndAGapMakesItPartial() {
        val gap = (0 until 12).filter { it != 4 }.map { reading("g-$it", 10 * 12 + it) }
        val hours = historyHours(fullHour(9) + gap, dayEnd)

        assertEquals(HistoryHourStatus.PARTIAL, hours.first { it.group.hour == 10 }.status)
        assertEquals(HistoryHourStatus.COMPLETE, hours.first { it.group.hour == 9 }.status)
        assertEquals("11 of 12 readings", hours.first { it.group.hour == 10 }.note(detailed = false).text)
    }

    @Test fun theHourInProgressIsLabelledAsSuchRatherThanPartial() {
        // 17:00-17:15 is cached; "now" is 17:15, so only three slots of this hour have elapsed.
        val samples = (0 until 3).map { reading("n-$it", 17 * 12 + it) }
        val hour = historyHours(samples, dayStart + 17 * 3_600_000L + 3 * slot).single()

        assertEquals(HistoryHourStatus.IN_PROGRESS, hour.status)
        val note = hour.note(detailed = false)
        assertEquals("In progress", note.badge)
        assertNull(note.text, "Simple mode keeps an in-progress hour quiet beyond its badge")
        assertEquals("3 of 3 readings", hour.note(detailed = true).text)
    }

    @Test fun anHourWithoutAnyUsableValueIsUnavailableNotZero() {
        val unknownUnit = (0 until 12).map { reading("u-$it", 8 * 12 + it, unit = null) }
        val hour = historyHours(unknownUnit, dayEnd).single()

        assertEquals(HistoryHourStatus.UNAVAILABLE, hour.status)
        assertNull(hour.group.energyWh)
        assertEquals("Unavailable", hour.note(detailed = false).badge)
    }

    @Test fun aCachedDayMissingHoursIsPartialAndCountsTheHoursItShouldHave() {
        val sparse = historyDays(fullHour(10) + fullHour(11), dayEnd).single()
        assertEquals(2, sparse.recordedHours)
        assertEquals(24, sparse.expectedHours)
        assertTrue(sparse.isPartial)

        val whole = historyDays((0 until 24).flatMap { fullHour(it) }, dayEnd).single()
        assertEquals(24, whole.recordedHours)
        assertEquals(24, whole.expectedHours)
        assertFalse(whole.isPartial)
        assertEquals(24 * 120.0, whole.energyWh)
    }

    @Test fun theCurrentDayExpectsOnlyTheHoursThatHaveBegun() {
        val now = dayStart + 17 * 3_600_000L + 3 * slot
        val today = historyDays((0..16).flatMap { fullHour(it) } + (0 until 3).map { reading("n-$it", 17 * 12 + it) }, now).single()

        assertTrue(today.isCurrentDay)
        assertEquals(18, today.expectedHours)
        assertEquals(18, today.recordedHours)
        assertFalse(today.isPartial, "A cache that is complete so far is not partial")
    }

    @Test fun aDayWithNoUsableEnergyHasNoTotalRatherThanZero() {
        val day = historyDays((0 until 12).map { reading("u-$it", it, value = null) }, dayEnd).single()
        assertNull(day.energyWh)
        assertTrue(day.isPartial)
    }

    // --- bars -------------------------------------------------------------

    @Test fun barFractionScalesToTheLargestHourAndNeverImpliesAGap() {
        assertEquals(1f, hourBarFraction(120.0, 120.0))
        assertEquals(0.5f, hourBarFraction(60.0, 120.0))
        assertEquals(0f, hourBarFraction(null, 120.0), "An unavailable hour has no bar")
        assertEquals(0f, hourBarFraction(0.0, 120.0), "A measured zero draws an empty track")
        assertEquals(0f, hourBarFraction(5.0, 0.0))
        assertTrue(hourBarFraction(0.001, 120.0) > 0f, "A non-zero reading keeps a visible sliver")
    }

    @Test fun maxHourEnergyIgnoresUnavailableHours() {
        val days = historyDays(fullHour(10) + (0 until 12).map { reading("u-$it", 8 * 12 + it, unit = null) }, dayEnd)
        assertEquals(120.0, maxHourEnergyWh(days))
        assertEquals(0.0, maxHourEnergyWh(emptyList()))
    }

    // --- labels -----------------------------------------------------------

    @Test fun dayTitlesNameTodayYesterdayThenTheDate() {
        val today = LocalDate.parse("2026-10-27")
        assertEquals("Today", historyDayTitle(today, today))
        assertEquals("Yesterday", historyDayTitle(LocalDate.parse("2026-10-26"), today))
        assertEquals("Sun 25 Oct", historyDayTitle(LocalDate.parse("2026-10-25"), today))
        assertEquals("Wed 31 Dec 2025", historyDayTitle(LocalDate.parse("2025-12-31"), today))
        assertEquals("Tue 27 Oct", historyShortDate(today))
        assertEquals("Tuesday 27 October 2026", historySpokenDate(today))
    }

    @Test fun yesterdayAcrossAMonthBoundaryStillResolves() {
        assertEquals("Yesterday", historyDayTitle(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-01")))
    }

    @Test fun hourRangesUseTwentyFourHourTimeAndWrapAtMidnight() {
        assertEquals("17:00–18:00", historyHourRange(17))
        assertEquals("00:00–01:00", historyHourRange(0))
        assertEquals("23:00–00:00", historyHourRange(23))
    }

    @Test fun todayFollowsTheAcsZoneNotTheViewers() {
        // 2026-10-24T16:30:00Z is already the 25th in Asia/Manila (UTC+8).
        assertEquals(LocalDate.parse("2026-10-25"), historyToday(1_792_859_400_000L))
    }

    // --- compact row summaries -------------------------------------------

    @Test fun aCleanUsableReadingIsJustItsValue() {
        val summary = reading("a", 0, value = 7.78).historyRowSummary()
        assertEquals("7.78 Wh", summary.valueText)
        assertNull(summary.badge)
        assertNull(summary.note)
    }

    @Test fun aKilowattHourReadingShowsItsRecordedUnitAndTheWattHourConversion() {
        val summary = reading("a", 0, value = 0.01, unit = EnergyUnit.KWH).historyRowSummary()
        assertEquals("0.01 kWh", summary.valueText)
        assertNull(summary.badge)
        assertEquals("≈ 10 Wh", summary.note)
    }

    @Test fun anAnomalousReadingStaysVisibleBadgedAndExcluded() {
        val summary = reading("a", 0, value = 412.0, quality = ObservationQuality.ANOMALOUS).historyRowSummary()
        assertEquals("412 Wh", summary.valueText)
        assertEquals("Anomalous", summary.badge)
        assertEquals("Excluded from usable totals", summary.note)
    }

    @Test fun gapsShowADashNeverAZero() {
        for (sample in listOf(
            reading("null", 0, value = null),
            reading("missing", 0, value = 42.0, quality = ObservationQuality.MISSING_INTERVAL),
            reading("unavailable", 0, value = 42.0, quality = ObservationQuality.UNAVAILABLE),
            reading("nan", 0, value = Double.NaN),
        )) {
            val summary = sample.historyRowSummary()
            assertEquals("—", summary.valueText, "${sample.sampleId} must read as a gap")
            assertEquals("Gap", summary.badge)
            assertNotNull(summary.note)
            assertFalse(summary.valueText.contains("42"), "${sample.sampleId} must not surface its stored value")
        }
    }

    @Test fun anEnergyReadFailureIsAGapEvenWhenAValueIsPresent() {
        val failed = reading("a", 0, value = 42.0).copy(energySourceFailure = SourceReadFailure(ok = false))
        val summary = failed.historyRowSummary()
        assertEquals("—", summary.valueText)
        assertEquals("Gap", summary.badge)
        assertEquals("Energy read failed", summary.note)
    }

    @Test fun aStateOnlyFailureLeavesTheEnergyValueIntact() {
        val stateFailed = reading("a", 0, value = 7.78).copy(stateSourceFailure = SourceReadFailure(ok = false))
        val summary = stateFailed.historyRowSummary()
        assertEquals("7.78 Wh", summary.valueText)
        assertNull(summary.badge)
        assertEquals("State read failed · energy unaffected", summary.note)
    }

    @Test fun anUnknownUnitIsBadgedAndNeverConverted() {
        val summary = reading("a", 0, value = 10.0, unit = null).copy(unitText = "legacy").historyRowSummary()
        assertEquals("10 (legacy)", summary.valueText)
        assertEquals("Unit unknown", summary.badge)
        assertEquals("Not converted", summary.note)
    }

    @Test fun typedQualityFlagsSurfaceAsAShortNote() {
        val flagged = reading("a", 0, value = 7.78).copy(qualityFlags = setOf(QualityFlag.PARTIAL_INTERVAL))
        assertEquals("Flags: partial interval", flagged.historyRowSummary().note)
    }
}
