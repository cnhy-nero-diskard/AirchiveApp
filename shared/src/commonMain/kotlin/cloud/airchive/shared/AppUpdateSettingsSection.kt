package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateFailureReason
import cloud.airchive.shared.data.update.UpdateManifest
import cloud.airchive.shared.data.update.UpdateUnavailableCause
import kotlinx.coroutines.launch

/** Settings presentation for update state. Checks and recovered-result dismissal are owner-triggered. */
@Composable
fun AppUpdateSettingsSection(
    bindings: AppUpdateBindings?,
    currentVersionName: String,
    currentVersionCode: Long?,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    val detail = LocalInterfaceDetail.current
    val state = bindings?.state?.collectAsState()?.value ?: AppUpdateState.Idle
    val preferenceSnapshot = bindings?.preferences?.appUpdateState?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val currentName = bindings?.currentVersionName?.takeIf(String::isNotBlank) ?: currentVersionName
    val currentCode = bindings?.currentVersionCode ?: currentVersionCode
    val pendingInstall = preferenceSnapshot?.pendingInstall
    val pendingOutcomeMatchesInstalledVersion = pendingInstall != null && currentCode != null && when (state) {
        is AppUpdateState.Updated -> currentCode >= pendingInstall.targetVersionCode
        is AppUpdateState.NotCompleted -> currentCode < pendingInstall.targetVersionCode
        else -> false
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Current version $currentName", style = MaterialTheme.typography.titleMedium)
        Text(
            preferenceSnapshot?.lastSuccessfulCheckEpochMillis?.let { checkedAt ->
                "Last checked ${productionAgeLabel((nowEpochMillis - checkedAt).coerceAtLeast(0L))} ago"
            } ?: "Last checked: Not yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail == InterfaceDetail.DETAILED && currentCode != null) {
            Text("Version code: $currentCode", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                AppUpdateState.Idle -> {
                    Text(
                        if (bindings == null) "In-app updates apply to release builds"
                        else "Check when you’re ready to see whether a newer version is available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (bindings != null) {
                        OutlinedButton(
                            onClick = { scope.launch { bindings.service.check() } },
                            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                                "Check for app updates",
                            ) { scope.launch { bindings.service.check() } },
                        ) {
                            Text("Check for updates")
                        }
                    }
                }

                AppUpdateState.Checking -> {
                    Text("Checking for updates", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                AppUpdateState.UpToDate -> {
                    Text("You’re up to date", style = MaterialTheme.typography.bodyMedium)
                    checkButton(bindings, "Check again", "Check for app updates again") {
                        scope.launch { bindings?.service?.check() }
                    }
                }

                is AppUpdateState.Unavailable -> {
                    Text("Updates unavailable · ${state.cause.label()}", style = MaterialTheme.typography.bodyMedium)
                    checkButton(bindings, "Retry", "Retry checking for app updates") {
                        scope.launch { bindings?.service?.check() }
                    }
                }

                is AppUpdateState.Available -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    OutlinedButton(
                        enabled = bindings?.downloadActions != null,
                        onClick = { bindings?.downloadActions?.download(state.manifest) },
                        modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                            "Download app update version ${state.manifest.versionName}",
                            enabled = bindings?.downloadActions != null,
                        ) { bindings?.downloadActions?.download(state.manifest) },
                    ) {
                        Text("Update")
                    }
                }

                is AppUpdateState.Downloading -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    Text("Downloading update · ${progressPercent(state.bytesReceived, state.totalBytes)}%",
                        style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progressFraction(state.bytesReceived, state.totalBytes) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${humanBytes(state.bytesReceived)} of ${humanBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        enabled = bindings?.downloadActions != null,
                        onClick = { bindings?.downloadActions?.cancelDownload() },
                        modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                            "Cancel app update download",
                            enabled = bindings?.downloadActions != null,
                        ) { bindings?.downloadActions?.cancelDownload() },
                    ) {
                        Text("Cancel")
                    }
                }

                is AppUpdateState.Verifying -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    Text("Verifying downloaded update", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is AppUpdateState.Failed -> {
                    Text("Update didn’t finish · ${state.reason.label()}", style = MaterialTheme.typography.bodyMedium)
                    checkButton(bindings, "Retry", "Retry checking for app updates") {
                        scope.launch { bindings?.service?.check() }
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    Text("The update is verified and ready to install.", style = MaterialTheme.typography.bodyMedium)
                    Button(
                        enabled = bindings?.actions != null,
                        onClick = { bindings?.actions?.install(state.manifest) },
                        modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                            "Install app update version ${state.manifest.versionName}",
                            enabled = bindings?.actions != null,
                        ) { bindings?.actions?.install(state.manifest) },
                    ) {
                        Text("Install update")
                    }
                }

                is AppUpdateState.NeedsInstallPermission -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    Text("Allow Airchive to install this update in Android settings, then return here.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        enabled = bindings?.actions != null,
                        onClick = { bindings?.actions?.requestInstallPermission(state.manifest) },
                        modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                            "Open Android install permission settings",
                            enabled = bindings?.actions != null,
                        ) { bindings?.actions?.requestInstallPermission(state.manifest) },
                    ) {
                        Text("Open install permission settings")
                    }
                }

                is AppUpdateState.Installing -> {
                    ManifestSummary(state.manifest, detail, bindings?.feedUrl)
                    Text("Waiting for Android to finish installing", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is AppUpdateState.Updated -> {
                    Text("Updated to ${state.versionName}", style = MaterialTheme.typography.bodyMedium)
                    if (pendingOutcomeMatchesInstalledVersion) {
                        TextButton(
                            onClick = { scope.launch { bindings.service.acknowledgeStartupOutcome() } },
                            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                                "Dismiss app update result",
                            ) { scope.launch { bindings.service.acknowledgeStartupOutcome() } },
                        ) {
                            Text("Dismiss")
                        }
                    }
                }

                is AppUpdateState.NotCompleted -> {
                    Text("Update didn’t finish · ${state.versionName}", style = MaterialTheme.typography.bodyMedium)
                    checkButton(bindings, "Retry", "Retry checking for app updates") {
                        scope.launch {
                            if (pendingOutcomeMatchesInstalledVersion) {
                                bindings.service.acknowledgeStartupOutcome()
                            }
                            bindings?.service?.check()
                        }
                    }
                    if (pendingOutcomeMatchesInstalledVersion) {
                        TextButton(
                            onClick = { scope.launch { bindings.service.acknowledgeStartupOutcome() } },
                            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                                "Dismiss app update result",
                            ) { scope.launch { bindings.service.acknowledgeStartupOutcome() } },
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun checkButton(
    bindings: AppUpdateBindings?,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    if (bindings == null) {
        Text("In-app updates apply to release builds", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp).appUpdateActionSemantics(
                description,
                onActivate = onClick,
            ),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ManifestSummary(manifest: UpdateManifest, detail: InterfaceDetail, feedUrl: String?) {
    Text("Version ${manifest.versionName} is available", style = MaterialTheme.typography.titleSmall)
    Text("Download size · ${humanBytes(manifest.apk.size)}", style = MaterialTheme.typography.bodyMedium)
    if (manifest.releaseNotes.isNotEmpty()) {
        Text("Release notes", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Release notes are intentionally ordinary Compose text: never interpret markup or links.
        Text(manifest.releaseNotes, style = MaterialTheme.typography.bodyMedium)
    }
    if (detail == InterfaceDetail.DETAILED) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Version code: ${manifest.versionCode}", style = MaterialTheme.typography.bodySmall)
            Text("Commit: ${manifest.commit}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256: ${manifest.apk.sha256}", style = MaterialTheme.typography.bodySmall)
            if (!feedUrl.isNullOrBlank()) Text("Feed URL: $feedUrl", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun UpdateUnavailableCause.label(): String = when (this) {
    UpdateUnavailableCause.OFFLINE -> "You’re offline"
    UpdateUnavailableCause.TIMEOUT -> "The update check timed out"
    UpdateUnavailableCause.FEED_ERROR -> "The release feed could not be read"
    UpdateUnavailableCause.UNKNOWN_SCHEMA -> "The release feed is not supported"
    UpdateUnavailableCause.PACKAGE_MISMATCH -> "The release is for a different app"
    UpdateUnavailableCause.SIGNER_MISMATCH -> "The release signer did not match"
    UpdateUnavailableCause.RELEASE_BUILDS_ONLY -> "Available in release builds only"
}

private fun UpdateFailureReason.label(): String = when (this) {
    UpdateFailureReason.STORAGE -> "Not enough storage space"
    UpdateFailureReason.DOWNLOAD -> "The download failed"
    UpdateFailureReason.DOWNLOAD_TOO_LARGE -> "The download was larger than expected"
    UpdateFailureReason.APK_SIZE_MISMATCH -> "The downloaded file size did not match"
    UpdateFailureReason.APK_INVALID -> "The downloaded file could not be read"
    UpdateFailureReason.HASH_MISMATCH -> "The downloaded file did not match its checksum"
    UpdateFailureReason.APK_PACKAGE_MISMATCH -> "The downloaded file was for a different app"
    UpdateFailureReason.APK_VERSION_MISMATCH -> "The downloaded version was not newer"
    UpdateFailureReason.APK_SIGNER_MISMATCH -> "The downloaded file has an unexpected signer"
    UpdateFailureReason.APK_HAS_MULTIPLE_SIGNERS -> "The downloaded file has multiple signers"
    UpdateFailureReason.APK_DEBUGGABLE -> "The downloaded file is a debug build"
    UpdateFailureReason.INSTALL_ABORTED -> "Android canceled the installation"
    UpdateFailureReason.INSTALL_CONFLICT -> "The installation conflicts with another package"
    UpdateFailureReason.INSTALL_INCOMPATIBLE -> "The update is not compatible with this device"
    UpdateFailureReason.INSTALL_STORAGE -> "Android needs more storage to install the update"
    UpdateFailureReason.INSTALL_BLOCKED -> "Android blocked the installation"
    UpdateFailureReason.INSTALL_ERROR -> "Android could not install the update"
}

private fun progressFraction(received: Long, total: Long): Float =
    if (total <= 0L) 0f else (received.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

private fun progressPercent(received: Long, total: Long): Int = (progressFraction(received, total) * 100).toInt()

private fun humanBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val megabyte = 1_048_576L
    val kilobyte = 1_024L
    return when {
        value >= megabyte -> "${value / megabyte}.${((value % megabyte) * 10L) / megabyte} MB"
        value >= kilobyte -> "${value / kilobyte} KB"
        else -> "$value bytes"
    }
}
