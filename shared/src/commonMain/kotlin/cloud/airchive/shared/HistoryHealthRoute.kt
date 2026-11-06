package cloud.airchive.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
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
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
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
 *  - **Virtualized and accessible.** Hour groups are virtualized, with selectable
 *    observation details available within each expanded group.
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
    val days = remember(observations, nowEpochMillis) { historyDays(observations, nowEpochMillis) }
    val maxHourWh = remember(days) { maxHourEnergyWh(days) }
    val today = remember(nowEpochMillis) { historyToday(nowEpochMillis) }
    val detailed = LocalInterfaceDetail.current == InterfaceDetail.DETAILED

    var selectedSample by remember(snapshot.partition) { mutableStateOf<HistorySampleSelection?>(null) }
    val selected = selectedHistoryObservation(selectedSample, snapshot)
    // Held at route level (not per row) so an opened hour stays open while it scrolls out of and back
    // into the virtualized list. A different owner/device partition starts with everything collapsed.
    val expandedHours = remember(snapshot.partition) { mutableStateListOf<String>() }

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
                    InstrumentPageHeading("History", if (detailed) "Hourly energy from recorded five-minute observations · newest first" else null)
                }
            }
            item(key = "overview") {
                InstrumentPanel {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (!detailed) "${counters.total} saved observations" else "${counters.total} cached · ${counters.usable} usable energy values", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(if (isRefreshing) "Refreshing history… Cached observations remain available." else syncPresentation.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        // Zero horizontal padding keeps the action's label on the same left edge as the text above.
                        onRefresh?.let { refresh -> TextButton(onClick = refresh, enabled = !isRefreshing,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (isRefreshing) "Syncing history…" else syncPresentation.retryLabel)
                        } }
                    }
                    InstrumentDisclosure("Sync and collector health", "Refresh history, collector status and sample quality", informational = true) {
                        SyncContextCard(snapshot.sync, snapshot.coveredRanges, nowEpochMillis, isRefreshing)
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
                        "Recorded hours (newest first)",
                        modifier = Modifier.padding(top = 20.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                // Each day reads as one panel: a header (with the day's total) and its hours,
                // split into virtualized rows whose corners are rounded only at the panel's ends.
                days.forEach { day ->
                    item(key = "day-${day.date}") {
                        HistoryDayHeader(day = day, today = today)
                    }
                    itemsIndexed(day.hours, key = { _, hour -> hour.key }) { index, hour ->
                        HistoryHourRow(
                            hour = hour,
                            maxWh = maxHourWh,
                            expanded = hour.key in expandedHours,
                            detailed = detailed,
                            isLast = index == day.hours.lastIndex,
                            // An open hour above ends in its own tinted band, which already separates the two.
                            showTopRule = index == 0 || day.hours[index - 1].key !in expandedHours,
                            onToggle = { if (!expandedHours.remove(hour.key)) expandedHours.add(hour.key) },
                            onSample = { sample -> selectedSample = HistorySampleSelection(snapshot.partition, sample.sampleId) },
                        )
                    }
                }
            }
        }
    }

    val selectedRow = selected?.let(::observationRowUi)
    if (selectedRow != null) {
        ObservationDetailDialog(row = selectedRow, onDismiss = { selectedSample = null })
    }
}

/** History detail selection belongs to the owner/project/device partition, not the PAT generation. */
internal data class HistorySampleSelection(
    val partition: HistoryPartition,
    val sampleId: String,
)

