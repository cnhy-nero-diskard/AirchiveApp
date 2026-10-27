package cloud.airchive.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation preference only; never changes data access or device authority. */
enum class InterfaceDetail(val storedValue: String) {
    SIMPLE("simple"), DETAILED("detailed");

    companion object {
        fun fromStoredValue(value: String?): InterfaceDetail = entries.firstOrNull { it.storedValue == value } ?: SIMPLE
    }
}

interface InterfaceDetailPreferences {
    val detail: StateFlow<InterfaceDetail>
    fun select(detail: InterfaceDetail)
}

/** Fallback for callers without a platform preference adapter. Android supplies durable storage. */
class InMemoryInterfaceDetailPreferences(initial: InterfaceDetail = InterfaceDetail.SIMPLE) : InterfaceDetailPreferences {
    private val state = MutableStateFlow(initial)
    override val detail = state.asStateFlow()
    override fun select(detail: InterfaceDetail) { state.value = detail }
}

internal val LocalInterfaceDetail = staticCompositionLocalOf { InterfaceDetail.SIMPLE }
private val LocalSelectInterfaceDetail = staticCompositionLocalOf<((InterfaceDetail) -> Unit)?> { null }

@Composable
fun InterfaceDetailProvider(preferences: InterfaceDetailPreferences, content: @Composable () -> Unit) {
    val detail by preferences.detail.collectAsState()
    CompositionLocalProvider(LocalInterfaceDetail provides detail, LocalSelectInterfaceDetail provides preferences::select,
        content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterfaceDetailSelector() {
    val selected = LocalInterfaceDetail.current
    val select = LocalSelectInterfaceDetail.current
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Interface detail", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InterfaceDetail.entries.forEach { detail ->
                FilterChip(selected = detail == selected, enabled = select != null,
                    onClick = { select?.invoke(detail) }, modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(if (detail == InterfaceDetail.SIMPLE) "Simple" else "Detailed") })
            }
        }
        Text(if (selected == InterfaceDetail.SIMPLE) "Essentials first. Open extra details when you need them."
            else "More explanations and technical details across the app.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Warnings, measurement units and confirmations stay visible in both modes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
