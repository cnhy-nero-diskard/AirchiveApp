package cloud.airchive.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.SignInFailure
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
import cloud.airchive.shared.data.energy.normalizeToWh
import cloud.airchive.shared.data.sync.HistorySyncPresentation
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.onboarding.FirstRunOnboardingController
import cloud.airchive.shared.data.onboarding.FirstRunOnboardingStep
import cloud.airchive.shared.data.onboarding.OwnerSettingsController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeProviders
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun AirchiveApp(
    providers: FakeProviders? = null,
    onManualRefresh: (() -> Unit)? = null,
    ownerAccessController: OwnerAccessCoordinator? = null,
    ownerAuthConfigurationMessage: String? = null,
    onboardingController: FirstRunOnboardingController? = null,
    ownerSettingsController: OwnerSettingsController? = null,
    liveDeviceStateController: ThinQLiveStateController? = null,
    acCommandController: AcCommandController? = null,
    historySyncInProgress: StateFlow<Boolean>? = null,
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

                is DemoHomeState.Ready -> DemoRouteShell(
                    providers = activeProviders,
                    context = state.context,
                    onManualRefresh = onManualRefresh,
                    googleSignInIsSimulated = ownerAccessController == null,
                    ownerAccessController = ownerAccessController,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    onboardingController = onboardingController,
                    ownerSettingsController = ownerSettingsController,
                    liveDeviceStateController = liveDeviceStateController,
                    acCommandController = acCommandController,
                    historySyncInProgress = historySyncInProgress,
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
    val credential: ThinQCredentialState,
)

private data class DemoSummary(
    val history: HistorySnapshot,
    val latestObservation: Observation?,
    val latestDayTotals: List<DailyTotal>,
    val collectorHealth: CollectorHealth,
    val device: ThinQDevice,
    val profile: ThinQDeviceProfile,
)

/** Main destinations for Home, device, energy, history, and account settings. */
private enum class DemoRoute { HOME, DEVICE, ENERGY, HISTORY, SETTINGS }

/**
 * Destination set shared by the demo route shell and the production history
 * graph so the vector destination glyphs are drawn in exactly one place.
 */
internal enum class ShellDestination { HOME, DEVICE, ENERGY, HISTORY, SETTINGS }

private fun DemoRoute.toShellDestination(): ShellDestination = when (this) {
    DemoRoute.HOME -> ShellDestination.HOME
    DemoRoute.DEVICE -> ShellDestination.DEVICE
    DemoRoute.ENERGY -> ShellDestination.ENERGY
    DemoRoute.HISTORY -> ShellDestination.HISTORY
    DemoRoute.SETTINGS -> ShellDestination.SETTINGS
}

/**
 * Main route shell for Home, Device, Energy, History, and Settings. Holds each
 * controller once so the same [ThinQLiveStateController] instance travels
 * Home<->Device and a later failed live read can still show its stale last
 * success. The bottom [NavigationBar] is text-labeled and stays clear of the
 * scrollable content via [Scaffold] insets/padding.
 */
@Composable
private fun DemoRouteShell(
    providers: FakeProviders,
    context: DemoHomeContext,
    onManualRefresh: (() -> Unit)?,
    googleSignInIsSimulated: Boolean,
    ownerAccessController: OwnerAccessCoordinator?,
    ownerAuthConfigurationMessage: String?,
    onboardingController: FirstRunOnboardingController?,
    ownerSettingsController: OwnerSettingsController?,
    liveDeviceStateController: ThinQLiveStateController?,
    acCommandController: AcCommandController?,
    historySyncInProgress: StateFlow<Boolean>?,
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
    // All Home sample/history data renders from this already-observed local
    // cache; the route shell performs no new Firestore or service reads.
    val history by remember(providers, context.partition) {
        providers.localHistory.observe(context.partition)
    }.collectAsState(initial = initialHistory)
    val credential by remember(providers) {
        providers.thinQCredentialStore.observe()
    }.collectAsState(initial = context.credential)
    val historySyncState = historySyncInProgress?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }
    val isHistorySyncing by historySyncState
    val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
    val latestDailyTotalDayStart = history.dailyTotals.maxOfOrNull { it.dayStartEpochMillis }
    val summary = DemoSummary(
        history = history,
        latestObservation = history.observations.maxWithOrNull(
            compareBy<Observation> { it.intervalStartEpochMillis }.thenBy { it.intervalEndEpochMillis },
        ),
        latestDayTotals = latestDailyTotalDayStart?.let { dayStart ->
            history.dailyTotals.filter { it.dayStartEpochMillis == dayStart }
        }.orEmpty(),
        collectorHealth = history.collectorHealth ?: CollectorHealth(status = CollectorStatus.UNKNOWN),
        device = context.device,
        profile = context.profile,
    )
    val activeOnboardingController = remember(providers, context.partition, ownerAccessController, onboardingController) {
            onboardingController ?: FirstRunOnboardingController(
            ownerAccess = ownerAccessController
                ?: OwnerAccessCoordinator(FakeOwnerIdentity(), providers.telemetry),
            credentialStore = providers.thinQCredentialStore,
            credentialVerifier = FakeThinQCredentialVerifier(context.device.deviceId),
            telemetry = providers.telemetry,
            local = providers.localHistory,
            firebaseProjectId = context.partition.firebaseProjectId,
            configuredDeviceId = context.device.deviceId,
            demoOnly = true,
        )
    }
    val onboardingState by activeOnboardingController.state.collectAsState()
    val activeSettingsController = remember(providers, context.partition, ownerSettingsController) {
        ownerSettingsController ?: OwnerSettingsController(
            identity = providers.ownerIdentity,
            credentialStore = providers.thinQCredentialStore,
            credentialVerifier = FakeThinQCredentialVerifier(context.device.deviceId),
            local = providers.localHistory,
            firebaseProjectId = context.partition.firebaseProjectId,
            configuredDeviceId = context.device.deviceId,
        )
    }
    val activeLiveDeviceStateController = remember(providers, context.device.deviceId, liveDeviceStateController) {
        // Default the visible demo to its labeled in-memory fake. A future
        // production graph can explicitly provide the Ktor-backed controller;
        // the demo never silently falls through to a live service.
        liveDeviceStateController ?: ThinQLiveStateController(
            thinQ = providers.thinQ,
            configuredDeviceId = context.device.deviceId,
            // The default graph uses its explicitly simulated fixture profile.
            // A real graph must supply a controller tied to a verified profile.
            hasVerifiedProfile = { context.profile.deviceId == context.device.deviceId },
        )
    }
    val activeAcCommandController = remember(providers, context.device.deviceId, acCommandController) {
        acCommandController ?: AcCommandController(
            thinQ = providers.thinQ,
            configuredDeviceId = context.device.deviceId,
        )
    }
    val liveDeviceStateIsSimulated = liveDeviceStateController == null

    var route by remember { mutableStateOf(DemoRoute.HOME) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            DemoBottomNavigation(current = route, onSelect = { route = it })
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (route) {
                DemoRoute.HOME -> DemoHome(
                    summary = summary,
                    historySync = presentHistorySync(history, nowEpochMillis),
                    isHistorySyncing = isHistorySyncing,
                    nowEpochMillis = nowEpochMillis,
                    onManualRefresh = onManualRefresh,
                    googleSignInIsSimulated = googleSignInIsSimulated,
                    onboardingController = activeOnboardingController,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    onOpenDevice = { route = DemoRoute.DEVICE },
                )

                // Composing DeviceLiveStateCard only while this route is active
                // makes ThinQLiveStateController.onDeviceOpened() run on Device
                // entry (and again on re-entry), never on Home.
                DemoRoute.DEVICE -> DemoDeviceRoute(
                    device = context.device,
                    profile = context.profile,
                    liveDeviceStateController = activeLiveDeviceStateController,
                    acCommandController = activeAcCommandController,
                    liveDeviceStateIsSimulated = liveDeviceStateIsSimulated,
                    onBackToHome = { route = DemoRoute.HOME },
                )

                DemoRoute.ENERGY -> EnergyRoute(
                    snapshot = history,
                    telemetry = providers.telemetry,
                    local = providers.localHistory,
                    partition = context.partition,
                )

                DemoRoute.HISTORY -> HistoryHealthRoute(
                    snapshot = history,
                    nowEpochMillis = nowEpochMillis,
                    onRefresh = onManualRefresh,
                    isRefreshing = isHistorySyncing,
                )

                DemoRoute.SETTINGS -> SettingsRoute(
                    controller = activeSettingsController,
                    credentialState = credential,
                    ownerAccessState = onboardingState.ownerAccess,
                    demoOnly = onboardingState.demoOnly,
                    afterSettingsAction = { activeOnboardingController.restore() },
                    partition = context.partition,
                    history = history,
                    syncPresentation = presentHistorySync(history, nowEpochMillis),
                    isHistorySyncing = isHistorySyncing,
                    nowEpochMillis = nowEpochMillis,
                    onManualRefresh = onManualRefresh,
                )
            }
        }
    }
}

