package cloud.airchive.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.energy.normalizeToWh
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.data.onboarding.ProductionAccountConfirmStep
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.data.sync.HistorySyncPresentation
import cloud.airchive.shared.data.sync.ProductionHistoryCoordinator
import cloud.airchive.shared.data.sync.presentHistorySync
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Production owner graph for the Android app.
 *
 * This composition never references [cloud.airchive.shared.fakes.FakeProviders],
 * never creates a demo partition, and never falls back to simulated data. It
 * renders the real owner's cached [HistorySnapshot] first (so a durable Room
 * cache appears before any network work and survives restart/offline for the
 * same owner), reuses the existing read-only [EnergyRoute] and
 * [HistoryHealthRoute], shows real Google sign-in/retry actions, and keeps
 * ThinQ operations on their independent protected-credential graph.
 *
 * Every screen keys off the exact partition derived by [coordinator] from the
 * live owner session; when the owner signs out or switches accounts the
 * observation keys change with the session and stale async refreshes are
 * suppressed by the coordinator before they can surface.
 *
 * Missing Firebase configuration is handled by the caller (MainActivity): this
 * graph is only ever built with a ready configuration, so there is no fake
 * fallback here to construct.
 *
 * [nowProvider] is the single clock seam for this shell: production callers
 * leave the default system clock, while the isolated debug/test fixture injects
 * a controlled clock so freshness/age rendering is deterministic. It never
 * changes which ports are constructed.
 */
@Composable
fun ProductionAirchiveApp(
    coordinator: ProductionHistoryCoordinator,
    accountController: ProductionAccountController,
    onManualRefresh: (() -> Unit)? = null,
    ownerAuthConfigurationMessage: String? = null,
    thinQBindings: ProductionThinQBindings? = null,
    interfacePreferences: InterfaceDetailPreferences? = null,
    nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val preferences = remember(interfacePreferences) { interfacePreferences ?: InMemoryInterfaceDetailPreferences() }
    InterfaceDetailProvider(preferences) {
        AirchiveTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ProductionRouteHost(
                    coordinator = coordinator,
                    accountController = accountController,
                    onManualRefresh = onManualRefresh,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    thinQBindings = thinQBindings,
                    nowProvider = nowProvider,
                )
            }
        }
    }
}

/**
 * Actionable unconfigured state rendered when the nonsecret Firebase client
 * options are missing. Production never falls back to demo providers here — the
 * owner must configure the build options instead.
 */
