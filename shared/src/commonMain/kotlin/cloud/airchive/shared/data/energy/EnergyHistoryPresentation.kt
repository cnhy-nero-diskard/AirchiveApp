package cloud.airchive.shared.data.energy

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import cloud.airchive.shared.data.telemetry.FALLBACK_INTERVAL_MILLIS
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Pure, deterministic day/week/month energy aggregation for the Energy route.
 *
 * This layer is deliberately free of charts, Compose, network, and Room: it
 * turns the normalized local projection (observations, daily totals, covered
 * ranges) plus a single `nowEpochMillis` into an immutable
 * [EnergyHistoryPresentation] the UI can render directly. It is written against
 * the *interface* types ([Observation]/[DailyTotal]/[CoveredRange]) so the
 * calling integration can supply either a [HistorySnapshot] or plain lists.
 *
 * ## Measurement meaning is preserved
 *
 *  - Local calendar days use the configured time zone (default
 *    [DEFAULT_DAY_TIME_ZONE], `Asia/Manila`) and each observation is grouped by
 *    the day that contains `intervalStartEpochMillis` in that zone — never by
 *    the unverified document `localDate`/path and never by a UTC date.
 *  - A week is ISO ([LocalDate.dayOfWeek].[isoDayNumber] with Monday = 1); a
 *    month is the covered calendar month.
 *  - A null interval, a `MISSING_INTERVAL`/`UNAVAILABLE` sample, or an energy
 *    source read failure is a **gap**, never a measured zero. A state-only
 *    source failure does not erase a successfully measured energy interval.
 *  - [ObservationQuality.ANOMALOUS] samples are counted and flagged and are
 *    excluded from every usable Wh sum.
 *  - Only finite [ObservationQuality.NORMAL] values with a recognized
 *    [EnergyUnit] enter the normalized Wh interval sum. Finitely-valued but
 *    unknown/legacy-null-unit samples are counted (`unusableUnitSlotCount`)
 *    but never guessed, and their raw `unitText` is preserved.
 *  - Finalized LG totals ([DailyTotalSource.LG_FINALIZED]) are kept as separate
 *    [EnergyDailyMeasure]s and are never merged with the summed five-minute
 *    intervals (`LOCAL_INTERVAL_SUM`), even when they share a local day.
 *
 * ## Coverage is truthful
 *
 * Measured slot coverage ([EnergyDaySummary.coverageFraction]) compares usable
 * slots against the elapsed five-minute slot count only — capped at 100%, `null`
 * for a day with no elapsed slots. Query-cache coverage ([QueryCoverage]) is
 * tracked separately from `HistorySnapshot.coveredRanges`, so an epoch window
 * that was fetched but contains all-null samples is not shown as fully usable.
 */
enum class EnergyPeriodType {
    /** The single selected calendar day. */
    DAY,

    /** The ISO week (Monday–Sunday) containing the selected day. */
    WEEK,

    /** The calendar month containing the selected day. */
    MONTH,
}

/**
 * The exact calendar and epoch bounds of a selected energy period.
 *
 * Dates are **inclusive** on both ends; the epoch window is the half-open
 * interval `[startEpochMillis, endExclusiveEpochMillis)` where the lower bound
 * is the selected zone's start of [inclusiveStartDate] and the upper bound is
 * the exclusive start of the day after [inclusiveEndDate].
 */
data class EnergyPeriodRange(
    val periodType: EnergyPeriodType,
    val selectedDate: LocalDate,
    val inclusiveStartDate: LocalDate,
    val inclusiveEndDate: LocalDate,
    val startEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val dayCount: Int,
) {
    /** Whether [date] is one of the period's calendar days (inclusive). */
    fun contains(date: LocalDate): Boolean =
        date >= inclusiveStartDate && date <= inclusiveEndDate

    /** Whether [epochMillis] falls inside the half-open epoch window. */
    fun containsEpochMillis(epochMillis: Long): Boolean =
        epochMillis >= startEpochMillis && epochMillis < endExclusiveEpochMillis
}