@Composable
private fun DemoBottomNavigation(
    current: DemoRoute,
    onSelect: (DemoRoute) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = current == DemoRoute.HOME,
            onClick = { onSelect(DemoRoute.HOME) },
            icon = { DestinationGlyph(DemoRoute.HOME.toShellDestination(), selected = current == DemoRoute.HOME) },
            label = { Text("Home") },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = current == DemoRoute.DEVICE,
            onClick = { onSelect(DemoRoute.DEVICE) },
            icon = { DestinationGlyph(DemoRoute.DEVICE.toShellDestination(), selected = current == DemoRoute.DEVICE) },
            label = { Text("Device") },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = current == DemoRoute.ENERGY,
            onClick = { onSelect(DemoRoute.ENERGY) },
            icon = { DestinationGlyph(DemoRoute.ENERGY.toShellDestination(), selected = current == DemoRoute.ENERGY) },
            label = { Text("Energy") },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = current == DemoRoute.HISTORY,
            onClick = { onSelect(DemoRoute.HISTORY) },
            icon = { DestinationGlyph(DemoRoute.HISTORY.toShellDestination(), selected = current == DemoRoute.HISTORY) },
            label = { Text("History") },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = current == DemoRoute.SETTINGS,
            onClick = { onSelect(DemoRoute.SETTINGS) },
            icon = { DestinationGlyph(DemoRoute.SETTINGS.toShellDestination(), selected = current == DemoRoute.SETTINGS) },
            label = { Text("Settings") },
            alwaysShowLabel = true,
        )
    }
}