/** Prevents an old partition's identifier from selecting a same-id row in another owner's cache. */
internal fun selectedHistoryObservation(
    selection: HistorySampleSelection?,
    snapshot: HistorySnapshot,
): Observation? {
    if (selection == null || selection.partition != snapshot.partition) return null
    return snapshot.observations.firstOrNull { it.sampleId == selection.sampleId }
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

/**
 * Sync status detail. The overview panel above already carries the live sync message and the one
 * refresh action, so this card adds only what that panel does not say.
 */
@Composable
private fun SyncContextCard(
    sync: SyncMetadata?,
    coveredRanges: List<CoveredRange>,
    nowEpochMillis: Long,
    isRefreshing: Boolean,
) {
    InstrumentBlock(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cache & sync", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            HistoryStatusBadge(when {
                isRefreshing -> "Syncing history"
                sync?.lastError != null -> syncErrorLabel(sync.lastError!!)
                sync?.lastSuccessfulSyncEpochMillis != null -> "History cached"
                else -> "Awaiting first sync"
            }, attention = sync?.lastError != null)
            val lastSuccess = sync?.lastSuccessfulSyncEpochMillis
            if (lastSuccess != null) {
                InstrumentDetail("Last successful sync", syncInstantLabel(lastSuccess, nowEpochMillis))
            } else {
                InstrumentDetail("Last successful sync", "Not yet")
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
                InstrumentDetail("Last sync error", "$label$whenText")
            } else {
                InstrumentDetail("Sync errors", "None recorded")
            }

            Text(
                coveredRangeSummary(coveredRanges, DEFAULT_DAY_TIME_ZONE),
                style = MaterialTheme.typography.bodySmall,
            )
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
                HistoryStatusBadge(health.status.label(), attention = health.status != CollectorStatus.HEALTHY)
                Text("Cached health record; this is not a live connection indicator.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                health.lastPollEpochMillis?.let {
                    InstrumentDetail("Last poll", "${ageLabel(ageFrom(it, nowEpochMillis))} ago")
                } ?: InstrumentDetail("Last poll", "Not recorded")
                health.lastSuccessEpochMillis?.let {
                    InstrumentDetail("Last successful collection", "${ageLabel(ageFrom(it, nowEpochMillis))} ago")
                } ?: InstrumentDetail("Last successful collection", "Not recorded")
                health.lastFailureEpochMillis?.let {
                    InstrumentDetail("Last failure", "${ageLabel(ageFrom(it, nowEpochMillis))} ago")
                } ?: InstrumentDetail("Collector failures", "None recorded")
                (health.consecutiveFailures ?: 0).takeIf { it > 0 }?.let { failures ->
                    InstrumentDetail("Consecutive failures", "$failures")
                }
                health.lastErrorClass?.takeIf { it.isNotBlank() }?.let { clazz ->
                    InstrumentDetail("Failure category", clazz.safeHistoryLabel())
                }
                health.collectorVersion?.takeIf { it.isNotBlank() }?.let { version ->
                    InstrumentDetail("Collector version", version.safeHistoryLabel())
                }
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
                InstrumentDetail("Cached observations", "${counters.total}")
                InstrumentDetail("Usable energy values", "${counters.usable}", "Finite readings with recognized units, excluding anomalies and energy-read failures.")
                InstrumentDetail("Sample quality", "${counters.normal} normal · ${counters.anomalous} anomalous", "${counters.missingOrUnavailable} missing or unavailable")
                InstrumentDetail("Source read failures", "${counters.energyFailed} energy · ${counters.stateFailed} state", "${counters.unclassifiedLegacyFailure} unclassified legacy source failures")
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
            InstrumentDetail("Normal", "No anomaly flagged", "Usable energy still requires a finite value and a recognized unit. Normal does not guarantee a usable reading.")
            InstrumentDetail("Anomalous", "Recorded, excluded from usable energy", "Flagged by the collector or dashboard, for example a partial observation, offline device or implausible value. The original sample stays visible.")
            InstrumentDetail("Missing interval", "Gap in measured energy", "An interval record exists, but it contains no usable energy value.")
            InstrumentDetail("Unavailable", "No sample for this slot", "No observation was recorded. This remains a gap.")
            InstrumentDetail("Unknown unit", "Value preserved without conversion", "A recorded value without a recognized unit is excluded from usable energy; its unit is never guessed.")
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
    /** `17:05–17:10`, in the sample's own zone, with the zone named only when it is not the AC's. */
    val clockText: String,
    val summary: HistoryRowSummary,
    /** Full spoken description: complete date, interval, zone, wording and status. */
    val contentDescription: String,
)

