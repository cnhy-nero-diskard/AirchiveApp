package cloud.airchive.shared.data.telemetry

import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.EnergyUnitSource
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** IANA day-boundary zone for daily totals (matches `config/day.timeZone`). */
const val DEFAULT_DAY_TIME_ZONE: String = "Asia/Manila"

/** Five-minute collector cadence used as the safe interval fallback. */
const val FALLBACK_INTERVAL_MILLIS: Long = 5 * 60 * 1000L

/** Sibling `IntervalStatus` values the local dashboard treats as anomalous. */
private val ANOMALOUS_STATUSES = setOf(
    "ANOMALOUS_DECREASE",
    "DAY_ROLLOVER_UNRESOLVED",
    "MULTI_DAY_GAP",
    "MISSING_PREVIOUS_SAMPLE",
    "ENERGY_UNAVAILABLE",
)

/** Sibling `QualityFlag` values the local dashboard treats as anomalous. */
private val ANOMALOUS_FLAGS = setOf(
    "DEVICE_OFFLINE",
    "RATE_LIMITED",
    "PARTIAL_OBSERVATION",
    "IMPLAUSIBLE_FINAL_TOTAL",
)

/**
 * Parses one `devices/{deviceId}/telemetry/{sampleId}` document into the
 * normalized [Observation] projection.
 *
 * Whitelist only: the top-level `state` and `raw` payload blocks plus any
 * unknown key are never read. [documentId] is used only as a last-resort
 * [Observation.sampleId]. Interval boundaries are derived from the source's
 * `intervalSeconds`, or from `energy.previous.observedAt` → current observed
 * timestamp, with a safe five-minute fallback ending at the scheduled/observed
 * time. Null intervals stay null — never a measured zero — and legacy missing
 * units stay absent rather than being guessed.
 */
fun parseObservation(
    documentId: String?,
    data: Map<String, Any?>,
    timeZone: String = DEFAULT_DAY_TIME_ZONE,
): Observation {
    val doc = TelemetryDoc(data)
    val sampleId = doc.string("sampleId") ?: documentId
        ?: throw IllegalArgumentException("Observation document has no sampleId and no document id.")

    val energy = doc.doc("energy")
    val quality = doc.doc("quality")
    val source = doc.doc("source")

    val scheduledAt = doc.timestampMillis("scheduledAt")
    val observedAt = doc.timestampMillis("observedAt")
    val persistedAt = doc.timestampMillis("persistedAt")
    val reconciledAt = doc.timestampMillis("reconciledAt")

    // Precise string is authoritative; the number is only a query mirror. Parse either.
    val intervalValue = energy?.string("intervalValue")
    val intervalValueNumber = energy?.double("intervalValueNumber")
    val energyValue: Double? = when {
        intervalValue != null -> intervalValue.toDoubleOrNull() ?: intervalValueNumber
        intervalValueNumber != null -> intervalValueNumber
        else -> null // Null interval = missing, never a measured zero.
    }
    val valuePresent = energyValue != null

    // --- Interval boundary derivation --------------------------------------
    val intervalSeconds = energy?.double("intervalSeconds")
    val previousObservedAt = energy?.doc("previous")?.timestampMillis("observedAt")
    val endAnchor = observedAt ?: scheduledAt ?: persistedAt
        ?: throw IllegalArgumentException(
            "Observation '$sampleId' has no scheduledAt/observedAt/persistedAt timestamp.",
        )
    val lengthMillis: Long = when {
        intervalSeconds != null && intervalSeconds > 0.0 -> (intervalSeconds * 1000.0).toLong()
        previousObservedAt != null && previousObservedAt < endAnchor -> endAnchor - previousObservedAt
        else -> FALLBACK_INTERVAL_MILLIS
    }

    val statusText = quality?.string("intervalStatus")
    val flagTexts = quality?.stringList("flags")?.toSet() ?: emptySet()

    val unitText = energy?.string("unit")
    val unitSourceText = energy?.string("unitSource")

    val energySourceFailure = source?.doc("energy")?.let(::parseSourceFailure)
    val stateSourceFailure = source?.doc("state")?.let(::parseSourceFailure)

    // Quality mirrors the collector's own anomaly rules (statuses + flags), then
    // adds the missing-interval case. Source outcomes stay independent of it.
    val qualitySummary = when {
        !valuePresent -> ObservationQuality.MISSING_INTERVAL
        isAnomalous(statusText, flagTexts) -> ObservationQuality.ANOMALOUS
        else -> ObservationQuality.NORMAL
    }

    val knownFlags = buildSet {
        if (!valuePresent) add(QualityFlag.MISSING_VALUE)
        if ("PARTIAL_OBSERVATION" in flagTexts) add(QualityFlag.PARTIAL_INTERVAL)
    }

    return Observation(
        sampleId = sampleId,
        deviceId = doc.string("deviceId"),
        intervalStartEpochMillis = endAnchor - lengthMillis,
        intervalEndEpochMillis = endAnchor,
        energyValue = energyValue,
        recordedUnit = mapEnergyUnit(unitText),
        intervalStatus = statusText,
        quality = qualitySummary,
        qualityFlags = knownFlags,
        sourceFailure = legacySourceFailure(energySourceFailure, stateSourceFailure),
        persistedAtEpochMillis = persistedAt,
        reconciledAtEpochMillis = reconciledAt,

        scheduledAtEpochMillis = scheduledAt,
        observedAtEpochMillis = observedAt,
        localDate = doc.string("localDate"),
        timezone = doc.string("timezone") ?: timeZone,
        completeness = doc.int("completeness"),
        metadataVersion = doc.string("metadataVersion"),
        collectorVersion = doc.string("collectorVersion"),
        energyProperty = energy?.string("property"),
        rawDailyTotal = energy?.string("rawDailyTotal"),
        rawDailyTotalNumber = energy?.double("rawDailyTotalNumber"),
        intervalValue = intervalValue,
        intervalValueNumber = intervalValueNumber,
        intervalSeconds = intervalSeconds,
        finalPreviousDayTotal = energy?.string("finalPreviousDayTotal"),
        finalPreviousDayTotalNumber = energy?.double("finalPreviousDayTotalNumber"),
        unitText = unitText,
        unitSource = mapUnitSource(unitSourceText),
        unitSourceText = unitSourceText,
        flagTexts = flagTexts,
        energySourceFailure = energySourceFailure,
        stateSourceFailure = stateSourceFailure,
    )
}

