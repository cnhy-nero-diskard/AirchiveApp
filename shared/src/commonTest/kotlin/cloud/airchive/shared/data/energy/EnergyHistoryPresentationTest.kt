package cloud.airchive.shared.data.energy

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import cloud.airchive.shared.data.telemetry.FALLBACK_INTERVAL_MILLIS
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic, credential-free tests for the pure energy presentation layer
 * ([presentEnergyHistory] / [energyPeriodRange]). Synthetic local fixtures only:
 * no network, no accounts, no Firebase, no charts/Compose.
 *
 * Covers the `Asia/Manila` day boundary versus UTC, ISO week and month ranges,
 * current-day partial (completed-only) slots, gaps that are never zeros,
 * out-of-order and duplicate slot resolution, anomalous/source-failed/
 * unknown-unit exclusion while still being reported, mixed Wh/kWh
 * normalization with source labels retained, finalized LG totals kept distinct
 * from interval sums, query-cache coverage versus measured coverage, unknown
 * daily-total units never guessed, and DST-short-day conversion without errors.
 */
class EnergyHistoryPresentationTest {

    private val manila = TimeZone.of(DEFAULT_DAY_TIME_ZONE)

    private fun dayStartIso(date: String): Long =
        LocalDate.parse(date).atStartOfDayIn(manila).toEpochMilliseconds()

    private fun slotStartIso(date: String, slotIndex: Int): Long =
        dayStartIso(date) + slotIndex * FALLBACK_INTERVAL_MILLIS

    private fun sample(
        id: String,
        start: Long,
        end: Long = start + FALLBACK_INTERVAL_MILLIS,
        value: Double? = null,
        unit: EnergyUnit? = if (value == null) null else EnergyUnit.WH,
        unitText: String? = unit?.name,
        unitSourceText: String? = "device",
        quality: ObservationQuality = if (value == null) ObservationQuality.MISSING_INTERVAL else ObservationQuality.NORMAL,
        intervalStatus: String? = "NORMAL",
        sourceFailure: String? = null,
        energySourceFailed: Boolean = false,
        persistedAt: Long? = null,
    ): Observation = Observation(
        sampleId = id,
        intervalStartEpochMillis = start,
        intervalEndEpochMillis = end,
        energyValue = value,
        recordedUnit = unit,
        unitText = unitText,
        unitSourceText = unitSourceText,
        quality = quality,
        intervalStatus = intervalStatus,
        sourceFailure = sourceFailure,
        persistedAtEpochMillis = persistedAt,
        energySourceFailure = if (energySourceFailed) {
            SourceReadFailure(ok = false, failureClass = "TRANSIENT", errorCode = "read_failed")
        } else {
            null
        },
    )

    private fun total(
        id: String,
        date: String,
        value: Double,
        unit: EnergyUnit? = EnergyUnit.KWH,
        unitText: String? = unit?.name,
        source: DailyTotalSource = DailyTotalSource.LG_FINALIZED,
        finalized: Boolean = true,
    ): DailyTotal = DailyTotal(
        totalId = id,
        dayStartEpochMillis = dayStartIso(date),
        energyValue = value,
        recordedUnit = unit,
        source = source,
        finalized = finalized,
        localDate = date,
        totalText = value.toString(),
        totalNumber = value,
        unitText = unitText,
    )

    // ------------------------------------------- Manila day boundary vs UTC