private fun observationRowUi(sample: Observation): ObservationRowUi {
    val zoneId = safeZoneId(sample.timezone)
    val zone = safeZone(zoneId)
    val start = localOf(sample.intervalStartEpochMillis, zone)
    val end = localOf(sample.intervalEndEpochMillis, zone)
    val intervalTimeText = "${describeDate(start)} ${describeClock(start)}–${describeClock(end)} $zoneId"
    val clockText = buildString {
        append(describeClock(start)).append('–').append(describeClock(end))
        if (zoneId != DEFAULT_DAY_TIME_ZONE) append(' ').append(zoneId)
    }
    val flagText = sampleFlagsText(sample)
    val failureText = sampleFailuresText(sample)
    val contentDescription = buildString {
        append("Sample at ").append(intervalTimeText).append(". ")
        append(sampleEnergyText(sample)).append(" ")
        append("Status: ").append(sample.quality.label()).append(". ")
        flagText?.let { append("Flags: ").append(it).append(". ") }
        failureText?.let { append(it).append(". ") }
        append("Tap for details.")
    }
    return ObservationRowUi(
        sample = sample,
        clockText = clockText,
        summary = sample.historyRowSummary(),
        contentDescription = contentDescription,
    )
}

/**
 * What a ledger row shows at a glance. A clean, usable reading carries only its [valueText]; anything
 * that changes how the reading should be trusted adds a worded [badge] (never color alone) and a
 * short [note]. The full wording remains in [historyEnergySummaryText] and the detail dialog.
 */
internal data class HistoryRowSummary(
    val valueText: String,
    val badge: String? = null,
    val note: String? = null,
)

private const val GAP_VALUE = "—"

internal fun Observation.historyRowSummary(): HistoryRowSummary {
    val value = energyValue
    val unit = recordedUnit
    val extras = listOfNotNull(
        if (hasStateReadFailure()) "State read failed · energy unaffected" else null,
        qualityFlags.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Flags: ") { it.label() },
    )
    fun note(vararg lead: String?) = (lead.filterNotNull() + extras).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return when {
        hasEnergyReadFailure() -> HistoryRowSummary(GAP_VALUE, "Gap", note("Energy read failed"))
        quality == ObservationQuality.MISSING_INTERVAL || quality == ObservationQuality.UNAVAILABLE ->
            HistoryRowSummary(GAP_VALUE, "Gap", note(quality.label()))
        value == null -> HistoryRowSummary(GAP_VALUE, "Gap", note("No energy value"))
        !value.isFinite() -> HistoryRowSummary(GAP_VALUE, "Gap", note("Value unusable"))
        quality == ObservationQuality.ANOMALOUS ->
            HistoryRowSummary(recordedValueText(value, unit), "Anomalous", note("Excluded from usable totals"))
        unit == null ->
            HistoryRowSummary(recordedValueText(value, null), "Unit unknown", note("Not converted"))
        else -> {
            val converted = if (unit == EnergyUnit.WH) null else normalizeToWh(value, unit)?.let { "≈ ${it.toTrimmedString()} Wh" }
            HistoryRowSummary("${value.toTrimmedString()} ${unit.energyUnitLabel()}", note = note(converted))
        }
    }
}

/** The recorded number with its unit when recognized, or the raw unit text, never a guessed one. */
private fun Observation.recordedValueText(value: Double, unit: EnergyUnit?): String {
    if (unit != null) return "${value.toTrimmedString()} ${unit.energyUnitLabel()}"
    val rawUnit = unitText?.takeIf { it.isNotBlank() }?.let { " (${it.safeHistoryLabel()})" }.orEmpty()
    return "${intervalValue ?: value.toTrimmedString()}$rawUnit"
}

// ---------------------------------------------------------------------------
// Ledger: day header, hour row, five-minute row
// ---------------------------------------------------------------------------

private val LedgerInset = 20.dp