/** Convenience for JSON fixture input; identical semantics to [parseObservation]. */
fun parseObservationJson(
    json: String,
    documentId: String? = null,
    timeZone: String = DEFAULT_DAY_TIME_ZONE,
): Observation = parseObservation(documentId, TelemetryJson.parse(json), timeZone)

/**
 * Parses one `devices/{deviceId}/dailyTotals/{localDate}` document.
 *
 * The authoritative decimal string (`total`) is preserved verbatim in
 * [DailyTotal.totalText] and mirrored numerically in [DailyTotal.energyValue].
 * A legacy row with a missing `unit` stays unit-less (never guessed); unknown
 * unit text stays in [DailyTotal.unitText]. [documentId] is the local date when
 * the `localDate` field is absent, and the day start is computed in [timeZone]
 * (default [DEFAULT_DAY_TIME_ZONE]). The `raw` payload block is never read.
 */
fun parseDailyTotal(
    documentId: String?,
    data: Map<String, Any?>,
    timeZone: String = DEFAULT_DAY_TIME_ZONE,
): DailyTotal {
    val doc = TelemetryDoc(data)
    val localDate = doc.string("localDate") ?: documentId
        ?: throw IllegalArgumentException("Daily total document has no localDate and no document id (the id is the local date).")

    val dayStart = runCatching {
        LocalDate.parse(localDate).atStartOfDayIn(TimeZone.of(timeZone)).toEpochMilliseconds()
    }.getOrElse { cause ->
        throw IllegalArgumentException("Daily total localDate '$localDate' is not a valid YYYY-MM-DD date.", cause)
    }

    val totalText = doc.string("total")
    val totalNumber = doc.double("totalNumber")
    val unitText = doc.string("unit")

    return DailyTotal(
        totalId = documentId ?: "daily:$localDate",
        dayStartEpochMillis = dayStart,
        energyValue = totalText?.toDoubleOrNull() ?: totalNumber
            ?: throw IllegalArgumentException("Daily total '$localDate' has no valid total or totalNumber."),
        recordedUnit = mapEnergyUnit(unitText),
        source = DailyTotalSource.LG_FINALIZED,
        finalized = true,
        localDate = localDate,
        totalText = totalText,
        totalNumber = totalNumber,
        unitText = unitText,
        deviceId = doc.string("deviceId"),
        fetchedAtEpochMillis = doc.timestampMillis("fetchedAt"),
    )
}

/** Convenience for JSON fixture input; identical semantics to [parseDailyTotal]. */
fun parseDailyTotalJson(
    json: String,
    documentId: String? = null,
    timeZone: String = DEFAULT_DAY_TIME_ZONE,
): DailyTotal = parseDailyTotal(documentId, TelemetryJson.parse(json), timeZone)

/**
 * Parses the `devices/{deviceId}` identity document. The `deviceId` field is
 * authoritative; [documentId] is the fallback for legacy rows that predate it.
 */