/** Small vector-only destination glyphs; their adjacent visible labels name the controls. */
@Composable
internal fun DestinationGlyph(
    destination: ShellDestination,
    selected: Boolean,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val scale = size.minDimension / 24f
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (destination) {
            ShellDestination.HOME -> {
                val roof = Path().apply {
                    moveTo(3f * scale, 11f * scale)
                    lineTo(12f * scale, 3f * scale)
                    lineTo(21f * scale, 11f * scale)
                }
                val body = Path().apply {
                    moveTo(5f * scale, 10f * scale)
                    lineTo(5f * scale, 21f * scale)
                    lineTo(19f * scale, 21f * scale)
                    lineTo(19f * scale, 10f * scale)
                    moveTo(10f * scale, 21f * scale)
                    lineTo(10f * scale, 15f * scale)
                    lineTo(14f * scale, 15f * scale)
                    lineTo(14f * scale, 21f * scale)
                }
                drawPath(roof, color, style = stroke)
                drawPath(body, color, style = stroke)
            }

            ShellDestination.DEVICE -> {
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(3f * scale, 5f * scale),
                    size = androidx.compose.ui.geometry.Size(18f * scale, 14f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(7f * scale, 10f * scale),
                    end = androidx.compose.ui.geometry.Offset(17f * scale, 10f * scale),
                    strokeWidth = 1.5f * scale,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(7f * scale, 14f * scale),
                    end = androidx.compose.ui.geometry.Offset(17f * scale, 14f * scale),
                    strokeWidth = 1.5f * scale,
                    cap = StrokeCap.Round,
                )
            }

            ShellDestination.ENERGY -> {
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(3f * scale, 21f * scale),
                    end = androidx.compose.ui.geometry.Offset(21f * scale, 21f * scale),
                    strokeWidth = 2f * scale,
                    cap = StrokeCap.Round,
                )
                listOf(7f to 12f, 12f to 6f, 17f to 9f).forEach { (x, top) ->
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x * scale, top * scale),
                        size = androidx.compose.ui.geometry.Size(3f * scale, (21f - top) * scale),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * scale),
                    )
                }
            }

            ShellDestination.HISTORY -> {
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(5f * scale, 3f * scale),
                    size = androidx.compose.ui.geometry.Size(14f * scale, 18f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale),
                    style = stroke,
                )
                listOf(8f, 12f, 16f).forEach { y ->
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(8f * scale, y * scale),
                        end = androidx.compose.ui.geometry.Offset(16f * scale, y * scale),
                        strokeWidth = 1.5f * scale,
                        cap = StrokeCap.Round,
                    )
                }
            }

            ShellDestination.SETTINGS -> {
                val center = androidx.compose.ui.geometry.Offset(12f * scale, 12f * scale)
                drawCircle(
                    color = color,
                    radius = 5f * scale,
                    center = center,
                    style = stroke,
                )
                val spokes = listOf(
                    androidx.compose.ui.geometry.Offset(12f, 2f) to androidx.compose.ui.geometry.Offset(12f, 5f),
                    androidx.compose.ui.geometry.Offset(12f, 19f) to androidx.compose.ui.geometry.Offset(12f, 22f),
                    androidx.compose.ui.geometry.Offset(2f, 12f) to androidx.compose.ui.geometry.Offset(5f, 12f),
                    androidx.compose.ui.geometry.Offset(19f, 12f) to androidx.compose.ui.geometry.Offset(22f, 12f),
                    androidx.compose.ui.geometry.Offset(4.9f, 4.9f) to androidx.compose.ui.geometry.Offset(7f, 7f),
                    androidx.compose.ui.geometry.Offset(17f, 17f) to androidx.compose.ui.geometry.Offset(19.1f, 19.1f),
                    androidx.compose.ui.geometry.Offset(17f, 7f) to androidx.compose.ui.geometry.Offset(19.1f, 4.9f),
                    androidx.compose.ui.geometry.Offset(4.9f, 19.1f) to androidx.compose.ui.geometry.Offset(7f, 17f),
                )
                spokes.forEach { (start, end) ->
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(start.x * scale, start.y * scale),
                        end = androidx.compose.ui.geometry.Offset(end.x * scale, end.y * scale),
                        strokeWidth = 2f * scale,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
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

/**
 * Home route. Leads with the configured AC and an actionable sample/history
 * summary read purely from the local [HistorySnapshot] cache: the latest
 * sampled interval end (with its unit/quality), the daily total source/unit,
 * a collector-health summary, and a clearly sized action into Device/quick
 * controls. The last history-sync time below stays distinct from sample data.
 */
@Composable
private fun DemoHome(
    summary: DemoSummary,
    historySync: HistorySyncPresentation,
    isHistorySyncing: Boolean,
    nowEpochMillis: Long,
    onManualRefresh: (() -> Unit)?,
    googleSignInIsSimulated: Boolean,
    onboardingController: FirstRunOnboardingController,
    ownerAuthConfigurationMessage: String?,
    onOpenDevice: () -> Unit,
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
        Text(
            "Airchive",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("Personal AC dashboard", style = MaterialTheme.typography.titleMedium)

        SummaryCard("Configured AC") {
            Text(summary.device.alias, style = MaterialTheme.typography.titleMedium)
            summary.device.modelName?.let { Text("Model: $it") }
            Text("Device ID: ${summary.device.deviceId}")
            Text(if (summary.device.online) "Reported online by the collector." else "Reported offline by the collector.")
            Text(
                "Profile supports: ${summary.profile.controls.supportedControlNames()}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SummaryCard("Energy snapshot · simulated") {
            Text("Based on ${summary.history.observations.size} cached observations.", style = MaterialTheme.typography.bodyMedium)
            val observation = summary.latestObservation
            when {
                observation == null -> Text("No cached sample is available yet.")
                observation.quality == ObservationQuality.ANOMALOUS ->
                    Text("Latest sample is flagged anomalous; its value is excluded from usable energy.")
                observation.energySourceFailure?.ok == false ||
                    (observation.energySourceFailure == null && observation.sourceFailure?.startsWith("energy:", ignoreCase = true) == true)
                -> Text("The energy read failed for the latest interval; it is shown as a gap, not zero.")
                observation.energyValue == null ->
                    Text("The latest interval has no usable energy value; it is shown as a gap.")
                observation.recordedUnit == null -> {
                    val sourceValue = observation.intervalValue ?: observation.energyValue.toString()
                    val rawUnit = observation.unitText?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    Text("Source value $sourceValue$rawUnit · unit not recorded/unknown; excluded from totals.")
                }
                observation.isUsableEnergySample() -> {
                    val valueText = observation.intervalValue ?: observation.energyValue.toString()
                    Text("$valueText ${observation.recordedUnit.label()}", style = MaterialTheme.typography.titleMedium)
                }
                else -> Text("Latest sample is unavailable for energy totals.")
            }
            observation?.let { latest ->
                Text("Five-minute sampled energy · not instantaneous wattage.")
                Text(
                    "Latest sample interval ended " +
                        "${Instant.fromEpochMilliseconds(latest.intervalEndEpochMillis)} UTC.",
                )
                Text("Sample quality: ${latest.quality.label()}.")
                latest.intervalStatus?.let { Text("Interval status: $it.") }
                Text("This sampled timestamp is independent of the history sync time shown below.")
            }
        }

        if (summary.latestDayTotals.isNotEmpty()) {
            SummaryCard("Daily totals · simulated") {
                summary.latestDayTotals.forEach { total ->
                    val totalUnit = total.recordedUnit?.label()
                        ?: total.unitText?.takeIf { it.isNotBlank() }
                        ?: "unit not recorded/unknown"
                    Text("${total.energyValue} $totalUnit · ${total.source.label()} · ${if (total.finalized) "finalized" else "not finalized"}.")
                    total.localDate?.let { Text("Local day: $it.") }
                    if (total.recordedUnit == null && !total.unitText.isNullOrBlank()) {
                        Text("Source unit text: \"${total.unitText}\"; no conversion is inferred.")
                    }
                }
                Text("Daily totals stay distinct from the sum of sampled intervals.")
            }
        }

        SummaryCard("Collector health · simulated") {
            Text("${summary.collectorHealth.status.label()} status.")
            summary.collectorHealth.lastSuccessEpochMillis?.let { last ->
                Text("Last collector success: ${last.epochAgeLabel(nowEpochMillis)} ago.")
            }
            summary.collectorHealth.lastErrorClass?.let { Text("Last failure class: $it.") }
            (summary.collectorHealth.consecutiveFailures ?: 0).takeIf { it > 0 }?.let { failures ->
                Text("$failures consecutive failures.")
            }
            Text("Health is cached sample data; it does not describe a live collector connection.")
        }

        SummaryCard("Device") {
            Text("Open the configured AC's state and quick controls.")
            Button(
                onClick = onOpenDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Open device & quick controls")
            }
        }

        ownerAuthConfigurationMessage?.let { configurationMessage ->
            SummaryCard("Firebase setup") {
                Text(configurationMessage)
            }
        }
        FirstRunOnboardingCard(
            controller = onboardingController,
            googleSignInIsSimulated = googleSignInIsSimulated,
        )

        SummaryCard(if (historySync.hasCachedHistory) "History sync" else "History setup") {
            Text(
                if (isHistorySyncing) {
                    "Syncing Airchive history… Cached rows remain visible. ${historySync.message}"
                } else {
                    historySync.message
                },
                modifier = if (isHistorySyncing || historySync.shouldAnnounceUpdate) {
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
                Button(
                    enabled = !isHistorySyncing,
                    onClick = refresh,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isHistorySyncing) "Syncing history…" else historySync.retryLabel)
                }
            }
        }

        SummaryCard("Demo mode") {
            Text("Energy history, AC state, and collector health on this screen use local demo fixtures.")
            Text("They are not live readings. The ThinQ credential store is fake and contains no real token.")
        }

    }
}

/**
 * Device route. Hosts the existing [DeviceLiveStateCard] so its entry read runs
 * only when this route is opened; the card separates the exact live-read
 * timestamp from Home's sample/history timestamps. Also presents the
 * profile-derived supported-controls summary without implying commands work.
 */
@Composable
private fun DemoDeviceRoute(
    device: ThinQDevice,
    profile: ThinQDeviceProfile,
    liveDeviceStateController: ThinQLiveStateController,
    acCommandController: AcCommandController,
    liveDeviceStateIsSimulated: Boolean,
    onBackToHome: () -> Unit,
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
        Text("Device", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(device.alias, style = MaterialTheme.typography.titleMedium)

        DeviceLiveStateCard(
            controller = liveDeviceStateController,
            device = device,
            profile = profile,
            simulated = liveDeviceStateIsSimulated,
        )

        DeviceControlsCard(
            profile = profile,
            liveStateController = liveDeviceStateController,
            commandController = acCommandController,
            simulated = liveDeviceStateIsSimulated,
            onCommandOutcome = { outcome -> liveDeviceStateController.acceptCommandOutcome(outcome) },
        )

        Button(
            onClick = onBackToHome,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("Back to Home")
        }
    }
}

private suspend fun loadDemoHomeContext(providers: FakeProviders): DemoHomeContext {
    // The demo cache is keyed by its fixed fixture owner, but Google history
    // sign-in remains an explicit first-run action in the onboarding panel.
    val owner = FakeOwnerIdentity.DEFAULT_SESSION
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
        credential = providers.thinQCredentialStore.observe().first(),
    )
}

@Composable
private fun FirstRunOnboardingCard(
    controller: FirstRunOnboardingController,
    googleSignInIsSimulated: Boolean,
) {
    val state by controller.state.collectAsState()
    var token by remember(controller) { mutableStateOf("") }
    var country by remember(controller) { mutableStateOf(FirstRunOnboardingController.DEFAULT_COUNTRY) }
    var busy by remember(controller) { mutableStateOf(false) }
    var portalUnavailable by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(controller) {
        controller.restore()
    }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
            } finally {
                busy = false
            }
        }
    }

    val stepNumber = when (state.step) {
        FirstRunOnboardingStep.GOOGLE_ACCESS -> 1
        FirstRunOnboardingStep.THINQ_CREDENTIAL -> 2
        FirstRunOnboardingStep.INITIAL_HISTORY -> 3
        FirstRunOnboardingStep.COMPLETE -> 3
    }

    SummaryCard(if (state.demoOnly) "First-run setup · demo preview" else "First-run setup") {
        state.demoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Step $stepNumber of 3 · ${state.step.label()}.")
        state.safeErrorCode?.let { code ->
            Text(
                code.onboardingErrorMessage(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        when (state.step) {
            FirstRunOnboardingStep.GOOGLE_ACCESS -> {
                val message = when (val access = state.ownerAccess) {
                    OwnerAccessState.SignedOut ->
                        "Use Google for Airchive history. Sign-in alone does not grant Firestore access."
                    is OwnerAccessState.SignInFailed -> when (access.reason) {
                        SignInFailure.USER_CANCELLED -> "Google sign-in was cancelled. You can retry when ready."
                        SignInFailure.NETWORK -> "Google sign-in could not reach Firebase. Check the connection and retry."
                        SignInFailure.PLATFORM -> "Google sign-in is not configured for this build."
                        SignInFailure.UNKNOWN -> "Google sign-in failed. Retry or check the app setup."
                    }
                    is OwnerAccessState.AccessPending ->
                        "Signed in as ${access.session.uid}, but Firestore access is pending or denied. Ask the operator to enroll this exact UID, then retry."
                    is OwnerAccessState.AccessCheckFailed ->
                        "Could not verify Airchive access (${access.safeErrorCode}). Keep this session and retry the read."
                    is OwnerAccessState.Authorized ->
                        "History access verified for ${access.device.alias}. Signed-in UID: ${access.session.uid}."
                }
                Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.ownerAccess is OwnerAccessState.AccessPending ||
                    state.ownerAccess is OwnerAccessState.AccessCheckFailed
                ) {
                    Button(enabled = !busy, onClick = { launchAction(controller::retryAccess) }) {
                        Text(if (busy) "Checking access…" else "Retry history access")
                    }
                } else if (state.ownerAccess !is OwnerAccessState.Authorized) {
                    Button(enabled = !busy, onClick = { launchAction(controller::signIn) }) {
                        Text(
                            if (busy) "Signing in…"
                            else if (googleSignInIsSimulated) "Simulate Google access"
                            else "Continue with Google",
                        )
                    }
                }
            }

            FirstRunOnboardingStep.THINQ_CREDENTIAL -> {
                Text(
                    "Create a separate control-capable LG ThinQ PAT. Do not reuse the collector’s read-only token. " +
                        "The app will verify this account can read the configured AC identity and profile.",
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        portalUnavailable = try {
                            uriHandler.openUri(FirstRunOnboardingController.PAT_PORTAL_URL)
                            false
                        } catch (_: Exception) {
                            true
                        }
                    },
                ) {
                    Text("Open LG ThinQ PAT portal")
                }
                if (portalUnavailable) {
                    Text("Open this link in a browser: ${FirstRunOnboardingController.PAT_PORTAL_URL}")
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("ThinQ Personal Access Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("ThinQ account country") },
                    supportingText = { Text("Two-letter country code · prefills with PH") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                state.credentialSetupFailure?.let { failure ->
                    Text(
                        when (failure) {
                            is ThinQCredentialSetupResult.Failure.MalformedInput ->
                                "Enter a token and a two-letter country code."
                            is ThinQCredentialSetupResult.Failure.InvalidToken ->
                                "LG rejected this token. Check that it is current and retry; any prior token stays active."
                            is ThinQCredentialSetupResult.Failure.WrongAccount ->
                                "This ThinQ account does not include the configured AC. The prior token stays active."
                            is ThinQCredentialSetupResult.Failure.DeviceUnavailable ->
                                "The configured AC or its profile is unavailable. Retry when it is reachable."
                            is ThinQCredentialSetupResult.Failure.NetworkFailure ->
                                "LG could not be reached. This does not prove the token is invalid; retry when online."
                            is ThinQCredentialSetupResult.Failure.RemoteFailure ->
                                "LG could not verify this token right now. Retry without losing the prior token."
                            is ThinQCredentialSetupResult.Failure.StoreKeyUnavailable ->
                                "Protected credential storage is unavailable. Retry setup without replacing a saved token."
                            is ThinQCredentialSetupResult.Failure.StoreSaveFailure ->
                                "The verified token could not be stored. The prior token remains active."
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text("A successful read check does not prove LG will accept control commands.")
                Button(
                    enabled = !busy,
                    onClick = {
                        val submittedToken = token
                        token = ""
                        launchAction { controller.saveThinQCredential(submittedToken, country) }
                    },
                ) {
                    Text(
                        when {
                            busy -> "Verifying…"
                            state.demoOnly -> "Verify synthetic token"
                            else -> "Verify and save PAT"
                        },
                    )
                }
            }

            FirstRunOnboardingStep.INITIAL_HISTORY -> {
                Text("Fetch only the latest 24 hours of samples and up to 30 finalized daily totals.")
                state.firstLoadProgress?.let { progress ->
                    Text(
                        "${progress.observationsSeen} observations · " +
                            "${progress.observationPagesFetched} pages · " +
                            "${progress.dailyTotalsFetched ?: "—"} finalized totals",
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        launchAction {
                            controller.syncInitialHistory(Clock.System.now().toEpochMilliseconds())
                        }
                    },
                ) {
                    Text(if (busy) "Syncing history…" else "Sync history now")
                }
            }

            FirstRunOnboardingStep.COMPLETE -> {
                val history = state.history
                Text(
                    "Initial history is ready: ${history?.observations?.size ?: 0} samples and " +
                        "${history?.dailyTotals?.size ?: 0} finalized daily totals are cached.",
                )
            }
        }
    }
}

private fun FirstRunOnboardingStep.label(): String = when (this) {
    FirstRunOnboardingStep.GOOGLE_ACCESS -> "Google history access"
    FirstRunOnboardingStep.THINQ_CREDENTIAL -> "ThinQ credential"
    FirstRunOnboardingStep.INITIAL_HISTORY -> "Initial history sync"
    FirstRunOnboardingStep.COMPLETE -> "Setup complete"
}

private fun String.onboardingErrorMessage(): String = when (this) {
    "ACCESS_DENIED" -> "Airchive access is pending or denied. Keep this account and retry after UID enrollment."
    "OFFLINE" -> "The history service is offline. Retry when the device is connected."
    "REMOTE_FAILURE" -> "The history request failed. Cached rows remain available; retry when ready."
    else -> "Setup could not complete. Retry this step when ready."
}

@Composable
internal fun SummaryCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
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

/** Turns an event epoch timestamp into a human "… ago" label against [nowEpochMillis]. */
private fun Long.epochAgeLabel(nowEpochMillis: Long): String =
    (nowEpochMillis - this).coerceAtLeast(0L).ageLabel()

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

/** Home highlights only finite, normal interval energy with a known unit and no energy-read failure. */
private fun Observation.isUsableEnergySample(): Boolean {
    val value = energyValue ?: return false
    val unit = recordedUnit ?: return false
    if (!value.isFinite() || quality != ObservationQuality.NORMAL) return false
    if (energySourceFailure?.ok == false) return false
    if (energySourceFailure == null && sourceFailure?.startsWith("energy:", ignoreCase = true) == true) return false
    return normalizeToWh(value, unit) != null
}

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
