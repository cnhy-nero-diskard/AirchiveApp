package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureCapability
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.AcCommandSubmitResult
import cloud.airchive.shared.data.thinq.AcCommandUiState
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import cloud.airchive.shared.data.thinq.ThinQRefreshFailureKind
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * Profile-derived quick controls for the configured AC (OpenSpec task 5.2).
 *
 * Everything here is driven by the supplied [profile], the live-state
 * [ThinQLiveStateController], and the one-at-a-time [AcCommandController];
 * this card never calls any other service, port, or controller.
 *
 * ## Gating (safety)
 *
 * Every control is enabled only while the live snapshot is
 * [ThinQLiveRefreshState.Current] **and** `canIssueCommands` is `true`
 * (which requires a separately verified profile) **and** the relevant profile
 * capability permits the requested value. NotRead / Loading / Stale /
 * Unavailable / no-verified-profile snapshots disable everything and render a
 * readable explanation instead. A read-only or absent capability renders an
 * explanation rather than an enabled widget.
 *
 * ## Commands
 *
 * Actions are explicit owner taps only; target temperature is adjusted with a
 * snap-to-allowed Slider (or the +/− steppers) and sent only by the explicit
 * Apply button — never while dragging. All buttons disable immediately while
 * [AcCommandUiState.Sending] and [AcCommandController] itself rejects a
 * concurrent submission, so a duplicate tap can never invoke the port twice.
 *
 * After a [AcCommandSubmitResult.Submitted] call, the resulting command outcome
 * is passed to [onCommandOutcome] only when the state is Accepted, Confirmed, or
 * Unconfirmed (the command may have taken effect). This lets the live-state
 * controller consume the adapter's already-performed bounded confirmation read
 * without issuing a redundant LG request. A Failed outcome or an
 * IgnoredWhilePending tap is not forwarded, and nothing is ever auto-resent.
 * Cancellation propagates untouched and is never dressed up as success.
 *
 * ## Outcomes and secrets
 *
 * Feedback text is derived only from the stable [CommandFailure] (or a fixed
 * generic fallback); the raw `CommandOutcome.message`, exception text, and
 * tokens are never displayed. No-control-authority text explicitly requires a
 * control-capable ThinQ PAT and points at Settings → ThinQ PAT replacement.
 *
 * [simulated] only labels the demo state: every action goes to the in-memory
 * `FakeThinQClient` and no real LG API call is made. For a non-demo graph this
 * card calls nothing besides the supplied controllers and callback.
 */
