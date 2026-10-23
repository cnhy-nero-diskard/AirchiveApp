package cloud.airchive.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.energy.EnergyDailyMeasure
import cloud.airchive.shared.data.energy.EnergyDaySummary
import cloud.airchive.shared.data.energy.EnergyHistoryPresentation
import cloud.airchive.shared.data.energy.EnergyIntervalPoint
import cloud.airchive.shared.data.energy.EnergyPeriodType
import cloud.airchive.shared.data.energy.EnergyPeriodRange
import cloud.airchive.shared.data.energy.energyPeriodRange
import cloud.airchive.shared.data.energy.intervalAveragePowerWatts
import cloud.airchive.shared.data.energy.navigateEnergyAnchor
import cloud.airchive.shared.data.energy.normalizeToWh
import cloud.airchive.shared.data.energy.presentEnergyHistory
import cloud.airchive.shared.data.sync.HistoricalRangeLoader
import cloud.airchive.shared.data.sync.HistoricalRangeResult
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Energy day/week/month route (OpenSpec task 5.3).
 *
 * This is a **read-only presentation shell** over the pure projection API in
 * `cloud.airchive.shared.data.energy`. The parent passes the already-observed
 * [HistorySnapshot] (from `LocalHistoryPort.observe`) so this screen renders
 * cached data first; when the parent's snapshot updates after a fetch, this
 * route recomposes from the new cache without ever querying Firestore directly.
 *
 * Contract guarantees:
 *
 *  - **No auto-fetch.** Entering the route, switching Day/Week/Month, and
 *    navigating periods only change *local presentation state* (the anchor
 *    [LocalDate], initially today in [timeZoneId]). Nothing is fetched until the
 *    explicit `Load sampled intervals for this period` button is pressed.
 *  - **Explicit read-and-cache only.** The button builds a
 *    [HistoricalRangeLoader] and requests exactly
 *    `period.startEpochMillis .. min(period.endExclusiveEpochMillis, now)`.
 *    Empty/future ranges are skipped with a fixed message, and every adapter
 *    failure maps to a fixed safe UI string — never a raw exception.
 *  - **Measurement meaning is preserved.** Five-minute interval values are shown
 *    as *sampled energy*, converted to Wh only from recognized source units, and
 *    any energy÷time conversion is labeled *interval-average power* — never
 *    instantaneous wattage. Null intervals stay gaps (never zeros), anomalous
 *    and unknown-unit values are counted and flagged but excluded from usable
 *    sums, and finalized LG daily totals stay separate from local interval sums
 *    and from the sampled five-minute sum.
 *  - **No ThinQ and no raw payload.** This route touches only the telemetry and
 *    local-history ports and renders only sanitized projections.
 */
