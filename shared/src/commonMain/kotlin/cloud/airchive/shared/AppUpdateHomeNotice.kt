package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
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
    val stackActions = LocalDensity.current.fontScale > 1.2f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Update available · ${manifest.versionName}",
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                )
                UpdateNoticeActions(
                    onOpenSettings = onOpenSettings,
                    onDismiss = { bindings.preferences.dismissVersion(manifest.versionCode) },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        } else {
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
                UpdateNoticeActions(
                    onOpenSettings = onOpenSettings,
                    onDismiss = { bindings.preferences.dismissVersion(manifest.versionCode) },
                )
            }
        }
    }
}

@Composable
private fun UpdateNoticeActions(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                "Review update in Settings",
                onActivate = onOpenSettings,
            ),
        ) {
            Text("Review")
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                "Dismiss update for this version",
                onActivate = onDismiss,
            ),
        ) {
            Text("Dismiss")
        }
    }
}