@Composable
fun DeviceControlsCard(
    profile: ThinQDeviceProfile,
    liveStateController: ThinQLiveStateController,
    commandController: AcCommandController,
    simulated: Boolean,
    onCommandOutcome: suspend (CommandOutcome) -> Unit,
) {
    val liveState by liveStateController.state.collectAsState()
    val commandUiState by commandController.state.collectAsState()
    val scope = rememberCoroutineScope()

    val isSending = commandUiState is AcCommandUiState.Sending
    val currentSnapshot = liveState as? ThinQLiveRefreshState.Current
    val liveIsCurrent = currentSnapshot != null
    val controlsEnabled = currentSnapshot?.canIssueCommands == true && !isSending

    val known = liveState.knownLiveState()
    val controls = profile.controls

    fun issueCommand(command: AcCommand) {
        scope.launch {
            val result = commandController.submit(command)
            if (result is AcCommandSubmitResult.Submitted) {
                when (val submittedState = commandController.state.value) {
                    // Ktor already performed its bounded confirmation GET. Feed
                    // that exact outcome/state back to the read controller; do
                    // not spend another rate-limited ThinQ request unnecessarily.
                    is AcCommandUiState.Accepted -> onCommandOutcome(submittedState.outcome)
                    is AcCommandUiState.Confirmed -> onCommandOutcome(submittedState.outcome)
                    is AcCommandUiState.Unconfirmed -> onCommandOutcome(submittedState.outcome)

                    // Never refresh on a failure: the command did not go out,
                    // or was provably rejected. Idle/Sending cannot occur right
                    // after a Submitted result; both are no-ops for refresh.
                    is AcCommandUiState.Failed,
                    AcCommandUiState.Idle,
                    is AcCommandUiState.Sending,
                    -> Unit
                }
            }
            // IgnoredWhilePending: another command already held the port; the
            // pending command's own flow owns any refresh. Nothing to do here.
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Device controls", style = MaterialTheme.typography.titleMedium)

            if (simulated) {
                Text(
                    "Simulated controls · every action goes to the in-memory FakeThinQClient. No real LG API call is made.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                liveState.gateExplanation(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )

            commandUiState.feedbackText(simulated)?.let { feedback ->
                Text(
                    feedback,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (commandUiState is AcCommandUiState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            PowerControl(
                controls = controls,
                powerIsOn = known?.powerOn,
                liveIsCurrent = liveIsCurrent,
                enabled = controlsEnabled,
                onPowerToggle = { powerOn ->
                    issueCommand(AcCommand.SetPower(requestedAtEpochMillis = nowEpochMillis(), powerOn = powerOn))
                },
            )

            TemperatureControl(
                capability = controls.targetTemperature,
                liveTemperature = known?.targetTemperature,
                liveIsCurrent = liveIsCurrent,
                enabled = controlsEnabled,
                onSubmit = { value, unit ->
                    issueCommand(
                        AcCommand.SetTargetTemperature(
                            requestedAtEpochMillis = nowEpochMillis(),
                            value = value,
                            unit = unit,
                        ),
                    )
                },
            )

            ModeControl(
                allowedModes = controls.allowedModes,
                currentMode = known?.mode,
                liveIsCurrent = liveIsCurrent,
                enabled = controlsEnabled,
                onSelect = { mode ->
                    issueCommand(AcCommand.SetMode(requestedAtEpochMillis = nowEpochMillis(), mode = mode))
                },
            )

            FanControl(
                allowedSpeeds = controls.allowedFanSpeeds,
                currentSpeed = known?.fanSpeed,
                liveIsCurrent = liveIsCurrent,
                enabled = controlsEnabled,
                onSelect = { speed ->
                    issueCommand(AcCommand.SetFanSpeed(requestedAtEpochMillis = nowEpochMillis(), speed = speed))
                },
            )
        }
    }
}

@Composable
private fun PowerControl(
    controls: AcControls,
    powerIsOn: Boolean?,
    liveIsCurrent: Boolean,
    enabled: Boolean,
    onPowerToggle: (powerOn: Boolean) -> Unit,
) {
    SectionTitle("Power")
    if (!controls.powerWritable) {
        Text(
            "Power is read-only on this model, so no power control is offered.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Text(
        when (powerIsOn) {
            true -> if (liveIsCurrent) "Currently on." else "Last known power was on; this state is old."
            false -> if (liveIsCurrent) "Currently off." else "Last known power was off; this state is old."
            null -> "Current power state is not known yet."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        enabled = enabled && powerIsOn != null,
        onClick = { onPowerToggle(powerIsOn != true) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(
            when {
                powerIsOn == null -> "Power state unknown"
                !liveIsCurrent -> "Refresh live state to enable power"
                powerIsOn == true -> "Turn off"
                else -> "Turn on"
            },
        )
    }
}

@Composable
private fun TemperatureControl(
    capability: TemperatureCapability?,
    liveTemperature: Double?,
    liveIsCurrent: Boolean,
    enabled: Boolean,
    onSubmit: (value: Double, unit: TemperatureUnit) -> Unit,
) {
    SectionTitle("Target temperature")
    if (capability == null) {
        Text(
            "Target temperature is not supported on this model, so no temperature control is offered.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val allowed = remember(capability) { capability.allowedValues() }
    if (allowed.isEmpty()) {
        Text(
            "No safe, writable target-temperature values can be represented from this profile; no temperature control is offered.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    val safeLiveTemperature = liveTemperature?.takeIf { it.isFinite() }
    var index by remember(capability, allowed) {
        mutableStateOf(allowed.nearestIndex(safeLiveTemperature ?: allowed.first()))
    }
    // Re-sync the pending selection to the freshest allowed live value after a
    // fresh read; the selection is never sent unless Apply is tapped.
    LaunchedEffect(liveTemperature) {
        safeLiveTemperature?.let { index = allowed.nearestIndex(it) }
    }
    val selected = allowed[index.coerceIn(allowed.indices)]

    Text(
        "Range ${capability.rangeLabel()} · adjustable in ${formatTemperature(capability.step)}° steps.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val finiteExcludedValues = capability.excludedValues.filter { it.isFinite() }.sorted()
    if (finiteExcludedValues.isNotEmpty()) {
        Text(
            "Not writable: ${finiteExcludedValues.joinToString { formatTemperature(it) + capability.unit.symbol() }}.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        "${if (liveIsCurrent) "Current setting" else "Last known setting (old)"}: " +
            "${liveTemperature?.let { temperatureLabel(it, capability.unit) } ?: "unknown — no target reported"}.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Text(
        temperatureLabel(selected, capability.unit),
        style = MaterialTheme.typography.titleLarge,
    )

    if (allowed.size >= 2) {
        val rangeStart = capability.min.toFloat()
        val rangeEnd = capability.max.toFloat()
        Slider(
            value = selected.toFloat().coerceIn(rangeStart, rangeEnd),
            onValueChange = { raw -> index = allowed.nearestIndex(raw.toDouble()) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Target temperature"
                    stateDescription = temperatureLabel(selected, capability.unit)
                },
            enabled = enabled,
            valueRange = rangeStart..rangeEnd,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = enabled && index > 0,
            onClick = { index -= 1 },
            modifier = Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Decrease target temperature" },
        ) {
            Text("−")
        }
        Button(
            enabled = enabled,
            onClick = { onSubmit(selected, capability.unit) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text("Apply ${temperatureLabel(selected, capability.unit)}")
        }
        TextButton(
            enabled = enabled && index < allowed.lastIndex,
            onClick = { index += 1 },
            modifier = Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Increase target temperature" },
        ) {
            Text("+")
        }
    }
}

@Composable
private fun ModeControl(
    allowedModes: List<AcOperatingMode>,
    currentMode: AcOperatingMode?,
    liveIsCurrent: Boolean,
    enabled: Boolean,
    onSelect: (mode: AcOperatingMode) -> Unit,
) {
    SectionTitle("Operating mode")
    if (allowedModes.isEmpty()) {
        Text(
            "Mode control is not supported on this model, so no mode buttons are offered.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    OptionRow(
        options = allowedModes,
        current = currentMode,
        liveIsCurrent = liveIsCurrent,
        enabled = enabled,
        label = { it.displayLabel() },
        onSelect = onSelect,
    )
}

@Composable
private fun FanControl(
    allowedSpeeds: List<FanSpeed>,
    currentSpeed: FanSpeed?,
    liveIsCurrent: Boolean,
    enabled: Boolean,
    onSelect: (speed: FanSpeed) -> Unit,
) {
    SectionTitle("Fan strength")
    if (allowedSpeeds.isEmpty()) {
        Text(
            "Fan control is not supported on this model, so no fan buttons are offered.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    OptionRow(
        options = allowedSpeeds,
        current = currentSpeed,
        liveIsCurrent = liveIsCurrent,
        enabled = enabled,
        label = { it.displayLabel() },
        onSelect = onSelect,
    )
}

@Composable
private fun <T> OptionRow(
    options: List<T>,
    current: T?,
    liveIsCurrent: Boolean,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isCurrent = option == current
            Button(
                enabled = enabled && !isCurrent,
                onClick = { onSelect(option) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        isCurrent && liveIsCurrent -> "${label(option)} · current"
                        isCurrent -> "${label(option)} · last observed (old)"
                        else -> "Set ${label(option)}"
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

// ------------------------------------------------------------------ state text

/**
 * Readable explanation for why controls are (or are not) enabled, driven solely
 * by the [ThinQLiveRefreshState]. Never claims commands are usable when they
 * are not; never surfaces a raw exception or token.
 */
private fun ThinQLiveRefreshState.gateExplanation(): String = when (this) {
    ThinQLiveRefreshState.NotRead ->
        "Live state has not been read yet. Controls stay disabled until a fresh verified read completes."
    is ThinQLiveRefreshState.Loading ->
        if (previous == null) {
            "Reading live state… Controls stay disabled until a fresh verified read completes."
        } else {
            "Refreshing live state… Controls stay disabled until this read succeeds."
        }
    is ThinQLiveRefreshState.Current ->
        if (canIssueCommands) {
            "Live state is current and the verified profile allows commands — controls below are enabled."
        } else {
            "A verified device profile is not available, so commands stay disabled for safety. " +
                "Verify the ThinQ credential/profile and refresh the state read."
        }
    is ThinQLiveRefreshState.Stale ->
        "The latest state read failed (${errorKind.gateDetail()}), so controls are disabled: current capability " +
            "knowledge is not trusted. Refresh the state read before acting."
    is ThinQLiveRefreshState.Unavailable ->
        "Controls are disabled: ${errorKind.gateDetail()}. No successful state read is available."
}

private fun ThinQRefreshFailureKind.gateDetail(): String = when (this) {
    ThinQRefreshFailureKind.NO_CREDENTIAL -> "no stored ThinQ credential"
    ThinQRefreshFailureKind.INVALID_CREDENTIAL -> "the stored ThinQ credential is invalid"
    ThinQRefreshFailureKind.NETWORK -> "LG could not be reached"
    ThinQRefreshFailureKind.TIMEOUT -> "the live-state read timed out"
    ThinQRefreshFailureKind.UNSUPPORTED_COUNTRY -> "the ThinQ account country is unsupported"
    ThinQRefreshFailureKind.MALFORMED_RESPONSE -> "LG returned an unreadable state"
    ThinQRefreshFailureKind.INVALID_TOKEN -> "LG rejected the token; replace the ThinQ PAT in Settings"
    ThinQRefreshFailureKind.WRONG_ACCOUNT -> "the configured AC is not available to this account"
    ThinQRefreshFailureKind.DEVICE_UNAVAILABLE -> "the AC is offline or unavailable"
    ThinQRefreshFailureKind.RATE_LIMITED -> "LG is rate-limiting reads"
    ThinQRefreshFailureKind.NO_CONTROL_AUTHORITY ->
        "LG reports this token has no control authority — controls need a control-capable ThinQ PAT; replace it in Settings → ThinQ PAT"
    ThinQRefreshFailureKind.TRANSIENT -> "LG returned a temporary error"
    ThinQRefreshFailureKind.INVALID_REQUEST -> "LG rejected the state read"
    ThinQRefreshFailureKind.UNKNOWN -> "an unexpected ThinQ error occurred"
}

// ------------------------------------------------------------- outcome text

/**
 * Stable, secret-free feedback for the latest command state. Messages are
 * derived only from the safe [CommandFailure] (or a fixed fallback); the raw
 * [AcCommandUiState] outcome `message`, any exception text, and tokens are
 * never displayed.
 */
private fun AcCommandUiState.feedbackText(simulated: Boolean): String? = when (this) {
    AcCommandUiState.Idle -> null
    is AcCommandUiState.Sending -> if (simulated) {
        "Sending simulated ${command.commandLabel()}…"
    } else {
        "Sending ${command.commandLabel()} to the AC…"
    }

    is AcCommandUiState.Accepted ->
        "${if (simulated) "The demo accepted" else "LG accepted"} ${outcome.command.commandLabel()}, " +
            "but current state is not confirmed. Refresh live state before acting again."

    is AcCommandUiState.Confirmed ->
        "${if (simulated) "Demo state confirms" else "Confirmed: AC currently reports"} ${outcome.command.commandLabel()}."

    is AcCommandUiState.Failed -> failureText(outcome.failure, simulated)
    is AcCommandUiState.Unconfirmed -> failureText(outcome.failure, simulated)
}

private fun failureText(failure: CommandFailure?, simulated: Boolean): String {
    if (simulated) {
        return "Simulated command result: " + when (failure) {
            CommandFailure.NO_CONTROL_AUTHORITY -> "the fixture has no control authority."
            CommandFailure.RATE_LIMITED -> "the fixture is rate-limiting commands."
            CommandFailure.INVALID_REQUEST -> "the fixture profile rejects this value."
            CommandFailure.TOKEN_REJECTED -> "the fake ThinQ credential is unavailable."
            CommandFailure.NETWORK -> "the in-memory command call did not respond."
            CommandFailure.TIMEOUT -> "the simulated command timed out. Refresh state before retrying."
            CommandFailure.DEVICE_UNAVAILABLE -> "the simulated device is unavailable."
            CommandFailure.UNKNOWN, null -> "the outcome is uncertain. Refresh state before acting again."
        }
    }
    return when (failure) {
    CommandFailure.NO_CONTROL_AUTHORITY ->
        "LG reports this token has no control authority. A control-capable ThinQ PAT is required: " +
            "replace the PAT in Settings → ThinQ PAT, then retry."
    CommandFailure.RATE_LIMITED ->
        "LG is rate-limiting commands. Wait a moment and try the command again."
    CommandFailure.INVALID_REQUEST ->
        "LG rejected the requested value for this model. Choose a value the profile allows and try again."
    CommandFailure.TOKEN_REJECTED ->
        "LG rejected the token. Replace the ThinQ PAT in Settings → ThinQ PAT and retry."
    CommandFailure.NETWORK ->
        "ThinQ did not answer; the command may or may not have been sent. It was not resent — refresh state before acting again."
    CommandFailure.TIMEOUT ->
        "ThinQ did not answer in time; the command may or may not have been sent. It was not resent — refresh state before acting again."
    CommandFailure.DEVICE_UNAVAILABLE ->
        "The AC is offline or unavailable. Try again when it is reachable."
    CommandFailure.UNKNOWN, null ->
        "The command could not be confirmed. Nothing was automatically resent — refresh state before acting again."
    }
}

// ------------------------------------------------------------------- helpers

/** Last known live state across every snapshot, so controls can label stale values honestly. */
private fun ThinQLiveRefreshState.knownLiveState(): ThinQLiveState? = when (this) {
    is ThinQLiveRefreshState.Current -> state
    is ThinQLiveRefreshState.Stale -> lastKnownState
    is ThinQLiveRefreshState.Loading -> previous?.knownLiveState()
    is ThinQLiveRefreshState.Unavailable -> null
    ThinQLiveRefreshState.NotRead -> null
}

private fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun AcCommand.commandLabel(): String = when (this) {
    is AcCommand.SetPower -> if (powerOn) "power on" else "power off"
    is AcCommand.SetTargetTemperature -> "target temperature ${temperatureLabel(value, unit)}"
    is AcCommand.SetMode -> "mode ${mode.displayLabel()}"
    is AcCommand.SetFanSpeed -> "fan ${speed.displayLabel()}"
}

private fun AcOperatingMode.displayLabel(): String =
    name.lowercase().split('_').joinToString(" ")

private fun FanSpeed.displayLabel(): String =
    name.lowercase().split('_').joinToString(" ")

private fun TemperatureCapability.allowedValues(): List<Double> {
    if (!min.isFinite() || !max.isFinite() || !step.isFinite() || step <= 0.0) return emptyList()
    if (max < min) return emptyList()
    val intervalCount = (max - min) / step
    // Do not materialize an untrusted/broken profile's enormous step grid in
    // Compose state; real AC target-temperature profiles contain only a small
    // number of choices. A range too large for this control stays disabled.
    if (!intervalCount.isFinite() || intervalCount < 0.0 || intervalCount > MAX_TEMPERATURE_STEPS) return emptyList()
    val count = intervalCount.toInt()
    return buildList {
        for (k in 0..count) {
            val candidate = min + k * step
            if (allows(candidate)) add(candidate)
        }
    }
}

private fun List<Double>.nearestIndex(target: Double): Int =
    withIndex().minBy { abs(it.value - target) }.index

private fun TemperatureCapability.rangeLabel(): String =
    "${formatTemperature(min)}–${formatTemperature(max)} ${unit.symbol()}"

private fun temperatureLabel(value: Double, unit: TemperatureUnit): String =
    "${formatTemperature(value)} ${unit.symbol()}"

private fun TemperatureUnit.symbol(): String = when (this) {
    TemperatureUnit.CELSIUS -> "°C"
    TemperatureUnit.FAHRENHEIT -> "°F"
}

private fun formatTemperature(value: Double): String =
    when {
        !value.isFinite() -> "—"
        abs(value - value.roundToLong()) < 1e-9 -> value.roundToLong().toString()
        else -> value.toString()
    }

private const val MAX_TEMPERATURE_STEPS = 256.0
