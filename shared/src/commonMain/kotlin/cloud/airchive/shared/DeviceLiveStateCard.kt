package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.thinq.ThinQRefreshFailureKind
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Live-state surface for the configured AC. Entering this panel performs a
 * direct read via [ThinQLiveStateController.onDeviceOpened]; the button performs
 * another explicit, uncached read. The ordinary demo graph passes its labeled
 * fake controller; only a production graph that explicitly supplies a Ktor
 * controller can show real LG state.
 */
@Composable
fun DeviceLiveStateCard(
    controller: ThinQLiveStateController,
    device: ThinQDevice,
    profile: ThinQDeviceProfile,
    simulated: Boolean,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val reading = state is ThinQLiveRefreshState.Loading

    LaunchedEffect(controller) {
        controller.onDeviceOpened()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (simulated) "AC state · simulated" else "AC live state",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(device.alias, style = MaterialTheme.typography.bodyLarge)
            Text(
                state.statusMessage(simulated),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )

            when (val snapshot = state) {
                ThinQLiveRefreshState.NotRead -> Unit
                is ThinQLiveRefreshState.Loading -> snapshot.previous?.lastKnownState()?.let { previous ->
                    Text("Last successful ${if (simulated) "simulated" else "LG"} read (old): ${previous.readAtLabel()} UTC.")
                    Text(previous.summary(profile.controls.temperatureUnit))
                }
                is ThinQLiveRefreshState.Current -> {
                    Text("${if (simulated) "Simulated read time" else "LG read time"}: ${snapshot.state.readAtLabel()} UTC.")
                    Text(snapshot.state.summary(profile.controls.temperatureUnit))
                    Text("This timestamp is independent from the Airchive history sync time.")
                    if (!snapshot.canIssueCommands) {
                        Text("Controls remain disabled until a verified device profile is available.")
                    }
                }
                is ThinQLiveRefreshState.Stale -> {
                    Text("Last successful ${if (simulated) "simulated" else "LG"} read (old): ${snapshot.lastKnownState.readAtLabel()} UTC.")
                    Text(snapshot.lastKnownState.summary(profile.controls.temperatureUnit))
                    Text("Refresh attempt: ${Instant.fromEpochMilliseconds(snapshot.failedAtEpochMillis)} UTC.")
                }
                is ThinQLiveRefreshState.Unavailable -> {
                    Text("No prior live state is available to show.")
                    Text("Refresh attempt: ${Instant.fromEpochMilliseconds(snapshot.failedAtEpochMillis)} UTC.")
                }
            }

            Button(
                enabled = !reading,
                onClick = { scope.launch { controller.refresh() } },
            ) {
                Text(
                    when {
                        reading -> "Reading state…"
                        simulated -> "Refresh simulated state"
                        else -> "Refresh live state"
                    },
                )
            }
        }
    }
}

private fun ThinQLiveRefreshState.statusMessage(simulated: Boolean): String = when (this) {
    ThinQLiveRefreshState.NotRead -> "State has not been read yet."
    is ThinQLiveRefreshState.Loading -> if (previous == null) {
        if (simulated) "Reading the in-memory ThinQ fixture…" else "Reading the AC directly from LG…"
    } else {
        "Refreshing. Any previously shown state is old until this read succeeds."
    }
    is ThinQLiveRefreshState.Current -> if (simulated) {
        "Simulated ThinQ response; this is not a real AC reading."
    } else {
        "Current state returned by LG."
    }
    is ThinQLiveRefreshState.Stale ->
        "Current AC state is unavailable (${errorKind.userLabel()}); showing the last successful read as old."
    is ThinQLiveRefreshState.Unavailable ->
        "AC state unavailable (${errorKind.userLabel()}); no previous successful read is available."
}

private fun ThinQRefreshFailureKind.userLabel(): String = when (this) {
    ThinQRefreshFailureKind.NO_CREDENTIAL -> "ThinQ credential is missing"
    ThinQRefreshFailureKind.INVALID_CREDENTIAL -> "ThinQ credential is invalid"
    ThinQRefreshFailureKind.NETWORK -> "LG could not be reached"
    ThinQRefreshFailureKind.TIMEOUT -> "the LG read timed out"
    ThinQRefreshFailureKind.UNSUPPORTED_COUNTRY -> "the ThinQ account country is unsupported"
    ThinQRefreshFailureKind.MALFORMED_RESPONSE -> "LG returned an unreadable state"
    ThinQRefreshFailureKind.INVALID_TOKEN -> "LG rejected the token"
    ThinQRefreshFailureKind.WRONG_ACCOUNT -> "the configured AC is not available to this account"
    ThinQRefreshFailureKind.DEVICE_UNAVAILABLE -> "the AC is offline or unavailable"
    ThinQRefreshFailureKind.RATE_LIMITED -> "LG is rate-limiting reads"
    ThinQRefreshFailureKind.NO_CONTROL_AUTHORITY -> "LG reports that the token has no control authority"
    ThinQRefreshFailureKind.TRANSIENT -> "LG returned a temporary error"
    ThinQRefreshFailureKind.INVALID_REQUEST -> "LG rejected the read request"
    ThinQRefreshFailureKind.UNKNOWN -> "an unexpected ThinQ error occurred"
}

private fun ThinQLiveRefreshState.lastKnownState(): ThinQLiveState? = when (this) {
    is ThinQLiveRefreshState.Current -> state
    is ThinQLiveRefreshState.Stale -> lastKnownState
    else -> null
}

private fun ThinQLiveState.readAtLabel(): String = Instant.fromEpochMilliseconds(observedAtEpochMillis).toString()

private fun ThinQLiveState.summary(unit: TemperatureUnit): String = buildString {
    append("Power ")
    append(if (powerOn) "on" else "off")
    targetTemperature?.let { value ->
        append(" · ")
        append(value)
        append(if (unit == TemperatureUnit.CELSIUS) " °C" else " °F")
    }
    mode?.let { append(" · "); append(it.name.lowercase().replace('_', ' ')) }
    fanSpeed?.let { append(" · fan "); append(it.name.lowercase()) }
}
