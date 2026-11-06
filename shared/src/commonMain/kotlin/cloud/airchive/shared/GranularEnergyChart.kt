package cloud.airchive.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    fun label(bucket: EnergyBucket): String {
        val zone = TimeZone.of(presentation.timeZoneId)
        val start = Instant.fromEpochMilliseconds(bucket.start).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(bucket.end).toLocalDateTime(zone)
        return "$start – ${end.time}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Granularity · ${if (minutes < 60) "$minutes minutes" else if (minutes == 1440) "1 day" else if (minutes == 60) "1 hour" else "${minutes / 60} hours"}",
            style = MaterialTheme.typography.titleSmall)
        Slider(value = resolution, onValueChange = { resolution = it }, valueRange = 0f..choices.lastIndex.toFloat(),
            steps = choices.size - 2, modifier = Modifier.semantics { contentDescription = "Energy chart time bucket size" })
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
        Text("Sampled energy (Wh) · scale 0–${maxWh.roundToInt()} Wh", style = MaterialTheme.typography.labelMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val dense = maxWidth / buckets.size.coerceAtLeast(1) < 12.dp
            Canvas(Modifier.fillMaxWidth().height(180.dp)
                .pointerInput(buckets) {
                    detectTapGestures { tap ->
                        selected = (tap.x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                    }
                }.semantics {
                    role = Role.Image
                    contentDescription = "${if (dense) "Smooth line" else "Bar"} chart, ${buckets.size} time buckets, $minutes minutes each. Missing readings remain gaps. Exact selected values follow the chart."
                    customActions = listOf(
                        CustomAccessibilityAction("Next time bucket") { selected = ((selected ?: -1) + 1).coerceAtMost(buckets.lastIndex); true },
                        CustomAccessibilityAction("Previous time bucket") { selected = ((selected ?: buckets.size) - 1).coerceAtLeast(0); true },
                    )
                }) {
                val width = size.width / buckets.size.coerceAtLeast(1)
                fun point(index: Int, value: Double) = Offset((index + .5f) * width, size.height * (1 - value / maxWh).toFloat())
                (0..4).forEach { tick ->
                    val y = size.height * tick / 4
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                buckets.forEachIndexed { index, bucket ->
                    val value = bucket.wh ?: return@forEachIndexed
                    val current = point(index, value)
                    val ink = if (bucket.complete) primary else warning
                    if (dense) {
                        val previous = buckets.getOrNull(index - 1)
                        // Never bridge missing buckets or smooth an incomplete sum into a complete one.
                        if (previous?.wh != null && previous.complete && bucket.complete) {
                            val from = point(index - 1, previous.wh)
                            val middle = (from.x + current.x) / 2
                            val path = Path().apply {
                                moveTo(from.x, from.y)
                                cubicTo(middle, from.y, middle, current.y, current.x, current.y)
                            }
                            drawPath(path, ink, style = Stroke(2.dp.toPx()))
                        }
                        drawCircle(ink, 2.dp.toPx(), current)
                    } else {
                        val height = (size.height - current.y).coerceAtLeast(1.dp.toPx())
                        drawRect(ink, Offset(index * width + width * .2f, size.height - height), Size(width * .6f, height),
                            style = if (bucket.complete) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.dp.toPx()))
                    }
                }
                selected?.let { index ->
                    val x = (index + .5f) * width
                    drawLine(primary, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(buckets.firstOrNull()?.let { label(it).substringBefore(" –") } ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(buckets.lastOrNull()?.let { label(it).substringBefore(" –") } ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        }
        selected?.let { index -> buckets.getOrNull(index)?.let { bucket ->
            InstrumentDetail(label(bucket), bucket.wh?.let { "${(it * 100).roundToInt() / 100.0} Wh" } ?: if (bucket.expected == 0) "Future" else "No usable reading",
                "${bucket.usable} / ${bucket.expected} elapsed intervals measured${if (!bucket.complete) " · incomplete" else " · complete"}")
        } } ?: Text("Tap the chart to inspect a time bucket.", style = MaterialTheme.typography.bodySmall)
        Text("Amber marks incomplete buckets. Blank space means no usable reading; it is never measured zero. Dense data uses a smooth line; gaps remain disconnected. Stored daily totals stay separate in Daily records.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
