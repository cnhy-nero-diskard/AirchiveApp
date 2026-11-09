package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateManifest

/** Projects a state into the one Home offer, while respecting work already in progress. */
internal fun appUpdateNoticeManifest(
    state: AppUpdateState,
    dismissedVersionCode: Long?,
    hasTemperatureDraft: Boolean,
    hasPendingConfirmation: Boolean,
    hasPendingCommand: Boolean,
): UpdateManifest? {
    val manifest = (state as? AppUpdateState.Available)?.manifest ?: return null
    if (manifest.versionCode == dismissedVersionCode) return null
    if (hasTemperatureDraft || hasPendingConfirmation || hasPendingCommand) return null
    return manifest
}

/** Small, non-modal Home notice. It observes state and only persists an explicit dismissal. */
@Composable
internal fun AppUpdateHomeNotice(
    bindings: AppUpdateBindings?,
    hasTemperatureDraft: Boolean,
    hasPendingConfirmation: Boolean,
    hasPendingCommand: Boolean,
    onOpenSettings: () -> Unit,
) {
    if (bindings == null) return
    val state by bindings.state.collectAsState()
    val preferences by bindings.preferences.appUpdateState.collectAsState()
    val manifest = appUpdateNoticeManifest(
        state = state,
        dismissedVersionCode = preferences.dismissedVersionCode,
        hasTemperatureDraft = hasTemperatureDraft,
        hasPendingConfirmation = hasPendingConfirmation,
        hasPendingCommand = hasPendingCommand,
    ) ?: return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Update available · ${manifest.versionName}",
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Review")
            }
            TextButton(
                onClick = { bindings.preferences.dismissVersion(manifest.versionCode) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Dismiss") }
        }
    }
}