@Composable
fun ProductionConfigBlockedScreen(message: String) {
    AirchiveTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()), contentAlignment = Alignment.TopCenter) {
                InstrumentPage("Airchive") {
                    SummaryCard("Owner sign-in needs configuration") {
                        Text(message, style = MaterialTheme.typography.bodyLarge)
                        Text("Configure the Firebase client options in config/airchive-mobile.properties, then reopen the app. This install does not fall back to demo data.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Main destinations for the production graph. */
private enum class ProductionRoute { HOME, DEVICE, ENERGY, HISTORY, SETTINGS }

private fun ProductionRoute.shellDestination(): ShellDestination = when (this) {
    ProductionRoute.HOME -> ShellDestination.HOME
    ProductionRoute.DEVICE -> ShellDestination.DEVICE
    ProductionRoute.ENERGY -> ShellDestination.ENERGY
    ProductionRoute.HISTORY -> ShellDestination.HISTORY
    ProductionRoute.SETTINGS -> ShellDestination.SETTINGS
}

private fun ProductionRoute.label(): String = when (this) {
    ProductionRoute.HOME -> "Home"
    ProductionRoute.DEVICE -> "Device"
    ProductionRoute.ENERGY -> "Energy"
    ProductionRoute.HISTORY -> "History"
    ProductionRoute.SETTINGS -> "Settings"
}

@Composable
private fun ProductionRouteHost(
    coordinator: ProductionHistoryCoordinator,
    accountController: ProductionAccountController,
    onManualRefresh: (() -> Unit)?,
    ownerAuthConfigurationMessage: String?,
    thinQBindings: ProductionThinQBindings?,
    nowProvider: () -> Long,
) {
    val session by coordinator.session.collectAsState()
    val isRefreshing by coordinator.isRefreshing.collectAsState()
    var rawAccessState by remember(coordinator, session?.uid) { mutableStateOf<OwnerAccessState>(OwnerAccessState.SignedOut) }
    val accessState = productionAccessForSession(rawAccessState, session)
    val partition = session?.let { coordinator.partitionFor(it) }

    val placeholderHistory = remember(coordinator) { coordinator.emptySnapshot() }
    val emptyCredentialState = remember(thinQBindings) {
        ThinQCredentialState(
            present = false,
            clientId = thinQBindings?.clientId.orEmpty(),
        )
    }
    val credentialState = if (thinQBindings == null) {
        emptyCredentialState
    } else {
        thinQBindings.credentialState.collectAsState(initial = emptyCredentialState).value
    }
    val historyFlow = remember(coordinator, partition) {
        partition?.let { coordinator.local.observe(it) } ?: flowOf(placeholderHistory)
    }
    val observedHistory by historyFlow.collectAsState(initial = placeholderHistory)
    // collectAsState may retain the previous flow's value until the new DAO
    // emits. Never render even one frame of a different owner's cache.
    val history = productionHistoryForPartition(observedHistory, partition, placeholderHistory)

    // Re-check the device-read authorization whenever the owner session changes.
    LaunchedEffect(coordinator, session?.uid) {
        rawAccessState = try {
            coordinator.access.restore()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Defensive only: restore() already classifies every failure into an
            // OwnerAccessState, so an unexpected throw just keeps the prior state.
            accessState
        }
    }

    // Bootstrap the initial 24h window as soon as the owner is authorized —
    // covering first sign-in, operator enrollment, and account switches. The
    // maintenance path (already covered) uses the gated foreground refresh;
    // MainActivity.onResume handles later resumes.
    val authorized = accessState is OwnerAccessState.Authorized
    LaunchedEffect(coordinator, partition, authorized) {
        if (authorized && partition != null) {
            val now = nowProvider()
            // Decide from the durable cache under the coordinator's lock, not
            // from a possibly still-empty Compose placeholder. A successful
            // access check must not bypass the ten-minute incremental gate.
            coordinator.onForeground(now)
        }
    }

    var route by remember { mutableStateOf(ProductionRoute.HOME) }
    val nowEpochMillis = nowProvider()
    val syncPresentation = presentHistorySync(history, nowEpochMillis)
    val sampledIdentity = (accessState as? OwnerAccessState.Authorized)?.device

    val afterSettingsAction: suspend () -> Unit = {
        val hasSavedCredential = try {
            thinQBindings?.credentialState?.first()?.present == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!hasSavedCredential) thinQBindings?.clearCachedDeviceProfile?.invoke()
        rawAccessState = try {
            coordinator.access.restore()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            accessState
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            ProductionBottomNavigation(current = route, onSelect = { route = it })
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (route) {
                ProductionRoute.HOME -> ProductionHome(
                    coordinator = coordinator,
                    history = history,
                    accessState = accessState,
                    onAccessChanged = { rawAccessState = it },
                    historySync = syncPresentation,
                    isHistorySyncing = isRefreshing,
                    nowEpochMillis = nowEpochMillis,
                    onManualRefresh = onManualRefresh,
                    onOpenDevice = { route = ProductionRoute.DEVICE },
                    onOpenSettings = { route = ProductionRoute.SETTINGS },
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    credentialState = credentialState,
                    thinQBindings = thinQBindings,
                    hasSession = session != null,
                    onOpenEnergy = { route = ProductionRoute.ENERGY },
                    nowProvider = nowProvider,
                )

                ProductionRoute.DEVICE -> if (partition != null) {
                    ProductionDeviceRoute(
                        history = history,
                        configuredDeviceId = coordinator.configuredDeviceId,
                        identity = sampledIdentity,
                        thinQBindings = thinQBindings,
                        credentialState = credentialState,
                        onBackToHome = { route = ProductionRoute.HOME },
                        onOpenSettings = { route = ProductionRoute.SETTINGS },
                    )
                } else {
                    ProductionSignedOutRoute(title = "Device")
                }

                ProductionRoute.ENERGY -> if (partition != null) {
                    key(partition) { EnergyRoute(
                        snapshot = history,
                        telemetry = coordinator.telemetry,
                        local = coordinator.local,
                        partition = partition,
                        remoteLoadEnabled = authorized,
                        loadRange = { from, to -> coordinator.loadHistoricalRange(partition, from, to) },
                    ) }
                } else {
                    ProductionSignedOutRoute(title = "Energy")
                }

                ProductionRoute.HISTORY -> HistoryHealthRoute(
                    snapshot = history,
                    nowEpochMillis = nowEpochMillis,
                    onRefresh = onManualRefresh,
                    isRefreshing = isRefreshing,
                )

                ProductionRoute.SETTINGS -> ProductionSettingsRoute(
                    accountController = accountController,
                    accessState = accessState,
                    session = session,
                    partition = partition,
                    history = history,
                    historySync = syncPresentation,
                    isHistorySyncing = isRefreshing,
                    nowEpochMillis = nowEpochMillis,
                    onManualRefresh = onManualRefresh,
                    afterSettingsAction = afterSettingsAction,
                    thinQBindings = thinQBindings,
                    credentialState = credentialState,
                    nowProvider = nowProvider,
                )
            }
        }
    }
}

@Composable
private fun ProductionBottomNavigation(
    current: ProductionRoute,
    onSelect: (ProductionRoute) -> Unit,
) {
    InstrumentNavigation(current.ordinal) { onSelect(ProductionRoute.entries[it]) }
}

@Composable
private fun ProductionSignedOutRoute(title: String) {
    InstrumentPage(title) {
        SummaryCard("Sign in to continue") {
            Text("Open Home to sign in with the enrolled Google account and load this AC's history.")
            Text("No other account's cached history is shown.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProductionHome(
    coordinator: ProductionHistoryCoordinator,
    history: HistorySnapshot,
    accessState: OwnerAccessState,
    onAccessChanged: (OwnerAccessState) -> Unit,
    historySync: HistorySyncPresentation,
    isHistorySyncing: Boolean,
    nowEpochMillis: Long,
    onManualRefresh: (() -> Unit)?,
    onOpenDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    ownerAuthConfigurationMessage: String?,
    credentialState: ThinQCredentialState,
    thinQBindings: ProductionThinQBindings?,
    hasSession: Boolean,
    onOpenEnergy: () -> Unit,
    nowProvider: () -> Long,
) {
    Column(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Airchive", modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (accessState !is OwnerAccessState.Authorized) {
            ProductionAccessCard(coordinator, accessState, onAccessChanged, nowProvider)
        }
        key(history.partition) {
            ProductionHomeInstrument(history, thinQBindings, credentialState, hasSession, onOpenEnergy, onOpenSettings,
                nowProvider = nowProvider)
        }
        Text(historySync.lastSuccessfulAgeMillis?.let { "History synced ${instrumentAge(it)} ago" }
            ?: "History has not synced yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isHistorySyncing || history.sync?.lastError != null || !historySync.hasCachedHistory) {
            Text(historySync.message, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        onManualRefresh?.let { refresh ->
            TextButton(shape = MaterialTheme.shapes.small, onClick = refresh, enabled = !isHistorySyncing) {
                Text(if (isHistorySyncing) "Refreshing history…" else "Refresh history")
            }
        }
        ownerAuthConfigurationMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable
private fun ProductionConfiguredAcCard(
    configuredDeviceId: String,
    identity: DeviceIdentity?,
) {
    SummaryCard(productionConfiguredAcTitle()) {
        Text(productionConfiguredAcTitle(), style = MaterialTheme.typography.titleMedium)
        Text("Device ID: $configuredDeviceId")
        productionSampledIdentityLines(identity).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
        if (identity == null) {
            Text(
                "Identity details appear once the owner's history access check reads the configured device.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProductionEnergySnapshotCard(
    history: HistorySnapshot,
    nowEpochMillis: Long,
) {
    SummaryCard("Energy snapshot") {
        Text("Based on ${history.observations.size} cached observations.", style = MaterialTheme.typography.bodyMedium)

        val observation = history.observations.maxWithOrNull(
            compareBy<Observation> { it.intervalStartEpochMillis }.thenBy { it.intervalEndEpochMillis },
        )
        when {
            observation == null -> Text("No cached sample is available yet.")
            observation.quality == ObservationQuality.ANOMALOUS ->
                Text("Latest sample is flagged anomalous; its value is excluded from usable energy.")
            observation.energySourceFailure?.ok == false ||
                (observation.energySourceFailure == null &&
                    observation.sourceFailure?.startsWith("energy:", ignoreCase = true) == true)
            -> Text("The energy read failed for the latest interval; it is shown as a gap, not zero.")
            observation.energyValue == null ->
                Text("The latest interval has no usable energy value; it is shown as a gap.")
            observation.recordedUnit == null -> {
                val sourceValue = observation.intervalValue ?: observation.energyValue.toString()
                val rawUnit = observation.unitText?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                Text("Source value $sourceValue$rawUnit · unit not recorded/unknown; excluded from totals.")
            }
            observation.productionIsUsableEnergySample() -> {
                val valueText = observation.intervalValue ?: observation.energyValue.toString()
                Text("$valueText ${observation.recordedUnit.productionEnergyUnitLabel()}", style = MaterialTheme.typography.titleMedium)
            }
            else -> Text("Latest sample is unavailable for energy totals.")
        }

        observation?.let { latest ->
            Text("Five-minute sampled energy · not instantaneous wattage.")
            Text(
                "Latest sample interval ended " +
                    "${Instant.fromEpochMilliseconds(latest.intervalEndEpochMillis)} UTC.",
            )
            Text("Sample quality: ${latest.quality.productionObservationQualityLabel()}.")
            latest.intervalStatus?.let { Text("Interval status: $it.") }
            Text("This sampled timestamp is independent of the history sync time shown below.")
        }

        val latestDayStart = history.dailyTotals.maxOfOrNull { it.dayStartEpochMillis }
        if (latestDayStart != null) {
            val latestTotals = history.dailyTotals.filter { it.dayStartEpochMillis == latestDayStart }
            Text("Latest day totals", style = MaterialTheme.typography.titleSmall)
            latestTotals.forEach { total ->
                val totalUnit = total.recordedUnit?.productionEnergyUnitLabel()
                    ?: total.unitText?.takeIf { it.isNotBlank() }
                    ?: "unit not recorded/unknown"
                Text(
                    "${total.energyValue} $totalUnit · ${total.source.productionDailyTotalSourceLabel()} · " +
                        (if (total.finalized) "finalized." else "not finalized."),
                )
                if (total.recordedUnit == null && !total.unitText.isNullOrBlank()) {
                    Text("Source unit text: \"${total.unitText}\"; no conversion is inferred.")
                }
            }
            Text("Daily totals stay distinct from the sum of sampled intervals.")
        }
    }
}

@Composable
private fun ProductionCollectorHealthCard(
    health: cloud.airchive.shared.contract.telemetry.CollectorHealth?,
    nowEpochMillis: Long,
) {
    SummaryCard("Collector health") {
        if (health == null) {
            Text("No collector health is cached yet; it appears after the first successful sync.")
            return@SummaryCard
        }
        Text("${health.status.productionCollectorStatusLabel()} status.")
        health.lastSuccessEpochMillis?.let { last ->
            Text("Last collector success: ${productionAgeLabel(nowEpochMillis - last)} ago.")
        }
        health.lastErrorClass?.let { Text("Last failure class: $it.") }
        (health.consecutiveFailures ?: 0).takeIf { it > 0 }?.let { failures ->
            Text("$failures consecutive failures.")
        }
        Text("Health is cached sample data; it does not describe a live collector connection.")
    }
}

@Composable
private fun ProductionAccessCard(
    coordinator: ProductionHistoryCoordinator,
    accessState: OwnerAccessState,
    onAccessChanged: (OwnerAccessState) -> Unit,
    nowProvider: () -> Long,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(coordinator) { mutableStateOf(false) }

    fun runAction(action: suspend () -> OwnerAccessState) {
        scope.launch {
            busy = true
            try {
                val checked = action()
                onAccessChanged(checked)
                if (checked is OwnerAccessState.Authorized) {
                    // Only an explicit sign-in/access-retry action may bypass
                    // a recent failed bootstrap, never an automatic resume.
                    coordinator.onAccessAuthorized(nowProvider())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Only a real owner session can carry an access-check failure.
                coordinator.session.value?.let { current ->
                    onAccessChanged(OwnerAccessState.AccessCheckFailed(current, "REMOTE_FAILURE"))
                }
            } finally {
                busy = false
            }
        }
    }

    SummaryCard("Airchive account & access") {
        Text(
            productionAccessMessage(accessState),
            modifier = if (accessState is OwnerAccessState.AccessPending ||
                accessState is OwnerAccessState.AccessCheckFailed ||
                accessState is OwnerAccessState.SignInFailed
            ) {
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                Modifier
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        when (accessState) {
            OwnerAccessState.SignedOut -> Button(shape = MaterialTheme.shapes.small,
                enabled = !busy,
                onClick = { runAction { coordinator.access.signIn() } },
            ) {
                Text(if (busy) "Signing in…" else "Continue with Google")
            }

            is OwnerAccessState.SignInFailed -> Button(shape = MaterialTheme.shapes.small,
                enabled = !busy,
                onClick = { runAction { coordinator.access.signIn() } },
            ) {
                Text(if (busy) "Signing in…" else "Retry Google sign-in")
            }

            is OwnerAccessState.AccessPending,
            is OwnerAccessState.AccessCheckFailed -> Button(shape = MaterialTheme.shapes.small,
                enabled = !busy,
                onClick = { runAction { coordinator.access.retryHistoryAccess() } },
            ) {
                Text(if (busy) "Checking access…" else "Retry history access")
            }

            is OwnerAccessState.Authorized -> {
                Text(
                    "Signing in and history authorization are separate matters: a Google session alone " +
                        "never grants Airchive history access.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "ThinQ uses a separate protected credential. History sync and cached energy remain available " +
                "if LG is offline or control access is unavailable.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProductionHistorySyncCard(
    historySync: HistorySyncPresentation,
    isHistorySyncing: Boolean,
    onManualRefresh: (() -> Unit)?,
) {
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
            historySync.lastSuccessfulAgeMillis?.let { "Last successful sync: ${productionAgeLabel(it)} ago." }
                ?: "Last successful sync: not yet.",
        )
        historySync.lastErrorAgeMillis?.let { Text("Last failed attempt: ${productionAgeLabel(it)} ago.") }
        onManualRefresh?.let { refresh ->
            Button(shape = MaterialTheme.shapes.small,
                enabled = !isHistorySyncing,
                onClick = refresh,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (isHistorySyncing) "Syncing history…" else historySync.retryLabel)
            }
        }
    }
}

// -------------------------------------------------------------------------
// Device (profile-gated live ThinQ controls)
// -------------------------------------------------------------------------

@Composable
fun ProductionDeviceRoute(
    history: HistorySnapshot,
    configuredDeviceId: String,
    identity: DeviceIdentity?,
    thinQBindings: ProductionThinQBindings?,
    credentialState: ThinQCredentialState,
    onBackToHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val device = thinQBindings?.device?.collectAsState()?.value
    val profile = thinQBindings?.profile?.collectAsState()?.value
    InstrumentPage("Device", if (LocalInterfaceDetail.current == InterfaceDetail.DETAILED) "Live state and supported controls for your air conditioner." else null) {
        ProductionHomeInstrument(history, thinQBindings, credentialState, hasSession = true,
            onEnergy = {}, onSettings = onOpenSettings, comfortOnly = true)
        InstrumentPanel {
            InstrumentDisclosure("Device details", "Identity, profile and last confirmed state", informational = true) {
                ProductionConfiguredAcCard(configuredDeviceId, identity)
                if (thinQBindings != null && device != null && profile != null) {
                    DeviceLiveStateCard(thinQBindings.liveStateController, device, profile,
                        simulated = false, onRefresh = thinQBindings.refreshLiveState, readOnOpen = false)
                }
            }
        }
        TextButton(shape = MaterialTheme.shapes.small, onClick = onBackToHome, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to Home") }
    }
}

// -------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------

@Composable
fun ProductionSettingsRoute(
    accountController: ProductionAccountController,
    accessState: OwnerAccessState,
    session: OwnerSession?,
    partition: HistoryPartition?,
    history: HistorySnapshot,
    historySync: HistorySyncPresentation,
    isHistorySyncing: Boolean,
    nowEpochMillis: Long,
    onManualRefresh: (() -> Unit)?,
    afterSettingsAction: suspend () -> Unit,
    thinQBindings: ProductionThinQBindings?,
    credentialState: ThinQCredentialState,
    nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    InstrumentPage("Settings", if (LocalInterfaceDetail.current == InterfaceDetail.DETAILED) "Your account, AC connection and saved history." else null) {
        InstrumentPanel { InterfaceDetailSelector() }
        InstrumentPanel {
            ProductionAccountCard(accountController, session, history, afterSettingsAction, nowProvider)
            if (thinQBindings != null) {
                ProductionThinQCredentialCard(thinQBindings, credentialState, signedIn = session != null)
            } else SummaryCard("AC connection") {
                Text("The protected ThinQ adapter is not configured in this build.")
            }
        }
        InstrumentPanel {
            InstrumentDisclosure("History access", if (accessState is OwnerAccessState.Authorized)
                "Access verified for this account" else "Review account access", initiallyExpanded = accessState !is OwnerAccessState.Authorized, informational = true) {
                OwnerAccessStatusCard(ownerAccessState = accessState)
            }
            InstrumentDisclosure("History and storage", if (historySync.hasCachedHistory)
                "${history.observations.size} cached observations" else "No history cached yet", informational = true) {
                SyncStatusCard(historySync, history.sync, nowEpochMillis, onManualRefresh, isHistorySyncing)
                if (partition != null) {
                    LocalDiagnosticsCard(partition, history, credentialState, credentialIsVisible = session != null,
                        thinQStatusLabel = credentialState.productionCredentialStatus(), nowEpochMillis = nowEpochMillis)
                } else SummaryCard("Local diagnostics · nonsecret") {
                    Text("Signed out: no local cache to inspect for another account.")
                }
            }
        }
    }
}

@Composable
private fun ProductionThinQCredentialCard(
    bindings: ProductionThinQBindings,
    credentialState: ThinQCredentialState,
    signedIn: Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(bindings) { mutableStateOf(false) }
    var replacing by remember(bindings) { mutableStateOf(false) }
    var confirmingRemoval by remember(bindings) { mutableStateOf(false) }
    var rawToken by remember(bindings) { mutableStateOf("") }
    var country by remember(bindings) { mutableStateOf("PH") }
    var feedback by remember(bindings) { mutableStateOf<String?>(null) }

    LaunchedEffect(credentialState.credential?.accountCountry, replacing) {
        if (!replacing) country = credentialState.credential?.accountCountry ?: "PH"
    }

    SummaryCard("AC connection") {
        if (!signedIn) {
            Text("Sign in with Google before setting up the separate ThinQ credential.")
        } else {
            Text(credentialState.productionCredentialStatus())
            InstrumentDisclosure("Connection details", "Protected storage and command access", inset = 0.dp, showDivider = false, informational = true) {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LG client ID: ${credentialState.clientId}", style = MaterialTheme.typography.bodySmall)
                    Text("The PAT is verified against the configured AC before it is stored in Android Keystore. A successful read does not prove command authority.", style = MaterialTheme.typography.bodySmall)
                }
            }

            feedback?.let {
                Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            if (!replacing) {
                Button(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = { replacing = true }) {
                    Text(if (credentialState.present || credentialState.unlockFailed) "Replace ThinQ PAT" else "Set up ThinQ PAT")
                }
            } else {
                OutlinedTextField(
                    value = rawToken,
                    onValueChange = { rawToken = it },
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Button(shape = MaterialTheme.shapes.small,
                    enabled = !busy,
                    onClick = {
                        val submittedToken = rawToken
                        rawToken = ""
                        scope.launch {
                            busy = true
                            feedback = try {
                                when (val result = bindings.setupCredential(submittedToken, country)) {
                                    is ThinQCredentialSetupResult.Success -> {
                                        replacing = false
                                        "The configured AC and profile were verified; the PAT was saved in protected storage. " +
                                            "LG may still require a control-capable PAT for commands."
                                    }
                                    is ThinQCredentialSetupResult.Failure -> result.productionFailureMessage()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                "ThinQ verification or protected storage failed. Any previously saved PAT was kept."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(if (busy) "Verifying and saving…" else "Verify and save securely")
                }
                TextButton(shape = MaterialTheme.shapes.small,
                    enabled = !busy,
                    onClick = { rawToken = ""; replacing = false },
                ) { Text("Cancel") }
            }

            if (credentialState.present || credentialState.unlockFailed) {
                TextButton(shape = MaterialTheme.shapes.small,
                    enabled = !busy,
                    onClick = { confirmingRemoval = true },
                ) { Text("Remove ThinQ PAT") }
            }
        }
    }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingRemoval = false },
            title = { Text("Remove ThinQ PAT?", modifier = Modifier.semantics { heading() }) },
            text = { Text("This removes the protected ThinQ credential. Your Google session and cached Airchive history stay intact.") },
            confirmButton = {
                Button(shape = MaterialTheme.shapes.small,
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                bindings.removeCredential()
                                rawToken = ""
                                replacing = false
                                feedback = "ThinQ PAT removed. Cached Airchive history was kept."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                feedback = "The ThinQ PAT could not be removed. Your current session and cached history remain."
                            } finally {
                                busy = false
                                confirmingRemoval = false
                            }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = { confirmingRemoval = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProductionAccountCard(
    controller: ProductionAccountController,
    session: OwnerSession?,
    history: HistorySnapshot,
    afterSettingsAction: suspend () -> Unit,
    nowProvider: () -> Long,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(controller) { mutableStateOf(false) }
    var feedback by remember(controller) { mutableStateOf<String?>(null) }

    LaunchedEffect(controller, session?.uid) { controller.refresh() }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
                afterSettingsAction()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = "The action could not be completed. Your saved data remains unchanged; retry when ready."
            } finally {
                busy = false
            }
        }
    }

    SummaryCard("Google account") {
        Text(
            session?.let { owner ->
                "${owner.displayName ?: owner.email ?: owner.uid}"
            } ?: "Google account: signed out",
            style = MaterialTheme.typography.bodyMedium,
        )

        history.sync?.let { sync ->
            Text(
                sync.lastSuccessfulSyncEpochMillis?.let { "Last successful history sync: ${sinceNowLabel(it, nowProvider())}." }
                    ?: "No successful history sync yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        feedback?.let { message ->
            Text(
                message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (session != null) {
            InstrumentDisclosure("Account actions", "Switch account, clear local history or sign out", inset = 0.dp, showDivider = false) {
            Text("Account UID: ${session.uid}", style = MaterialTheme.typography.bodySmall)
            TextButton(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = controller::requestChangeAccount) {
                Text("Switch Google account")
            }
            TextButton(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = controller::requestClearCache) {
                Text("Clear local history cache")
            }
            TextButton(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = controller::requestSignOut) {
                Text("Sign out of Google")
            }
            }
        } else {
            Text("Sign in from the Home screen to load Airchive history for this device.")
        }
    }

    when (state.confirmStep) {
        ProductionAccountConfirmStep.NONE -> Unit
        ProductionAccountConfirmStep.SIGN_OUT -> ProductionConfirmationDialog(
            title = "Sign out of Google?",
            message = "This clears this Google account's cached Airchive history and protected ThinQ PAT on this device, then signs out. The action cannot be undone.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = controller.signOutConfirmed().productionOutcomeMessage()
                }
            },
        )
        ProductionAccountConfirmStep.CHANGE_ACCOUNT -> ProductionConfirmationDialog(
            title = "Switch Google account?",
            message = "This first clears the current account's cached Airchive history and protected ThinQ PAT, then opens Google sign-in for another account.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = controller.changeAccountConfirmed().productionOutcomeMessage()
                }
            },
        )
        ProductionAccountConfirmStep.CLEAR_CACHE -> ProductionConfirmationDialog(
            title = "Clear local Airchive cache?",
            message = "This deletes only this Google account's cached Airchive history and sync markers on this device. Your Google sign-in stays, and a refresh can repopulate history.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    val result = controller.clearCacheConfirmed()
                    feedback = if (result is OwnerSettingsActionResult.Success) {
                        "This account's local history cache was cleared. Google sign-in is unchanged."
                    } else result.productionOutcomeMessage()
                }
            },
        )
    }
}

@Composable
private fun ProductionConfirmationDialog(
    title: String,
    message: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = { Text(message) },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(shape = MaterialTheme.shapes.small, enabled = !busy, onClick = onCancel) { Text("Cancel") }
        },
    )
}

// -------------------------------------------------------------------------
// Small formatting / presentation helpers (real data only, no invented labels)
// -------------------------------------------------------------------------

/** Generic configured-AC label; no alias/model is guessed when identity is unknown. */
internal fun productionConfiguredAcTitle(): String = "Configured AC"

/** Guards the transitional Compose frame before a new partition's DAO emits. */
internal fun productionHistoryForPartition(
    observed: HistorySnapshot,
    partition: HistoryPartition?,
    empty: HistorySnapshot,
): HistorySnapshot = if (partition != null && observed.partition == partition) {
    observed
} else {
    empty.copy(partition = partition ?: empty.partition)
}

/** Access-check callbacks for an old UID must not authorize the current UID. */
internal fun productionAccessForSession(state: OwnerAccessState, session: OwnerSession?): OwnerAccessState {
    val checkedUid = when (state) {
        is OwnerAccessState.Authorized -> state.session.uid
        is OwnerAccessState.AccessPending -> state.session.uid
        is OwnerAccessState.AccessCheckFailed -> state.session.uid
        else -> return state
    }
    return if (session?.uid == checkedUid) state else OwnerAccessState.SignedOut
}

/** Sampled identity lines only; `null` identity adds nothing and never fabricates labels. */
internal fun productionSampledIdentityLines(identity: DeviceIdentity?): List<String> = buildList {
    if (identity == null) return@buildList
    identity.alias?.takeIf { it.isNotBlank() }?.let { add("Alias: ${it.productionBoundLabel()}") }
    identity.modelName?.takeIf { it.isNotBlank() }?.let { add("Model: ${it.productionBoundLabel()}") }
    identity.deviceType?.takeIf { it.isNotBlank() }?.let { add("Device type: ${it.productionBoundLabel()}") }
}

/** Fixed, real access-copy per state; never implies a simulated sign-in succeeded. */
internal fun productionAccessMessage(access: OwnerAccessState): String = when (access) {
    OwnerAccessState.SignedOut ->
        "You are signed out of Google. Airchive history uses the enrolled owner's Google account: " +
            "sign in, then check this account's history access."
    is OwnerAccessState.SignInFailed ->
        "Google sign-in did not complete (${productionSignInFailureReason(access.reason)}). You can retry when ready."
    is OwnerAccessState.AccessPending ->
        "Signed in, but Airchive history access is pending. Sign-in alone never grants access: ask the " +
            "operator to enroll this exact account UID, then retry."
    is OwnerAccessState.AccessCheckFailed ->
        "History access could not be verified yet. Keep this account and retry the access check when ready."
    is OwnerAccessState.Authorized ->
        "History access is verified for this device. Signed-in UID: ${access.session.uid}."
}

internal fun productionSignInFailureReason(reason: SignInFailure): String = when (reason) {
    SignInFailure.USER_CANCELLED -> "the account chooser was closed"
    SignInFailure.NETWORK -> "no connection to the sign-in service"
    SignInFailure.PLATFORM -> "Google sign-in is not configured for this build"
    SignInFailure.UNKNOWN -> "an unexpected sign-in failure"
}

private fun OwnerSettingsActionResult.productionOutcomeMessage(): String = when (this) {
    is OwnerSettingsActionResult.Success -> if (session == null) {
        "Signed out. This account's local history cache and ThinQ PAT were cleared."
    } else {
        "Account switched. The previous account's local history cache and ThinQ PAT were cleared."
    }
    OwnerSettingsActionResult.AccountChangeCancelled ->
        "The previous account's local history cache and ThinQ PAT were cleared; the new sign-in was cancelled."
    is OwnerSettingsActionResult.AccountChangeSignInFailed ->
        "The previous account's local history cache and ThinQ PAT were cleared; Google sign-in failed " +
            "(${productionSignInFailureReason(reason)})."
    is OwnerSettingsActionResult.Failure -> when (reason) {
        cloud.airchive.shared.data.onboarding.OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED ->
            "Could not clear this account's local history. Nothing was changed; retry cleanup."
        cloud.airchive.shared.data.onboarding.OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED ->
            "The history cache was cleared, but the ThinQ PAT could not be removed. You remain signed in; retry cleanup."
        cloud.airchive.shared.data.onboarding.OwnerSettingsActionFailure.SIGN_OUT_FAILED ->
            "Local history and the ThinQ PAT were cleared, but sign-out did not finish. Retry or restart the app."
    }
}

private fun ThinQCredentialState.productionCredentialStatus(): String = when {
    unlockFailed -> "Saved ThinQ PAT could not be unlocked. Verify and save it again."
    present -> "ThinQ PAT saved: ${credential?.maskedToken ?: "••••"} · country ${credential?.accountCountry ?: "unknown"}."
    else -> "No ThinQ PAT is saved in the app's protected storage."
}

private fun ThinQCredentialSetupResult.Failure.productionFailureMessage(): String = when (this) {
    is ThinQCredentialSetupResult.Failure.MalformedInput -> "Enter a token and a two-letter country code."
    is ThinQCredentialSetupResult.Failure.InvalidToken -> "LG rejected this token; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.WrongAccount -> "The configured AC is not in this ThinQ account; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.DeviceUnavailable -> "The AC or its profile is unavailable; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.NetworkFailure -> "LG could not be reached. Retry online; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.RemoteFailure -> "LG verification failed. Retry later; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.StoreKeyUnavailable -> "Android Keystore is unavailable; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.StoreSaveFailure -> "The verified PAT could not be saved; the previous PAT was kept."
}

private fun Observation.productionIsUsableEnergySample(): Boolean {
    val value = energyValue ?: return false
    val unit = recordedUnit ?: return false
    if (!value.isFinite() || quality != ObservationQuality.NORMAL) return false
    if (energySourceFailure?.ok == false) return false
    if (energySourceFailure == null && sourceFailure?.startsWith("energy:", ignoreCase = true) == true) return false
    return normalizeToWh(value, unit) != null
}

private fun EnergyUnit?.productionEnergyUnitLabel(): String = when (this) {
    EnergyUnit.WH -> "Wh"
    EnergyUnit.KWH -> "kWh"
    null -> "unit not recorded"
}

private fun DailyTotalSource.productionDailyTotalSourceLabel(): String = when (this) {
    DailyTotalSource.LG_FINALIZED -> "LG finalized"
    DailyTotalSource.LOCAL_INTERVAL_SUM -> "Local interval sum"
}

private fun CollectorStatus.productionCollectorStatusLabel(): String =
    name.lowercase().replace('_', ' ')

private fun ObservationQuality.productionObservationQualityLabel(): String =
    name.lowercase().replace('_', ' ')

private fun String.productionBoundLabel(maxChars: Int = 120): String {
    val flattened = map { char -> if (char == '\n' || char == '\r' || char == '\t') ' ' else char }
        .filter { char -> char >= ' ' && char != '\u007f' }
        .joinToString(separator = "")
    return if (flattened.length <= maxChars) flattened else flattened.take(maxChars - 1) + "…"
}

private fun productionAgeLabel(ageMillis: Long): String = when {
    ageMillis < 60_000L -> "less than a minute"
    ageMillis < 60 * 60_000L -> "${ageMillis / 60_000L} minutes"
    ageMillis < 24 * 60 * 60_000L -> "${ageMillis / (60 * 60_000L)} hours"
    else -> "${ageMillis / (24 * 60 * 60_000L)} days"
}

private fun sinceNowLabel(epochMillis: Long, nowEpochMillis: Long): String {
    val age = (nowEpochMillis - epochMillis).coerceAtLeast(0L)
    return "${productionAgeLabel(age)} ago"
}
