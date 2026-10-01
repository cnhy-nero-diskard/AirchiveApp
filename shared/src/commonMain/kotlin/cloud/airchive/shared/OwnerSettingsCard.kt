package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionFailure
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.data.onboarding.OwnerSettingsConfirmStep
import cloud.airchive.shared.data.onboarding.OwnerSettingsController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Interactive account/credential settings card hosted by [SettingsRoute]. Every
 * removal, cache clear, or account switch is guarded by a
 * native confirmation dialog. The only credential text shown is the platform
 * store's masked form; the PAT input is password-transformed and immediately
 * passed to the verify-before-save controller.
 */
@Composable
fun OwnerSettingsCard(
    controller: OwnerSettingsController,
    credentialState: ThinQCredentialState,
    ownerAccessState: OwnerAccessState,
    demoOnly: Boolean,
    afterSettingsAction: suspend () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(controller) { mutableStateOf(false) }
    var replacing by remember(controller) { mutableStateOf(false) }
    var rawToken by remember(controller) { mutableStateOf("") }
    var country by remember(controller) { mutableStateOf("PH") }
    var feedback by remember(controller) { mutableStateOf<String?>(null) }

    LaunchedEffect(controller, credentialState, ownerAccessState) { controller.refresh() }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = "The action could not be completed. Your other saved data remains unchanged; retry when ready."
            } finally {
                busy = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings · account and credentials", style = MaterialTheme.typography.titleMedium)
            if (demoOnly) {
                Text(
                    "Demo settings use an in-memory credential store. Do not enter a real PAT; saved fixture credentials disappear on restart.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                state.session?.let { session ->
                    "Google account: ${session.displayName ?: session.email ?: session.uid} · UID ${session.uid}"
                } ?: "Google account: signed out",
            )

            val credential = if (state.session == null) null else credentialState
            Text(
                when {
                    credential == null -> "ThinQ credential status is hidden while signed out."
                    credential.unlockFailed -> "Saved PAT is unavailable because its protected key could not be unlocked. Set it up again."
                    credential.present -> "ThinQ PAT: ${credential.credential?.maskedToken ?: "••••"} · country ${credential.credential?.accountCountry ?: "not recorded"}"
                    else -> "No ThinQ PAT is saved."
                },
            )
            if (credential != null) Text("LG client ID: ${credential.clientId}")

            state.history?.sync?.let { sync ->
                Text(
                    sync.lastSuccessfulSyncEpochMillis?.let { "Last successful history sync: ${sinceNowLabel(it)}." }
                        ?: "No successful history sync yet.",
                )
                sync.lastError?.let { error ->
                Text("Last sync issue: ${error.settingsErrorLabel()} · ${sync.lastErrorEpochMillis?.let(::sinceNowLabel) ?: "time unavailable"}.")
                }
            }

            feedback?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

            if (state.session != null) {
                if (!replacing) {
                    Button(enabled = !busy, onClick = { replacing = true }) {
                        Text(if (credential?.present == true || credential?.unlockFailed == true) "Replace ThinQ PAT" else "Set up ThinQ PAT")
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
                    Button(
                        enabled = !busy,
                        onClick = {
                            val submitted = rawToken
                            rawToken = ""
                            launchAction {
                                feedback = when (val result = controller.replaceCredential(submitted, country)) {
                                    is ThinQCredentialSetupResult.Success -> {
                                        replacing = false
                                        "ThinQ identity/profile verified and the masked credential was saved. Read access does not prove command permission."
                                    }
                                    is ThinQCredentialSetupResult.Failure -> result.failureMessage()
                                }
                                afterSettingsAction()
                            }
                        },
                    ) {
                        Text(if (busy) "Verifying…" else "Verify and save token")
                    }
                    TextButton(enabled = !busy, onClick = { rawToken = ""; replacing = false }) {
                        Text("Cancel replacement")
                    }
                }

                if (credential?.present == true || credential?.unlockFailed == true) {
                    Button(enabled = !busy, onClick = controller::requestRemoveCredential) {
                        Text("Remove ThinQ PAT")
                    }
                }
                Button(enabled = !busy, onClick = controller::requestChangeAccount) {
                    Text("Switch Google account")
                }
                Button(enabled = !busy, onClick = controller::requestClearCache) {
                    Text("Clear local Airchive cache")
                }
                Button(enabled = !busy, onClick = controller::requestSignOut) {
                    Text("Sign out and clear this account's data")
                }
            }
        }
    }

    when (state.confirmStep) {
        OwnerSettingsConfirmStep.NONE -> Unit
        OwnerSettingsConfirmStep.REMOVE_CREDENTIAL -> ConfirmationDialog(
            title = "Remove ThinQ PAT?",
            message = "The protected token will be removed. Cached Airchive history and your Google session stay intact.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = when (val result = controller.removeCredentialConfirmed()) {
                        is OwnerSettingsActionResult.Success -> "ThinQ PAT removed. Cached history was kept."
                        is OwnerSettingsActionResult.Failure -> result.failureMessage()
                        else -> "ThinQ PAT removal finished."
                    }
                    afterSettingsAction()
                }
            },
        )
        OwnerSettingsConfirmStep.CLEAR_CACHE -> ConfirmationDialog(
            title = "Clear local Airchive cache?",
            message = "This deletes only this Google account's cached Airchive history and sync markers on this device. Your Google sign-in and saved ThinQ PAT remain untouched, and refreshing can repopulate history from Airchive. The cleared cache cannot be restored.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = when (val result = controller.clearCacheConfirmed()) {
                        is OwnerSettingsActionResult.Success -> "This account's local cache was cleared. You remain signed in; a refresh can repopulate the history."
                        is OwnerSettingsActionResult.Failure -> result.failureMessage()
                        else -> "Cache clear finished."
                    }
                    afterSettingsAction()
                }
            },
        )
        OwnerSettingsConfirmStep.SIGN_OUT -> ConfirmationDialog(
            title = "Sign out and clear local data?",
            message = "This clears this Google account's cached Airchive history and ThinQ PAT before signing out. The action cannot be undone.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = when (val result = controller.signOutConfirmed()) {
                        is OwnerSettingsActionResult.Success -> "Signed out. This account's cache and ThinQ PAT were cleared."
                        is OwnerSettingsActionResult.Failure -> result.failureMessage()
                        else -> "Sign-out cleanup finished."
                    }
                    replacing = false
                    rawToken = ""
                    afterSettingsAction()
                }
            },
        )
        OwnerSettingsConfirmStep.CHANGE_ACCOUNT -> ConfirmationDialog(
            title = "Switch Google account?",
            message = "This first clears the current account's Room history and ThinQ PAT, then opens Google sign-in for another account.",
            busy = busy,
            onCancel = controller::dismissConfirmation,
            onConfirm = {
                launchAction {
                    feedback = when (val result = controller.changeAccountConfirmed()) {
                        is OwnerSettingsActionResult.Success -> "Account changed. The previous account's cache and PAT were cleared."
                        is OwnerSettingsActionResult.AccountChangeCancelled -> "Previous account data was cleared; sign-in was cancelled."
                        is OwnerSettingsActionResult.AccountChangeSignInFailed -> "Previous account data was cleared; Google sign-in failed (${result.reason})."
                        is OwnerSettingsActionResult.Failure -> result.failureMessage()
                    }
                    replacing = false
                    rawToken = ""
                    afterSettingsAction()
                }
            },
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(enabled = !busy, onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
        },
    )
}