/** Tabular figures keep the stacked kWh readings aligned as the digits change. */
private fun tabularStyle(base: TextStyle): TextStyle = base.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
private fun HistoryDayHeader(day: HistoryDay, today: LocalDate) {
    val title = historyDayTitle(day.date, today)
    val dateLine = if (title == "Today" || title == "Yesterday") historyShortDate(day.date) else null
    val total = day.energyWh?.let { "${hourlyKwh(it)} kWh" }
    val caption = when {
        day.energyWh == null -> null
        day.recordedHours < day.expectedHours -> "Partial · ${day.recordedHours} of ${day.expectedHours} hours"
        day.isPartial -> "Partial · some readings missing"
        day.isCurrentDay -> "So far"
        else -> null
    }
    val spoken = buildString {
        if (dateLine != null) append(title).append(", ")
        append(historySpokenDate(day.date)).append(". ")
        append(day.energyWh?.let { "${hourlyKwh(it)} kilowatt-hours recorded" } ?: "No usable energy recorded").append(". ")
        caption?.let { append(it).append('.') }
    }
    val corner = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = corner.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LedgerInset, end = LedgerInset, top = 16.dp, bottom = 12.dp)
                .semantics(mergeDescendants = true) { heading(); contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                dateLine?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.widthIn(max = 200.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    total ?: "Unavailable",
                    style = tabularStyle(MaterialTheme.typography.titleMedium),
                    color = if (total == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                caption?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (day.isPartial) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryHourRow(
    hour: HistoryHour,
    maxWh: Double,
    expanded: Boolean,
    detailed: Boolean,
    isLast: Boolean,
    showTopRule: Boolean,
    onToggle: () -> Unit,
    onSample: (Observation) -> Unit,
) {
    val range = historyHourRange(hour.group.hour)
    val energyWh = hour.group.energyWh
    val note = hour.note(detailed)
    val amount = energyWh?.let { "${hourlyKwh(it)} kWh" } ?: "Unavailable"
    val spoken = buildString {
        append("Hour ").append(range.replace("–", " to ")).append(". ")
        append(energyWh?.let { "${hourlyKwh(it)} kilowatt-hours" } ?: "No usable energy").append(". ")
        note.badge?.let { append(it).append(". ") }
        note.text?.let { append(it).append('.') }
    }
    val corner = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (isLast) corner.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize) else RectangleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            if (showTopRule) HorizontalDivider(Modifier.padding(horizontal = LedgerInset), color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(
                        onClickLabel = if (expanded) "Hide five-minute readings" else "Show five-minute readings",
                        role = Role.Button,
                        onClick = onToggle,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = LedgerInset, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(range, style = tabularStyle(MaterialTheme.typography.titleSmall), modifier = Modifier.weight(1f))
                    Text(
                        amount,
                        style = tabularStyle(MaterialTheme.typography.titleMedium),
                        color = if (energyWh == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    InstrumentChevron(expanded)
                }
                if (energyWh != null) {
                    HistoryEnergyBar(hourBarFraction(energyWh, maxWh), attention = hour.status == HistoryHourStatus.PARTIAL)
                }
                if (note.badge != null || note.text != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        note.badge?.let { HistoryChip(it, attention = hour.status != HistoryHourStatus.IN_PROGRESS) }
                        note.text?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (expanded) {
                // Readings sit on the porcelain ground so they read as the hour's contents, not more hours.
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                    hour.samples.forEachIndexed { index, sample ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = LedgerInset + 12.dp, end = LedgerInset), color = MaterialTheme.colorScheme.outlineVariant)
                        HistorySampleRow(observationRowUi(sample), onClick = { onSample(sample) })
                    }
                }
            }
        }
    }
}

/** Relative size of the hour among all cached hours. Decorative: the kWh figure carries the value. */
@Composable
private fun HistoryEnergyBar(fraction: Float, attention: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics {},
    ) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(if (attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** A short worded status. Amber marks incomplete or untrusted data; slate marks neutral context. */
@Composable
private fun HistoryChip(label: String, attention: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (attention) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (attention) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun HistorySampleRow(row: ObservationRowUi, onClick: () -> Unit) {
    val summary = row.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "View sample detail", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = row.contentDescription }
            .padding(start = LedgerInset + 12.dp, end = LedgerInset, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.clockText, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            if (summary.badge != null || summary.note != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.badge?.let { HistoryChip(it, attention = true) }
                    summary.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        Text(
            summary.valueText,
            style = tabularStyle(MaterialTheme.typography.bodyMedium),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        InstrumentChevron(expanded = false, modifier = Modifier.rotate(-90f), ink = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun HistoryStatusBadge(label: String, attention: Boolean) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = if (attention) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (attention) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