    @Test
    fun groupsByConfiguredZoneCalendarDayNotUtcDate() {
        // 2026-01-14T16:05:00Z is 2026-01-15T00:05:00+08:00 in Manila.
        val start = Instant.parse("2026-01-14T16:05:00Z").toEpochMilliseconds()
        val now = start + FALLBACK_INTERVAL_MILLIS

        val presentation = presentEnergyHistory(
            observations = listOf(sample("boundary", start = start, value = 60.0, unit = EnergyUnit.WH, unitText = "Wh")),
            dailyTotals = emptyList(),
            coveredRanges = emptyList(),
            periodType = EnergyPeriodType.DAY,
            selectedDate = LocalDate.parse("2026-01-15"),
            nowEpochMillis = now,
        )

        assertEquals(1, presentation.days.size)
        val day = presentation.days.single()
        assertEquals(LocalDate.parse("2026-01-15"), day.date)
        // The same instant is a *different* UTC calendar day, so grouping must come
        // from the configured zone (intervalStart -> Manila), never UTC.
        assertEquals(
            LocalDate.parse("2026-01-14"),
            Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.UTC).date,
        )
        assertEquals(1, day.points.size)
        assertEquals(LocalDate.parse("2026-01-15"), day.points.single().localDay)
        assertEquals(60.0, day.usableEnergyValueWh!!, 1e-9)
    }

    // ------------------------------------------- ISO week and month date ranges

    @Test
    fun weekPeriodIsIsoMondayToSunday() {
        // 2026-01-15 is a Thursday; its ISO week is Mon 01-12 .. Sun 01-18.
        val week = energyPeriodRange(EnergyPeriodType.WEEK, LocalDate.parse("2026-01-15"))
        assertEquals(LocalDate.parse("2026-01-12"), week.inclusiveStartDate)
        assertEquals(LocalDate.parse("2026-01-18"), week.inclusiveEndDate)
        assertEquals(7, week.dayCount)
        assertEquals(dayStartIso("2026-01-12"), week.startEpochMillis)
        assertEquals(dayStartIso("2026-01-19"), week.endExclusiveEpochMillis)

        // Sunday still belongs to the Monday-aligned ISO week.
        val sundayWeek = energyPeriodRange(EnergyPeriodType.WEEK, LocalDate.parse("2026-01-18"))
        assertEquals(LocalDate.parse("2026-01-12"), sundayWeek.inclusiveStartDate)
        assertEquals(LocalDate.parse("2026-01-18"), sundayWeek.inclusiveEndDate)
    }

    @Test
    fun monthPeriodCoversWholeCalendarMonth() {
        val january = energyPeriodRange(EnergyPeriodType.MONTH, LocalDate.parse("2026-01-15"))
        assertEquals(LocalDate.parse("2026-01-01"), january.inclusiveStartDate)
        assertEquals(LocalDate.parse("2026-01-31"), january.inclusiveEndDate)
        assertEquals(31, january.dayCount)
        assertEquals(dayStartIso("2026-02-01"), january.endExclusiveEpochMillis)

        val february = energyPeriodRange(EnergyPeriodType.MONTH, LocalDate.parse("2026-02-10"))
        assertEquals(LocalDate.parse("2026-02-01"), february.inclusiveStartDate)
        assertEquals(LocalDate.parse("2026-02-28"), february.inclusiveEndDate)
        assertEquals(28, february.dayCount)
    }

    @Test
    fun dayRangeIsTheSingleSelectedDay() {
        val day = energyPeriodRange(EnergyPeriodType.DAY, LocalDate.parse("2026-01-15"))
        assertEquals(LocalDate.parse("2026-01-15"), day.inclusiveStartDate)
        assertEquals(LocalDate.parse("2026-01-15"), day.inclusiveEndDate)
        assertEquals(1, day.dayCount)
        assertEquals(dayStartIso("2026-01-16"), day.endExclusiveEpochMillis)
    }

    @Test
    fun monthNavigationPreservesDayAndClampsOnlyWhenTheTargetMonthIsShorter() {
        assertEquals(
            LocalDate.parse("2026-02-15"),
            navigateEnergyAnchor(EnergyPeriodType.MONTH, LocalDate.parse("2026-03-15"), -1),
        )
        assertEquals(
            LocalDate.parse("2026-02-28"),
            navigateEnergyAnchor(EnergyPeriodType.MONTH, LocalDate.parse("2026-01-31"), 1),
        )
        assertEquals(
            LocalDate.parse("2024-02-29"),
            navigateEnergyAnchor(EnergyPeriodType.MONTH, LocalDate.parse("2024-01-31"), 1),
        )
    }

    // ------------------------------------------- current-day partial slots

    @Test
    fun currentDayCountsOnlyCompletedIntervals() {
        val today = "2026-01-15"
        // 10:30 local = 126 completed five-minute slots (slots 0..125).
        val now = dayStartIso(today) + 126 * FALLBACK_INTERVAL_MILLIS
        val samples = (0 until 126).map { i ->
            sample(
                id = "s-$i",
                start = dayStartIso(today) + i * FALLBACK_INTERVAL_MILLIS,
                value = 10.0,
                unit = EnergyUnit.WH,
                unitText = "Wh",
            )
        } + listOf(
            // A future slot (10:30-10:35, interval end after now) must not count.
            sample("future", start = dayStartIso(today) + 126 * FALLBACK_INTERVAL_MILLIS, value = 999.0,
                unit = EnergyUnit.WH, unitText = "Wh"),
        )

        val presentation = presentEnergyHistory(
            samples, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(today), now,
        )

        val day = presentation.days.single()
        assertEquals(126, day.expectedSlotCount)
        assertEquals(126, day.usableSlotCount)
        assertEquals(0, day.missingSlotCount)
        assertEquals(1.0, day.coverageFraction!!, 1e-9)
        assertEquals(1260.0, day.usableEnergyValueWh!!, 1e-9)
        assertEquals(126, day.points.size)
        assertTrue(day.points.none { it.sampleId == "future" })
        assertTrue(day.isCurrentDay)
    }

    @Test
    fun currentDayPartialCoverageIsTruthful() {
        val today = "2026-01-15"
        val now = dayStartIso(today) + 126 * FALLBACK_INTERVAL_MILLIS
        val samples = (0 until 100).map { i ->
            sample("p-$i", start = dayStartIso(today) + i * FALLBACK_INTERVAL_MILLIS,
                value = 10.0, unit = EnergyUnit.WH, unitText = "Wh")
        }

        val presentation = presentEnergyHistory(
            samples,
            emptyList(),
            listOf(CoveredRange(dayStartIso(today), now)),
            EnergyPeriodType.DAY,
            LocalDate.parse(today),
            now,
        )
        val day = presentation.days.single()

        assertEquals(100, day.usableSlotCount)
        assertEquals(26, day.missingSlotCount)
        assertEquals(100.0 / 126.0, day.coverageFraction!!, 1e-9)
        assertTrue(
            presentation.queryCoverage.fullyQueried,
            "future slots later today are outside the currently elapsed range that the explicit loader can fetch",
        )
    }

    // ------------------------------------------- missing/null are gaps, never zeros

    @Test
    fun missingAndNullIntervalsAreGapsNotZeros() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val observations = listOf(
            sample("a", start = base, value = 100.0, unit = EnergyUnit.WH, unitText = "Wh"),
            sample("b", start = base + FALLBACK_INTERVAL_MILLIS, value = null), // null interval
            // slot index 2 has no observation at all
            sample("d", start = base + 3 * FALLBACK_INTERVAL_MILLIS, value = 50.0, unit = EnergyUnit.WH, unitText = "Wh"),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS // complete past day

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        assertEquals(288, summary.expectedSlotCount)
        assertEquals(2, summary.usableSlotCount)
        assertEquals(286, summary.missingSlotCount)
        // The null slot never enters the sum (and is not a measured zero).
        assertEquals(150.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals(2.0 / 288.0, summary.coverageFraction!!, 1e-9)

        val nullPoint = summary.points.single { it.sampleId == "b" }
        assertNull(nullPoint.energyValue)
        assertNull(nullPoint.energyValueWh)
        assertFalse(nullPoint.usable)
    }

    @Test
    fun allNullBucketHasNullSumAndNonFullCoverage() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val observations = listOf(
            sample("n1", start = base, value = null),
            sample("n2", start = base + FALLBACK_INTERVAL_MILLIS, value = null),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        assertNull(summary.usableEnergyValueWh)
        assertEquals(0, summary.usableSlotCount)
        assertEquals(288, summary.missingSlotCount)
        assertEquals(0.0, summary.coverageFraction!!, 1e-9)
    }

    @Test
    fun measuredZeroRemainsUsableAndNonFiniteValuesRemainGaps() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS
        val observations = listOf(
            sample("zero", start = base, value = 0.0, unit = EnergyUnit.WH, unitText = "Wh"),
            sample("nan", start = base + FALLBACK_INTERVAL_MILLIS, value = Double.NaN,
                unit = EnergyUnit.WH, unitText = "Wh"),
            sample("positive-infinity", start = base + 2 * FALLBACK_INTERVAL_MILLIS, value = Double.POSITIVE_INFINITY,
                unit = EnergyUnit.WH, unitText = "Wh"),
            sample("negative-infinity", start = base + 3 * FALLBACK_INTERVAL_MILLIS, value = Double.NEGATIVE_INFINITY,
                unit = EnergyUnit.WH, unitText = "Wh"),
            sample("unavailable-value", start = base + 4 * FALLBACK_INTERVAL_MILLIS, value = 99.0,
                unit = EnergyUnit.WH, unitText = "Wh", quality = ObservationQuality.UNAVAILABLE),
        )

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        assertEquals(1, summary.usableSlotCount)
        assertEquals(0.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals(1.0 / 288.0, summary.coverageFraction!!, 1e-9)
        assertEquals(287, summary.missingSlotCount)
        assertTrue(summary.points.single { it.sampleId == "zero" }.usable)
        listOf("nan", "positive-infinity", "negative-infinity", "unavailable-value").forEach { id ->
            val point = summary.points.single { it.sampleId == id }
            assertFalse(point.usable, id)
            assertNull(point.energyValueWh, id)
        }
    }

    // ------------------------------------------- out-of-order / duplicates

    @Test
    fun outOfOrderAndDuplicateSamplesResolveDeterministicallyPerSlot() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val secondAtTwo = sample(
            "dup-2-late", start = base + 2 * FALLBACK_INTERVAL_MILLIS, value = 2.0,
            unit = EnergyUnit.WH, unitText = "Wh", persistedAt = 1_000_000_000L,
        )
        val firstAtTwo = sample(
            "dup-2-early", start = base + 2 * FALLBACK_INTERVAL_MILLIS, value = 1.0,
            unit = EnergyUnit.WH, unitText = "Wh",
        )
        // Deliberately shuffled input order, including the duplicate pair.
        val observations = listOf(
            sample("slot3", start = base + 3 * FALLBACK_INTERVAL_MILLIS, value = 3.0,
                unit = EnergyUnit.WH, unitText = "Wh"),
            firstAtTwo,
            sample("slot0", start = base, value = 0.5, unit = EnergyUnit.WH, unitText = "Wh"),
            secondAtTwo,
            sample("slot1", start = base + FALLBACK_INTERVAL_MILLIS, value = 1.5,
                unit = EnergyUnit.WH, unitText = "Wh"),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        assertEquals(4, summary.usableSlotCount)
        assertEquals(4, summary.points.size)
        assertEquals(listOf(0, 1, 2, 3), summary.points.map { it.slotIndex })
        // The later-persisted duplicate won the contested slot exactly once.
        assertEquals("dup-2-late", summary.points[2].sampleId)
        assertEquals(2.0, summary.points[2].energyValue!!, 1e-9)
        assertEquals(0.5 + 1.5 + 2.0 + 3.0, summary.usableEnergyValueWh!!, 1e-9)
    }

    // ----------------------------------- anomalous / source-failed / unknown unit

    @Test
    fun anomalousSourceFailedAndUnknownUnitSamplesExcludedButReported() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val observations = listOf(
            sample("usable", start = base, value = 100.0, unit = EnergyUnit.WH, unitText = "Wh"),
            sample("anomalous", start = base + FALLBACK_INTERVAL_MILLIS, value = 9.9,
                unit = EnergyUnit.WH, unitText = "Wh",
                quality = ObservationQuality.ANOMALOUS, intervalStatus = "ANOMALOUS_DECREASE"),
            sample("source-failed", start = base + 2 * FALLBACK_INTERVAL_MILLIS, value = 1.0,
                unit = EnergyUnit.WH, unitText = "Wh", energySourceFailed = true),
            sample("unknown-unit", start = base + 3 * FALLBACK_INTERVAL_MILLIS, value = 5.5,
                unit = null, unitText = "unknown-unit"),
            sample("kwh", start = base + 4 * FALLBACK_INTERVAL_MILLIS, value = 2.0,
                unit = EnergyUnit.KWH, unitText = "kWh"),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        // Only the two normal known-unit samples contribute.
        assertEquals(2, summary.usableSlotCount)
        assertEquals(100.0 + 2000.0, summary.usableEnergyValueWh!!, 1e-9)
        // Everything else is counted and reported under its own heading.
        assertEquals(1, summary.anomalousSlotCount)
        assertEquals(1, summary.unusableUnitSlotCount)
        assertEquals(284, summary.missingSlotCount) // source-failed = gap + absent slots

        assertTrue("Wh" in summary.unitTexts)
        assertTrue("kWh" in summary.unitTexts)
        assertTrue("unknown-unit" in summary.unitTexts)

        val anomalousPoint = summary.points.single { it.sampleId == "anomalous" }
        assertFalse(anomalousPoint.usable)
        assertNull(anomalousPoint.energyValueWh)
        assertEquals("ANOMALOUS_DECREASE", anomalousPoint.intervalStatus)

        val unknownPoint = summary.points.single { it.sampleId == "unknown-unit" }
        assertEquals(5.5, unknownPoint.energyValue!!, 1e-9)
        assertNull(unknownPoint.energyValueWh)
        assertFalse(unknownPoint.usable)

        val failedPoint = summary.points.single { it.sampleId == "source-failed" }
        assertFalse(failedPoint.usable)
        assertNull(failedPoint.energyValueWh)
        assertNull(failedPoint.sourceFailure) // typed energy-read failure, not the legacy one-line summary
    }

    @Test
    fun stateOnlyFailureDoesNotEraseAUsableEnergyMeasurement() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val measured = sample(
            id = "energy-ok-state-failed",
            start = base,
            value = 125.0,
            unit = EnergyUnit.WH,
        ).copy(
            sourceFailure = "state:timeout",
            stateSourceFailure = SourceReadFailure(ok = false, failureClass = "TRANSIENT"),
        )

        val summary = presentEnergyHistory(
            listOf(measured),
            emptyList(),
            emptyList(),
            EnergyPeriodType.DAY,
            LocalDate.parse(day),
            base + 288 * FALLBACK_INTERVAL_MILLIS,
        ).days.single()

        assertEquals(1, summary.usableSlotCount)
        assertEquals(287, summary.missingSlotCount, "the other elapsed five-minute slots were not observed")
        assertEquals(125.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals("state:timeout", summary.points.single().sourceFailure)
    }

    @Test
    fun typedLiveStateFailureAloneDoesNotInvalidateSuccessfulEnergyRead() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val measured = sample(
            id = "energy-ok-live-state-failed",
            start = base,
            value = 125.0,
            unit = EnergyUnit.WH,
        ).copy(
            stateSourceFailure = SourceReadFailure(ok = false, failureClass = "TRANSIENT"),
        )

        val summary = presentEnergyHistory(
            listOf(measured),
            emptyList(),
            emptyList(),
            EnergyPeriodType.DAY,
            LocalDate.parse(day),
            base + 288 * FALLBACK_INTERVAL_MILLIS,
        ).days.single()

        assertEquals(1, summary.usableSlotCount)
        assertEquals(125.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals(287, summary.missingSlotCount)
        assertEquals(false, measured.stateSourceFailure?.ok)
        assertNull(summary.points.single().sourceFailure)
    }

    @Test
    fun legacyEnergyFailureMarksTheIntervalAsGapWithoutCoercingItsValueToZero() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val legacyFailure = sample(
            id = "legacy-energy-failed",
            start = base,
            value = 125.0,
            unit = EnergyUnit.WH,
        ).copy(sourceFailure = "energy:transient")

        val summary = presentEnergyHistory(
            listOf(legacyFailure),
            emptyList(),
            emptyList(),
            EnergyPeriodType.DAY,
            LocalDate.parse(day),
            base + 288 * FALLBACK_INTERVAL_MILLIS,
        ).days.single()

        assertEquals(0, summary.usableSlotCount)
        assertEquals(288, summary.missingSlotCount)
        assertNull(summary.usableEnergyValueWh)
        assertNull(summary.points.single().energyValueWh)
        assertEquals(125.0, summary.points.single().energyValue!!, 1e-9, "the source value is retained, not presented as zero")
    }

    // ------------------------------------------- mixed Wh/kWh normalization

    @Test
    fun mixedWhAndKwhNormalizeToWhAndRetainLabels() {
        val day = "2026-01-10"
        val base = dayStartIso(day)
        val observations = listOf(
            sample("a", start = base, value = 1.5, unit = EnergyUnit.KWH, unitText = "kWh", unitSourceText = "device"),
            sample("b", start = base + FALLBACK_INTERVAL_MILLIS, value = 200.0,
                unit = EnergyUnit.WH, unitText = "Wh", unitSourceText = "configured"),
            sample("c", start = base + 2 * FALLBACK_INTERVAL_MILLIS, value = 300.0,
                unit = EnergyUnit.WH, unitText = "Wh", unitSourceText = "device"),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS

        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single()

        assertEquals(2000.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals(setOf(EnergyUnit.KWH, EnergyUnit.WH), summary.recordedUnits)
        assertEquals(setOf("kWh", "Wh"), summary.unitTexts)
        assertEquals(setOf("device", "configured"), summary.unitSourceTexts)
        assertEquals(listOf(1500.0, 200.0, 300.0), summary.points.map { it.energyValueWh })

        // Day-level interval-average power: 2000 Wh over a full 24h local day.
        assertEquals(2000.0 / 24.0, summary.averagePowerWatts!!, 1e-9)

        // Per-interval helper: 1.5 kWh over five minutes = 18 000 W average.
        assertEquals(18000.0, intervalAveragePowerWatts(1500.0, FALLBACK_INTERVAL_MILLIS)!!, 1e-9)
        assertNull(intervalAveragePowerWatts(null, FALLBACK_INTERVAL_MILLIS))
        assertNull(intervalAveragePowerWatts(10.0, 0L))

        // The Wh helper never guesses an unknown/missing unit.
        assertNull(normalizeToWh(5.0, null))
        assertEquals(5.0, normalizeToWh(5.0, EnergyUnit.WH)!!, 1e-9)
        assertEquals(5000.0, normalizeToWh(5.0, EnergyUnit.KWH)!!, 1e-9)
    }

    // --------------------------------------- finalized totals vs interval sums

    @Test
    fun finalizedDailyTotalsRemainDistinctFromIntervalSums() {
        val day = "2026-01-15"
        val base = dayStartIso(day)
        // Twenty-one usable 100 Wh intervals sum to exactly 2100 Wh.
        val observations = (0 until 21).map { i ->
            sample("iv-$i", start = base + i * FALLBACK_INTERVAL_MILLIS,
                value = 100.0, unit = EnergyUnit.WH, unitText = "Wh")
        }
        val totals = listOf(
            total("lg:2026-01-15", "2026-01-15", value = 2500.0, unit = EnergyUnit.KWH, unitText = "kWh",
                source = DailyTotalSource.LG_FINALIZED, finalized = true),
            total("sum:2026-01-15", "2026-01-15", value = 2100.0, unit = EnergyUnit.KWH, unitText = "kWh",
                source = DailyTotalSource.LOCAL_INTERVAL_SUM, finalized = false),
        )
        val now = base + 21 * FALLBACK_INTERVAL_MILLIS

        val presentation = presentEnergyHistory(
            observations, totals, emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        )
        val summary = presentation.days.single()

        // Both measures for the same local day are preserved separately.
        assertEquals(2, summary.measures.size)
        val lg = summary.measures.single { it.original.source == DailyTotalSource.LG_FINALIZED }
        val local = summary.measures.single { it.original.source == DailyTotalSource.LOCAL_INTERVAL_SUM }
        assertEquals(2500.0, lg.original.energyValue, 1e-9)
        assertEquals(2100.0, local.original.energyValue, 1e-9)
        assertTrue(lg.original.finalized)
        assertFalse(local.original.finalized)
        assertEquals("kWh", lg.original.unitText)
        assertEquals(LocalDate.parse(day), lg.localDay)

        // The interval sum itself stays separate from the finalized LG total.
        assertEquals(2100.0, summary.usableEnergyValueWh!!, 1e-9)
        assertTrue(summary.usableEnergyValueWh != lg.original.energyValue)
        assertEquals(2, presentation.dailyMeasures.size)
        assertTrue(presentation.dailyMeasures.map { it.original.source }.toSet() ==
            setOf(DailyTotalSource.LG_FINALIZED, DailyTotalSource.LOCAL_INTERVAL_SUM))
    }

    @Test
    fun finalizedLocalAndSampledTotalsCanAllDifferWithoutMerging() {
        val day = "2026-01-15"
        val base = dayStartIso(day)
        val observations = (0 until 3).map { i ->
            sample("three-way-$i", start = base + i * FALLBACK_INTERVAL_MILLIS,
                value = 600.0, unit = EnergyUnit.WH, unitText = "Wh")
        }
        val totals = listOf(
            total("lg:$day", day, value = 2.5, unit = EnergyUnit.KWH,
                source = DailyTotalSource.LG_FINALIZED, finalized = true),
            total("local:$day", day, value = 2.1, unit = EnergyUnit.KWH,
                source = DailyTotalSource.LOCAL_INTERVAL_SUM, finalized = false),
        )

        val summary = presentEnergyHistory(
            observations,
            totals,
            emptyList(),
            EnergyPeriodType.DAY,
            LocalDate.parse(day),
            base + 3 * FALLBACK_INTERVAL_MILLIS,
        ).days.single()

        assertEquals(1800.0, summary.usableEnergyValueWh!!, 1e-9)
        assertEquals(2, summary.measures.size)
        assertEquals(2500.0, summary.measures.single {
            it.original.source == DailyTotalSource.LG_FINALIZED
        }.energyValueWh!!, 1e-9)
        assertEquals(2100.0, summary.measures.single {
            it.original.source == DailyTotalSource.LOCAL_INTERVAL_SUM
        }.energyValueWh!!, 1e-9)
        assertEquals(3, summary.usableSlotCount)
    }

    // ---------------------------------- query-cache coverage vs measured coverage

    @Test
    fun queryCacheCoverageIsSeparateFromMeasuredCoverage() {
        val period = energyPeriodRange(EnergyPeriodType.WEEK, LocalDate.parse("2026-01-15"))
        // Day 1 of the week fully usable; the rest fetched-but-empty.
        val base = dayStartIso("2026-01-12")
        val observations = (0 until 288).map { i ->
            sample("d1-$i", start = base + i * FALLBACK_INTERVAL_MILLIS,
                value = 1.0, unit = EnergyUnit.WH, unitText = "Wh")
        }
        val fullyCovered = listOf(CoveredRange(period.startEpochMillis, period.endExclusiveEpochMillis))
        val now = dayStartIso("2026-01-19")

        val presentation = presentEnergyHistory(
            observations, emptyList(), fullyCovered, EnergyPeriodType.WEEK, LocalDate.parse("2026-01-15"), now,
        )

        assertEquals(7, presentation.days.size)
        // The epoch window was fully queried...
        assertTrue(presentation.queryCoverage.fullyQueried)
        assertEquals(1.0, presentation.queryCoverage.queriedFraction, 1e-9)
        // ...yet measured coverage tells a different story.
        assertEquals(1.0, presentation.days[0].coverageFraction!!, 1e-9)
        assertEquals(0.0, presentation.days[1].coverageFraction!!, 1e-9)
        assertNull(presentation.days[1].usableEnergyValueWh)
        assertEquals(288, presentation.days[1].missingSlotCount)
        // An empty-but-fetched day is never shown as 100% usable.
        assertNull(presentation.days[1].usableEnergyValueWh)

        // Partial query cache: only the first 24h were fetched.
        val partial = presentEnergyHistory(
            observations, emptyList(),
            listOf(CoveredRange(period.startEpochMillis, period.startEpochMillis + 24L * 3_600_000L)),
            EnergyPeriodType.WEEK, LocalDate.parse("2026-01-15"), now,
        )
        assertFalse(partial.queryCoverage.fullyQueried)
        assertEquals(1.0 / 7.0, partial.queryCoverage.queriedFraction, 1e-9)
    }

    @Test
    fun overlappingCoveredRangesAreUnionedOnceAndManilaExplicitMatchesDefault() {
        val day = "2026-01-15"
        val period = energyPeriodRange(EnergyPeriodType.DAY, LocalDate.parse(day))
        val observations = listOf(
            sample("manila", start = slotStartIso(day, 0), value = 0.0, unit = EnergyUnit.WH, unitText = "Wh"),
        )
        val ranges = listOf(
            CoveredRange(period.startEpochMillis, period.startEpochMillis + 2 * FALLBACK_INTERVAL_MILLIS),
            CoveredRange(period.startEpochMillis + FALLBACK_INTERVAL_MILLIS, period.startEpochMillis + 4 * FALLBACK_INTERVAL_MILLIS),
            CoveredRange(period.startEpochMillis + 4 * FALLBACK_INTERVAL_MILLIS, period.endExclusiveEpochMillis),
        )
        val now = period.endExclusiveEpochMillis

        val defaultZone = presentEnergyHistory(
            observations, emptyList(), ranges, EnergyPeriodType.DAY, LocalDate.parse(day), now,
        )
        val explicitManila = presentEnergyHistory(
            observations, emptyList(), ranges, EnergyPeriodType.DAY, LocalDate.parse(day), now,
            timeZoneId = "Asia/Manila",
        )

        assertTrue(defaultZone.queryCoverage.fullyQueried)
        assertEquals(1.0, defaultZone.queryCoverage.queriedFraction, 1e-9)
        assertEquals(defaultZone.period, explicitManila.period)
        assertEquals(defaultZone.days, explicitManila.days)
        assertEquals(defaultZone.queryCoverage, explicitManila.queryCoverage)
        assertEquals(0.0, defaultZone.days.single().usableEnergyValueWh!!, 1e-9)
    }

    // ------------------------------------------- unknown daily-total units

    @Test
    fun unknownDailyTotalUnitsAreNeverGuessed() {
        val day = "2026-01-15"
        val base = dayStartIso(day)
        val totals = listOf(
            DailyTotal(
                totalId = "lg:legacy",
                dayStartEpochMillis = base,
                energyValue = 10.0,
                recordedUnit = null,
                source = DailyTotalSource.LG_FINALIZED,
                finalized = true,
                localDate = day,
                totalText = "10.0",
                totalNumber = 10.0,
                unitText = "??source??",
            ),
        )
        val now = base + 288 * FALLBACK_INTERVAL_MILLIS

        val measure = presentEnergyHistory(
            emptyList(), totals, emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        ).days.single().measures.single()

        assertNull(measure.energyValueWh)
        assertNull(measure.original.recordedUnit)
        assertEquals("??source??", measure.original.unitText)
        assertEquals(10.0, measure.original.energyValue, 1e-9)
    }

    // ------------------------------------------------ DST / short local days

    @Test
    fun dstShortDaysUseActualLocalDayLengthWithoutErrors() {
        val zone = TimeZone.of("America/New_York")
        val day = LocalDate.parse("2026-03-08") // spring forward: 23-hour local day
        val dayStart = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        assertEquals(23L * 3_600_000L, dayEnd - dayStart)
        val expectedSlots = ((dayEnd - dayStart) / FALLBACK_INTERVAL_MILLIS).toInt()
        assertEquals(276, expectedSlots)

        val observations = (0 until expectedSlots).map { i ->
            sample("dst-$i", start = dayStart + i * FALLBACK_INTERVAL_MILLIS,
                value = 1.0, unit = EnergyUnit.WH, unitText = "Wh")
        }
        val summary = presentEnergyHistory(
            observations, emptyList(), emptyList(), EnergyPeriodType.DAY, day,
            nowEpochMillis = dayEnd, timeZoneId = "America/New_York",
        ).days.single()

        // The 23-hour day has 276 completed slots, each usable.
        assertEquals(276, summary.expectedSlotCount)
        assertEquals(276, summary.usableSlotCount)
        assertEquals(276, summary.points.size)
        assertEquals(1.0, summary.coverageFraction!!, 1e-9)
        assertEquals(276.0, summary.usableEnergyValueWh!!, 1e-9)

        // The same calendar date in Manila stays an exact 24-hour, 288-slot day.
        val manilaDay = energyPeriodRange(EnergyPeriodType.DAY, day)
        assertEquals(288L * FALLBACK_INTERVAL_MILLIS, manilaDay.endExclusiveEpochMillis - manilaDay.startEpochMillis)
    }

    // ------------------------------------------- snapshot convenience overload

    @Test
    fun snapshotOverloadRendersTheSameResult() {
        val day = "2026-01-15"
        val base = dayStartIso(day)
        val observations = listOf(
            sample("a", start = base, value = 40.0, unit = EnergyUnit.WH, unitText = "Wh"),
            sample("b", start = base + FALLBACK_INTERVAL_MILLIS, value = 60.0,
                unit = EnergyUnit.WH, unitText = "Wh"),
        )
        val totals = listOf(total("lg:$day", day, value = 88.0, unit = EnergyUnit.WH, unitText = "Wh"))
        val now = base + 2 * FALLBACK_INTERVAL_MILLIS
        val snapshot = HistorySnapshot(
            partition = HistoryPartition("demo-project", "owner-1", "device-1"),
            observations = observations,
            dailyTotals = totals,
            coveredRanges = listOf(CoveredRange(base, now)),
        )

        val fromLists = presentEnergyHistory(
            observations, totals, listOf(CoveredRange(base, now)),
            EnergyPeriodType.DAY, LocalDate.parse(day), now,
        )
        val fromSnapshot = presentEnergyHistory(
            snapshot, EnergyPeriodType.DAY, LocalDate.parse(day), now,
        )

        assertEquals(fromLists.days, fromSnapshot.days)
        assertEquals(fromLists.queryCoverage, fromSnapshot.queryCoverage)
        assertEquals(fromLists.period, fromSnapshot.period)
        assertEquals(fromSnapshot.days.single().usableEnergyValueWh!!, 100.0, 1e-9)
        assertEquals(1, fromSnapshot.days.single().measures.size)
    }

    // ----------------------------------------------- empty history

    @Test
    fun emptyHistoryPresentsEmptyUsableDayWithUnqueriedWindow() {
        val day = "2026-01-10"
        val now = dayStartIso(day) + 288 * FALLBACK_INTERVAL_MILLIS

        val presentation = presentEnergyHistory(
            emptyList(), emptyList(), emptyList(), EnergyPeriodType.DAY, LocalDate.parse(day), now,
        )

        val summary = presentation.days.single()
        assertEquals(288, summary.expectedSlotCount)
        assertEquals(288, summary.missingSlotCount)
        assertEquals(0, summary.usableSlotCount)
        assertNull(summary.usableEnergyValueWh)
        assertEquals(0.0, summary.coverageFraction!!, 1e-9)
        assertTrue(summary.points.isEmpty())
        assertFalse(presentation.queryCoverage.fullyQueried)
        assertEquals(0.0, presentation.queryCoverage.queriedFraction, 1e-9)
        assertNull(presentation.usableEnergyValueWh)
    }
}
