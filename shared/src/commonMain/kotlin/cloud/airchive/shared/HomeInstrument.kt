package cloud.airchive.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*

/**
 * A new owner/credential must finish its own read before retained controller
 * state can appear (OpenSpec `deliver-shared-instrument-ui` tasks 2.1/2.3).
 *
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
    val commandSlotOccupied = commandSlot is AcCommandSlotState.Occupied
    val neutralCommandBusy = commandSlotIsNeutralBusy(commandSlot, contextKey)
    var entrySettled by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    var entryReadComplete by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    var entryReadFailed by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    var reading by remember(bindings, history.partition, credential, hasSession, contextKey) { mutableStateOf(false) }
    val now by produceState(nowProvider()) {
        while (true) { value = nowProvider(); delay(30_000) }
    }
    suspend fun refresh(forceProfileRefresh: Boolean = false) {
        if (reading) return
        reading = true
        entrySettled = false
        entryReadComplete = false
        entryReadFailed = false
        val capturedKey = contextKey
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
            entryReadComplete = homeEntryReady(
                result = result,
                capturedKey = capturedKey,
                currentKey = entryBindings.contextKey.value,
                live = entryBindings.liveStateController.state.value,
                profile = entryBindings.deviceProfile.value?.profile,
            )
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
    SharedInstrumentPanel(
        snapshot = history, nowEpochMillis = now, showEnergy = !comfortOnly,
        live = if (canRead && entrySettled) live else ThinQLiveRefreshState.NotRead,
        profile = profile?.takeIf { canRead && entrySettled },
        deviceName = device?.alias?.takeIf { canRead && entrySettled } ?: "Air conditioner",
        commandState = if (canRead && entrySettled) command else AcCommandUiState.Idle,
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
        temperatureDraft = temperatureDraft,
        onTemperatureDraftChange = onTemperatureDraftChange,
        setupMessage = when {
            !hasSession -> "Sign in to connect your AC and recorded history."
            !canRead -> "Connect ThinQ in Settings to read and control your AC."
            !entrySettled -> "Reading device state… Controls will appear after verification."
            entryReadFailed -> "The device read failed. Check the connection and retry."
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
) {
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
    var localDraft by remember { mutableStateOf<TemperatureDraft?>(null) }
    val draft = if (onTemperatureDraftChange != null) temperatureDraft else localDraft
    val draftResolution = resolveTemperatureDraft(draft, observed, capability)
    fun updateDraft(value: TemperatureDraft?) {
        if (onTemperatureDraftChange != null) onTemperatureDraftChange(value) else localDraft = value
    }
    val selected = draftResolution.displayValue
    val enabled = homeControlsEnabled(live, profile, commandState, commandSlotOccupied) && onCommand != null
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                    Text(known?.let { "Device read ${instrumentAge(nowEpochMillis - it.observedAtEpochMillis)} ago" }
                        ?: "No device read yet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (known?.powerOn) { true -> "On"; false -> "Off"; null -> "Unknown" },
                        style = MaterialTheme.typography.labelSmall)
                    Switch(checked = known?.powerOn == true,
                        onCheckedChange = { onCommand?.invoke(AcCommand.SetPower(nowEpochMillis, it)) },
                        enabled = enabled && profile?.controls?.powerWritable == true && known != null,
                        modifier = Modifier.semantics { contentDescription = "AC power" })
                }
            }
            setupMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // At this width, the two 76sp readouts can wrap a normal
                // five-character energy value (for example 15.11) even
                // though the measurement field is still above the semantic
                // narrow-width floor. Stack before either reading splits.
                val stack = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f
                val target: @Composable () -> Unit = {
                    InstrumentReadout("Set temperature", selected?.let(::formatTemperature) ?: "—",
                        (draftResolution.displayUnit ?: capability?.unit)?.symbol() ?: "°C")
                }
                val usage: @Composable () -> Unit = {
                    InstrumentReadout("Sampled today", day.usableEnergyValueWh?.let { instrumentNumber(it / 1000) } ?: "—", "kWh")
                }
                if (!showEnergy) target()
                else if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { target(); HorizontalDivider(); usage() }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { target() }
                    VerticalDivider(modifier = Modifier.height(132.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f)) { usage() }
                }
            }
            if (profile != null) {
                if (allowed.isNotEmpty()) {
                    if (draft != null) Text(
                        "${if (live is ThinQLiveRefreshState.Current) "Currently" else "Last confirmed"} " +
                            "${observed?.let(::formatTemperature) ?: "unknown"} ${capability?.unit?.symbol().orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    draftResolution.unsupportedDraft?.let { unsupported ->
                        Text(
                            "${formatTemperature(unsupported.value)} ${unsupported.unit.symbol()} is no longer supported. " +
                                "Choose a supported temperature; the value will not be changed or applied automatically.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Temperature", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val controlBase = selected?.takeIf { it in allowed }
                            ?: observed?.takeIf { it in allowed }
                            ?: allowed.first()
                        val index = allowed.indexOf(controlBase)
                        StepControl(false, enabled && index > 0) {
                            updateDraft(TemperatureDraft(allowed[index - 1], capability!!.unit))
                        }
                        StepControl(true, enabled && index < allowed.lastIndex) {
                            updateDraft(TemperatureDraft(allowed[index + 1], capability!!.unit))
                        }
                        Button(onClick = {
                            draft?.takeIf { draftResolution.canApply }?.let {
                                onCommand?.invoke(AcCommand.SetTargetTemperature(nowEpochMillis, it.value, it.unit))
                            }
                        },
                            enabled = enabled && draftResolution.canApply,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Apply") }
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
                InstrumentChoice("Mode", known?.mode, profile.controls.allowedModes, enabled,
                    label = { it.displayLabel().replaceFirstChar(Char::uppercase) },
                    onSelect = { onCommand?.invoke(AcCommand.SetMode(nowEpochMillis, it)) })
                InstrumentChoice("Fan speed", known?.fanSpeed, profile.controls.allowedFanSpeeds, enabled,
                    label = { it.displayLabel().replaceFirstChar(Char::uppercase) },
                    onSelect = { onCommand?.invoke(AcCommand.SetFanSpeed(nowEpochMillis, it)) })
            }
            if (!enabled && setupMessage == null) {
                Text("Controls unavailable until a fresh verified device read succeeds.", style = MaterialTheme.typography.bodySmall)
            }
            commandState.feedbackText(false)?.let { feedback ->
                Text(feedback, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (neutralCommandBusy) {
                Text(
                    "A previous control request is still finishing. No new command was sent or queued; wait, then retry.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (commandSlotOccupied && commandState == AcCommandUiState.Idle) {
                Text(
                    "A control request is still finishing. Controls are paused until it completes.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (onSetup != null) TextButton(onClick = onSetup) { Text("Open Settings") }
            else if ((!enabled || !showEnergy) && commandState !is AcCommandUiState.Sending) {
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Reading device state…" else "Refresh device state") }
            }
            if (showEnergy) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today’s record", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onEnergy) { Text("Energy") }
            }
            Text("3-hour recorded energy (Wh)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            InstrumentEnergyChart(day)
            if (LocalInterfaceDetail.current == InterfaceDetail.DETAILED) {
                Text("${day.usableSlotCount} of ${day.expectedSlotCount} elapsed five-minute intervals carry usable energy. Gaps are not measured zero. Sampled sums stay separate from finalized LG totals. Day boundaries use $DEFAULT_DAY_TIME_ZONE.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

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

/** True only when the one device-global command slot belongs to another context. */
internal fun commandSlotIsNeutralBusy(slot: AcCommandSlotState, currentKey: ThinQContextKey?): Boolean =
    slot is AcCommandSlotState.Occupied && slot.contextKey != currentKey

