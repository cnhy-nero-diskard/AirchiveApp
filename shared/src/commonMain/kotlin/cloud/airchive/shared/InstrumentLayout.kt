package cloud.airchive.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal val LocalInstrumentGrouping = staticCompositionLocalOf { false }

/** Shared phone reading field; the shell owns navigation and system insets. */
@Composable
fun InstrumentPage(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstrumentPageHeading(title, description)
        content()
    }
}

@Composable
internal fun InstrumentPageHeading(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Related sections share one flat surface rather than nested cards. */
@Composable
fun InstrumentPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        CompositionLocalProvider(LocalInstrumentGrouping provides true) {
            Column(content = content)
        }
    }
}

@Composable
internal fun InstrumentBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalInstrumentGrouping.current) {
        Column(modifier) { content(); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    } else {
        Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

/** Health of what a disclosure row describes; the row's summary text always carries the same meaning in words. */
enum class InstrumentStatus { OK, ATTENTION, NEUTRAL }

/**
 * Decorative status mark. Shape, not color alone, separates the states (check, exclamation, dash),
 * and it is hidden from accessibility because the adjacent summary text states the condition.
 */
@Composable
internal fun InstrumentStatusGlyph(status: InstrumentStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = when (status) {
        InstrumentStatus.OK -> scheme.primary
        InstrumentStatus.ATTENTION -> scheme.error
        InstrumentStatus.NEUTRAL -> scheme.outline
    }
    val mark = scheme.onError
    Canvas(modifier.size(22.dp).clearAndSetSemantics {}) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val ringRadius = size.minDimension / 2 - stroke / 2
        when (status) {
            InstrumentStatus.OK -> {
                drawCircle(ink, ringRadius, center, style = Stroke(stroke))
                drawLine(ink, Offset(w * .3f, h * .52f), Offset(w * .45f, h * .66f), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * .45f, h * .66f), Offset(w * .72f, h * .36f), stroke, StrokeCap.Round)
            }
            InstrumentStatus.ATTENTION -> {
                drawCircle(ink, size.minDimension / 2, center)
                drawLine(mark, Offset(w * .5f, h * .28f), Offset(w * .5f, h * .56f), stroke, StrokeCap.Round)
                drawCircle(mark, stroke * .6f, Offset(w * .5f, h * .72f))
            }
            InstrumentStatus.NEUTRAL -> {
                drawCircle(ink, ringRadius, center, style = Stroke(stroke))
                drawLine(ink, Offset(w * .32f, h * .5f), Offset(w * .68f, h * .5f), stroke, StrokeCap.Round)
            }
        }
    }
}

/** Technical explanation remains available without preceding the everyday task. [status] adds a leading mark; it never changes expanded/collapsed semantics. */
@Composable
fun InstrumentDisclosure(title: String, summary: String? = null, initiallyExpanded: Boolean = false,
                         inset: Dp = 20.dp, showDivider: Boolean = true, informational: Boolean = false,
                         alwaysShowSummary: Boolean = false, status: InstrumentStatus? = null,
                         content: @Composable ColumnScope.() -> Unit) {
    val detail = LocalInterfaceDetail.current
    val openByDefault = initiallyExpanded || (informational && detail == InterfaceDetail.DETAILED)
    var expanded by remember(initiallyExpanded, detail) { mutableStateOf(openByDefault) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded },
            shape = if (inset == 0.dp) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }, contentPadding = PaddingValues(horizontal = inset, vertical = 12.dp)) {
            status?.let { InstrumentStatusGlyph(it, Modifier.padding(end = 14.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                summary?.takeIf { alwaysShowSummary || detail == InterfaceDetail.DETAILED }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            InstrumentChevron(expanded, Modifier.padding(start = 12.dp))
        }
        if (expanded) CompositionLocalProvider(LocalInstrumentGrouping provides true) { Column(content = content) }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Decorative disclosure glyph: points down when collapsed and up when expanded. */
@Composable
internal fun InstrumentChevron(expanded: Boolean, modifier: Modifier = Modifier,
                               ink: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(18.dp).clearAndSetSemantics {}) {
        val middle = if (expanded) .35f else .65f
        val edge = if (expanded) .65f else .35f
        drawLine(ink, Offset(size.width * .2f, size.height * edge), Offset(size.width * .5f, size.height * middle), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(size.width * .5f, size.height * middle), Offset(size.width * .8f, size.height * edge), 2.dp.toPx(), StrokeCap.Round)
    }
}

/** Stacked fields remain readable at large text sizes and with long identifiers. */
@Composable
internal fun InstrumentDetail(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun InstrumentDetailLine(text: String) {
    val split = text.indexOf(": ")
    if (split > 0) InstrumentDetail(text.substring(0, split), text.substring(split + 2))
    else Text(text, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 6.dp))
}
