package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The iOS target is a linked scaffold, not a production client yet. Keep the
 * demo composition out of the iOS launcher until its native adapters are wired.
 */
fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Airchive iOS setup is incomplete",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "This project is wired to the shared Kotlin module, but iOS Firebase sign-in, " +
                        "read-only Firestore, and Keychain adapters are not connected. This build " +
                        "does not sign in, sync owner history, store a ThinQ token, or control the AC.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Complete the Mac-side steps in iosApp/README.md before using this app.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