@Composable
fun EnergyRoute(
    snapshot: HistorySnapshot,
    telemetry: AirchiveTelemetryPort,
    local: LocalHistoryPort,
    partition: HistoryPartition,
    timeZoneId: String = DEFAULT_DAY_TIME_ZONE,
    remoteLoadEnabled: Boolean = true,
    loadRange: (suspend (Long, Long) -> HistoricalRangeResult)? = null,
) {
    // A malformed/unknown zone id must never crash the route; fall back to the
    // documented default day boundary.
    val zoneId = remember(timeZoneId) {
        if (runCatching { TimeZone.of(timeZoneId) }.isSuccess) timeZoneId else DEFAULT_DAY_TIME_ZONE
    }
    val scope = rememberCoroutineScope()

    var periodType by remember { mutableStateOf(EnergyPeriodType.DAY) }
    var anchorDate by remember(zoneId) { mutableStateOf(todayIn(zoneId)) }
    var loadState by remember(periodType, anchorDate, zoneId) {
        mutableStateOf<EnergyLoadUiState>(EnergyLoadUiState.Idle)
    }

    val period = remember(periodType, anchorDate, zoneId) {
        energyPeriodRange(periodType, anchorDate, zoneId)
    }

    // Immediate cache-first render of the parent-provided snapshot. "Now" is
    // captured when the inputs change so elapsed-slot accounting stays stable
    // between recompositions and only recomputes on real input changes.
    val presentation = remember(snapshot, period, zoneId) {
        presentEnergyHistory(
            snapshot = snapshot,
            periodType = period.periodType,
            selectedDate = period.selectedDate,
            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
            timeZoneId = zoneId,
        )
    }

    fun launchLoad() {
        // Capture the selection at press time; navigation during the load never
        // reports feedback for the wrong period.
        val requestedType = periodType
        val requestedAnchor = anchorDate
        scope.launch {
            loadState = EnergyLoadUiState.Loading
            val now = Clock.System.now().toEpochMilliseconds()
            val requestedPeriod = energyPeriodRange(requestedType, requestedAnchor, zoneId)
            val end = minOf(requestedPeriod.endExclusiveEpochMillis, now)
            if (end <= requestedPeriod.startEpochMillis) {
                // Empty or entirely future range: nothing to fetch.
                loadState = EnergyLoadUiState.SkippedFuture
                return@launch
            }
            loadState = try {
                val result = loadRange?.invoke(requestedPeriod.startEpochMillis, end)
                    ?: HistoricalRangeLoader(telemetry, local, partition)
                        .loadHistoricalRange(requestedPeriod.startEpochMillis, end)
                EnergyLoadUiState.Loaded(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fixed safe message; raw exceptions never surface.
                EnergyLoadUiState.Failed
            }
        }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Energy", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(
            "Five-minute sampled AC energy by local calendar day. Day boundaries use the " +
                "$zoneId zone; nothing is fetched automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )

        EnergyPeriodSelector(selected = periodType, onSelect = { periodType = it })

        EnergyPeriodNavigation(
            period = period,
            onNavigate = { anchorDate = it },
        )

        EnergyLoadCard(
            state = loadState,
            loading = loadState is EnergyLoadUiState.Loading,
            onLoad = ::launchLoad,
            remoteLoadEnabled = remoteLoadEnabled,
        )

        if (!remoteLoadEnabled) Text("Cached energy remains available. Verify history access from Home before downloading another range.")

        EnergySummaryCard(presentation = presentation)

        EnergyChartSection(presentation = presentation)

        EnergyDailySections(
            presentation = presentation,
            showDayDetail = periodType == EnergyPeriodType.DAY,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Units and conversions", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Interval values keep each source's recorded unit. Wh conversions are shown only for " +
                        "recognized units (Wh verbatim, kWh × 1000). An unknown or absent unit is labeled " +
                        "\"unit not recorded/unknown\" and is never guessed. Energy per time is always shown as " +
                        "interval-average power, never instantaneous wattage.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Selection chrome
// ---------------------------------------------------------------------------

private fun EnergyPeriodType.label(): String = when (this) {
    EnergyPeriodType.DAY -> "Day"
    EnergyPeriodType.WEEK -> "Week"
    EnergyPeriodType.MONTH -> "Month"
}

private fun EnergyPeriodType.unitLabel(): String = when (this) {
    EnergyPeriodType.DAY -> "day"
    EnergyPeriodType.WEEK -> "week"
    EnergyPeriodType.MONTH -> "month"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnergyPeriodSelector(
    selected: EnergyPeriodType,
    onSelect: (EnergyPeriodType) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EnergyPeriodType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(type.label()) },
            )
        }
    }
}

@Composable
private fun EnergyPeriodNavigation(
    period: EnergyPeriodRange,
    onNavigate: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(periodRangeTitle(period), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onNavigate(navigateAnchor(period.periodType, period.selectedDate, -1)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Previous ${period.periodType.unitLabel()}")
            }
            Button(
                onClick = { onNavigate(navigateAnchor(period.periodType, period.selectedDate, +1)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Next ${period.periodType.unitLabel()}")
            }
        }
    }
}

private fun periodRangeTitle(period: EnergyPeriodRange): String = when (period.periodType) {
    EnergyPeriodType.DAY -> period.inclusiveStartDate.longDate()
    EnergyPeriodType.WEEK ->
        "${period.inclusiveStartDate.shortDate()} – ${period.inclusiveEndDate.shortDate()}, " +
            period.inclusiveEndDate.year
    EnergyPeriodType.MONTH -> "${period.inclusiveStartDate.monthName()} ${period.inclusiveStartDate.year}"
}

private fun navigateAnchor(type: EnergyPeriodType, anchor: LocalDate, direction: Int): LocalDate =
    navigateEnergyAnchor(type, anchor, direction)

// ---------------------------------------------------------------------------
// Explicit load feedback
// ---------------------------------------------------------------------------

private sealed interface EnergyLoadUiState {
    data object Idle : EnergyLoadUiState
    data object Loading : EnergyLoadUiState
    data object SkippedFuture : EnergyLoadUiState
    data object Failed : EnergyLoadUiState
    data class Loaded(val result: HistoricalRangeResult) : EnergyLoadUiState
}

@Composable
private fun EnergyLoadCard(
    state: EnergyLoadUiState,
    loading: Boolean,
    onLoad: () -> Unit,
    remoteLoadEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Fetch sampled intervals", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Select a period and press the button to load and cache its five-minute intervals. " +
                    "Revisiting an already-fetched period reads the local cache with zero remote reads.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = !loading && remoteLoadEnabled,
                onClick = onLoad,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(if (loading) "Loading sampled intervals…" else "Load sampled intervals for this period")
            }
            loadStateDialogText(state)?.let { text ->
                Text(
                    text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun loadStateDialogText(state: EnergyLoadUiState): String? = when (state) {
    EnergyLoadUiState.Idle -> null
    EnergyLoadUiState.Loading -> "Loading sampled intervals for this period…"
    EnergyLoadUiState.SkippedFuture ->
        "This selection has no elapsed time yet (it is empty or still in the future), so there is nothing to fetch."
    EnergyLoadUiState.Failed ->
        "The sampled intervals for this period could not be retrieved. Your cached data is unchanged; retry when ready."
    is EnergyLoadUiState.Loaded -> {
        val result = state.result
        if (result.remotePagesFetched == 0) {
            "This period's sampled intervals were already cached; no remote reads were needed."
        } else {
            "${result.observations.size} sampled intervals are cached for this period · " +
                "${result.remotePagesFetched} remote page(s) fetched across ${result.gapsFetched} gap(s)."
        }
    }
}

// ---------------------------------------------------------------------------
// Period summary: sums, averages, coverage
// ---------------------------------------------------------------------------

@Composable
private fun EnergySummaryCard(presentation: EnergyHistoryPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Period summary", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)

            // ---- Sampled interval sum (only from recognized units). ----
            Text("Sampled five-minute interval energy", style = MaterialTheme.typography.labelLarge)
            val unusableUnitSlots = presentation.days.sumOf { it.unusableUnitSlotCount }
            val expected = presentation.days.sumOf { it.expectedSlotCount }
            val usableValue = presentation.usableEnergyValueWh
            if (usableValue != null) {
                Text("Sampled interval sum: ${usableValue.toTrimmedString()} Wh (normalized to Wh only where the source unit was recognized).")
            } else if (unusableUnitSlots > 0) {
                Text("Sampled interval sum: unavailable — interval values were recorded without a recognized unit and are kept raw (unit not recorded/unknown; never guessed).")
            } else {
                Text("Sampled interval sum: no usable sampled energy in this period yet.")
            }

            presentation.averagePowerWatts?.let { averageWatts ->
                Text(
                    "Interval-average power: ${averageWatts.toTrimmedString()} W — sampled energy ÷ elapsed time. " +
                        "This is an average over elapsed slots, never instantaneous wattage.",
                )
            }

            // ---- Query-cache coverage (explicitly distinct from measured). ----
            Text("Cached query coverage", style = MaterialTheme.typography.labelLarge)
            if (expected == 0) {
                Text("No elapsed five-minute range is ready to query yet; future slots are not missing data.")
            } else {
                val queriedTenths = (presentation.queryCoverage.queriedFraction * 1000.0)
                    .toInt()
                    .coerceIn(0, 1000)
                val queriedPercent = if (presentation.queryCoverage.fullyQueried) {
                    "100%"
                } else {
                    val tenths = queriedTenths.coerceAtMost(999)
                    "${tenths / 10}.${tenths % 10}%"
                }
                Text(
                    "Queried into the local cache: $queriedPercent of this period's elapsed time · " +
                        "fully queried: ${if (presentation.queryCoverage.fullyQueried) "yes" else "no"}.",
                )
                if (!presentation.queryCoverage.fullyQueried) {
                    Text("Some elapsed range remains unfetched — use the Load button above.")
                }
                Text(
                    "This describes which epoch ranges have been fetched from Airchive, separate from measured " +
                        "interval usability below: a fetched range can still contain gaps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- Measured slot coverage (period level). ----
            Text("Measured interval coverage", style = MaterialTheme.typography.labelLarge)
            val usable = presentation.days.sumOf { it.usableSlotCount }
            val gaps = presentation.days.sumOf { it.missingSlotCount }
            val anomalous = presentation.days.sumOf { it.anomalousSlotCount }
            val unknownUnits = presentation.days.sumOf { it.unusableUnitSlotCount }
            if (expected == 0) {
                Text("No elapsed five-minute slots in this period yet.")
            } else {
                val measuredPercent = (usable.toDouble() / expected * 100.0).roundToInt()
                Text("Usable five-minute slots: $usable of $expected ($measuredPercent%).")
                buildList {
                    if (gaps > 0) add("$gaps gap(s)")
                    if (anomalous > 0) add("$anomalous anomalous")
                    if (unknownUnits > 0) add("$unknownUnits unknown-unit value(s)")
                }.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("${list.joinToString(" · ")} — these are counted but never counted as measured zeros.")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Charts: day slot strip (DAY) and per-day bars (WEEK/MONTH)
// ---------------------------------------------------------------------------

private data class EnergyLegendColors(val scheme: ColorScheme) {
    val usable: Color = scheme.primary
    val gap: Color = scheme.surfaceVariant
    val anomalous: Color = scheme.tertiary
    val unknownUnit: Color = scheme.secondary
    val finalized: Color = scheme.tertiary
    val localSum: Color = scheme.secondary
}

@Composable
private fun EnergyChartSection(presentation: EnergyHistoryPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (presentation.periodType == EnergyPeriodType.DAY) {
                    "Five-minute interval coverage"
                } else {
                    "Daily energy measures"
                },
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            val colors = EnergyLegendColors(MaterialTheme.colorScheme)
            EnergyLegend(colors = colors, intervalDay = presentation.periodType == EnergyPeriodType.DAY)
            when (presentation.periodType) {
                EnergyPeriodType.DAY -> {
                    val day = presentation.days.firstOrNull()
                    if (day != null) IntervalStrip(day = day, colors = colors)
                }
                EnergyPeriodType.WEEK, EnergyPeriodType.MONTH -> DayBars(days = presentation.days, colors = colors)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnergyLegend(colors: EnergyLegendColors, intervalDay: Boolean) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (intervalDay) {
            LegendItem(colors.usable, "Usable interval (recognized unit)")
            LegendItem(colors.gap, "Gap / missing interval")
            LegendItem(colors.anomalous, "Anomalous sample")
            LegendItem(colors.unknownUnit, "Unknown-unit value")
        } else {
            LegendItem(colors.usable, "Sampled interval sum")
            LegendItem(colors.finalized, "Finalized LG total")
            LegendItem(colors.localSum, "Local interval-sum total")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
            .size(10.dp)
                .background(color, CircleShape)
                .clearAndSetSemantics {},
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** One segment per elapsed five-minute slot of a single day, colored by status. */
@Composable
private fun IntervalStrip(day: EnergyDaySummary, colors: EnergyLegendColors) {
    val expected = day.expectedSlotCount
    if (expected <= 0) {
        Text("No elapsed five-minute slots yet for this day.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val bySlot = day.points.associateBy { it.slotIndex }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .focusable()
            .semantics(mergeDescendants = true) {
                role = Role.Image
                contentDescription = day.stripContentDescription()
            },
    ) {
        val slotWidth = size.width / expected
        for (index in 0 until expected) {
            val point = bySlot[index]
            drawRect(
                color = point?.statusColor(colors) ?: colors.gap,
                topLeft = Offset(index.toFloat() * slotWidth, 0f),
                size = Size(slotWidth, size.height),
            )
        }
    }
}

private fun EnergyDaySummary.stripContentDescription(): String =
        "Day strip of $expectedSlotCount five-minute slots: usable $usableSlotCount, gaps $missingSlotCount, " +
            "anomalous $anomalousSlotCount, unknown-unit $unusableUnitSlotCount. The strip represents slot " +
            "status only; the day summary below gives exact aggregate values and counts, not a substitute for " +
            "each interval's precise value."

private fun EnergyIntervalPoint.statusColor(colors: EnergyLegendColors): Color = when {
    energyValueWh != null -> colors.usable
    quality == ObservationQuality.ANOMALOUS -> colors.anomalous
    energyValue != null && recordedUnit == null -> colors.unknownUnit
    else -> colors.gap
}

/** Grouped per-day bars keep interval sums and stored daily measure sources distinct. */
@Composable
private fun DayBars(days: List<EnergyDaySummary>, colors: EnergyLegendColors) {
    val barsByDay = days.map { day ->
        buildList {
            day.usableEnergyValueWh?.takeIf { it.isFinite() }?.let { value ->
                add(BarDatum(valueWh = value, color = colors.usable))
            }
            day.measures.firstOrNull {
                it.original.source == DailyTotalSource.LG_FINALIZED && it.original.finalized
            }?.energyValueWh?.takeIf { it.isFinite() }?.let { value ->
                add(BarDatum(valueWh = value, color = colors.finalized))
            }
            day.measures.firstOrNull { it.original.source == DailyTotalSource.LOCAL_INTERVAL_SUM }
                ?.energyValueWh?.takeIf { it.isFinite() }?.let { value ->
                    add(BarDatum(valueWh = value, color = colors.localSum))
                }
        }
    }
    val maxWh = barsByDay.flatten().map { it.valueWh }.maxOrNull() ?: 0.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .focusable()
            .semantics(mergeDescendants = true) {
                role = Role.Image
                contentDescription =
                    "Daily energy chart for ${days.size} days. Grouped bars show sampled five-minute sums, " +
                        "finalized LG daily totals, or local interval sums in Wh on one relative scale. " +
                        "Each source is separately named in the legend, with exact units, coverage, and values " +
                        "in the day details below."
            },
    ) {
        val groupWidth = size.width / days.size.coerceAtLeast(1)
        val maxBarsPerGroup = 3
        val barWidth = groupWidth / (maxBarsPerGroup + 1)
        for ((index, day) in days.withIndex()) {
            for ((barIndex, bar) in barsByDay[index].withIndex()) {
                val barHeight = if (maxWh > 0.0) (bar.valueWh / maxWh * size.height).toFloat() else 0f
                drawRect(
                    color = bar.color,
                    topLeft = Offset(
                        index.toFloat() * groupWidth + (barIndex + 1) * barWidth,
                        size.height - barHeight.coerceAtLeast(0f),
                    ),
                    size = Size(barWidth * 0.75f, barHeight.coerceAtLeast(0f)),
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Text(
                text = day.date.dayOfMonthLabel(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
    Text(
        "Bar height is relative usable energy normalized to Wh; bar color names the distinct source. " +
            "Coverage and missing/anomalous slots are listed in each day summary below; sources are never merged.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private data class BarDatum(val valueWh: Double, val color: Color)

// ---------------------------------------------------------------------------
// Daily sections: textual fallback so status is never color-only
// ---------------------------------------------------------------------------

@Composable
private fun EnergyDailySections(
    presentation: EnergyHistoryPresentation,
    showDayDetail: Boolean,
) {
    if (presentation.days.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No days in this period", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    presentation.days.forEach { day ->
        if (showDayDetail) {
            DayDetailCard(day = day)
        } else {
            DaySummaryCard(day = day)
        }
    }
}

/** Full single-day detail: slot strip, all counts, units, preserved measures. */
@Composable
private fun DayDetailCard(day: EnergyDaySummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(dayHeading(day), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            slotCountsText(day)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            day.usableEnergyValueWh?.let { value ->
                Text("Usable sampled five-minute energy: ${value.toTrimmedString()} Wh (recognized units only).")
            }
            day.averagePowerWatts?.let { averageWatts ->
                Text(
                    "Interval-average power: ${averageWatts.toTrimmedString()} W — sampled energy ÷ elapsed time " +
                        "(an average, never instantaneous wattage).",
                )
            }
            peakIntervalAverageWatts(day)?.let { watts ->
                Text(
                    "Peak sampled interval-average power: ${watts.toTrimmedString()} W (a five-minute average, " +
                        "not instantaneous).",
                )
            }

            unitLabels(day)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            DailyMeasuresSection(day = day)
        }
    }
}

/** Compact textual row for week/month views. */
@Composable
private fun DaySummaryCard(day: EnergyDaySummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(dayHeading(day), style = MaterialTheme.typography.titleSmall)
            slotCountsText(day)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            day.usableEnergyValueWh?.let { value ->
                Text("Usable sampled five-minute energy: ${value.toTrimmedString()} Wh.")
            }
            day.averagePowerWatts?.let { averageWatts ->
                Text(
                    "Interval-average power: ${averageWatts.toTrimmedString()} W (average over elapsed slots, " +
                        "never instantaneous wattage).",
                )
            }
            unitLabels(day)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            DaySummaryMeasures(day = day)
        }
    }
}

private fun dayHeading(day: EnergyDaySummary): String {
    val prefix = day.date.longDate()
    return if (day.isCurrentDay) "$prefix · today (partial elapsed slots)" else prefix
}

private fun slotCountsText(day: EnergyDaySummary): String? = if (day.expectedSlotCount == 0) {
    "No elapsed five-minute slots yet for this day."
} else {
    val coverage = day.coverageFraction?.let { "${(it * 100.0).roundToInt()}%" } ?: "n/a"
    buildString {
        append("Elapsed five-minute slots: ${day.expectedSlotCount} · measured usable: ")
        append("${day.usableSlotCount} of ${day.expectedSlotCount} ($coverage coverage).")
        val notes = buildList {
            if (day.missingSlotCount > 0) add("${day.missingSlotCount} gap(s)")
            if (day.anomalousSlotCount > 0) add("${day.anomalousSlotCount} anomalous")
            if (day.unusableUnitSlotCount > 0) add("${day.unusableUnitSlotCount} unknown-unit value(s)")
        }
        if (notes.isNotEmpty()) append(" ${notes.joinToString(" · ")}; these never become measured zeros. ")
    }
}

private fun unitLabels(day: EnergyDaySummary): String? = buildList {
    if (day.recordedUnits.isNotEmpty()) {
        add("Recognized unit(s): ${day.recordedUnits.joinToString { it.energyUnitLabel() }}")
    }
    if (day.unitTexts.isNotEmpty()) {
        add("Source unit text: ${day.unitTexts.joinToString { "\"$it\"" }}")
    }
    if (day.unitSourceTexts.isNotEmpty()) {
        add("Unit provenance: ${day.unitSourceTexts.joinToString()}")
    }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Peak single-interval average power for the day (uses [intervalAveragePowerWatts]). */
private fun peakIntervalAverageWatts(day: EnergyDaySummary): Double? {
    val peak = day.points.filter { it.usable }.maxByOrNull { it.energyValueWh ?: 0.0 } ?: return null
    return intervalAveragePowerWatts(
        energyValueWh = peak.energyValueWh,
        intervalSpanMillis = peak.intervalEndEpochMillis - peak.intervalStartEpochMillis,
    )
}

// ---------------------------------------------------------------------------
// Preserved daily measures (LG finalized / local interval sums)
// ---------------------------------------------------------------------------

@Composable
private fun DailyMeasuresSection(day: EnergyDaySummary) {
    val lg = day.measures.filter { it.original.source == DailyTotalSource.LG_FINALIZED }
    val localSums = day.measures.filter { it.original.source == DailyTotalSource.LOCAL_INTERVAL_SUM }
    if (lg.isEmpty() && localSums.isEmpty()) {
        Text(
            "No stored daily totals for this day.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    if (lg.isNotEmpty()) {
        Text("Finalized LG daily totals (kept separate from interval sums)", style = MaterialTheme.typography.labelLarge)
        lg.forEach { DailyMeasureRow(measure = it) }
    }
    if (localSums.isNotEmpty()) {
        Text("Local interval-sum daily totals", style = MaterialTheme.typography.labelLarge)
        localSums.forEach { DailyMeasureRow(measure = it) }
    }
    Text(
        "These stored totals are never merged with the sampled five-minute interval sum; they are distinct " +
            "measurement types.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DaySummaryMeasures(day: EnergyDaySummary) {
    if (day.measures.isEmpty()) return
    day.measures.forEach { measure ->
        val total = measure.original
        Text(
            "${total.source.sourceLabel()} · ${if (total.finalized) "finalized" else "not finalized"} · " +
                "${total.energyValue.toTrimmedString()} ${measureUnitLabel(measure)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DailyMeasureRow(measure: EnergyDailyMeasure) {
    val total = measure.original
    val recorded = total.recordedUnit
    val normalized = measure.energyValueWh ?: normalizeToWh(total.energyValue, recorded)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${total.source.sourceLabel()} · ${if (total.finalized) "finalized" else "not finalized"}")
        Text("${total.energyValue.toTrimmedString()} ${measureUnitLabel(measure)}")
        when {
            recorded == null && total.unitText.isNullOrBlank() ->
                Text("Unit not recorded/unknown — no Wh conversion is shown (never guessed).")
            !total.unitText.isNullOrBlank() && total.unitText != recorded?.energyUnitLabel() ->
                Text("Raw source unit text: \"${total.unitText}\"")
        }
        normalized?.let { value ->
            Text("≈ ${value.toTrimmedString()} Wh (normalized from the recorded unit).")
        }
    }
}

private fun measureUnitLabel(measure: EnergyDailyMeasure): String {
    val recorded = measure.original.recordedUnit
    val rawText = measure.original.unitText?.takeIf { it.isNotBlank() }
    return recorded?.energyUnitLabel() ?: rawText ?: "unit not recorded/unknown"
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

private const val UNIT_UNKNOWN_LABEL = "unit not recorded/unknown"
private const val DECIMAL_SCALE = 100L

private fun EnergyUnit?.energyUnitLabel(): String = when (this) {
    EnergyUnit.WH -> "Wh"
    EnergyUnit.KWH -> "kWh"
    null -> UNIT_UNKNOWN_LABEL
}

private fun DailyTotalSource.sourceLabel(): String = when (this) {
    DailyTotalSource.LG_FINALIZED -> "LG finalized daily total"
    DailyTotalSource.LOCAL_INTERVAL_SUM -> "Local interval-sum daily total"
}

/** Fixed scale (2 decimals), trailing zeros trimmed; never scientific notation. */
private fun Double.toTrimmedString(): String {
    if (!isFinite()) return "n/a"
    val negative = this < 0
    val scaled = (abs(this) * DECIMAL_SCALE).roundToLong()
    val intPart = scaled / DECIMAL_SCALE
    val frac = (scaled % DECIMAL_SCALE).toInt()
    val text = buildString {
        append(intPart)
        if (frac != 0) {
            append('.')
            var digits = frac.toString().padStart(2, '0')
            while (digits.endsWith('0')) digits = digits.dropLast(1)
            append(digits)
        }
    }
    return if (negative && (intPart > 0 || frac != 0)) "-$text" else text
}

private fun todayIn(timeZoneId: String): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.of(timeZoneId)).date

private fun LocalDate.dayOfMonthLabel(): String {
    val first = LocalDate(year, month, 1)
    return ((toEpochDays() - first.toEpochDays()).toInt() + 1).toString()
}

private fun LocalDate.shortDate(): String = "${monthName()} ${dayOfMonthNumber()}"

private fun LocalDate.longDate(): String = "${dayOfWeekName()}, ${shortDate()}, $year"

private fun LocalDate.dayOfWeekName(): String = DAY_NAMES[dayOfWeek.ordinal]

private fun LocalDate.monthName(): String = MONTH_NAMES[month.ordinal]

private fun LocalDate.dayOfMonthNumber(): Int {
    val first = LocalDate(year, month, 1)
    return (toEpochDays() - first.toEpochDays()).toInt() + 1
}

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
