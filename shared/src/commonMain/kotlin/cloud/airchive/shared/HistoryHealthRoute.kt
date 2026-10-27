package cloud.airchive.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.EnergyUnitSource
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import cloud.airchive.shared.data.energy.normalizeToWh
import cloud.airchive.shared.data.sync.HistorySyncPresentation
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * History/Health route (OpenSpec task 5.4).
 *
 * This is a **read-only presentation shell** over one already-observed local
 * [HistorySnapshot]. The parent passes the cached projection it collects from
 * `LocalHistoryPort.observe`, so this screen renders cached rows first and
 * simply recomposes when the parent's snapshot updates after a refresh. This
 * route performs no network, Firestore, or raw-payload reads of its own.
 *
 * Contract guarantees:
 *
 *  - **Cache-first, status-agnostic.** The screen always renders
 *    `snapshot.observations` independently of sync status. Denied, offline,
 *    stale, and error conditions only change the "Cache & sync" context card
 *    (via [presentHistorySync]); cached rows stay visible and unchanged.
 *  - **Safe fields only.** The status/counters/health/detail surfaces read the
 *    normalized model (`SyncMetadata` stable error codes, `CollectorHealth`
 *    whitelisted fields, `Observation` quality/flags/typed
 *    `SourceReadFailure`s). No `raw` payload, exception message, token, or PAT
 *    is ever rendered, and a failed state read never invalidates a valid energy
 *    sample.
 *  - **Measurement meaning is preserved.** A null/missing energy value is a
 *    gap, never a measured zero; anomalous values stay visible but are excluded
 *    from usable counts; unknown/legacy-null units are labeled "unit not
 *    recorded/unknown" and never guessed; usable Wh conversions are shown only
 *    for recognized units.
 *  - **Virtualized and accessible.** The observation list is a [LazyColumn]
 *    keyed by `sampleId` (history may hold thousands of explicit cached rows),
 *    every row is a tappable, labeled control with a screen-reader description,
 *    and tapping opens a detail dialog that whitelists exactly the display
 *    fields above.
 */