/**
 * Whether the full requested epoch interval was actually queried and cached.
 *
 * This is the documented `HistorySnapshot.coveredRanges` query-cache coverage,
 * kept strictly separate from measured slot coverage and clipped to the elapsed
 * portion of a current/future period: slots that have not occurred yet do not
 * need to be fetched. A range can be fully queried yet measured 0% usable (every
 * fetched sample was null/gap), and a range can be only partially queried yet
 * the queried part fully usable.
 */
data class QueryCoverage(
    /** True when [start, end) of the period is fully inside cached ranges. */
    val fullyQueried: Boolean,

    /** Covered fraction of the epoch window in `0.0..1.0`. */
    val queriedFraction: Double,
)

/**
 * One five-minute interval point in a local day, ready for charting.
 *
 * [energyValueWh] is the normalized-to-Wh interval value **only** when the
 * sample is usable (finite [ObservationQuality.NORMAL] value with a recognized
 * unit); it is `null` otherwise so the chart can never imply a measured value
 * for a gap, an anomalous sample, or an unknown-unit sample. The original
 * [energyValue], [recordedUnit], [unitText], [unitSourceText], and
 * [intervalStatus] strings are preserved verbatim.
 */
data class EnergyIntervalPoint(
    val sampleId: String,
    val intervalStartEpochMillis: Long,
    val intervalEndEpochMillis: Long,

    /** The local calendar day this point was grouped into (from `intervalStart`). */
    val localDay: LocalDate,

    /** Grid position (× [FALLBACK_INTERVAL_MILLIS]) within [localDay], 0-based. */
    val slotIndex: Int,

    /** Original interval value as recorded (`null` = missing). */
    val energyValue: Double?,

    /** Normalized Wh value when usable; `null` otherwise (never guessed). */
    val energyValueWh: Double?,

    /** Whether this point contributed to the usable Wh sums. */
    val usable: Boolean,

    val quality: ObservationQuality,
    val recordedUnit: EnergyUnit?,
    val unitText: String?,
    val unitSourceText: String?,
    val intervalStatus: String?,
    val sourceFailure: String?,
) {
    /**
     * Interval-average power for this interval, in watts, computed as
     * `energyValueWh` divided by the interval duration in hours. This is
     * **average power**, never instantaneous wattage; `null` when the interval
     * is not usable or has no span.
     */
    val averagePowerWatts: Double?
        get() = intervalAveragePowerWatts(
            energyValueWh,
            intervalEndEpochMillis - intervalStartEpochMillis,
        )
}

/**
 * A preserved daily measure (a stored [DailyTotal]), never conflated with the
 * interval-derived sum. [original] retains the untouched value, [recordedUnit],
 * `unitText`, [DailyTotal.source], [DailyTotal.finalized], and
 * [DailyTotal.localDate]; multiple measures sharing one local day are each kept
 * as their own entry.
 */
data class EnergyDailyMeasure(
    /** The untouched original daily total. */
    val original: DailyTotal,

    /** Parsed local day for chart/date alignment; the bucket day when unparsable. */
    val localDay: LocalDate,

    /**
     * Normalized-to-Wh convenience value only for recognized units;
     * `null` for unknown/legacy null units (never guessed).
     */
    val energyValueWh: Double?,
)

/**
 * One local calendar day's energy summary within a period.
 *
 * Slot accounting uses the fixed five-minute cadence
 * ([FALLBACK_INTERVAL_MILLIS]): [expectedSlotCount] is the number of **elapsed,
 * completed** slots only (a full local day when the day is complete, else the
 * slots elapsed up to `now`), so future slots on the current day never count.
 * Every expected slot is accounted exactly once across [usableSlotCount],
 * [missingSlotCount] (no sample / null / unavailable / source-failed),
 * [anomalousSlotCount], and [unusableUnitSlotCount].
 */
