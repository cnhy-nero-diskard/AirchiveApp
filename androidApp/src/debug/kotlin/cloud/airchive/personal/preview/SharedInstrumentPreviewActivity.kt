package cloud.airchive.personal.preview

import android.os.Bundle
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.*
import cloud.airchive.shared.contract.history.*
import cloud.airchive.shared.contract.telemetry.*
import cloud.airchive.shared.contract.thinq.*
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import kotlin.time.Instant

/** Debug-only visual/interaction fixture: no Firebase, credentials, HTTP clients or real devices. */
class SharedInstrumentPreviewActivity : ComponentActivity() {
    var submittedCommands = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = if (intent.getBooleanExtra("dark", false)) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val interfacePreferences = AndroidInterfaceDetailPreferences(applicationContext, "airchive_preview_interface")
        if (savedInstanceState == null && intent.hasExtra("detail")) {
            interfacePreferences.select(InterfaceDetail.fromStoredValue(intent.getStringExtra("detail")))
        }
        setContent {
            InterfaceDetailProvider(interfacePreferences) {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val start = now - (now + 8 * 3_600_000L) % 86_400_000L
            val snapshot = remember { previewHistory(start, now) }
            val profile = remember { ThinQDeviceProfile("preview-ac", controls = AcControls(
                true, TemperatureCapability(18.0, 30.0, 1.0, TemperatureUnit.CELSIUS),
                listOf(AcOperatingMode.FAN, AcOperatingMode.DRY), listOf(FanSpeed.LOW, FanSpeed.HIGH),
            )) }
            var state by remember { mutableStateOf(ThinQLiveState("preview-ac", now - 20_000, true, 25.0, AcOperatingMode.FAN, FanSpeed.HIGH)) }
            val stale = intent.getBooleanExtra("stale", false)
            AirchiveTheme(dark = intent.getBooleanExtra("dark", false)) {
                var route by remember { mutableStateOf(intent.getIntExtra("route", 0)) }
                Scaffold(topBar = { if (route != 0) Text("Preview data · no device connection", modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    bottomBar = { InstrumentNavigation(route) { route = it } }, containerColor = MaterialTheme.colorScheme.background) { padding ->
                    if (route != 0) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                        InstrumentRoutePreview(route, snapshot, now, onHome = { route = 0 }, onSettings = { route = 4 })
                    } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row {
                            Text("Airchive", style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Preview data", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        SharedInstrumentPanel(snapshot, now,
                            live = if (stale) ThinQLiveRefreshState.Stale(state, cloud.airchive.shared.data.thinq.ThinQRefreshFailureKind.NETWORK, now)
                                else ThinQLiveRefreshState.Current(state, true),
                            profile = profile,
                            onCommand = { request ->
                                submittedCommands++
                                state = when(request) {
                                    is AcCommand.SetPower -> state.copy(powerOn = request.powerOn)
                                    is AcCommand.SetTargetTemperature -> state.copy(targetTemperature = request.value)
                                    is AcCommand.SetMode -> state.copy(mode = request.mode)
                                    is AcCommand.SetFanSpeed -> state.copy(fanSpeed = request.speed)
                                }
                            },
                        )
                        Text("History synced 2 min ago", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            }
        }
    }
}

private fun previewHistory(start: Long, now: Long): HistorySnapshot {
    val groups = listOf(120.0, 180.0, 240.0, null, 220.0, 280.0, 200.0, null)
    val observations = (0 until ((now - start) / 300_000L).toInt()).map { slot ->
        Observation("preview-$slot", start + slot * 300_000L, start + (slot + 1) * 300_000L,
            energyValue = groups[slot / 36]?.div(36), recordedUnit = EnergyUnit.WH)
    }
    return HistorySnapshot(HistoryPartition("preview", "preview-owner", "preview-ac"), observations,
        listOf(DailyTotal("yesterday", start - 86_400_000, 3.18, EnergyUnit.KWH, DailyTotalSource.LG_FINALIZED, true, java.time.Instant.ofEpochMilli(start - 86_400_000L).atZone(java.time.ZoneId.of("Asia/Manila")).toLocalDate().toString())),
        listOf(CoveredRange(start, now)), SyncMetadata(lastSuccessfulSyncEpochMillis = now - 120_000))
}
