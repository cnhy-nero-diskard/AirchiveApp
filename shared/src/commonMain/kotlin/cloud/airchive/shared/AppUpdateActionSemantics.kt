package cloud.airchive.shared

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role

/** Keeps the TalkBack label, button role, and accessibility click on one node. */
internal fun Modifier.appUpdateActionSemantics(
    description: String,
    enabled: Boolean = true,
    onActivate: () -> Unit,
): Modifier = clearAndSetSemantics {
    contentDescription = description
    role = Role.Button
    if (enabled) {
        onClick(label = description) {
            onActivate()
            true
        }
    } else {
        disabled()
    }
}
