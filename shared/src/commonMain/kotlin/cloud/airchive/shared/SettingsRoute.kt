package cloud.airchive.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.data.onboarding.OwnerSettingsController
import cloud.airchive.shared.data.sync.HistorySyncPresentation
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Settings route (OpenSpec task 5.5).
 *
 * Dedicated presentation shell for the owner's account/credential/local-cache
 * settings. It composes the existing [OwnerSettingsCard] unchanged for the
 * interactive Google account, masked ThinQ credential, replace/remove, account
 * switch/sign-out, and the confirmed clear-cache action, and adds four
 * read-only material cards:
 *
 *  1. **Settings header/introduction** with the copy that this screen reads
 *     only local state and starts no network work on its own.
 *  2. **Owner history-access status** from [OwnerAccessState], rendered with
 *     fixed safe copy only (SignedOut / SignInFailed / AccessPending /
 *     AccessCheckFailed / Authorized). No exception text, error code, or
 *     identity detail is ever interpolated, and a fixed line says that Firebase
 *     sign-in and Airchive authorization are separate.
 *  3. **Sync status** from [HistorySyncPresentation] + `history.sync`: the safe
 *     presentation message, the last successful sync instant/age, and the last
 *     error as a fixed mapped label (`ACCESS_DENIED` / `OFFLINE` /
 *     `REMOTE_FAILURE`, unknown → generic; the raw `lastError` code is never
 *     echoed), plus a visible ≥48dp manual refresh [Button] only when the
 *     parent provides a callback, labeled with
 *     `syncPresentation.retryLabel`.
 *  4. **Secret-free local diagnostics** from [HistoryPartition], [HistorySnapshot],
 *     [ThinQCredentialState], and `history.collectorHealth`: nonsecret
 *     project/device IDs, cached observation/daily-total/covered-range counts,
 *     credential presence only (present / missing / unlock-failed — never a raw
 *     token), and bounded collector status/error-class/counter labels, with an
 *     explicit note that no PAT, exception text, or raw telemetry payload is
 *     included.
 *
 * Contract guarantees: **read-only and local.** This route performs no
 * networking or Firestore access and never calls the controller's destructive
 * `*Confirmed()` methods itself; the cache-clearing flows stay behind the
 * confirmation dialogs owned by [OwnerSettingsCard], and the manual refresh
 * action only invokes the parent-provided callback. No raw message, client
 * token, PAT, or plaintext secret is ever rendered, and model `toString()` /
 * raw `lastError` / source-failure text is never shown. The screen is one
 * vertically scrollable column capped at 640dp wide, follows the active
 * [MaterialTheme] (light/dark), and announces only the sync-status live region
 * when the presentation signals a status worth announcing (avoiding excessive
 * screen-reader announcements).
 */
@Composable
fun SettingsRoute(
    controller: OwnerSettingsController,
    credentialState: ThinQCredentialState,
    ownerAccessState: OwnerAccessState,
    demoOnly: Boolean,
    afterSettingsAction: suspend () -> Unit,
    partition: HistoryPartition,
    history: HistorySnapshot,
    syncPresentation: HistorySyncPresentation,
    isHistorySyncing: Boolean,
    nowEpochMillis: Long,
    onManualRefresh: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHeaderCard()
        // The interactive account and credential settings lead the route; the
        // read-only state cards below provide context and diagnostics.
        OwnerSettingsCard(
            controller = controller,
            credentialState = credentialState,
            ownerAccessState = ownerAccessState,
            demoOnly = demoOnly,
            afterSettingsAction = afterSettingsAction,
        )
        OwnerAccessStatusCard(ownerAccessState = ownerAccessState)
        SyncStatusCard(
            presentation = syncPresentation,
            sync = history.sync,
            nowEpochMillis = nowEpochMillis,
            onManualRefresh = onManualRefresh,
            isHistorySyncing = isHistorySyncing,
        )
        LocalDiagnosticsCard(
            partition = partition,
            history = history,
            credentialState = credentialState,
            credentialIsVisible = ownerAccessState !is OwnerAccessState.SignedOut &&
                ownerAccessState !is OwnerAccessState.SignInFailed,
            nowEpochMillis = nowEpochMillis,
        )
    }
}

// ---------------------------------------------------------------------------
// 1. Settings header / introduction
// ---------------------------------------------------------------------------

