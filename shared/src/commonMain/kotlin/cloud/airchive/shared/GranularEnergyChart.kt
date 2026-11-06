package cloud.airchive.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.data.energy.EnergyDaySummary
import cloud.airchive.shared.data.energy.EnergyHistoryPresentation
import cloud.airchive.shared.data.energy.EnergyPeriodType
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal data class EnergyBucket(
    val start: Long,
    val end: Long,
    val wh: Double?,
    val usable: Int,
    val expected: Int,
) {
    val complete: Boolean get() = expected == ((end - start) / 300_000L).toInt() && usable == expected
}

internal fun energyBuckets(days: List<EnergyDaySummary>, minutes: Int): List<EnergyBucket> {
    require(minutes >= 5 && minutes % 5 == 0 && 1440 % minutes == 0)
    val slots = minutes / 5
    return days.flatMap { day ->
        val points = day.points.associateBy { it.slotIndex }
        val daySlots = ((day.dayEndEpochMillis - day.dayStartEpochMillis) / 300_000L).toInt()
        (0 until daySlots step slots).map { start ->
            val end = minOf(start + slots, daySlots)
            val values = (start until end).mapNotNull { points[it]?.takeIf { p -> p.usable }?.energyValueWh }
            EnergyBucket(
                start = day.dayStartEpochMillis + start * 300_000L,
                end = day.dayStartEpochMillis + end * 300_000L,
                wh = values.takeIf { it.isNotEmpty() }?.sum(),
                usable = values.size,
                expected = (day.expectedSlotCount - start).coerceIn(0, end - start),
            )
        }
    }
}

internal data class EnergyViewport(val start: Float, val count: Float) {
    fun constrained(total: Int): EnergyViewport {
        val visible = count.coerceIn(minOf(5, total).toFloat(), total.coerceAtLeast(1).toFloat())
        return EnergyViewport(start.coerceIn(0f, (total - visible).coerceAtLeast(0f)), visible)
    }

    fun transform(total: Int, anchor: Float, zoom: Float, panFraction: Float): EnergyViewport {
        val nextCount = (count / zoom.coerceAtLeast(.01f)).coerceIn(minOf(5, total).toFloat(), total.toFloat())
        return EnergyViewport(start + anchor * (count - nextCount) - panFraction * nextCount, nextCount).constrained(total)
    }
}

internal fun energyAxisStep(maxWh: Double): Double {
    val raw = maxWh.coerceAtLeast(.001) / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
}