data class EnergyDaySummary(
    val date: LocalDate,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
    val isCurrentDay: Boolean,

    /** Completed five-minute slots up to `now` (or the full local day). */
    val expectedSlotCount: Int,

    /** Slots with a finite [ObservationQuality.NORMAL] known-unit value. */
    val usableSlotCount: Int,

    /** Gaps: expected slots with no sample or a non-usable (null/unavailable/failed) sample. */
    val missingSlotCount: Int,

    /** Expected slots whose sample is [ObservationQuality.ANOMALOUS]. */
    val anomalousSlotCount: Int,

    /** Expected slots with a finite value but an unknown/legacy-null unit. */
    val unusableUnitSlotCount: Int,

    /** Sum of usable interval values normalized to Wh; `null` when none were usable. */
    val usableEnergyValueWh: Double?,

    /** Usable slots ÷ expected slots, capped at `1.0`; `null` when expected is 0. */
    val coverageFraction: Double?,

    /** Recognized units seen in the day's present-value samples. */
    val recordedUnits: Set<EnergyUnit>,

    /** Raw `unitText` strings seen, including unknown labels, preserved verbatim. */
    val unitTexts: Set<String>,

    /** Raw `unitSourceText` provenance strings seen, preserved verbatim. */
    val unitSourceTexts: Set<String>,

    /** Usable Wh ÷ elapsed local-day hours; interval-average power, `null` when no usable energy. */
    val averagePowerWatts: Double?,

    /** One point per observed slot, ordered by [EnergyIntervalPoint.slotIndex]. */
    val points: List<EnergyIntervalPoint>,

    /** Preserved daily measures (finalized LG and/or local interval sums) for this day. */
    val measures: List<EnergyDailyMeasure>,
)

/**
 * The complete immutable presentation of one day/week/month selection.
 *
 * [days] contains one summary per local calendar day of the period (including
 * future days, which simply report zero expected slots), [intervalPoints] is the
 * flat chart series, and [dailyMeasures] is every preserved daily total in the
 * period.
 */
data class EnergyHistoryPresentation(
    val periodType: EnergyPeriodType,
    val selectedDate: LocalDate,
    val timeZoneId: String,
    val nowEpochMillis: Long,
    val period: EnergyPeriodRange,
    val queryCoverage: QueryCoverage,
    val days: List<EnergyDaySummary>,
) {

    /** Every interval point in the period (day order, slot order). */
    val intervalPoints: List<EnergyIntervalPoint>
        get() = days.flatMap { it.points }

    /** Every preserved daily measure in the period. */
    val dailyMeasures: List<EnergyDailyMeasure>
        get() = days.flatMap { it.measures }

    /** Sum of the days' usable interval energy in Wh; `null` when no day was usable. */
    val usableEnergyValueWh: Double?
        get() {
            val sums = days.mapNotNull { it.usableEnergyValueWh }
            return if (sums.isEmpty()) null else sums.sum()
        }

    /** Period interval-average power in watts (usable Wh ÷ elapsed local hours). */
    val averagePowerWatts: Double?
        get() {
            val totalWh = usableEnergyValueWh ?: return null
            val elapsedHours = days.sumOf {
                (minOf(it.dayEndEpochMillis, nowEpochMillis) - it.dayStartEpochMillis).coerceAtLeast(0L)
            } / MILLIS_PER_HOUR.toDouble()
            return if (elapsedHours > 0) totalWh / elapsedHours else null
        }
}

/**
 * The exact bounds of [periodType]'s range around [selectedDate] in
 * [timeZoneId]. See [EnergyPeriodRange] for the inclusive/exclusive semantics.
 */