@Composable
private fun SettingsHeaderCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Settings", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(
                "Account, ThinQ credential, history access, sync, and local cache for this device. " +
                    "Cached state is shown without automatic network work. History refresh and ThinQ credential " +
                    "verification are explicit actions that contact their respective services.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Owner history-access status (fixed safe copy only)
// ---------------------------------------------------------------------------

@Composable
private fun OwnerAccessStatusCard(ownerAccessState: OwnerAccessState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("History access", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // Only fixed copy per state: the reason, error code, and session
            // identity details of the sealed state are deliberately never
            // interpolated into UI strings.
            Text(
                when (ownerAccessState) {
                    OwnerAccessState.SignedOut ->
                        "Status: signed out of Google. Sign in from the setup card on Home to start."
                    is OwnerAccessState.SignInFailed ->
                        "Status: Google sign-in did not complete. You can retry it from the setup card on Home."
                    is OwnerAccessState.AccessPending ->
                        "Status: signed in, but history access is pending or not yet granted for this account. " +
                            "Have the operator enroll this account's UID, then retry the access check."
                    is OwnerAccessState.AccessCheckFailed ->
                        "Status: history access could not be verified for the signed-in account. Stay signed in " +
                            "and retry the access check when ready."
                    is OwnerAccessState.Authorized ->
                        "Status: history access is authorized for this account and device."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Firebase sign-in and Airchive authorization are separate matters: a Google session alone " +
                    "never grants Airchive history access.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Sync status (presentation message, last success, mapped last error)
// ---------------------------------------------------------------------------

@Composable
private fun SyncStatusCard(
    presentation: HistorySyncPresentation,
    sync: SyncMetadata?,
    nowEpochMillis: Long,
    onManualRefresh: (() -> Unit)?,
    isHistorySyncing: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sync status", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // One polite live region, limited to sync-status changes the
            // presentation flags as worth announcing (denied/offline/remote
            // failure transitions), so the screen never over-announces.
            Text(
                if (isHistorySyncing) {
                    "Syncing Airchive history… Cached rows remain available. ${presentation.message}"
                } else {
                    presentation.message
                },
                modifier = if (isHistorySyncing || presentation.shouldAnnounceUpdate) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            val lastSuccess = sync?.lastSuccessfulSyncEpochMillis
            Text(
                if (lastSuccess != null) {
                    "Last successful sync: ${syncInstantLabel(lastSuccess, nowEpochMillis)}."
                } else {
                    "Last successful sync: not yet."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            val lastErrorCode = sync?.lastError
            if (lastErrorCode != null) {
                val whenText = sync.lastErrorEpochMillis?.let {
                    " (${ageFrom(it, nowEpochMillis).ageLabel()} ago)"
                } ?: ""
                Text("Last sync error: ${lastErrorCode.syncErrorLabel()}$whenText.")
            } else {
                Text("No recorded sync errors.")
            }

            onManualRefresh?.let { refresh ->
                Button(
                    enabled = !isHistorySyncing,
                    onClick = refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(if (isHistorySyncing) "Syncing history…" else presentation.retryLabel)
                }
            }
        }
    }
}

/** Maps the persisted stable sync error code to a fixed label (never the raw code). */
private fun String.syncErrorLabel(): String = when (this) {
    "ACCESS_DENIED" -> "Access denied"
    "OFFLINE" -> "Offline"
    "REMOTE_FAILURE" -> "Remote failure"
    else -> "Other sync error"
}

// ---------------------------------------------------------------------------
// 4. Secret-free local diagnostics
// ---------------------------------------------------------------------------

@Composable
private fun LocalDiagnosticsCard(
    partition: HistoryPartition,
    history: HistorySnapshot,
    credentialState: ThinQCredentialState,
    credentialIsVisible: Boolean,
    nowEpochMillis: Long,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Local diagnostics · nonsecret", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // Nonsecret configuration only; the owner UID is deliberately never
            // shown here.
            Text(
                "Project ID: ${partition.firebaseProjectId} · Device ID: ${partition.deviceId}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Cached: ${history.observations.size} observation(s) · ${history.dailyTotals.size} daily " +
                    "total(s) · ${history.coveredRanges.size} covered range(s).",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("ThinQ credential", style = MaterialTheme.typography.labelLarge)
            Text(
                if (credentialIsVisible) credentialState.safeCredentialPresenceLabel()
                else "ThinQ credential status is hidden while signed out.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Collector health", style = MaterialTheme.typography.labelLarge)
            val health = history.collectorHealth
            if (health == null) {
                Text(
                    "No collector health record is cached for this device yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Status: ${health.status.safeLabel()}.", style = MaterialTheme.typography.bodyMedium)
                health.lastPollEpochMillis?.let {
                    Text("Last poll: ${ageFrom(it, nowEpochMillis).ageLabel()} ago.")
                } ?: Text("Last poll: not recorded.")
                health.lastSuccessEpochMillis?.let {
                    Text("Last success: ${ageFrom(it, nowEpochMillis).ageLabel()} ago.")
                } ?: Text("Last success: not recorded.")
                health.lastFailureEpochMillis?.let {
                    Text("Last failure: ${ageFrom(it, nowEpochMillis).ageLabel()} ago.")
                } ?: Text("No recorded failure.")
                health.lastErrorClass?.takeIf { it.isNotBlank() }?.let { clazz ->
                    Text("Last failure class: ${clazz.boundedLabel()}.")
                }
                (health.consecutiveFailures ?: 0).takeIf { it > 0 }?.let { failures ->
                    Text("$failures consecutive failure(s).")
                }
                (health.consecutiveRateLimits ?: 0).takeIf { it > 0 }?.let { limits ->
                    Text("$limits consecutive rate-limited attempt(s).")
                }
                (health.collectorVersion?.takeIf { it.isNotBlank() })?.let { version ->
                    Text("Collector version: ${version.boundedLabel()}.")
                }
            }

            Text(
                "Diagnostics include no raw PAT, exception text, or telemetry payload; the account card shows only a masked token.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Credential presence only — present / missing / unlock-failed; never a raw token. */
private fun ThinQCredentialState.safeCredentialPresenceLabel(): String = when {
    unlockFailed -> "Present, but the protected key could not be unlocked; set the PAT up again."
    present -> "Present."
    else -> "Not present."
}

private fun CollectorStatus.safeLabel(): String = when (this) {
    CollectorStatus.UNKNOWN -> "Unknown"
    CollectorStatus.HEALTHY -> "Healthy"
    CollectorStatus.DEGRADED -> "Degraded"
    CollectorStatus.OFFLINE -> "Offline"
}

/**
 * Flattens and bounds source-provided labels before they enter UI strings, so
 * collector-version/error-class text at most contains no control characters and
 * stays within [maxChars].
 */
private fun String.boundedLabel(maxChars: Int = MAX_BOUNDED_LABEL_CHARS): String {
    val flattened = map { char -> if (char == '\n' || char == '\r' || char == '\t') ' ' else char }
        .filter { char -> char >= ' ' && char != '\u007f' }
        .joinToString(separator = "")
    return if (flattened.length <= maxChars) flattened else flattened.take(maxChars - 1) + "…"
}

private const val MAX_BOUNDED_LABEL_CHARS = 80

// ---------------------------------------------------------------------------
// Local time/age helpers (precedents: HistoryHealthRoute, OwnerSettingsCard)
// ---------------------------------------------------------------------------

private fun ageFrom(eventEpochMillis: Long, nowEpochMillis: Long): Long =
    (nowEpochMillis - eventEpochMillis).coerceAtLeast(0L)

private fun Long.ageLabel(): String = when {
    this < 60_000L -> "less than a minute"
    this < 60 * 60_000L -> "${this / 60_000L} minutes"
    this < 24 * 60 * 60_000L -> "${this / (60 * 60_000L)} hours"
    else -> "${this / (24 * 60 * 60_000L)} days"
}

private fun syncInstantLabel(epochMillis: Long, nowEpochMillis: Long): String {
    val zone = safeZone(DEFAULT_DAY_TIME_ZONE)
    val at = "${describeDateTime(localOf(epochMillis, zone))} $DEFAULT_DAY_TIME_ZONE"
    return "$at (${ageFrom(epochMillis, nowEpochMillis).ageLabel()} ago)"
}

private fun safeZone(zoneId: String): TimeZone =
    runCatching { TimeZone.of(zoneId) }.getOrElse { TimeZone.of(DEFAULT_DAY_TIME_ZONE) }

private fun localOf(epochMillis: Long, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)

private fun describeDate(value: LocalDateTime): String =
    "${value.year}-${(value.month.ordinal + 1).toString().padStart(2, '0')}-${value.day.toString().padStart(2, '0')}"

private fun describeClock(value: LocalDateTime): String =
    "${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"

private fun describeDateTime(value: LocalDateTime): String =
    "${describeDate(value)} ${describeClock(value)}"
