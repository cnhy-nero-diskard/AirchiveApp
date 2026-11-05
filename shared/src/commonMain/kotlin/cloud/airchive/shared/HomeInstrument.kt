package cloud.airchive.shared

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.thinq.*
import cloud.airchive.shared.data.energy.*
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import cloud.airchive.shared.data.thinq.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*

/**
 * A new owner/credential must finish its own read before retained controller
 * state can appear (OpenSpec `deliver-shared-instrument-ui` tasks 2.1/2.3).
 *
 * Retained readings are gated by the graph-owned publication's context key.
 * Route entry reuses them without polling; scheduled and explicit refreshes
 * keep them visible while controls wait for a current verified reading.
 * All entry state is keyed on the active lifecycle context, so an invalidation
 * — including a replacement whose credential mask and timestamp are identical
 * to the old one — resets it and starts a fresh entry read. Readiness is
 * computed by [homeEntryReady]: a successful device-matching live read plus a
 * matching verified profile **for the captured context**, never because the
 * entry callback merely returned. A superseded read never settles under the
 * old context; a same-context failure settles as failed and shows only the
 * controller's explicitly labeled old state with commands disabled.
 */
@Composable
internal fun ProductionHomeInstrument(
    history: HistorySnapshot,
    bindings: ProductionThinQBindings?,
    credential: ThinQCredentialState,
    hasSession: Boolean,
    onEnergy: () -> Unit,
    onSettings: () -> Unit,
    comfortOnly: Boolean = false,
    nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    temperatureDraft: TemperatureDraft? = null,
    onTemperatureDraftChange: ((TemperatureDraft?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val contextKey = bindings?.contextKey?.collectAsState()?.value
    val publication = bindings?.deviceProfile?.collectAsState()?.value
    val profile = publication?.profile
    val device = publication?.device
    val live = bindings?.liveStateController?.state?.collectAsState()?.value ?: ThinQLiveRefreshState.NotRead
    val command = bindings?.commandController?.state?.collectAsState()?.value ?: AcCommandUiState.Idle
    val commandSlot = bindings?.commandController?.slotState?.collectAsState()?.value ?: AcCommandSlotState.Idle
    val commandSlotOccupied = commandSlot != AcCommandSlotState.Idle
    val neutralCommandBusy = commandSlotIsNeutralBusy(commandSlot, contextKey)
    var entrySettled by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    var entryReadFailed by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    var reading by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    val now by produceState(nowProvider()) {
        while (true) { value = nowProvider(); delay(30_000) }
    }
    suspend fun refresh(forceProfileRefresh: Boolean = false) {
        if (reading) return
        reading = true
        entryReadFailed = false
        try {
            val entryBindings = bindings ?: return
            // Route entry reuses a valid same-context publication; only the
            // explicit "Refresh device state" action forces the same-context
            // profile-capability refetch (task 3.2). No automatic/polling
            // path ever forces it.
            val result = if (forceProfileRefresh) {
                entryBindings.refreshDeviceProfile()
            } else {
                entryBindings.onDeviceOpened()
            }
            entryReadFailed = result == ThinQEntryResult.Failed
            // A superseded read must not settle: the new context's remembered
            // state and LaunchedEffect take over as soon as the key changes.
            entrySettled = result != ThinQEntryResult.Superseded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entrySettled = true
            entryReadFailed = true
        } finally {
            reading = false
        }
    }
    LaunchedEffect(bindings, history.partition, credential, hasSession, contextKey) {
        if (hasSession && credential.present && !credential.unlockFailed) refresh()
    }
    val canRead = hasSession && credential.present && !credential.unlockFailed && bindings != null
    // The publication and live controller are graph-owned and invalidated
    // together. Route-local entry flags must never hide their retained readings.
    val presentationReady = canRead && contextKey != null && publication?.contextKey == contextKey
    val entryReadComplete = presentationReady && homeEntryReady(
        ThinQEntryResult.Success, contextKey, bindings?.contextKey?.value, live, profile,
    )
    SharedInstrumentPanel(
        snapshot = history, nowEpochMillis = now, showEnergy = !comfortOnly,
        live = if (presentationReady) live else ThinQLiveRefreshState.NotRead,
        profile = profile?.takeIf { presentationReady },
        deviceName = device?.alias?.takeIf { presentationReady } ?: "Air conditioner",
        commandState = if (presentationReady) command else AcCommandUiState.Idle,
        // Controls dispatch only after a successful matching read for this
        // context; the tap-time context key fences the scheduled submission.
        onCommand = if (canRead && entryReadComplete) { request ->
            // The graph-owned host synchronously tries the one device-global
            // slot and then owns the coroutine beyond this route's lifetime.
            // The tap-time context key is retained across every command/result
            // publication; navigation/detail recomposition never resends it.
            bindings.dispatchCommand(request, contextKey)
        } else null,
        onRefresh = { scope.launch { refresh(forceProfileRefresh = true) } }, onEnergy = onEnergy,
        onSetup = if (!canRead) onSettings else null,
        refreshing = reading,
        commandSlotOccupied = commandSlotOccupied,
        neutralCommandBusy = neutralCommandBusy,
        temperatureContextKey = contextKey,
        temperatureDraft = temperatureDraft,
        onTemperatureDraftChange = onTemperatureDraftChange,
        setupMessage = when {
            !hasSession -> "Sign in to connect your AC and recorded history."
            !canRead -> "Connect ThinQ in Settings to read and control your AC."
            !presentationReady && !entrySettled -> "Reading device state… Controls will appear after verification."
            presentationReady && live is ThinQLiveRefreshState.Loading -> null
            live is ThinQLiveRefreshState.Stale || live is ThinQLiveRefreshState.Unavailable ||
                (!presentationReady && entryReadFailed) -> "The device read failed. Check the connection and retry."
            !entryReadComplete -> "Device profile unavailable. Retry the device read."
            else -> null
        },
    )
}

/**
 * Home entry readiness (task 2.3): ready only after a successful
 * device-matching live read **and** a matching verified profile that both
 * belong to the active context.
 *
 * The entry callback merely returning is never enough — a failed read, a
 * superseded context, a missing/unverified profile, or a device mismatch all
 * stay unready, so no control can be dispatched on them.
 */
internal fun homeEntryReady(
    result: ThinQEntryResult?,
    capturedKey: ThinQContextKey?,
    currentKey: ThinQContextKey?,
    live: ThinQLiveRefreshState,
    profile: ThinQDeviceProfile?,
): Boolean = result == ThinQEntryResult.Success &&
    capturedKey != null &&
    capturedKey == currentKey &&
    profile != null &&
    live is ThinQLiveRefreshState.Current &&
    live.canIssueCommands &&
    live.state.deviceId == profile.deviceId

/** Keep the last observed power visible while refreshing or recovering. */
internal fun instrumentPowerLabel(live: ThinQLiveRefreshState): String {
    val label = when (live.knownLiveState()?.powerOn) {
        true -> "On"
        false -> "Off"
        null -> return "Unknown"
    }
    return if (live is ThinQLiveRefreshState.Stale) "Last confirmed: $label" else label
}

/** Semantic native implementation of the approved Shared Instrument; no concept values ship here. */
@Composable
fun SharedInstrumentPanel(
    snapshot: HistorySnapshot,
    nowEpochMillis: Long,
    live: ThinQLiveRefreshState,
    profile: ThinQDeviceProfile?,
    commandState: AcCommandUiState = AcCommandUiState.Idle,
    deviceName: String = "Air conditioner",
    onCommand: ((AcCommand) -> Unit)? = null,
    onRefresh: () -> Unit = {},
    onEnergy: () -> Unit = {},
    onSetup: (() -> Unit)? = null,
    setupMessage: String? = null,
    refreshing: Boolean = false,
    showEnergy: Boolean = true,
    commandSlotOccupied: Boolean = false,
    neutralCommandBusy: Boolean = false,
    temperatureDraft: TemperatureDraft? = null,
    onTemperatureDraftChange: ((TemperatureDraft?) -> Unit)? = null,
    temperatureContextKey: ThinQContextKey? = null,
) {
    var showConfirmation by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(commandState) {
        showConfirmation = commandState is AcCommandUiState.Confirmed
        if (showConfirmation) { delay(3000); showConfirmation = false }
    }
    val zone = remember { TimeZone.of(DEFAULT_DAY_TIME_ZONE) }
    val today = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date
    val energy = remember(snapshot, today, nowEpochMillis) {
        presentEnergyHistory(snapshot, EnergyPeriodType.DAY, today, nowEpochMillis, DEFAULT_DAY_TIME_ZONE)
    }
    val day = energy.days.single()
    val known = live.knownLiveState()
    val capability = profile?.controls?.targetTemperature
    val allowed = remember(capability) { capability?.allowedValues().orEmpty() }
    val observed = known?.targetTemperature?.takeIf { it.isFinite() }
    var localDraft by remember(snapshot.partition, temperatureContextKey) { mutableStateOf<TemperatureDraft?>(null) }
    // Only a deliberate step arms a send. Restoring a draft never does.
    var pendingTemperature by remember(snapshot.partition, temperatureContextKey, capability) {
        mutableStateOf<TemperatureDraft?>(null)
    }
    val draft = if (onTemperatureDraftChange != null) temperatureDraft else localDraft
    val draftResolution = resolveTemperatureDraft(draft, observed, capability)
    val fallbackSetpoint = remember(capability) { defaultTemperatureSetpoint(capability) }
    fun updateDraft(value: TemperatureDraft?) {
        if (onTemperatureDraftChange != null) onTemperatureDraftChange(value) else localDraft = value
    }
    val selected = draftResolution.displayValue
        ?: fallbackSetpoint.value.takeIf { draft == null && observed == null }
    val enabled = homeControlsEnabled(live, profile, commandState, commandSlotOccupied) && onCommand != null
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestNow by rememberUpdatedState(nowEpochMillis)
    LaunchedEffect(pendingTemperature, enabled, draftResolution.status) {
        val pending = pendingTemperature ?: return@LaunchedEffect
        if (!enabled || !draftResolution.canApply) {
            pendingTemperature = null
            return@LaunchedEffect
        }
        delay(1_000)
        // Consume before dispatch: failure, ambiguous acceptance, and subsequent
        // state reads must never re-arm this request or retry it automatically.
        pendingTemperature = null
        latestOnCommand?.invoke(AcCommand.SetTargetTemperature(latestNow, pending.value, pending.unit))
    }
    fun stepTemperature(increase: Boolean) {
        val base = selected?.takeIf { it in allowed }
            ?: observed?.takeIf { it in allowed }
            ?: fallbackSetpoint.value.takeIf { it in allowed }
            ?: allowed.firstOrNull() ?: return
        val next = allowed.getOrNull(allowed.indexOf(base) + if (increase) 1 else -1) ?: return
        val nextDraft = TemperatureDraft(next, capability!!.unit)
        updateDraft(nextDraft)
        pendingTemperature = nextDraft
    }
    val controlBase = selected?.takeIf { it in allowed } ?: observed?.takeIf { it in allowed }
        ?: fallbackSetpoint.value.takeIf { it in allowed } ?: allowed.firstOrNull()
    val controlIndex = allowed.indexOf(controlBase)
    val yesterday = today.minus(1, DateTimeUnit.DAY).toString()
    val finalized = snapshot.dailyTotals.firstOrNull {
        it.localDate == yesterday && it.source == DailyTotalSource.LG_FINALIZED && it.finalized &&
            normalizeToWh(it.energyValue, it.recordedUnit)?.isFinite() == true
    }
    val panelShape = MaterialTheme.shapes.large
    Box(Modifier.fillMaxWidth().clip(panelShape).background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.matchParentSize().padding(end = 0.dp)) {
            Box(Modifier.fillMaxHeight().width(3.dp).background(MaterialTheme.colorScheme.primary))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 440.dp || LocalDensity.current.fontScale > 1.2f
            val identity: @Composable () -> Unit = {
                Column {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                    Text(known?.let { "Device read ${instrumentAge(nowEpochMillis - it.observedAtEpochMillis)} ago" }
                        ?: "No device read yet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val power: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowerIndicator(known?.powerOn)
                    Text(instrumentPowerLabel(live),
                        style = MaterialTheme.typography.labelSmall)
                    Switch(checked = known?.powerOn == true,
                        onCheckedChange = {
                            haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            onCommand?.invoke(AcCommand.SetPower(nowEpochMillis, it))
                        },
                        enabled = enabled && profile?.controls?.powerWritable == true && known?.powerOn != null,
                        modifier = Modifier.semantics { contentDescription = "AC power" })
                }
            }
            if (compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { identity(); power() }
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { identity() }; power()
            }
            }
            setupMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stack = maxWidth < 600.dp || LocalDensity.current.fontScale > 1.2f
                val target: @Composable () -> Unit = {
                    ThermostatReadout(
                        selected = selected,
                        unit = draftResolution.displayUnit ?: capability?.unit ?: fallbackSetpoint.unit,
                        roomTemperature = known?.currentTemperature?.takeIf { it.isFinite() },
                        roomUnit = profile?.controls?.temperatureUnit ?: fallbackSetpoint.unit,
                        current = live is ThinQLiveRefreshState.Current,
                        capability = capability,
                        canDecrease = enabled && controlIndex > 0,
                        canIncrease = enabled && controlIndex >= 0 && controlIndex < allowed.lastIndex,
                        onStep = ::stepTemperature,
                    )
                    val targetStatus = when {
                        pendingTemperature != null -> "Sending in 1 second…"
                        draftResolution.canApply -> "Selected target · not confirmed"
                        observed == null -> "Suggested target · not read from AC"
                        live !is ThinQLiveRefreshState.Current -> "Last observed target"
                        else -> null
                    }
                    targetStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                val usage: @Composable () -> Unit = {
                    val localHour = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).hour
                    val hour = currentHourEnergy(day, localHour, energy.queryCoverage.fullyQueried)
                    Column {
                        InstrumentReadout("Hour to date", hour.energyWh?.let { instrumentNumber(it / 1000) } ?: "\u2014", "kWh \u00b7 ${localHour.toString().padStart(2, '0')}:00\u2013now")
                        Text(if (hour.energyWh == null) "Hour unavailable" else if (hour.partial) "Partial hour" else "Recorded this hour", style = MaterialTheme.typography.bodySmall)
                        Text("Today so far: ${day.usableEnergyValueWh?.let { instrumentNumber(it / 1000) + " kWh" } ?: "unavailable"}${if (day.usableSlotCount < day.expectedSlotCount || !energy.queryCoverage.fullyQueried) " \u00b7 partial" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!showEnergy) target()
                else if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { target(); HorizontalDivider(); usage() }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { target() }
                    VerticalDivider(modifier = Modifier.height(132.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f)) { usage() }
                }
            }
            val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (profile != null) {
                if (allowed.isNotEmpty()) {
                    draftResolution.unsupportedDraft?.let { unsupported ->
                        Text(
                            "${formatTemperature(unsupported.value)} ${unsupported.unit.symbol()} is no longer supported. " +
                                "Choose a supported temperature; the value will not be changed or applied automatically.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                } else {
                    draftResolution.unsupportedDraft?.let { unsupported ->
                        Text(
                            "${formatTemperature(unsupported.value)} ${unsupported.unit.symbol()} is no longer supported by this profile; " +
                                "temperature adjustment is unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } ?: Text("Temperature adjustment is unavailable on this profile.", style = MaterialTheme.typography.bodySmall)
                }
                SteppedChoice("Mode", known?.mode, profile.controls.allowedModes, enabled, live is ThinQLiveRefreshState.Current,
                    label = { it.displayLabel().replaceFirstChar(Char::uppercase) },
                    onSelect = { onCommand?.invoke(AcCommand.SetMode(nowEpochMillis, it)) },
                    status = commandState.selectorControlStatus(showConfirmation) { it is AcCommand.SetMode })
                SteppedChoice("Fan speed", known?.fanSpeed, profile.controls.allowedFanSpeeds, enabled, live is ThinQLiveRefreshState.Current,
                    label = { it.selectorLabel() },
                    onSelect = { onCommand?.invoke(AcCommand.SetFanSpeed(nowEpochMillis, it)) },
                    status = commandState.selectorControlStatus(showConfirmation) { it is AcCommand.SetFanSpeed })
            }
            if (!enabled && setupMessage == null) {
                Text("Controls unavailable until a fresh verified device read succeeds.", style = MaterialTheme.typography.bodySmall)
            }
            commandState.feedbackText(false)?.takeIf { commandState !is AcCommandUiState.Confirmed || showConfirmation }?.let { feedback ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (commandState is AcCommandUiState.Sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(feedback, style = MaterialTheme.typography.bodySmall, color = if (commandState is AcCommandUiState.Failed || commandState is AcCommandUiState.Unconfirmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (neutralCommandBusy) {
                Text(
                    "A previous control request is still finishing. No new command was sent or queued; wait, then retry.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (live is ThinQLiveRefreshState.Loading) {
                Text("Refreshing device state…", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else if (commandSlotOccupied && commandState == AcCommandUiState.Idle) {
                Text(
                    "A control request is still finishing. Controls are paused until it completes.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (onSetup != null) TextButton(onClick = onSetup) { Text("Open Settings") }
            else if ((!enabled || !showEnergy || commandState is AcCommandUiState.Unconfirmed || commandState is AcCommandUiState.Failed) && commandState !is AcCommandUiState.Sending) {
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Reading device state…" else "Refresh device state") }
            }
            }
            }
            val energyContent: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showEnergy) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily Cumulative Usage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onEnergy) { Text("Energy") }
            }
            Text("Hourly recorded energy (kWh)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            InstrumentEnergyChart(day, nowEpochMillis)
            if (LocalInterfaceDetail.current == InterfaceDetail.DETAILED) {
                InstrumentDisclosure("Coverage details", "Samples and day boundary") {
                    Text("${day.usableSlotCount} of ${day.expectedSlotCount} elapsed intervals have usable energy. Gaps are not zero. Day boundary: $DEFAULT_DAY_TIME_ZONE.", style = MaterialTheme.typography.bodySmall)
                }
            }
            val hasSamples = day.usableSlotCount > 0
            val complete = hasSamples && day.usableSlotCount == day.expectedSlotCount && energy.queryCoverage.fullyQueried
            val latest = latestUsableEnergyPoint(day)
            val sampleAge = latest?.let { "Latest usable sample ${instrumentAge(nowEpochMillis - it.intervalEndEpochMillis)} ago" }
                ?: "History appears after the first download."
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverageBadge: @Composable () -> Unit = { Surface(shape = RoundedCornerShape(8.dp),
                color = if (complete) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = if (complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer,
                border = if (complete) null else BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
                Text(when { !hasSamples -> "No usable samples yet"; complete -> "Recorded coverage complete"; else -> "Incomplete coverage" },
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            } }
            if (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { coverageBadge(); Text(sampleAge, style = MaterialTheme.typography.bodySmall) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1.4f)) { coverageBadge() }
                Text(sampleAge, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            finalized?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Yesterday · LG finalized", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${instrumentNumber(normalizeToWh(it.energyValue, it.recordedUnit)!! / 1000)} kWh",
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            }
            }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (showEnergy && useInstrumentColumns(maxWidth.value, LocalDensity.current.fontScale)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { controls() }
                        Box(Modifier.weight(1f)) { energyContent() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        controls()
                        energyContent()
                    }
                }
            }
        }
    }
}

internal fun useInstrumentColumns(widthDp: Float, fontScale: Float): Boolean =
    widthDp >= 640f && fontScale <= 1.2f

internal fun homeControlsEnabled(
    live: ThinQLiveRefreshState,
    profile: ThinQDeviceProfile?,
    command: AcCommandUiState,
    commandSlotOccupied: Boolean = false,
): Boolean = live is ThinQLiveRefreshState.Current && live.canIssueCommands && profile != null &&
    live.state.deviceId == profile.deviceId && command !is AcCommandUiState.Sending && !commandSlotOccupied

/** Ephemeral setpoint selection; carries its unit and is never persisted. */
data class TemperatureDraft(val value: Double, val unit: TemperatureUnit)

internal enum class TemperatureDraftStatus { NONE, DIRTY, MATCHES_OBSERVATION, UNSUPPORTED }

internal data class TemperatureDraftResolution(
    val displayValue: Double?,
    val displayUnit: TemperatureUnit?,
    val status: TemperatureDraftStatus,
    val observedValue: Double?,
    val unsupportedDraft: TemperatureDraft? = null,
) {
    val canApply: Boolean get() = status == TemperatureDraftStatus.DIRTY
}

/**
 * Reconcile a context-scoped draft with the latest successful same-context
 * observation and profile without replacing, clamping, or submitting it.
 */
internal fun resolveTemperatureDraft(
    draft: TemperatureDraft?,
    observed: Double?,
    capability: TemperatureCapability?,
): TemperatureDraftResolution {
    val safeObserved = observed?.takeIf { it.isFinite() }
    if (draft == null) {
        return TemperatureDraftResolution(
            displayValue = safeObserved,
            displayUnit = capability?.unit,
            status = TemperatureDraftStatus.NONE,
            observedValue = safeObserved,
        )
    }
    if (capability == null || draft.unit != capability.unit || !capability.allows(draft.value)) {
        return TemperatureDraftResolution(
            displayValue = null,
            displayUnit = capability?.unit ?: draft.unit,
            status = TemperatureDraftStatus.UNSUPPORTED,
            observedValue = safeObserved,
            unsupportedDraft = draft,
        )
    }
    val matchesObservation = safeObserved != null && abs(draft.value - safeObserved) <= 1e-9
    return TemperatureDraftResolution(
        displayValue = draft.value,
        displayUnit = capability.unit,
        status = if (matchesObservation) TemperatureDraftStatus.MATCHES_OBSERVATION else TemperatureDraftStatus.DIRTY,
        observedValue = safeObserved,
    )
}

/** The initial local target is 25°C (77°F), rounded only to a profile-supported value. */
internal fun defaultTemperatureSetpoint(capability: TemperatureCapability?): TemperatureDraft {
    val unit = capability?.unit ?: TemperatureUnit.CELSIUS
    val requestedValue = when (unit) {
        TemperatureUnit.CELSIUS -> 25.0
        TemperatureUnit.FAHRENHEIT -> 77.0
    }
    val supportedValue = capability?.allowedValues()?.minByOrNull { abs(it - requestedValue) }
    return TemperatureDraft(supportedValue ?: requestedValue, unit)
}

/** True only when the one device-global command slot belongs to another context. */
internal fun commandSlotIsNeutralBusy(slot: AcCommandSlotState, currentKey: ThinQContextKey?): Boolean =
    slot is AcCommandSlotState.Occupied && slot.contextKey != currentKey

/** A profile-scaled thermostat arc frames the target and its independent room sensor. */
@Composable
private fun ThermostatReadout(
    selected: Double?,
    unit: TemperatureUnit,
    roomTemperature: Double?,
    roomUnit: TemperatureUnit,
    current: Boolean,
    capability: TemperatureCapability?,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (Boolean) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val range = capability?.let { it.max - it.min } ?: 0.0
    val fraction = if (selected != null && capability != null && range > 0) {
        ((selected - capability.min) / range).coerceIn(0.0, 1.0).toFloat()
    } else null
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
            Canvas(Modifier.matchParentSize()) {
                val inset = 12.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val origin = Offset(inset, inset)
                drawArc(outline, 150f, 240f, false, origin, arcSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                fun point(angle: Float, insetBy: Float = 0f): Offset {
                    val radians = angle * PI / 180
                    return Offset(size.width / 2 + cos(radians).toFloat() * (arcSize.width / 2 - insetBy),
                        size.height / 2 + sin(radians).toFloat() * (arcSize.height / 2 - insetBy))
                }
                for (tick in 0..24) {
                    val angle = 150f + tick * 10f
                    drawLine(outline, point(angle, 5.dp.toPx()), point(angle, if (tick % 4 == 0) 13.dp.toPx() else 9.dp.toPx()), 1.dp.toPx())
                }
                fraction?.let {
                    drawArc(primary, 150f, 240f * it, false, origin, arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(primary, 5.dp.toPx(), point(150f + 240f * it))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 36.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text("Set temperature", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selected?.let(::formatTemperature) ?: "—", style = InstrumentReading,
                            modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 76.sp))
                        Text(unit.symbol(), modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(if (current) "Room temperature" else "Last room temperature",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Text(roomTemperature?.let { "${formatTemperature(it)} ${roomUnit.symbol()}" } ?: "Unavailable",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepControl(false, canDecrease) { onStep(false) }
                    StepControl(true, canIncrease) { onStep(true) }
                }
            }
        }
    }
}

@Composable
private fun InstrumentReadout(label: String, value: String, unit: String, inlineUnit: Boolean = false) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (inlineUnit) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, style = InstrumentReading)
                Text(unit, modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(value, style = InstrumentReading)
            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepControl(increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    val haptic = LocalHapticFeedback.current
    FilledTonalIconButton(onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onClick()
    }, enabled = enabled, shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(48.dp).semantics { contentDescription = if (increase) "Increase target temperature" else "Decrease target temperature" }) {
        Canvas(Modifier.size(20.dp)) {
            drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
            if (increase) drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

internal enum class SelectorControlStatus { SUCCESS, ERROR }

internal fun AcCommandUiState.selectorControlStatus(
    showConfirmation: Boolean,
    matches: (AcCommand) -> Boolean,
): SelectorControlStatus? = when (this) {
    is AcCommandUiState.Confirmed ->
        if (showConfirmation && matches(outcome.command)) SelectorControlStatus.SUCCESS else null
    is AcCommandUiState.Failed ->
        if (matches(outcome.command)) SelectorControlStatus.ERROR else null
    else -> null
}

@Composable
private fun SelectorStatusIndicator(title: String, status: SelectorControlStatus?) {
    if (status == null) {
        Spacer(Modifier.size(24.dp))
        return
    }
    val color = when (status) {
        SelectorControlStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        SelectorControlStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Canvas(
        Modifier.size(24.dp).semantics {
            contentDescription = when (status) {
                SelectorControlStatus.SUCCESS -> "$title change confirmed"
                SelectorControlStatus.ERROR -> "$title change failed"
            }
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        val strokeWidth = 2.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - strokeWidth,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(strokeWidth),
        )
        if (status == SelectorControlStatus.SUCCESS) {
            drawLine(color, Offset(size.width * .28f, size.height * .51f), Offset(size.width * .44f, size.height * .66f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(size.width * .44f, size.height * .66f), Offset(size.width * .73f, size.height * .35f), strokeWidth, StrokeCap.Round)
        } else {
            drawLine(color, Offset(size.width * .35f, size.height * .35f), Offset(size.width * .65f, size.height * .65f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(size.width * .65f, size.height * .35f), Offset(size.width * .35f, size.height * .65f), strokeWidth, StrokeCap.Round)
        }
    }
}

@Composable
internal fun <T> SteppedChoice(
    title: String,
    current: T?,
    options: List<T>,
    enabled: Boolean,
    liveIsCurrent: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    status: SelectorControlStatus? = null,
) {
    var selection by remember(options, current) { mutableStateOf(current?.takeIf { it in options }) }
    var lastHapticIndex by remember(options, current) { mutableStateOf(options.indexOf(current)) }
    val index = options.indexOf(selection)
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f),
                MaterialTheme.shapes.medium,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val changed = selection != null && selection != current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectorStatusIndicator(title, status)
        }
        if (options.isEmpty()) Text("Unavailable", style = MaterialTheme.typography.bodySmall)
        else {
            if (options.size > 1) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stopCellWidth = maxWidth / options.size.toFloat()
                    val thumbRadius = 14.dp
                    val sliderInset = (stopCellWidth / 2 - thumbRadius).coerceAtLeast(0.dp)
                    val sliderColors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        activeTickColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        inactiveTickColor = MaterialTheme.colorScheme.outline,
                        disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                        disabledActiveTickColor = MaterialTheme.colorScheme.surface,
                        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
                        disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                    )
                    val tickColor = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                    val accessibleState = when {
                        changed -> "Selected ${selection?.let(label) ?: "unknown"}; current value ${current?.let(label) ?: "unknown"}, not confirmed"
                        liveIsCurrent && current != null -> "Current confirmed value ${label(current)}"
                        else -> "Last observed value ${current?.let(label) ?: "unknown"}"
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Slider(
                            value = index.coerceAtLeast(0).toFloat(),
                            onValueChange = { raw ->
                                val nextIndex = raw.roundToInt().coerceIn(0, options.lastIndex)
                                if (enabled && nextIndex != lastHapticIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    lastHapticIndex = nextIndex
                                }
                                selection = options[nextIndex]
                            },
                            onValueChangeFinished = { selection?.takeIf { enabled && it != current }?.let(onSelect) },
                            valueRange = 0f..options.lastIndex.toFloat(),
                            steps = (options.size - 2).coerceAtLeast(0),
                            enabled = enabled,
                            colors = sliderColors,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = sliderInset).semantics {
                                contentDescription = "$title selection. $accessibleState"
                                stateDescription = accessibleState
                            },
                            thumb = {
                                Box(
                                    Modifier.size(28.dp)
                                        .shadow(2.dp, CircleShape)
                                        .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), CircleShape)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    enabled = enabled,
                                    colors = sliderColors,
                                    drawStopIndicator = { offset ->
                                        drawLine(
                                            color = tickColor,
                                            start = Offset(offset.x, offset.y - 4.dp.toPx()),
                                            end = Offset(offset.x, offset.y + 4.dp.toPx()),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        )
                                    },
                                    drawTick = { offset, color ->
                                        drawLine(
                                            color = color,
                                            start = Offset(offset.x, offset.y - 4.dp.toPx()),
                                            end = Offset(offset.x, offset.y + 4.dp.toPx()),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        )
                                    },
                                )
                            },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { optionIndex, option ->
                                Text(
                                    label(option),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (optionIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            } else if (selection == null) {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    selection = options.single()
                    onSelect(options.single())
                }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose ${label(options.single())}") }
            }
        }
    }
}

internal data class CurrentHourEnergy(val energyWh: Double?, val partial: Boolean)

internal fun currentHourEnergy(day: EnergyDaySummary, localHour: Int, fullyQueried: Boolean): CurrentHourEnergy {
    val firstSlot = localHour * 12
    val elapsed = (day.expectedSlotCount - firstSlot).coerceIn(0, 12)
    val points = day.points.filter { it.slotIndex in firstSlot until firstSlot + elapsed && it.usable && it.energyValueWh != null }
    return CurrentHourEnergy(points.takeIf { it.isNotEmpty() }?.sumOf { it.energyValueWh!! }, !fullyQueried || points.size < elapsed)
}

@Composable
private fun PowerIndicator(power: Boolean?) {
    val rotation = if (power == true) {
        val transition = rememberInfiniteTransition()
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "fan").value
    } else 0f
    val color = if (power == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.padding(end = 8.dp).size(22.dp).rotate(rotation).clearAndSetSemantics {}) {
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = size.minDimension * .10f)
        repeat(3) { i ->
            val angle = (i * 120.0 - 90.0) * kotlin.math.PI / 180.0
            drawLine(color, center, Offset(center.x + kotlin.math.cos(angle).toFloat() * size.width * .4f, center.y + kotlin.math.sin(angle).toFloat() * size.height * .4f), strokeWidth = size.width * .15f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun InstrumentEnergyChart(day: EnergyDaySummary, nowEpochMillis: Long) {
    HourlyEnergyChart(day, nowEpochMillis)
}

internal data class InstrumentEnergyGroup(val hour: Int, val expected: Int, val usable: Int, val energyWh: Double?)

internal fun instrumentEnergyGroups(day: EnergyDaySummary): List<InstrumentEnergyGroup> = (0 until 24).map { index ->
    val start = index * 12
    val expected = (day.expectedSlotCount - start).coerceIn(0, 12)
    val values = day.points.filter { it.slotIndex in start until start + expected }.mapNotNull { it.energyValueWh }
    InstrumentEnergyGroup(index, expected, values.size, values.takeIf { it.isNotEmpty() }?.sum())
}

internal fun latestUsableEnergyPoint(day: EnergyDaySummary): EnergyIntervalPoint? =
    day.points.filter { it.energyValueWh != null }.maxByOrNull { it.intervalEndEpochMillis }

internal fun instrumentAge(millis: Long): String = when {
    millis < 60_000 -> "${(millis.coerceAtLeast(0) / 1000)} sec"
    millis < 3_600_000 -> "${millis / 60_000} min"
    millis < 86_400_000 -> "${millis / 3_600_000} hr"
    else -> "${millis / 86_400_000} days"
}

internal fun instrumentNumber(value: Double): String {
    if (!value.isFinite()) return "—"
    if (abs(value) >= 1e12) return value.toString()
    val hundredths = (abs(value) * 100).roundToLong()
    val sign = if (value < 0 && hundredths != 0L) "−" else ""
    return "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