@Composable
private fun InstrumentReadout(label: String, value: String, unit: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = InstrumentReading)
        Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepControl(increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(48.dp).semantics { contentDescription = if (increase) "Increase target temperature" else "Decrease target temperature" }) {
        Canvas(Modifier.size(20.dp)) {
            drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
            if (increase) drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun <T> InstrumentChoice(title: String, current: T?, options: List<T>, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box {
        TextButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(0.dp)) {
            Text(title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Text(current?.let(label) ?: if (options.isEmpty()) "Unavailable" else "Unknown")
            val arrow = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
            Canvas(Modifier.padding(start = 8.dp).size(16.dp).clearAndSetSemantics {}) {
                drawLine(arrow, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(arrow, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEach { option -> DropdownMenuItem(
                text = { Text(label(option) + if (option == current) " · current" else "") },
                enabled = option != current,
                onClick = { open = false; onSelect(option) },
            ) }
        }
    }
    }
}

@Composable
private fun InstrumentEnergyChart(day: EnergyDaySummary) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val groups = remember(day) { instrumentEnergyGroups(day) }
    val values = groups.mapNotNull { it.energyWh }
    val maximum = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val minimum = values.minOrNull()?.coerceAtMost(0.0) ?: 0.0
    val description = groups.joinToString("; ") {
        "${it.hour}:00, ${it.energyWh?.let { value -> instrumentNumber(value) + " Wh recorded" } ?: if (it.expected == 0) "future" else "gap"}, ${it.usable} of ${it.expected} intervals measured"
    } + ". Dashed outlines mark partial groups. Open Energy for individual five-minute values."
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth()) {
        groups.forEach { group ->
            Text(group.energyWh?.let { if (abs(it - it.roundToLong()) < .01) it.roundToLong().toString() else instrumentNumber(it) }
                ?: if (group.expected == 0) "" else "Gap", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(76.dp).semantics { contentDescription = description }) {
        val step = size.width / 8f
        val chartHeight = size.height - 8.dp.toPx()
        val baseline = 4.dp.toPx() + (maximum / (maximum - minimum) * chartHeight).toFloat()
        drawLine(outline, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        groups.forEachIndexed { slot, group ->
            val value = group.energyWh
            val x = slot * step + step * .2f
            if (value != null) {
                val h = (value / (maximum - minimum) * chartHeight).toFloat()
                if (value == 0.0) drawLine(primary, Offset(x, baseline), Offset(x + step * .6f, baseline), 2.dp.toPx())
                else drawRect(primary, Offset(x, if (h >= 0) baseline - h else baseline),
                    Size(step * .6f, abs(h)), style = if (group.usable < group.expected)
                        Stroke(width = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                        else androidx.compose.ui.graphics.drawscope.Fill)
            } else if (group.expected > 0) drawLine(outline, Offset(x, baseline - 2.dp.toPx()), Offset(x + step * .6f, baseline - 2.dp.toPx()), 1.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        groups.forEach {
            Text(it.hour.toString().padStart(2, '0'), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (groups.any { it.usable > 0 && it.usable < it.expected }) Text("Outlined bars: partial samples", style = MaterialTheme.typography.bodySmall)
    }
}

internal data class InstrumentEnergyGroup(val hour: Int, val expected: Int, val usable: Int, val energyWh: Double?)

internal fun instrumentEnergyGroups(day: EnergyDaySummary): List<InstrumentEnergyGroup> = (0 until 8).map { index ->
    val start = index * 36
    val expected = (day.expectedSlotCount - start).coerceIn(0, 36)
    val values = day.points.filter { it.slotIndex in start until start + expected }.mapNotNull { it.energyValueWh }
    InstrumentEnergyGroup(index * 3, expected, values.size, values.takeIf { it.isNotEmpty() }?.sum())
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