fun parseDeviceIdentity(documentId: String?, data: Map<String, Any?>): DeviceIdentity {
    val doc = TelemetryDoc(data)
    val deviceId = doc.string("deviceId") ?: documentId
        ?: throw IllegalArgumentException("Device document has no deviceId and no document id.")
    return DeviceIdentity(
        deviceId = deviceId,
        alias = doc.string("alias"),
        modelName = doc.string("modelName"),
        deviceType = doc.string("deviceType"),
        updatedAtEpochMillis = doc.timestampMillis("updatedAt"),
    )
}

/** Convenience for JSON fixture input; identical semantics to [parseDeviceIdentity]. */
fun parseDeviceIdentityJson(documentId: String?, json: String): DeviceIdentity =
    parseDeviceIdentity(documentId, TelemetryJson.parse(json))

/**
 * Parses the `devices/{deviceId}/runtime/collector` health document.
 *
 * Only the safe runtime fields are retained: attempt/success/error timestamps,
 * the last sample reference, the consecutive-failure and rate-limit counters,
 * the rate-limit hold, the last sanitized error, and the collector version.
 * Advisory `lease*` fields and unknown doc sections are ignored.
 */
fun parseCollectorHealth(data: Map<String, Any?>): CollectorHealth {
    val doc = TelemetryDoc(data)
    val lastAttemptAt = doc.timestampMillis("lastAttemptAt")
    val lastSuccessAt = doc.timestampMillis("lastSuccessAt")
    val lastErrorAt = doc.timestampMillis("lastErrorAt")
    val consecutiveFailures = doc.int("consecutiveFailures")
    val consecutiveRateLimits = doc.int("consecutiveRateLimits")
    val rateLimitedUntil = doc.timestampMillis("rateLimitedUntil")

    val status = when {
        lastSuccessAt != null && (consecutiveFailures == null || consecutiveFailures == 0) &&
            (consecutiveRateLimits == null || consecutiveRateLimits == 0) && rateLimitedUntil == null ->
            CollectorStatus.HEALTHY
        lastSuccessAt != null -> CollectorStatus.DEGRADED
        lastAttemptAt != null -> CollectorStatus.OFFLINE
        else -> CollectorStatus.UNKNOWN
    }

    return CollectorHealth(
        status = status,
        lastPollEpochMillis = lastAttemptAt,
        lastSuccessEpochMillis = lastSuccessAt,
        lastFailureEpochMillis = lastErrorAt,
        lastFailureReason = doc.string("lastErrorMessage"),
        lastSampleId = doc.string("lastSampleId"),
        lastSamplePath = doc.string("lastSamplePath"),
        lastErrorClass = doc.string("lastErrorClass"),
        consecutiveFailures = consecutiveFailures,
        consecutiveRateLimits = consecutiveRateLimits,
        rateLimitedUntilEpochMillis = rateLimitedUntil,
        collectorVersion = doc.string("collectorVersion"),
    )
}

/** Convenience for JSON fixture input; identical semantics to [parseCollectorHealth]. */
fun parseCollectorHealthJson(json: String): CollectorHealth =
    parseCollectorHealth(TelemetryJson.parse(json))

// --- private mapping helpers ---------------------------------------------

private fun parseSourceFailure(source: TelemetryDoc): SourceReadFailure = SourceReadFailure(
    ok = source.boolOrNull("ok") ?: true,
    failureClass = source.string("failureClass"),
    errorCode = source.string("errorCode"),
    errorName = source.string("errorName"),
)

private fun isAnomalous(status: String?, flags: Set<String>): Boolean =
    (status != null && status in ANOMALOUS_STATUSES) || flags.any { it in ANOMALOUS_FLAGS }

/** Maps known unit text to the enum; unknown text stays available via [Observation.unitText]. */
private fun mapEnergyUnit(unit: String?): EnergyUnit? = when (unit?.uppercase()) {
    "WH" -> EnergyUnit.WH
    "KWH" -> EnergyUnit.KWH
    else -> null
}

/** Maps known provenance text; unknown text stays available via [Observation.unitSourceText]. */
private fun mapUnitSource(source: String?): EnergyUnitSource? = when (source?.lowercase()) {
    "device" -> EnergyUnitSource.DEVICE
    "configured" -> EnergyUnitSource.CONFIGURED
    else -> null
}

private fun failureDetail(failure: SourceReadFailure): String =
    failure.errorName ?: failure.failureClass ?: failure.errorCode ?: "failed"

/** Legacy one-line summary naming the first failed source (energy reports first). */
private fun legacySourceFailure(energy: SourceReadFailure?, state: SourceReadFailure?): String? {
    val energyText = energy?.takeIf { !it.ok }?.let { "energy:${failureDetail(it)}" }
    val stateText = state?.takeIf { !it.ok }?.let { "state:${failureDetail(it)}" }
    return energyText ?: stateText
}