fun energyPeriodRange(
    periodType: EnergyPeriodType,
    selectedDate: LocalDate,
    timeZoneId: String = DEFAULT_DAY_TIME_ZONE,
): EnergyPeriodRange {
    val inclusiveStart: LocalDate
    val inclusiveEnd: LocalDate
    when (periodType) {
        EnergyPeriodType.DAY -> {
            inclusiveStart = selectedDate
            inclusiveEnd = selectedDate
        }
        EnergyPeriodType.WEEK -> {
            // ISO week: Monday = isoDayNumber 1 .. Sunday = 7.
            val monday = selectedDate.minus(selectedDate.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            inclusiveStart = monday
            inclusiveEnd = monday.plus(6, DateTimeUnit.DAY)
        }
        EnergyPeriodType.MONTH -> {
            val first = LocalDate(selectedDate.year, selectedDate.month, 1)
            val nextMonthFirst = first.plus(1, DateTimeUnit.MONTH)
            inclusiveStart = first
            inclusiveEnd = nextMonthFirst.minus(1, DateTimeUnit.DAY)
        }
    }
    val zone = TimeZone.of(timeZoneId)
    val startMillis = inclusiveStart.atStartOfDayIn(zone).toEpochMilliseconds()
    val endExclusiveMillis = inclusiveEnd.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    return EnergyPeriodRange(
        periodType = periodType,
        selectedDate = selectedDate,
        inclusiveStartDate = inclusiveStart,
        inclusiveEndDate = inclusiveEnd,
        startEpochMillis = startMillis,
        endExclusiveEpochMillis = endExclusiveMillis,
        dayCount = inclusiveEnd.toEpochDays().toInt() - inclusiveStart.toEpochDays().toInt() + 1,
    )
}

/** Moves a period anchor by one or more periods; month changes clamp the day to the target month. */
fun navigateEnergyAnchor(
    periodType: EnergyPeriodType,
    selectedDate: LocalDate,
    direction: Int,
): LocalDate = when (periodType) {
    EnergyPeriodType.DAY -> selectedDate.plus(direction, DateTimeUnit.DAY)
    EnergyPeriodType.WEEK -> selectedDate.plus(direction * 7, DateTimeUnit.DAY)
    EnergyPeriodType.MONTH -> addMonthsClamped(selectedDate, direction)
}

private fun addMonthsClamped(date: LocalDate, months: Int): LocalDate {
    var monthIndex = date.year * 12 + date.month.ordinal + months
    var targetYear = monthIndex / 12
    var targetMonthIndex = monthIndex % 12
    if (targetMonthIndex < 0) {
        targetMonthIndex += 12
        targetYear -= 1
    }
    val targetMonth = kotlinx.datetime.Month.entries[targetMonthIndex]
    val targetFirst = LocalDate(targetYear, targetMonth, 1)
    val nextFirst = targetFirst.plus(1, DateTimeUnit.MONTH)
    val daysInMonth = (nextFirst.toEpochDays() - targetFirst.toEpochDays()).toInt()
    // Keep the original day number (e.g. Mar 15 -> Feb 15), clamping only when
    // the target month has fewer days (e.g. Jan 31 -> Feb 28/29).
    return LocalDate(targetYear, targetMonth, minOf(date.day, daysInMonth))
}

/**
 * Builds the day/week/month presentation from the plain normalized projection.
 *
 * Observations are grouped into local calendar days by the zone's calendar day
 * of `intervalStartEpochMillis`, clamped to the period and to `now` (only
 * completed intervals on the current day count). Duplicate/out-of-order samples
 * that map to the same grid slot are resolved deterministically (the sample with
 * the latest interval end, then latest `persistedAt`/`reconciledAt`, then
 * sample id), so a re-fetched or reconciled row never double-counts a slot.
 */
fun presentEnergyHistory(
    observations: List<Observation>,
    dailyTotals: List<DailyTotal>,
    coveredRanges: List<CoveredRange>,
    periodType: EnergyPeriodType,
    selectedDate: LocalDate,
    nowEpochMillis: Long,
    timeZoneId: String = DEFAULT_DAY_TIME_ZONE,
): EnergyHistoryPresentation {
    val zone = TimeZone.of(timeZoneId)
    val period = energyPeriodRange(periodType, selectedDate, timeZoneId)
    val days = summarizeDays(observations, dailyTotals, period, zone, nowEpochMillis)
    val queryEnd = nowEpochMillis.coerceIn(period.startEpochMillis, period.endExclusiveEpochMillis)
    val queryWindow = period.copy(endExclusiveEpochMillis = queryEnd)
    return EnergyHistoryPresentation(
        periodType = periodType,
        selectedDate = selectedDate,
        timeZoneId = timeZoneId,
        nowEpochMillis = nowEpochMillis,
        period = period,
        queryCoverage = if (queryEnd == period.startEpochMillis) {
            QueryCoverage(fullyQueried = true, queriedFraction = 1.0)
        } else {
            computeQueryCoverage(queryWindow, coveredRanges)
        },
        days = days,
    )
}

/** Convenience overload reading the projection from a [HistorySnapshot]. */
fun presentEnergyHistory(
    snapshot: HistorySnapshot,
    periodType: EnergyPeriodType,
    selectedDate: LocalDate,
    nowEpochMillis: Long,
    timeZoneId: String = DEFAULT_DAY_TIME_ZONE,
): EnergyHistoryPresentation = presentEnergyHistory(
    observations = snapshot.observations,
    dailyTotals = snapshot.dailyTotals,
    coveredRanges = snapshot.coveredRanges,
    periodType = periodType,
    selectedDate = selectedDate,
    nowEpochMillis = nowEpochMillis,
    timeZoneId = timeZoneId,
)

/**
 * Normalizes a recorded energy value to Wh **only** for recognized units.
 *
 * Unknown/legacy null units return `null` — a caller must never relabel a
 * missing/unknown source unit as Wh/kWh. Use [EnergyUnit.KWH] `× 1000`, [EnergyUnit.WH]
 * verbatim.
 */
fun normalizeToWh(value: Double, unit: EnergyUnit?): Double? = when (unit) {
    EnergyUnit.WH -> value.takeIf { it.isFinite() }
    EnergyUnit.KWH -> (value * 1000.0).takeIf { value.isFinite() && it.isFinite() }
    null -> null
}

/**
 * Interval-average power in watts for one interval: Wh ÷ span hours.
 *
 * This is **average** power (never instantaneous wattage); `null` when the
 * interval value or span is unusable.
 */
fun intervalAveragePowerWatts(energyValueWh: Double?, intervalSpanMillis: Long): Double? {
    if (energyValueWh == null || !energyValueWh.isFinite() || intervalSpanMillis <= 0) return null
    return energyValueWh / (intervalSpanMillis / MILLIS_PER_HOUR.toDouble())
}

/**
 * The union query-cache coverage of [ranges] over [period]'s epoch window.
 * Exposed separately from measured slot coverage so callers can distinguish
 * "this range was fetched" from "this range measured usable energy".
 */
fun computeQueryCoverage(period: EnergyPeriodRange, ranges: List<CoveredRange>): QueryCoverage {
    val windowMillis = (period.endExclusiveEpochMillis - period.startEpochMillis).coerceAtLeast(1L)
    val relevant = ranges
        .filter { it.toEpochMillis > period.startEpochMillis && it.fromEpochMillis < period.endExclusiveEpochMillis }
        .sortedBy { it.fromEpochMillis }
    var cursor = period.startEpochMillis
    var covered = 0L
    for (range in relevant) {
        val from = maxOf(range.fromEpochMillis, period.startEpochMillis)
        val to = minOf(range.toEpochMillis, period.endExclusiveEpochMillis)
        if (to <= from) continue
        if (from > cursor) cursor = from
        if (to > cursor) {
            covered += to - cursor
            cursor = to
        }
    }
    val fraction = (covered.toDouble() / windowMillis).coerceIn(0.0, 1.0)
    return QueryCoverage(
        fullyQueried = covered >= windowMillis,
        queriedFraction = fraction,
    )
}

// --- helpers -----------------------------------------------------------------

private const val MILLIS_PER_HOUR: Long = 3_600_000L

/** One local-day bucket with its expected slot count and chosen per-slot sample. */
private class DayWork(
    val date: LocalDate,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
    val expectedSlotCount: Int,
    val isCurrentDay: Boolean,
    val slots: MutableMap<Int, Observation> = sortedMapOf(),
)

private fun summarizeDays(
    observations: List<Observation>,
    dailyTotals: List<DailyTotal>,
    period: EnergyPeriodRange,
    timeZone: TimeZone,
    nowEpochMillis: Long,
): List<EnergyDaySummary> {
    val buckets = buildDayBuckets(period, timeZone, nowEpochMillis)
    val bucketByDate = buckets.associateBy { it.date }

    for (observation in observations) {
        // Clamp to the period and to now: outside the window, or an interval
        // that has not yet completed, contributes nothing on the current day.
        if (observation.intervalStartEpochMillis >= period.endExclusiveEpochMillis) continue
        if (observation.intervalEndEpochMillis <= period.startEpochMillis) continue
        if (observation.intervalEndEpochMillis > nowEpochMillis) continue

        val dayDate = Instant.fromEpochMilliseconds(observation.intervalStartEpochMillis)
            .toLocalDateTime(timeZone)
            .date
        val bucket = bucketByDate[dayDate] ?: continue
        if (bucket.expectedSlotCount <= 0) continue
        val slotIndex = ((observation.intervalStartEpochMillis - bucket.dayStartEpochMillis) / FALLBACK_INTERVAL_MILLIS)
            .toInt()
        if (slotIndex < 0 || slotIndex >= bucket.expectedSlotCount) continue
        bucket.slots[slotIndex] = chooseSlotSample(bucket.slots[slotIndex], observation)
    }

    return buckets.map { bucket -> summarizeDay(bucket, dailyTotals, nowEpochMillis) }
}

private fun buildDayBuckets(
    period: EnergyPeriodRange,
    timeZone: TimeZone,
    nowEpochMillis: Long,
): MutableList<DayWork> {
    val buckets = mutableListOf<DayWork>()
    var date = period.inclusiveStartDate
    while (true) {
        val dayStart = date.atStartOfDayIn(timeZone).toEpochMilliseconds()
        val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds()
        val effectiveEnd = minOf(dayEnd, nowEpochMillis)
        val expected = if (effectiveEnd > dayStart) {
            ((effectiveEnd - dayStart) / FALLBACK_INTERVAL_MILLIS).toInt()
        } else {
            0
        }
        buckets += DayWork(
            date = date,
            dayStartEpochMillis = dayStart,
            dayEndEpochMillis = dayEnd,
            expectedSlotCount = expected,
            isCurrentDay = dayStart <= nowEpochMillis && nowEpochMillis < dayEnd,
        )
        if (date >= period.inclusiveEndDate) break
        date = date.plus(1, DateTimeUnit.DAY)
    }
    return buckets
}

/**
 * Out-of-order / duplicate sample resolution: the sample with the latest
 * completed interval wins; ties break on newest `persistedAt`/`reconciledAt`,
 * then lexicographically on sample id. Deterministic regardless of input order.
 */
private fun chooseSlotSample(current: Observation?, candidate: Observation): Observation {
    if (current == null) return candidate
    return if (compareSlotSamples(current, candidate) >= 0) current else candidate
}

private fun compareSlotSamples(a: Observation, b: Observation): Int = compareValuesBy(
    a,
    b,
    { it.intervalEndEpochMillis },
    { it.persistedAtEpochMillis ?: -1L },
    { it.reconciledAtEpochMillis ?: -1L },
    { it.sampleId },
)

/** A gap is missing data: null value, missing/unavailable quality, or a failed energy read. */
private fun isGap(observation: Observation): Boolean =
    observation.energyValue == null ||
        observation.quality == ObservationQuality.MISSING_INTERVAL ||
        observation.quality == ObservationQuality.UNAVAILABLE ||
        observation.energySourceFailure?.ok == false ||
        // Legacy sourceFailure is a combined one-line summary. The parser uses
        // the stable `energy:`/`state:` prefixes; only the former invalidates an
        // energy value when the newer typed energy result is absent.
        (observation.energySourceFailure == null &&
            observation.sourceFailure?.startsWith("energy:", ignoreCase = true) == true)

private fun summarizeDay(
    bucket: DayWork,
    dailyTotals: List<DailyTotal>,
    nowEpochMillis: Long,
): EnergyDaySummary {
    val expected = bucket.expectedSlotCount
    var usableCount = 0
    var anomalousCount = 0
    var unusableUnitCount = 0
    var gapCount = 0
    var sumWh = 0.0
    var hasUsable = false

    val recordedUnits = mutableSetOf<EnergyUnit>()
    val unitTexts = mutableSetOf<String>()
    val unitSourceTexts = mutableSetOf<String>()
    val points = mutableListOf<EnergyIntervalPoint>()

    for ((slotIndex, observation) in bucket.slots) {
        val value = observation.energyValue
        val finiteValue = value != null && value.isFinite()
        val gap = isGap(observation) || (value != null && !finiteValue)
        val anomalous = observation.quality == ObservationQuality.ANOMALOUS
        val wh = if (finiteValue && !gap && !anomalous && observation.recordedUnit != null) {
            normalizeToWh(value, observation.recordedUnit)
        } else {
            null
        }
        val usable = wh != null
        val unrepresentableUnitValue = finiteValue && !gap && !anomalous &&
            observation.recordedUnit != null && wh == null

        // Record-unit labels/provenance come from any sample that carries a value.
        if (value != null) {
            observation.recordedUnit?.let { recordedUnits += it }
            observation.unitText?.let { unitTexts += it }
            observation.unitSourceText?.let { unitSourceTexts += it }
        }

        when {
            gap -> gapCount++
            anomalous -> anomalousCount++
            observation.recordedUnit == null -> unusableUnitCount++
            unrepresentableUnitValue -> gapCount++
            else -> {
                usableCount++
                sumWh += wh!!
                hasUsable = true
            }
        }

        points += EnergyIntervalPoint(
            sampleId = observation.sampleId,
            intervalStartEpochMillis = observation.intervalStartEpochMillis,
            intervalEndEpochMillis = observation.intervalEndEpochMillis,
            localDay = bucket.date,
            slotIndex = slotIndex,
            energyValue = value,
            energyValueWh = wh,
            usable = usable,
            quality = observation.quality,
            recordedUnit = observation.recordedUnit,
            unitText = observation.unitText,
            unitSourceText = observation.unitSourceText,
            intervalStatus = observation.intervalStatus,
            sourceFailure = observation.sourceFailure,
        )
    }

    val missingCount = maxOf(0, expected - usableCount - anomalousCount - unusableUnitCount)
    val coverageFraction = if (expected == 0) {
        null
    } else {
        (usableCount.toDouble() / expected).coerceIn(0.0, 1.0)
    }
    val usableWh = if (hasUsable) sumWh else null
    val elapsedHours =
        (minOf(bucket.dayEndEpochMillis, nowEpochMillis) - bucket.dayStartEpochMillis).coerceAtLeast(0L) /
            MILLIS_PER_HOUR.toDouble()

    return EnergyDaySummary(
        date = bucket.date,
        dayStartEpochMillis = bucket.dayStartEpochMillis,
        dayEndEpochMillis = bucket.dayEndEpochMillis,
        isCurrentDay = bucket.isCurrentDay,
        expectedSlotCount = expected,
        usableSlotCount = usableCount,
        missingSlotCount = missingCount,
        anomalousSlotCount = anomalousCount,
        unusableUnitSlotCount = unusableUnitCount,
        usableEnergyValueWh = usableWh,
        coverageFraction = coverageFraction,
        recordedUnits = recordedUnits,
        unitTexts = unitTexts,
        unitSourceTexts = unitSourceTexts,
        averagePowerWatts = if (hasUsable && elapsedHours > 0) sumWh / elapsedHours else null,
        points = points,
        measures = dailyTotals.mapNotNull { total -> measureForDay(total, bucket.date, bucket.dayStartEpochMillis) },
    )
}

/** Keeps a [DailyTotal] only when it belongs to the bucket's local day; never merges totals. */
private fun measureForDay(total: DailyTotal, date: LocalDate, dayStartEpochMillis: Long): EnergyDailyMeasure? {
    val matchesLocalDate = total.localDate != null && total.localDate == date.toString()
    val matchesDayStart = total.localDate == null && total.dayStartEpochMillis == dayStartEpochMillis
    if (!matchesLocalDate && !matchesDayStart) return null
    val localDay = total.localDate?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() } ?: date
    return EnergyDailyMeasure(
        original = total,
        localDay = localDay,
        energyValueWh = normalizeToWh(total.energyValue, total.recordedUnit),
    )
}
