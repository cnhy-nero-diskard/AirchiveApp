package cloud.airchive.shared

import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.data.energy.EnergyPeriodType
import cloud.airchive.shared.data.energy.presentEnergyHistory
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Pure presentation model for the History ledger: observations grouped into days, then hours.
 *
 * Everything here derives from the same [presentEnergyHistory] day summaries the Energy route
 * uses, so a day total always equals the sum of the hour rows beneath it, a missing measurement
 * stays a gap (never a zero), and day boundaries follow the AC's [DEFAULT_DAY_TIME_ZONE].
 */

/** Five-minute slots in one hour. */
private const val SLOTS_PER_HOUR = 12

internal enum class HistoryHourStatus {
    /** Every expected five-minute reading is usable. */
    COMPLETE,

    /** The hour has not finished yet, and every reading so far is usable. */
    IN_PROGRESS,

    /** At least one expected reading is missing, anomalous, or has an unknown unit. */
    PARTIAL,

    /** The hour has cached samples, but none carries a usable energy value. */
    UNAVAILABLE,
}

internal data class HistoryHour(
    val key: String,
    val group: InstrumentEnergyGroup,
    val samples: List<Observation>,
    val date: LocalDate,
) {
    val status: HistoryHourStatus
        get() = when {
            group.energyWh == null -> HistoryHourStatus.UNAVAILABLE
            group.usable < group.expected -> HistoryHourStatus.PARTIAL
            group.expected < SLOTS_PER_HOUR -> HistoryHourStatus.IN_PROGRESS
            else -> HistoryHourStatus.COMPLETE
        }
}

internal data class HistoryDay(
    val date: LocalDate,
    val hours: List<HistoryHour>,
    /** Sum of the usable hour totals, or `null` when no hour has a usable value. */
    val energyWh: Double?,
    val isCurrentDay: Boolean,
    /** Hours that have at least one cached sample. */
    val recordedHours: Int,
    /** Hours that have elapsed (or begun) in this day, so a cache gap is visible as missing hours. */
    val expectedHours: Int,
) {
    /** True when the total omits readings: a missing hour, or any partial/unavailable hour. */
    val isPartial: Boolean
        get() = recordedHours < expectedHours || hours.any {
            it.status == HistoryHourStatus.PARTIAL || it.status == HistoryHourStatus.UNAVAILABLE
        }
}

/** Newest-first days, each with newest-first hours. */
internal fun historyDays(observations: List<Observation>, nowEpochMillis: Long): List<HistoryDay> {
    val zone = TimeZone.of(DEFAULT_DAY_TIME_ZONE)
    val byDate = observations.groupBy { Instant.fromEpochMilliseconds(it.intervalStartEpochMillis).toLocalDateTime(zone).date }
    return byDate.entries.sortedByDescending { it.key }.map { (date, samples) ->
        val summary = presentEnergyHistory(samples, emptyList(), emptyList(), EnergyPeriodType.DAY, date, nowEpochMillis).days.single()
        val groups = instrumentEnergyGroups(summary)
        val hours = samples
            .groupBy { Instant.fromEpochMilliseconds(it.intervalStartEpochMillis).toLocalDateTime(zone).hour }
            .entries.sortedByDescending { it.key }
            .map { (hour, values) ->
                HistoryHour(
                    key = "$date ${hour.toString().padStart(2, '0')}",
                    group = groups[hour],
                    samples = values.sortedByDescending { it.intervalStartEpochMillis },
                    date = date,
                )
            }
        HistoryDay(
            date = date,
            hours = hours,
            energyWh = hours.mapNotNull { it.group.energyWh }.takeIf { it.isNotEmpty() }?.sum(),
            isCurrentDay = summary.isCurrentDay,
            recordedHours = hours.size,
            expectedHours = (summary.expectedSlotCount + SLOTS_PER_HOUR - 1) / SLOTS_PER_HOUR,
        )
    }
}

/**
 * The words under an hour's reading: a [badge] only when coverage is something other than complete,
 * and a plain [text] explanation. Complete hours stay quiet in Simple mode.
 */
internal data class HistoryHourNote(val badge: String?, val text: String?)

internal fun HistoryHour.note(detailed: Boolean): HistoryHourNote {
    val readings = "${group.usable} of ${group.expected} readings"
    return when (status) {
        HistoryHourStatus.COMPLETE -> HistoryHourNote(null, if (detailed) readings else null)
        HistoryHourStatus.IN_PROGRESS -> HistoryHourNote("In progress", if (detailed) readings else null)
        HistoryHourStatus.PARTIAL -> HistoryHourNote("Partial", readings)
        HistoryHourStatus.UNAVAILABLE -> HistoryHourNote("Unavailable", "No usable energy values")
    }
}

/** Flat newest-first hours, kept for callers that do not need the day level. */
internal fun historyHours(observations: List<Observation>, nowEpochMillis: Long): List<HistoryHour> =
    historyDays(observations, nowEpochMillis).flatMap { it.hours }

/** The largest usable hour total, used to scale the relative bars. `0.0` when nothing is usable. */
internal fun maxHourEnergyWh(days: List<HistoryDay>): Double =
    days.maxOfOrNull { day -> day.hours.maxOfOrNull { it.group.energyWh ?: 0.0 } ?: 0.0 } ?: 0.0

/** Bar fill in `0f..1f`. A non-zero reading always shows a sliver, so it never looks like a gap. */
internal fun hourBarFraction(energyWh: Double?, maxWh: Double): Float {
    if (energyWh == null || !energyWh.isFinite() || energyWh <= 0.0 || maxWh <= 0.0) return 0f
    return (energyWh / maxWh).toFloat().coerceIn(MIN_VISIBLE_BAR, 1f)
}

private const val MIN_VISIBLE_BAR = 0.03f

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

/** The date a viewer's "now" falls on in the AC's zone. */
internal fun historyToday(nowEpochMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(TimeZone.of(DEFAULT_DAY_TIME_ZONE)).date

/** "Today", "Yesterday", otherwise "Sat 25 Oct" (with the year once it differs from this year). */
internal fun historyDayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
    else -> historyShortDate(date, includeYear = date.year != today.year)
}

/** "Tue 27 Oct", optionally followed by the year. */
internal fun historyShortDate(date: LocalDate, includeYear: Boolean = false): String =
    "${date.dayOfWeek.shortName()} ${date.day} ${date.month.shortName()}" + if (includeYear) " ${date.year}" else ""

/** "Tuesday 27 October" for screen readers. */
internal fun historySpokenDate(date: LocalDate): String =
    "${date.dayOfWeek.name.titleCase()} ${date.day} ${date.month.name.titleCase()} ${date.year}"

/** "17:00–18:00" in 24-hour time, consistent with the five-minute rows beneath it. */
internal fun historyHourRange(hour: Int): String {
    fun clock(value: Int) = "${(value % 24).toString().padStart(2, '0')}:00"
    return "${clock(hour)}–${clock(hour + 1)}"
}

private fun Enum<*>.shortName(): String = name.titleCase().take(3)

private fun String.titleCase(): String = lowercase().replaceFirstChar { it.uppercase() }