@Composable
fun HistoryHealthRoute(
    snapshot: HistorySnapshot,
    nowEpochMillis: Long,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
) {
    // Stable error code persisted by the sync layer; never a raw server message.
    val syncPresentation = presentHistorySync(snapshot, nowEpochMillis)

    // Deduplicate by sampleId (the local store merges rows by sampleId, but a
    // defensive dedupe keeps the LazyColumn key unique no matter the input) and
    // precompute the newest-first, fully-virtualized row projections once.
    val observations = remember(snapshot.observations) {
        snapshot.observations
            .sortedWith(
                compareByDescending<Observation> { it.intervalStartEpochMillis }
                    .thenByDescending { it.intervalEndEpochMillis },
            )
            .distinctBy { it.sampleId }
    }
    val counters = remember(observations) { countSampleTypes(observations) }

    var selectedSampleId by remember { mutableStateOf<String?>(null) }
    val selected = selectedSampleId?.let { id -> observations.firstOrNull { it.sampleId == id } }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InstrumentPageHeading("History", if (LocalInterfaceDetail.current == InterfaceDetail.DETAILED) "Recorded five-minute observations · newest first" else null)
                }
            }
            item(key = "overview") {
                InstrumentPanel {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (LocalInterfaceDetail.current == InterfaceDetail.SIMPLE) "${counters.total} saved observations" else "${counters.total} cached · ${counters.usable} usable energy values", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(if (isRefreshing) "Refreshing history… Cached observations remain available." else syncPresentation.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        onRefresh?.let { refresh -> TextButton(onClick = refresh, enabled = !isRefreshing,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (isRefreshing) "Syncing history…" else syncPresentation.retryLabel)
                        } }
                    }
                    InstrumentDisclosure("Sync and collector health", "Refresh history, collector status and sample quality", informational = true) {
                        SyncContextCard(syncPresentation, snapshot.sync, snapshot.coveredRanges, nowEpochMillis, onRefresh, isRefreshing)
                        CollectorHealthCard(snapshot.collectorHealth, nowEpochMillis)
                        SampleCountersCard(counters)
                    }
                    InstrumentDisclosure("Sample status guide", "Gaps, anomalies and unknown units", informational = true) { SampleLegendCard() }
                }
            }
            if (observations.isEmpty()) {
                item(key = "empty") {
                    EmptyHistoryCard(onRefresh = onRefresh, retryLabel = syncPresentation.retryLabel)
                }
            } else {
                if (counters.usable == 0) {
                    item(key = "no-usable") {
                        NoUsableMeasurementsCard(
                            counters = counters,
                            onRefresh = onRefresh,
                            retryLabel = syncPresentation.retryLabel,
                        )
                    }
                }
                item(key = "list-header") {
                    Text(
                        "Cached observations (newest first)",
                        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(observations, key = { it.sampleId }) { sample ->
                    // Row strings are formed lazily only for visible items; the
                    // cached snapshot can include thousands of explicitly loaded
                    // historical observations.
                    val row = observationRowUi(sample)
                    ObservationRow(row = row, onClick = { selectedSampleId = row.sample.sampleId })
                }
            }
        }
    }

    val selectedRow = selected?.let(::observationRowUi)
    if (selectedRow != null) {
        ObservationDetailDialog(row = selectedRow, onDismiss = { selectedSampleId = null })
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun HistoryHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
         Text("History & health", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(
            "Cached Airchive observations and collector quality for this AC. This screen reads only " +
                "the local cached snapshot; it refreshes when the app runs a history sync.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------------------------------------------------------------------------
// Cache & sync context card
// ---------------------------------------------------------------------------

@Composable
private fun SyncContextCard(
    presentation: HistorySyncPresentation,
    sync: SyncMetadata?,
    coveredRanges: List<CoveredRange>,
    nowEpochMillis: Long,
    onRefresh: (() -> Unit)?,
    isRefreshing: Boolean,
) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cache & sync", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // One concise polite live region: only announces when sync status
            // changes to an error state (denied/offline/remote failure).
            Text(
                if (isRefreshing) {
                    "Refreshing Airchive history… Cached observations remain visible. ${presentation.message}"
                } else {
                    presentation.message
                },
                modifier = if (isRefreshing || presentation.shouldAnnounceUpdate) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            val lastSuccess = sync?.lastSuccessfulSyncEpochMillis
            if (lastSuccess != null) {
                Text("Last successful sync: ${syncInstantLabel(lastSuccess, nowEpochMillis)}.")
            } else {
                Text("Last successful sync: not yet.")
            }

            val lastErrorCode = sync?.lastError
            val lastErrorMillis = sync?.lastErrorEpochMillis
            if (lastErrorCode != null) {
                val label = syncErrorLabel(lastErrorCode)
                val whenText = if (lastErrorMillis != null) {
                    " (${ageLabel(ageFrom(lastErrorMillis, nowEpochMillis))} ago)"
                } else {
                    ""
                }
                Text("Last sync error: $label$whenText.")
            } else {
                Text("No recorded sync errors.")
            }

            Text(
                coveredRangeSummary(coveredRanges, DEFAULT_DAY_TIME_ZONE),
                style = MaterialTheme.typography.bodySmall,
            )

            onRefresh?.let { refresh ->
                Button(
                    enabled = !isRefreshing,
                    onClick = refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(if (isRefreshing) "Syncing history…" else presentation.retryLabel)
                }
            }
        }
    }
}

/** Maps the persisted stable error code to a fixed safe label (never a raw message). */
private fun syncErrorLabel(code: String): String = when (code) {
    "ACCESS_DENIED" -> "Access denied"
    "OFFLINE" -> "Offline"
    "REMOTE_FAILURE" -> "Remote failure"
    else -> "Other sync error"
}

private fun coveredRangeSummary(ranges: List<CoveredRange>, zoneId: String): String {
    if (ranges.isEmpty()) return "No cached covered ranges recorded yet."
    val zone = safeZone(zoneId)
    val from = localOf(ranges.minOf { it.fromEpochMillis }, zone)
    val to = localOf(ranges.maxOf { it.toEpochMillis }, zone)
    return "${ranges.size} covered range(s) on record, earliest ${describeDate(from)} → latest " +
        "${describeDate(to)} ($zoneId). These endpoints are an envelope; disjoint ranges may contain " +
        "uncovered gaps."
}

// ---------------------------------------------------------------------------
// Collector health card
// ---------------------------------------------------------------------------

@Composable
private fun CollectorHealthCard(health: CollectorHealth?, nowEpochMillis: Long) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Collector health", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (health == null) {
                Text(
                    "No collector health is cached for this partition yet. It appears once the collector " +
                        "writes its runtime record for this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Status: ${health.status.label()}.", style = MaterialTheme.typography.bodyMedium)
                health.lastPollEpochMillis?.let {
                    Text("Last poll: ${ageLabel(ageFrom(it, nowEpochMillis))} ago.")
                } ?: Text("Last poll: not recorded.")
                health.lastSuccessEpochMillis?.let {
                    Text("Last success: ${ageLabel(ageFrom(it, nowEpochMillis))} ago.")
                } ?: Text("Last success: not recorded.")
                health.lastFailureEpochMillis?.let {
                    Text("Last failure: ${ageLabel(ageFrom(it, nowEpochMillis))} ago.")
                } ?: Text("No recorded failure.")
                (health.consecutiveFailures ?: 0).takeIf { it > 0 }?.let { failures ->
                    Text("$failures consecutive failure(s).")
                }
                health.lastErrorClass?.takeIf { it.isNotBlank() }?.let { clazz ->
                    Text("Last failure class: ${clazz.safeHistoryLabel()}.")
                }
                health.collectorVersion?.takeIf { it.isNotBlank() }?.let { version ->
                    Text("Collector version: ${version.safeHistoryLabel()}.")
                }
                Text(
                    "Health is cached sample data from the collector; it does not describe a live connection.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun CollectorStatus.label(): String = when (this) {
    CollectorStatus.UNKNOWN -> "Unknown"
    CollectorStatus.HEALTHY -> "Healthy"
    CollectorStatus.DEGRADED -> "Degraded"
    CollectorStatus.OFFLINE -> "Offline"
}

// ---------------------------------------------------------------------------
// Sample counters card
// ---------------------------------------------------------------------------

internal data class SampleCounters(
    val total: Int,
    val normal: Int,
    val anomalous: Int,
    val missingOrUnavailable: Int,
    val energyFailed: Int,
    val stateFailed: Int,
    val unclassifiedLegacyFailure: Int,
    val usable: Int,
)

internal fun countSampleTypes(samples: List<Observation>): SampleCounters {
    var normal = 0
    var anomalous = 0
    var missingOrUnavailable = 0
    var energyFailed = 0
    var stateFailed = 0
    var unclassifiedLegacyFailure = 0
    var usable = 0
    for (sample in samples) {
        when (sample.quality) {
            ObservationQuality.NORMAL -> normal++
            ObservationQuality.ANOMALOUS -> anomalous++
            ObservationQuality.MISSING_INTERVAL, ObservationQuality.UNAVAILABLE -> missingOrUnavailable++
        }
        if (sample.hasEnergyReadFailure()) energyFailed++
        if (sample.hasStateReadFailure()) stateFailed++
        if (sample.sourceFailure?.isNotBlank() == true &&
            !sample.hasEnergyReadFailure() && !sample.hasStateReadFailure()
        ) {
            unclassifiedLegacyFailure++
        }
        if (isUsableEnergy(sample)) usable++
    }
    return SampleCounters(
        total = samples.size,
        normal = normal,
        anomalous = anomalous,
        missingOrUnavailable = missingOrUnavailable,
        energyFailed = energyFailed,
        stateFailed = stateFailed,
        unclassifiedLegacyFailure = unclassifiedLegacyFailure,
        usable = usable,
    )
}

@Composable
private fun SampleCountersCard(counters: SampleCounters) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Cached samples", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (counters.total == 0) {
                Text(
                    "No samples are cached yet. Use the retry action below to try loading Airchive history data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "${counters.total} cached observation(s): ${counters.normal} normal · " +
                        "${counters.anomalous} anomalous · ${counters.missingOrUnavailable} missing/unavailable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${counters.energyFailed} energy-read failure(s) · ${counters.stateFailed} state-read " +
                        "failure(s) · ${counters.unclassifiedLegacyFailure} unclassified legacy source " +
                        "failure(s) · ${counters.usable} usable energy value(s).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Counts can overlap — one sample may be anomalous and also have a failed source read.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status legend (never color-only)
// ---------------------------------------------------------------------------

@Composable
private fun SampleLegendCard() {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("What each sample status means", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "NORMAL — the collector did not flag an anomaly. The energy value may still be missing or " +
                    "carry an unknown unit; only finite values with a recognized unit count as usable energy.",
            )
            Text(
                "ANOMALOUS — the interval exists but the collector/dashboard flags it (for example a partial " +
                    "observation, device offline, or an implausible value). It stays visible but is excluded from " +
                    "usable-energy counts.",
            )
            Text("MISSING_INTERVAL — the interval exists but has no usable energy value; it is a gap.")
            Text("UNAVAILABLE — no sample exists for the slot; it is a gap.")
            Text(
                "A null or missing energy value is a gap — never a measured zero.",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty / no-usable-measurement states
// ---------------------------------------------------------------------------

@Composable
private fun EmptyHistoryCard(onRefresh: (() -> Unit)?, retryLabel: String) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No cached observations yet", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing is cached for this device yet. Use refresh/retry to load Airchive history; once rows " +
                    "exist they remain available offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            onRefresh?.let { refresh ->
                Button(
                    onClick = refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Composable
private fun NoUsableMeasurementsCard(
    counters: SampleCounters,
    onRefresh: (() -> Unit)?,
    retryLabel: String,
) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cached, but no usable measurements yet", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${counters.total} sample(s) are cached, but none carries a usable energy value (normal quality " +
                    "with a recognized unit and no energy-read failure). Gaps, anomalous values, and unknown-unit " +
                    "values are never counted as measured zero.",
                style = MaterialTheme.typography.bodyMedium,
            )
            onRefresh?.let { refresh ->
                Button(
                    onClick = refresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(retryLabel)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Observation row (virtualized, clickable, accessible)
// ---------------------------------------------------------------------------

/** Immutable, precomputed row projection so each LazyColumn row stays cheap. */
private data class ObservationRowUi(
    val sample: Observation,
    val zoneId: String,
    val intervalTimeText: String,
    val energyText: String,
    val statusText: String,
    val flagText: String?,
    val failureText: String?,
    val contentDescription: String,
)

private fun observationRowUi(sample: Observation): ObservationRowUi {
    val zoneId = safeZoneId(sample.timezone)
    val zone = safeZone(zoneId)
    val start = localOf(sample.intervalStartEpochMillis, zone)
    val end = localOf(sample.intervalEndEpochMillis, zone)
    val intervalTimeText = "${describeDate(start)} ${describeClock(start)}–${describeClock(end)} $zoneId"
    val energyText = sampleEnergyText(sample)
    val statusText = buildString {
        append(sample.quality.label())
        sample.intervalStatus?.takeIf { it.isNotBlank() }?.let {
            append(" · status \"").append(it.safeHistoryLabel()).append('"')
        }
    }
    val flagText = sampleFlagsText(sample)
    val failureText = sampleFailuresText(sample)
    val contentDescription = buildString {
        append("Sample at ").append(intervalTimeText).append(". ")
        append(energyText).append(" ")
        append("Status: ").append(sample.quality.label()).append(". ")
        flagText?.let { append("Flags: ").append(it).append(". ") }
        failureText?.let { append(it).append(". ") }
        append("Tap for details.")
    }
    return ObservationRowUi(
        sample = sample,
        zoneId = zoneId,
        intervalTimeText = intervalTimeText,
        energyText = energyText,
        statusText = statusText,
        flagText = flagText,
        failureText = failureText,
        contentDescription = contentDescription,
    )
}

@Composable
private fun ObservationRow(row: ObservationRowUi, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "View sample detail", onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = row.contentDescription
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.intervalTimeText, style = MaterialTheme.typography.titleSmall)
            Text(row.energyText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                row.statusText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            row.flagText?.let {
                Text("Flags: $it", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            row.failureText?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * One-line energy summary for a row. Shows the recorded value/unit only when a
 * value is present; unknown/missing units stay "unit not recorded/unknown".
 * A failed energy read (or a null value) is described as a gap, never a zero.
 */
private fun sampleEnergyText(sample: Observation): String = sample.historyEnergySummaryText()

/** Pure row/detail projection shared with unit tests; only safe normalized fields are read. */
internal fun Observation.historyEnergySummaryText(): String {
    val value = energyValue
    val unit = recordedUnit
    return when {
        hasEnergyReadFailure() -> "Energy read failed — shown as a gap, never a measured zero."
        quality == ObservationQuality.MISSING_INTERVAL || quality == ObservationQuality.UNAVAILABLE ->
            "${quality.label()} — treated as a gap, never a measured zero."
        value == null -> "No energy value — a gap, never a measured zero."
        !value.isFinite() -> "Non-finite energy value — unusable gap, never a measured zero."
        quality == ObservationQuality.ANOMALOUS -> {
            val sourceValue = value.toTrimmedString()
            if (unit == null) {
                val rawUnit = unitText?.takeIf { it.isNotBlank() }?.let { " (${it.safeHistoryLabel()})" }.orEmpty()
                "$sourceValue$rawUnit · unit not recorded/unknown; anomalous and excluded from usable totals"
            } else {
                "$sourceValue ${unit.energyUnitLabel()} · anomalous, excluded from usable totals"
            }
        }
        unit != null && value.isFinite() && quality == ObservationQuality.NORMAL -> {
            val normalized = normalizeToWh(value, unit)
            val whText = normalized?.let { " ≈ ${it.toTrimmedString()} Wh" }.orEmpty()
            "${value.toTrimmedString()} ${unit.energyUnitLabel()}$whText"
        }
        unit == null -> {
            val sourceValue = intervalValue ?: value.toTrimmedString()
            val rawUnit = unitText?.takeIf { it.isNotBlank() }?.let { " (${it.safeHistoryLabel()})" }.orEmpty()
            "$sourceValue$rawUnit · unit not recorded/unknown (never guessed)"
        }
        else -> "${value.toTrimmedString()} ${unit.energyUnitLabel()}"
    }
}

private fun sampleFlagsText(sample: Observation): String? = buildList {
    sample.qualityFlags.forEach { add(it.label()) }
    val sourceFlags = sample.flagTexts.filter { it.isNotBlank() }.sorted()
    sourceFlags.take(MAX_SOURCE_FLAGS).forEach { add(it.safeHistoryLabel()) }
    if (sourceFlags.size > MAX_SOURCE_FLAGS) add("${sourceFlags.size - MAX_SOURCE_FLAGS} additional source flag(s) omitted")
}.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")

/** Typed energy/state failure summary; never names the source `raw` block or raw messages. */
private fun sampleFailuresText(sample: Observation): String? = sample.historySourceFailureSummary()

/** Whitelisted typed/legacy source-failure explanation for row and detail rendering. */
internal fun Observation.historySourceFailureSummary(): String? = buildList {
    when {
        energySourceFailure?.ok == false -> add("Energy read failed: ${energySourceFailure.safeDetail()}")
        hasEnergyReadFailure() -> add("A legacy energy-read failure is recorded.")
    }
    when {
        stateSourceFailure?.ok == false ->
            add("State read failed: ${stateSourceFailure.safeDetail()} (this sample's energy value is unaffected)")
        hasStateReadFailure() ->
            add("A legacy state-read failure is recorded (this sample's energy value is unaffected).")
    }
    if (sourceFailure?.isNotBlank() == true &&
        !hasEnergyReadFailure() && !hasStateReadFailure()
    ) {
        add("A legacy source failure is recorded.")
    }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun ObservationQuality.label(): String = when (this) {
    ObservationQuality.NORMAL -> "Normal"
    ObservationQuality.ANOMALOUS -> "Anomalous"
    ObservationQuality.MISSING_INTERVAL -> "Missing interval"
    ObservationQuality.UNAVAILABLE -> "Unavailable"
}

private fun QualityFlag.label(): String = when (this) {
    QualityFlag.MISSING_VALUE -> "missing value"
    QualityFlag.OUT_OF_BOUNDS -> "out of bounds"
    QualityFlag.SPIKED -> "spiked"
    QualityFlag.PARTIAL_INTERVAL -> "partial interval"
    QualityFlag.SOURCE_FAILED -> "source failed"
}

/**
 * Whitelisted failure detail: `failureClass`/`errorCode`/`errorName` only.
 * `ok` is intentionally not shown as a bare boolean in the dialog-safe text.
 */
private fun SourceReadFailure.safeDetail(): String = buildList {
    failureClass?.takeIf { it.isNotBlank() }?.let { add("class ${it.safeHistoryLabel(80)}") }
    errorCode?.takeIf { it.isNotBlank() }?.let { add("code ${it.safeHistoryLabel(80)}") }
    errorName?.takeIf { it.isNotBlank() }?.let { add("name ${it.safeHistoryLabel(80)}") }
}.joinToString(" · ").ifEmpty { "failed" }

/** Flatten and bound source-provided labels before they enter UI/accessibility strings. */
private fun String.safeHistoryLabel(maxChars: Int = MAX_SOURCE_LABEL_CHARS): String {
    val flattened = map { char -> if (char == '\n' || char == '\r' || char == '\t') ' ' else char }
        .filter { char -> char >= ' ' && char != '\u007f' }
        .joinToString(separator = "")
    return if (flattened.length <= maxChars) flattened else flattened.take(maxChars - 1) + "…"
}

private const val MAX_SOURCE_LABEL_CHARS = 120
private const val MAX_SOURCE_FLAGS = 12

private fun EnergyUnit?.energyUnitLabel(): String = when (this) {
    EnergyUnit.WH -> "Wh"
    EnergyUnit.KWH -> "kWh"
    null -> "unit not recorded/unknown"
}

private fun EnergyUnitSource.label(): String = when (this) {
    EnergyUnitSource.DEVICE -> "device"
    EnergyUnitSource.CONFIGURED -> "configured"
}

/** Finite NORMAL value, recognized unit, no energy-read failure. */
private fun isUsableEnergy(sample: Observation): Boolean = sample.hasUsableHistoryEnergy()

/** Finite NORMAL value, recognized unit, no energy-read failure, and no explicit gap quality. */
internal fun Observation.hasUsableHistoryEnergy(): Boolean {
    val value = energyValue ?: return false
    val unit = recordedUnit ?: return false
    if (!value.isFinite() || quality != ObservationQuality.NORMAL) return false
    if (hasEnergyReadFailure()) return false
    return normalizeToWh(value, unit) != null
}

/** Resolves legacy combined failure text conservatively without echoing its detail. */
private fun Observation.hasEnergyReadFailure(): Boolean =
    energySourceFailure?.ok == false ||
        (energySourceFailure == null && sourceFailure?.startsWith("energy:", ignoreCase = true) == true)

/** State-only failure stays independent from energy quality and energy sums. */
private fun Observation.hasStateReadFailure(): Boolean =
    stateSourceFailure?.ok == false ||
        (stateSourceFailure == null && sourceFailure?.startsWith("state:", ignoreCase = true) == true)

// ---------------------------------------------------------------------------
// Observation detail dialog (whitelisted display fields only)
// ---------------------------------------------------------------------------

@Composable
private fun ObservationDetailDialog(row: ObservationRowUi, onDismiss: () -> Unit) {
    val sample = row.sample
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sample detail", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailLine("Sample ID", sample.sampleId.safeHistoryLabel(160))
                DetailLine("Interval", exactIntervalText(sample))
                DetailLine("Local date", sample.localDate?.takeIf { it.isNotBlank() }?.safeHistoryLabel() ?: "not recorded")
                DetailLine("Timezone", zoneDisplay(sample.timezone))
                DetailLine("Scheduled at", epochMillisText(sample.scheduledAtEpochMillis, zoneDisplay(sample.timezone)))
                DetailLine("Observed at", epochMillisText(sample.observedAtEpochMillis, zoneDisplay(sample.timezone)))
                DetailLine("Energy", detailEnergyText(sample))
                DetailLine("Unit provenance", unitProvenanceText(sample))
                DetailLine("Completeness", completenessText(sample))
                DetailLine("Quality", sample.quality.label())
                sample.intervalStatus?.takeIf { it.isNotBlank() }?.let {
                    DetailLine("Interval status", it.safeHistoryLabel())
                }
                val flags = sampleFlagsText(sample)
                if (flags != null) DetailLine("Flags", flags)

                DetailLine(
                    "Source read failures",
                    sampleFailuresText(sample) ?: "No source read failures recorded.",
                )

                sample.persistedAtEpochMillis?.let {
                    DetailLine("Persisted at", epochMillisText(it, zoneDisplay(sample.timezone)))
                }
                sample.reconciledAtEpochMillis?.let {
                    DetailLine("Reconciled at", epochMillisText(it, zoneDisplay(sample.timezone)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun exactIntervalText(sample: Observation): String {
    val zoneId = zoneDisplay(sample.timezone)
    val zone = safeZone(zoneId)
    val start = localOf(sample.intervalStartEpochMillis, zone)
    val end = localOf(sample.intervalEndEpochMillis, zone)
    return buildString {
        append(describeDateTime(start)).append(" → ").append(describeDateTime(end))
        append(" · ").append(sample.intervalStartEpochMillis).append("–").append(sample.intervalEndEpochMillis)
        append(" ms · ").append(zoneId)
    }
}

private fun detailEnergyText(sample: Observation): String {
    val value = sample.energyValue
    val unit = sample.recordedUnit
    val energyFailed = sample.hasEnergyReadFailure()
    return when {
        energyFailed -> "Energy read failed — this interval is shown as a gap, never a measured zero."
        sample.quality == ObservationQuality.MISSING_INTERVAL || sample.quality == ObservationQuality.UNAVAILABLE ->
            "${sample.quality.label()} — this interval is a gap, never a measured zero (any source value is excluded)."
        value == null -> "No energy value recorded — gap (never a measured zero)."
        !value.isFinite() -> "Non-finite energy value — unusable gap, never a measured zero."
        sample.quality == ObservationQuality.ANOMALOUS -> {
            if (unit == null) {
                val sourceValue = sample.intervalValue?.safeHistoryLabel() ?: value.toTrimmedString()
                val rawUnit = sample.unitText?.takeIf { it.isNotBlank() }?.let { " (\"${it.safeHistoryLabel()}\")" }.orEmpty()
                "$sourceValue$rawUnit · unit not recorded/unknown; anomalous and excluded from usable totals."
            } else {
                "${value.toTrimmedString()} ${unit.energyUnitLabel()} · anomalous, so it is excluded from usable totals."
            }
        }
        unit == null -> {
            val sourceValue = sample.intervalValue?.safeHistoryLabel() ?: value.toTrimmedString()
            val rawUnit = sample.unitText?.takeIf { it.isNotBlank() }?.let { " (\"${it.safeHistoryLabel()}\")" }.orEmpty()
            "$sourceValue$rawUnit · unit not recorded/unknown, kept raw and never guessed."
        }
        value.isFinite() -> {
            val normalized = normalizeToWh(value, unit)
            val whText = normalized?.let { " ≈ ${it.toTrimmedString()} Wh (normalized)" }.orEmpty()
            "${value.toTrimmedString()} ${unit.energyUnitLabel()}$whText."
        }
        else -> "${value.toTrimmedString()} ${unit.energyUnitLabel()}."
    }
}

private fun unitProvenanceText(sample: Observation): String = buildList {
    sample.recordedUnit?.let { add("recognized ${it.energyUnitLabel()}") }
    sample.unitSource?.let { add("source: ${it.label()}") }
    sample.unitText?.takeIf { it.isNotBlank() }?.let { add("raw unit text \"${it.safeHistoryLabel()}\"") }
    sample.unitSourceText?.takeIf { it.isNotBlank() }?.let { add("unit provenance \"${it.safeHistoryLabel()}\"") }
}.joinToString(" · ").ifEmpty { "not recorded/unknown" }

private fun completenessText(sample: Observation): String = sample.completeness?.let {
    "$it (2 × energy read ok + 1 × state read ok, per the collector)"
} ?: "not recorded"

private fun epochMillisText(epochMillis: Long?, zoneId: String): String {
    if (epochMillis == null) return "not recorded"
    val zone = safeZone(zoneId)
    return "${describeDateTime(localOf(epochMillis, zone))} $zoneId · $epochMillis ms"
}

// ---------------------------------------------------------------------------
// Small formatting helpers
// ---------------------------------------------------------------------------

private fun syncInstantLabel(epochMillis: Long, nowEpochMillis: Long): String {
    val zone = safeZone(DEFAULT_DAY_TIME_ZONE)
    val at = "${describeDateTime(localOf(epochMillis, zone))} $DEFAULT_DAY_TIME_ZONE"
    val age = ageFrom(epochMillis, nowEpochMillis)
    return "$at (${ageLabel(age)} ago)"
}

private fun ageFrom(eventEpochMillis: Long, nowEpochMillis: Long): Long =
    (nowEpochMillis - eventEpochMillis).coerceAtLeast(0L)

private fun ageLabel(ageMillis: Long): String = when {
    ageMillis < 60_000L -> "less than a minute"
    ageMillis < 60 * 60_000L -> "${ageMillis / 60_000L} minutes"
    ageMillis < 24 * 60 * 60_000L -> "${ageMillis / (60 * 60_000L)} hours"
    else -> "${ageMillis / (24 * 60 * 60_000L)} days"
}

/** The survey zone for display: the sample's recorded zone, or the safe fallback. */
private fun zoneDisplay(timezone: String?): String = timezone
    ?.takeIf { runCatching { TimeZone.of(it) }.isSuccess }
    ?: DEFAULT_DAY_TIME_ZONE

private fun safeZoneId(timezone: String?): String = zoneDisplay(timezone)

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

/** Fixed scale (2 decimals), trailing zeros trimmed; never scientific notation. */
private fun Double.toTrimmedString(): String {
    if (!isFinite()) return "n/a"
    val negative = this < 0
    val scaled = (abs(this) * 100L).roundToLong()
    val intPart = scaled / 100L
    val frac = (scaled % 100L).toInt()
    val text = buildString {
        append(intPart)
        if (frac != 0) {
            append('.')
            var digits = frac.toString().padStart(2, '0')
            while (digits.endsWith('0')) digits = digits.dropLast(1)
            append(digits)
        }
    }
    return if (negative && (intPart > 0 || frac != 0)) "-$text" else text
}
