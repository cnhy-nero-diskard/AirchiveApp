package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.fakes.FakeProviders
import kotlinx.coroutines.flow.first

@Composable
fun AirchiveApp(providers: FakeProviders? = null) {
    val activeProviders = providers ?: remember { FakeProviders() }
    var screenState by remember(activeProviders) {
        mutableStateOf<DemoHomeState>(DemoHomeState.Loading)
    }

    LaunchedEffect(activeProviders) {
        screenState = try {
            DemoHomeState.Ready(loadDemoHome(activeProviders))
        } catch (_: Exception) {
            DemoHomeState.Failed
        }
    }

    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val state = screenState) {
                DemoHomeState.Loading -> DemoMessage("Loading the in-memory demo…")
                DemoHomeState.Failed -> DemoMessage(
                    title = "Demo data is unavailable",
                    message = "The local fake providers could not load their sample data. No account or network is required.",
                )

                is DemoHomeState.Ready -> DemoHome(state.summary)
            }
        }
    }
}

private sealed interface DemoHomeState {
    data object Loading : DemoHomeState
    data object Failed : DemoHomeState
    data class Ready(val summary: DemoSummary) : DemoHomeState
}

private data class DemoSummary(
    val history: HistorySnapshot,
    val latestObservation: Observation?,
    val finalizedTotal: DailyTotal?,
    val collectorHealth: CollectorHealth,
    val device: ThinQDevice,
    val profile: ThinQDeviceProfile,
    val liveState: ThinQLiveState,
    val credential: ThinQCredentialState,
)

private suspend fun loadDemoHome(providers: FakeProviders): DemoSummary {
    val owner = when (val result = providers.ownerIdentity.signIn()) {
        is SignInResult.Success -> result.session
        else -> error("The deterministic demo identity did not sign in.")
    }

    val device = providers.thinQ.listDevices().firstOrNull()
        ?: error("The deterministic demo has no device.")
    val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = owner.uid,
        deviceId = device.deviceId,
    )
    val observations = providers.telemetry.latestObservations(
        sinceEpochMillis = 0L,
        pageSize = 24,
    ).items
    val totals = providers.telemetry.latestFinalizedTotals(limit = 1)
    val collectorHealth = providers.telemetry.collectorHealth()

    providers.localHistory.upsertObservations(partition, observations)
    providers.localHistory.upsertDailyTotals(partition, totals)
    providers.localHistory.upsertCollectorHealth(partition, collectorHealth)
    if (observations.isNotEmpty()) {
        providers.localHistory.upsertCoveredRange(
            partition,
            CoveredRange(
                fromEpochMillis = observations.minOf { it.intervalStartEpochMillis },
                toEpochMillis = observations.maxOf { it.intervalEndEpochMillis },
            ),
        )
    }

    return DemoSummary(
        history = providers.localHistory.observe(partition).first(),
        latestObservation = observations.firstOrNull(),
        finalizedTotal = totals.firstOrNull(),
        collectorHealth = collectorHealth,
        device = device,
        profile = providers.thinQ.profile(device.deviceId),
        liveState = providers.thinQ.liveState(device.deviceId),
        credential = providers.thinQCredentialStore.observe().first(),
    )
}

@Composable
private fun DemoMessage(title: String, message: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DemoHome(summary: DemoSummary) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Airchive", style = MaterialTheme.typography.headlineMedium)
            Text("Personal AC dashboard", style = MaterialTheme.typography.titleMedium)

            SummaryCard("Demo mode") {
                Text("This screen uses in-memory sample data only.")
                Text("No Google account, Firebase project, network, or LG credential is connected.")
            }

            SummaryCard("AC state · simulated") {
                Text(summary.device.alias, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Power ${if (summary.liveState.powerOn) "on" else "off"} · " +
                        "${summary.liveState.targetTemperature ?: "—"} °C",
                )
                Text("Simulated ThinQ read, separate from Airchive sample time.")
                Text("Profile-gated controls in this fixture: ${summary.profile.controls.supportedControlNames()}.")
            }

            SummaryCard("Recent sampled energy") {
                Text(
                    summary.latestObservation?.let { observation ->
                        observation.energyValue?.let { value -> "${value} ${observation.recordedUnit.label()}" }
                    } ?: "No usable interval in this sample",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Five-minute sampled energy · not instantaneous wattage.")
                summary.latestObservation?.let { observation ->
                    Text("Sample quality: ${observation.quality.label()}.")
                    observation.intervalStatus?.let { Text("Interval status: $it.") }
                }
                Text("Cached observations: ${summary.history.observations.size}.")
            }

            summary.finalizedTotal?.let { total ->
                SummaryCard("Finalized daily total · simulated") {
                    Text("${total.energyValue} ${total.recordedUnit.label()}", style = MaterialTheme.typography.titleMedium)
                    Text("${total.source.label()} · finalized separately from sampled intervals.")
                }
            }

            SummaryCard("Collector health · simulated") {
                Text("${summary.collectorHealth.status.label()} status.")
                Text("Status is sample data; it does not describe a live collector connection.")
            }

            SummaryCard("Credentials") {
                Text(if (summary.credential.present) "ThinQ credential configured in fake store." else "No ThinQ credential configured.")
                Text("The demo never saves or displays a real token.")
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            },
        )
    }
}

private fun EnergyUnit?.label(): String = when (this) {
    EnergyUnit.WH -> "Wh"
    EnergyUnit.KWH -> "kWh"
    null -> "unit not recorded"
}

private fun DailyTotalSource.label(): String = when (this) {
    DailyTotalSource.LG_FINALIZED -> "LG finalized"
    DailyTotalSource.LOCAL_INTERVAL_SUM -> "Local interval sum"
}

private fun CollectorStatus.label(): String = name.lowercase().replace('_', ' ')

private fun ObservationQuality.label(): String = name.lowercase().replace('_', ' ')

private fun AcControls.supportedControlNames(): String = buildList {
    if (powerWritable) add("power")
    if (targetTemperature != null) add("temperature (${targetTemperature.unit.label()})")
    if (allowedModes.isNotEmpty()) add("mode")
    if (allowedFanSpeeds.isNotEmpty()) add("fan")
}.joinToString().ifEmpty { "none" }

private fun TemperatureUnit.label(): String = when (this) {
    TemperatureUnit.CELSIUS -> "°C"
    TemperatureUnit.FAHRENHEIT -> "°F"
}
