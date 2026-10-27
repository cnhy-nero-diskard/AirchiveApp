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
import androidx.compose.ui.graphics.StrokeCap
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

/** Technical explanation remains available without preceding the everyday task. */
@Composable
fun InstrumentDisclosure(title: String, summary: String? = null, initiallyExpanded: Boolean = false,
                         inset: Dp = 20.dp, showDivider: Boolean = true, informational: Boolean = false,
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                summary?.takeIf { detail == InterfaceDetail.DETAILED }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val ink = MaterialTheme.colorScheme.primary
            Canvas(Modifier.padding(start = 12.dp).size(18.dp).clearAndSetSemantics {}) {
                val middle = if (expanded) .35f else .65f
                val edge = if (expanded) .65f else .35f
                drawLine(ink, Offset(size.width * .2f, size.height * edge), Offset(size.width * .5f, size.height * middle), 2.dp.toPx(), StrokeCap.Round)
                drawLine(ink, Offset(size.width * .5f, size.height * middle), Offset(size.width * .8f, size.height * edge), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (expanded) CompositionLocalProvider(LocalInstrumentGrouping provides true) { Column(content = content) }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
