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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.SignInFailure
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
import cloud.airchive.shared.data.sync.HistorySyncPresentation
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.fakes.FakeProviders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
fun AirchiveApp(
    providers: FakeProviders? = null,
    onManualRefresh: (() -> Unit)? = null,
    ownerAccessController: OwnerAccessCoordinator? = null,
    ownerAuthConfigurationMessage: String? = null,
) {
    val activeProviders = providers ?: remember { FakeProviders() }
    var screenState by remember(activeProviders) {
        mutableStateOf<DemoHomeState>(DemoHomeState.Loading)
    }

    LaunchedEffect(activeProviders) {
        screenState = try {
            DemoHomeState.Ready(loadDemoHomeContext(activeProviders))
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

                is DemoHomeState.Ready -> DemoHomeContent(
                    providers = activeProviders,
                    context = state.context,
                    onManualRefresh = onManualRefresh,
                    ownerAccessController = ownerAccessController,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                )
            }
        }
    }
}

private sealed interface DemoHomeState {
    data object Loading : DemoHomeState
    data object Failed : DemoHomeState
    data class Ready(val context: DemoHomeContext) : DemoHomeState
}

private data class DemoHomeContext(
    val partition: HistoryPartition,
    val device: ThinQDevice,
    val profile: ThinQDeviceProfile,
    val liveState: ThinQLiveState,
    val credential: ThinQCredentialState,
)

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

@Composable
private fun DemoHomeContent(
    providers: FakeProviders,
    context: DemoHomeContext,
    onManualRefresh: (() -> Unit)?,
    ownerAccessController: OwnerAccessCoordinator?,
    ownerAuthConfigurationMessage: String?,
) {
    val initialHistory = remember(context.partition) {
        HistorySnapshot(
            partition = context.partition,
            observations = emptyList(),
            dailyTotals = emptyList(),
            coveredRanges = emptyList(),
            sync = SyncMetadata(),
        )
    }
    val history by remember(providers, context.partition) {
        providers.localHistory.observe(context.partition)
    }.collectAsState(initial = initialHistory)
    val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
    val summary = DemoSummary(
        history = history,
        latestObservation = history.observations.maxByOrNull { it.intervalStartEpochMillis },
        finalizedTotal = history.dailyTotals.maxByOrNull { it.dayStartEpochMillis },
        collectorHealth = history.collectorHealth ?: CollectorHealth(status = CollectorStatus.UNKNOWN),
        device = context.device,
        profile = context.profile,
        liveState = context.liveState,
        credential = context.credential,
    )
    DemoHome(
        summary = summary,
        historySync = presentHistorySync(history, nowEpochMillis),
        onManualRefresh = onManualRefresh,
        ownerAccessController = ownerAccessController,
        ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
    )
}

private suspend fun loadDemoHomeContext(providers: FakeProviders): DemoHomeContext {
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
    return DemoHomeContext(
        partition = partition,
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
private fun DemoHome(
    summary: DemoSummary,
    historySync: HistorySyncPresentation,
    onManualRefresh: (() -> Unit)?,
    ownerAccessController: OwnerAccessCoordinator?,
    ownerAuthConfigurationMessage: String?,
) {
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

            OwnerAccessCard(ownerAccessController, ownerAuthConfigurationMessage)

            SummaryCard(if (historySync.hasCachedHistory) "History sync" else "History setup") {
                Text(
                    historySync.message,
                    modifier = if (historySync.shouldAnnounceUpdate) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    },
                )
                Text(
                    historySync.lastSuccessfulAgeMillis?.let { "Last successful sync: ${it.ageLabel()} ago." }
                        ?: "Last successful sync: not yet.",
                )
                historySync.lastErrorAgeMillis?.let { Text("Last failed attempt: ${it.ageLabel()} ago.") }
                onManualRefresh?.let { refresh ->
                    Button(onClick = refresh) {
                        Text(historySync.retryLabel)
                    }
                }
            }

            SummaryCard("Demo mode") {
                Text("Energy history, AC state, and collector health on this screen use local demo fixtures.")
                Text("They are not live readings. The ThinQ credential store is fake and contains no real token.")
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
private fun OwnerAccessCard(
    controller: OwnerAccessCoordinator?,
    configurationMessage: String?,
) {
    if (controller == null) {
        SummaryCard("Google history access") {
            Text(
                configurationMessage
                    ?: "This demo build has no Google account connected. Configure Firebase client options to enable owner sign-in.",
            )
        }
        return
    }

    var state by remember(controller) { mutableStateOf<OwnerAccessState?>(null) }
    var busy by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller) {
        state = controller.restore()
    }

    fun launchAction(action: suspend () -> OwnerAccessState) {
        scope.launch {
            busy = true
            try {
                state = action()
            } finally {
                busy = false
            }
        }
    }

    SummaryCard("Google history access") {
        val current = state
        val message = when (current) {
            null -> "Restoring Firebase sign-in session…"
            OwnerAccessState.SignedOut ->
                "Sign in with Google to request history access. Sign-in alone does not grant Firestore access."
            is OwnerAccessState.SignInFailed -> when (current.reason) {
                SignInFailure.USER_CANCELLED -> "Google sign-in was cancelled. You can try again when ready."
                SignInFailure.NETWORK -> "Google sign-in could not reach Firebase. Check the connection and retry."
                SignInFailure.PLATFORM -> "Google sign-in is not configured for this app build."
                SignInFailure.UNKNOWN -> "Google sign-in failed. Retry or check the app setup."
            }
            is OwnerAccessState.AccessPending ->
                "Signed in as ${current.session.uid}, but Firestore access is pending or denied. Ask the operator to enroll this exact UID, then retry."
            is OwnerAccessState.AccessCheckFailed ->
                "Could not verify Airchive access (${current.safeErrorCode}). Your sign-in remains active; retry the read."
            is OwnerAccessState.Authorized ->
                "History access verified for ${current.device.alias}. Signed-in UID: ${current.session.uid}."
        }
        Text(
            message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        when (current) {
            null -> Unit
            OwnerAccessState.SignedOut,
            is OwnerAccessState.SignInFailed,
            -> Button(
                enabled = !busy,
                onClick = { launchAction(controller::signIn) },
            ) {
                Text(if (busy) "Signing in…" else "Continue with Google")
            }

            is OwnerAccessState.AccessPending,
            is OwnerAccessState.AccessCheckFailed,
            -> Button(
                enabled = !busy,
                onClick = { launchAction(controller::retryHistoryAccess) },
            ) {
                Text(if (busy) "Checking access…" else "Retry history access")
            }

            is OwnerAccessState.Authorized -> Unit
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

private fun Long.ageLabel(): String = when {
    this < 60_000L -> "less than a minute"
    this < 60 * 60_000L -> "${this / 60_000L} minutes"
    this < 24 * 60 * 60_000L -> "${this / (60 * 60_000L)} hours"
    else -> "${this / (24 * 60 * 60_000L)} days"
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