internal fun energyTimeTickStride(visibleBuckets: Float, labelCapacity: Int): Int =
    ceil(visibleBuckets / labelCapacity.coerceAtLeast(1)).toInt().coerceAtLeast(1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GranularEnergyChart(presentation: EnergyHistoryPresentation) {
    val choices = if (presentation.periodType == EnergyPeriodType.DAY) listOf(5, 15, 30, 60)
        else listOf(60, 360, 1440)
    var resolution by remember(presentation.periodType) { mutableStateOf(choices.lastIndex.toFloat()) }
    val minutes = choices[resolution.roundToInt().coerceIn(choices.indices)]
    val buckets = remember(presentation.days, minutes) { energyBuckets(presentation.days, minutes) }
    var selected by remember(presentation.period, minutes) { mutableStateOf<Int?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val warning = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxWh = buckets.mapNotNull { it.wh }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val axisStep = energyAxisStep(maxWh)
    val axisMax = axisStep * 4
    val zoomEnabled = minutes in listOf(5, 15, 30)
    var autoZoom by remember { mutableStateOf(false) }
    var viewport by remember(presentation.period, presentation.days, minutes) {
        mutableStateOf(EnergyViewport(0f, buckets.size.coerceAtLeast(1).toFloat()))
    }
    val animatedStart by animateFloatAsState(viewport.start, tween(if (autoZoom) 300 else 0))
    val animatedCount by animateFloatAsState(viewport.count, tween(if (autoZoom) 300 else 0))
    val visible = EnergyViewport(animatedStart, animatedCount).constrained(buckets.size.coerceAtLeast(1))
    val currentVisible by rememberUpdatedState(visible)
    val currentViewport by rememberUpdatedState(viewport)
    fun select(index: Int) {
        selected = index
        if (zoomEnabled && autoZoom) {
            viewport = EnergyViewport(index - 2f, 5f).constrained(buckets.size)
        }
    }
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val density = LocalDensity.current
    val axisLabels = (0..4).map { tick ->
        val value = axisStep * tick
        if (value % 1.0 == 0.0) value.toLong().toString() else ((value * 100).roundToInt() / 100.0).toString()
    }
    val axisLeft = with(density) {
        (axisLabels.maxOf { measurer.measure(it, axisStyle).size.width }.toDp() + 6.dp)
    }
    fun label(bucket: EnergyBucket): String {
        val zone = TimeZone.of(presentation.timeZoneId)
        val start = Instant.fromEpochMilliseconds(bucket.start).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(bucket.end).toLocalDateTime(zone)
        return "$start – ${end.time}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Granularity · ${if (minutes < 60) "$minutes minutes" else if (minutes == 1440) "1 day" else if (minutes == 60) "1 hour" else "${minutes / 60} hours"}",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (zoomEnabled) {
                Text("Auto zoom", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = autoZoom, onCheckedChange = { autoZoom = it },
                    modifier = Modifier.graphicsLayer { scaleX = .8f; scaleY = .8f }
                        .semantics { contentDescription = "Auto zoom. Off uses manual pinch and pan." })
            }
        }
        EnergyGranularitySlider(resolution, choices.lastIndex) { resolution = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (choices.first() == 5) "5 minutes" else "1 hour", style = MaterialTheme.typography.labelSmall)
            Text(if (choices.last() == 60) "1 hour" else "1 day", style = MaterialTheme.typography.labelSmall)
        }
        if (presentation.periodType == EnergyPeriodType.DAY && minutes == 60) {
            presentation.days.firstOrNull()?.let { HourlyEnergyChart(it, presentation.nowEpochMillis) }
            return@Column
        }
        if (minutes == 1440) {
            val colors = EnergyLegendColors(MaterialTheme.colorScheme)
            EnergyLegend(colors, intervalDay = false)
            DayBars(presentation.days, colors)
            return@Column
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sampled energy (Wh)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (zoomEnabled) {
                TextButton(onClick = { viewport = EnergyViewport(0f, buckets.size.toFloat()) },
                    enabled = viewport.count < buckets.size) {
                    Text("Reset zoom", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(212.dp)
            .pointerInput(buckets, zoomEnabled, autoZoom, axisLeft) {
                if (zoomEnabled && !autoZoom) awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragged = false
                    var travel = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed && it.previousPressed }
                        val left = axisLeft.toPx()
                        val plotWidth = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
                        if (active.size >= 2) {
                            val previousSpan = abs(active[0].previousPosition.x - active[1].previousPosition.x)
                            val span = abs(active[0].position.x - active[1].position.x)
                            if (previousSpan > viewConfiguration.touchSlop && span > 0f) {
                                val center = (active[0].position.x + active[1].position.x) / 2
                                val previousCenter = (active[0].previousPosition.x + active[1].previousPosition.x) / 2
                                viewport = currentViewport.transform(buckets.size,
                                    ((previousCenter - left) / plotWidth).coerceIn(0f, 1f), span / previousSpan,
                                    (center - previousCenter) / plotWidth)
                                dragged = true
                                event.changes.forEach { it.consume() }
                            }
                        } else if (active.size == 1) {
                            val change = active.first()
                            val delta = change.position - change.previousPosition
                            travel += delta
                            if (!dragged && (change.isConsumed || (abs(travel.y) > viewConfiguration.touchSlop && abs(travel.y) > abs(travel.x)))) {
                                break
                            }
                            if (dragged || abs(travel.x) > viewConfiguration.touchSlop) {
                                dragged = true
                                viewport = currentViewport.transform(buckets.size, 0f, 1f, delta.x / plotWidth)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                }
            }
            .pointerInput(buckets, autoZoom, zoomEnabled, axisLeft) {
                detectTapGestures { tap ->
                    if (buckets.isNotEmpty() && tap.x >= axisLeft.toPx() && tap.y <= 180.dp.toPx()) {
                        val plotWidth = (size.width - (axisLeft + 8.dp).toPx()).coerceAtLeast(1f)
                        select((currentVisible.start + (tap.x - axisLeft.toPx()) / plotWidth * currentVisible.count)
                            .toInt().coerceIn(buckets.indices))
                    }
                }
            }.semantics {
                role = Role.Image
                stateDescription = "Showing ${visible.count.roundToInt()} of ${buckets.size} buckets, starting at ${visible.start.roundToInt()}${selected?.let { ", selected bucket $it" } ?: ""}"
                contentDescription = "Energy chart, ${buckets.size} time buckets, $minutes minutes each. Y axis 0 to $axisMax Wh. Missing readings remain gaps. Exact selected values follow the chart."
                customActions = buildList {
                    if (buckets.isNotEmpty()) {
                        add(CustomAccessibilityAction("Next time bucket") { select(((selected ?: -1) + 1).coerceAtMost(buckets.lastIndex)); true })
                        add(CustomAccessibilityAction("Previous time bucket") { select(((selected ?: buckets.size) - 1).coerceAtLeast(0)); true })
                    }
                    if (zoomEnabled) {
                        add(CustomAccessibilityAction("Zoom in") { viewport = viewport.transform(buckets.size, .5f, 2f, 0f); true })
                        add(CustomAccessibilityAction("Zoom out") { viewport = viewport.transform(buckets.size, .5f, .5f, 0f); true })
                        add(CustomAccessibilityAction("Pan earlier") { viewport = viewport.transform(buckets.size, 0f, 1f, .5f); true })
                        add(CustomAccessibilityAction("Pan later") { viewport = viewport.transform(buckets.size, 0f, 1f, -.5f); true })
                    }
                }
            }) {
            val left = axisLeft.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 180.dp.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            val width = plotWidth / visible.count
            val dense = width < 12.dp.toPx()
            fun point(index: Int, value: Double) = Offset(left + (index + .5f - visible.start) * width,
                bottom - (bottom - top) * (value / axisMax).toFloat())
            (0..4).forEach { tick ->
                val y = bottom - (bottom - top) * tick / 4
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
                val text = measurer.measure(axisLabels[tick], axisStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), y - text.size.height / 2))
            }
            clipRect(left, top, right, bottom) {
                val first = floor(visible.start).toInt().coerceAtLeast(0)
                val last = ceil(visible.start + visible.count).toInt().coerceAtMost(buckets.lastIndex)
                for (index in first..last) {
                    val bucket = buckets[index]
                    val value = bucket.wh ?: continue
                    val current = point(index, value)
                    val ink = if (bucket.complete) primary else warning
                    if (dense) {
                        val previous = buckets.getOrNull(index - 1)
                        if (previous?.wh != null && previous.complete && bucket.complete) {
                            val from = point(index - 1, previous.wh)
                            val middle = (from.x + current.x) / 2
                            drawPath(Path().apply {
                                moveTo(from.x, from.y)
                                cubicTo(middle, from.y, middle, current.y, current.x, current.y)
                            }, ink, style = Stroke(2.dp.toPx()))
                        }
                        drawCircle(ink, 2.dp.toPx(), current)
                    } else {
                        val height = (bottom - current.y).coerceAtLeast(1.dp.toPx())
                        drawRect(ink, Offset(current.x - width * .3f, bottom - height), Size(width * .6f, height),
                            style = if (bucket.complete) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.dp.toPx()))
                    }
                }
                selected?.let { index ->
                    val x = point(index, 0.0).x
                    drawLine(primary, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                }
            }
            val zone = TimeZone.of(presentation.timeZoneId)
            val sampleLabel = if (presentation.periodType == EnergyPeriodType.DAY) "00:00" else "00/00 00h"
            val labelWidth = measurer.measure(sampleLabel, axisStyle).size.width + 16.dp.toPx()
            val stride = energyTimeTickStride(visible.count, (plotWidth / labelWidth).toInt())
            val firstTick = ceil(visible.start / stride).toInt() * stride
            for (index in firstTick..buckets.lastIndex step stride) {
                val x = point(index, 0.0).x
                if (x > right) break
                val date = Instant.fromEpochMilliseconds(buckets[index].start).toLocalDateTime(zone)
                val time = "${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}"
                val label = if (presentation.periodType == EnergyPeriodType.DAY) time else "${date.month.ordinal + 1}/${date.day} ${date.hour}h"
                val text = measurer.measure(label, axisStyle)
                drawLine(grid, Offset(x, bottom), Offset(x, bottom + 4.dp.toPx()), 1.dp.toPx())
                drawText(text, topLeft = Offset((x - text.size.width / 2).coerceIn(left, (right - text.size.width).coerceAtLeast(left)), bottom + 6.dp.toPx()))
            }
        }
        selected?.let { index -> buckets.getOrNull(index)?.let { bucket ->
            InstrumentDetail(label(bucket), bucket.wh?.let { "${(it * 100).roundToInt() / 100.0} Wh" } ?: if (bucket.expected == 0) "Future" else "No usable reading",
                "${bucket.usable} / ${bucket.expected} elapsed intervals measured${if (!bucket.complete) " · incomplete" else " · complete"}")
        } } ?: Text("Tap the chart to inspect a time bucket.", style = MaterialTheme.typography.bodySmall)
        Text("Amber marks incomplete buckets. Blank space means no usable reading; it is never measured zero. Dense data uses a smooth line; gaps remain disconnected. Stored daily totals stay separate in Daily records.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Keep native discrete input and accessibility, but animate the visual position between stops. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnergyGranularitySlider(value: Float, lastIndex: Int, onValueChange: (Float) -> Unit) {
    val position by animateFloatAsState(value, tween(260), label = "Granularity indicator")
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Slider(value = value, onValueChange = { onValueChange(it.roundToInt().toFloat()) },
            valueRange = 0f..lastIndex.toFloat(), steps = lastIndex - 1,
            thumb = {
                // Native thumb keeps the input geometry; the track draws the animated dot.
                Box(Modifier.size(10.dp))
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val y = size.height / 2
                    val x = size.width * position / lastIndex
                    drawLine(inactive, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
                    drawLine(primary, Offset(0f, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
                    (0..lastIndex).forEach { stop ->
                        drawCircle(if (stop <= position) primary else inactive, 2.dp.toPx(),
                            Offset(size.width * stop / lastIndex, y))
                    }
                    drawCircle(primary, 5.dp.toPx(), Offset(x, y))
                }
            },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Energy chart time bucket size" })
}