private fun ThinQCredentialSetupResult.Failure.failureMessage(): String = when (this) {
    is ThinQCredentialSetupResult.Failure.MalformedInput -> "Enter a token and a two-letter country code."
    is ThinQCredentialSetupResult.Failure.InvalidToken -> "LG rejected this token; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.WrongAccount -> "The expected AC is not in this ThinQ account; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.DeviceUnavailable -> "The AC or its profile is unavailable; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.NetworkFailure -> "LG could not be reached. Retry online; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.RemoteFailure -> "LG verification failed. Retry later; the previous PAT was kept."
    is ThinQCredentialSetupResult.Failure.StoreKeyUnavailable -> "The protected key is unavailable; the previous credential remains unchanged."
    is ThinQCredentialSetupResult.Failure.StoreSaveFailure -> "The verified PAT could not be saved; the previous PAT was kept."
}

private fun OwnerSettingsActionResult.Failure.failureMessage(): String = when (reason) {
    OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED -> "Could not clear this account's local history. The cache, account, and ThinQ credential were not changed; retry cleanup."
    OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED -> "Could not remove the ThinQ PAT. You remain signed in; retry cleanup."
    OwnerSettingsActionFailure.SIGN_OUT_FAILED -> "Local data was cleared, but sign-out did not finish. Retry or restart the app."
}

private fun String.settingsErrorLabel(): String = when (this) {
    "ACCESS_DENIED" -> "Access denied"
    "OFFLINE" -> "Offline"
    "REMOTE_FAILURE" -> "Remote failure"
    else -> "Other sync error"
}

private fun sinceNowLabel(epochMillis: Long): String {
    val age = (kotlin.time.Clock.System.now().toEpochMilliseconds() - epochMillis).coerceAtLeast(0L)
    val label = when {
        age < 60_000L -> "less than a minute"
        age < 60 * 60_000L -> "${age / 60_000L} minutes"
        age < 24 * 60 * 60_000L -> "${age / (60 * 60_000L)} hours"
        else -> "${age / (24 * 60 * 60_000L)} days"
    }
    return "$label ago"
}
