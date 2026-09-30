package cloud.airchive.shared.contract.telemetry

/**
 * Bounded, **read-only** view of the Airchive collector for the configured AC.
 *
 * This is the only contract that talks to Firestore. It deliberately exposes no
 * write method and no raw payload: Firestore documents may include `raw`, but
 * callers only ever see the normalized display/sync projection below, plus
 * informational status/flag/failure text. The signed-in owner identity and the
 * configured device ID come from outside this port; adapters are responsible for
 * honoring the four allowed path shapes and the 250-document page maximum.
 *
 * The in-memory [FakeAirchiveTelemetry] implements this without accounts,
 * network, or Firebase and is deterministic for a fixed clock.
 */
interface AirchiveTelemetryPort {

    /**
     * Latest observations whose five-minute interval ends at or after
     * [sinceEpochMillis] (the bounded 24-hour initial window), returned newest
     * first in bursts of at most [pageSize]. Pass the returned
     * [TelemetryPage.nextCursor] to fetch the next burst; a durable sync layer
     * may persist that cursor so an interrupted bounded read resumes later.
     */
    suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int = MAX_PAGE_SIZE,
        cursor: String? = null,
    ): TelemetryPage<Observation>

    /**
     * Observations overlapping `(fromEpochMillis, toEpochMillis)` for an
     * explicitly selected historical range, returned oldest first with the same
     * bounded paging rules (see [latestObservations]).
     */
    suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int = MAX_PAGE_SIZE,
        cursor: String? = null,
    ): TelemetryPage<Observation>

    /** The most recent finalized daily totals, bounded at [limit]. */
    suspend fun latestFinalizedTotals(limit: Int = DEFAULT_TOTALS_LIMIT): List<DailyTotal>

    /** Latest collector health record, or an offline heartbeat. */
    suspend fun collectorHealth(): CollectorHealth

    /**
     * The configured AC's identity document at `devices/{configuredDeviceId}`.
     * Read-only identity metadata (alias/model/type); adapters fail closed with
     * a clear error when the collector has not written the document yet.
     */
    suspend fun deviceIdentity(): DeviceIdentity

    companion object {
        /** Maximum documents per Firestore query, per the Airchive access contract. */
        const val MAX_PAGE_SIZE = 250

        /** Maximum daily-total documents per read (matches the page bound). */
        const val MAX_TOTALS_LIMIT = 250

        /** Bounded initial total-count default (30 finalized daily totals). */
        const val DEFAULT_TOTALS_LIMIT = 30

        /** Enforces the page bound `1..MAX_PAGE_SIZE` on every read. */
        fun requireValidPageSize(pageSize: Int) {
            require(pageSize in 1..MAX_PAGE_SIZE) {
                "pageSize must be in 1..$MAX_PAGE_SIZE, got $pageSize"
            }
        }

        /** Enforces the daily-total list bound `1..MAX_TOTALS_LIMIT`. */
        fun requireValidTotalsLimit(limit: Int) {
            require(limit in 1..MAX_TOTALS_LIMIT) {
                "limit must be in 1..$MAX_TOTALS_LIMIT, got $limit"
            }
        }
    }
}

/**
 * One bounded burst of paged results. [nextCursor] is an opaque, durable token:
 * it encodes the adapter's next read position and may be stored for later
 * resumption. It is `null` on the final burst.
 */
data class TelemetryPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

/**
 * One five-minute telemetry sample.
 *
 * [energyValue] is nullable on purpose: a **null interval is missing data and is
 * never presented as a measured zero**. [recordedUnit] is nullable to survive
 * legacy rows that lacked a unit and unknown unit text (kept verbatim in
 * [unitText]); [intervalStatus] carries the source's own status text verbatim
 * (informational, never the raw payload); [quality] is the UI-level summary;
 * [qualityFlags] explain *why* that summary applies; [flagTexts] retains every
 * source flag string, including unknown future flags; and the typed
 * [energySourceFailure]/[stateSourceFailure] preserve the outcome of each
 * request independently of the interval status, with [sourceFailure] left as a
 * legacy one-line summary. The server-managed mutation timestamps
 * `persistedAt`/`reconciledAt` stay nullable so incremental sync can merge by
 * [sampleId]. No raw payload fields exist here: `raw`/`state` payloads are
 * discarded by the parsers, and the precise decimal strings
 * ([intervalValue], [rawDailyTotal], [finalPreviousDayTotal]) are kept alongside
 * their numeric query mirrors so the authoritative text is never lost.
 */
data class Observation(
    val sampleId: String,
    val intervalStartEpochMillis: Long,
    val intervalEndEpochMillis: Long,
    val energyValue: Double? = null,
    val recordedUnit: EnergyUnit? = null,
    /** Source's own interval status text, preserved verbatim (e.g. `"NORMAL"`, `"ENERGY_UNAVAILABLE"`). */
    val intervalStatus: String? = null,
    val quality: ObservationQuality = ObservationQuality.NORMAL,
    val qualityFlags: Set<QualityFlag> = emptySet(),
    val sourceFailure: String? = null,
    val persistedAtEpochMillis: Long? = null,
    val reconciledAtEpochMillis: Long? = null,

    /** Device the sample belongs to (mirrors the configured device path). */
    val deviceId: String? = null,

    /** Original collector schedule moment, normalized to epoch millis. */
    val scheduledAtEpochMillis: Long? = null,

    /** Moment the sensor was actually read, normalized to epoch millis. */
    val observedAtEpochMillis: Long? = null,

    /** Collector's local calendar date for this sample, e.g. `"2026-01-15"`. */
    val localDate: String? = null,

    /** IANA timezone the collector recorded, e.g. `"Asia/Manila"`. */
    val timezone: String? = null,

    /** Source completeness score (`2*energy ok + 1*state ok`), when recorded. */
    val completeness: Int? = null,

    /** Collector profile/schema version in effect when the sample was taken. */
    val metadataVersion: String? = null,

    /** Collector binary version that produced the sample. */
    val collectorVersion: String? = null,

    /** Energy property the collector read (e.g. `"WA_ENERGY"`), when known. */
    val energyProperty: String? = null,

    /** Authoritative string form of the raw device counter this interval derives from. */
    val rawDailyTotal: String? = null,

    /** Query mirror of [rawDailyTotal]. */
    val rawDailyTotalNumber: Double? = null,

    /** Precise string decimal of this interval's energy; null interval = missing data. */
    val intervalValue: String? = null,

    /** Query mirror of [intervalValue]. */
    val intervalValueNumber: Double? = null,

    /** Source-reported interval span in seconds (float), when provided. */
    val intervalSeconds: Double? = null,

    /** Finalized previous-day total used to resolve a day rollover. */
    val finalPreviousDayTotal: String? = null,

    /** Query mirror of [finalPreviousDayTotal]. */
    val finalPreviousDayTotalNumber: Double? = null,

    /** Raw unit text from the source, preserved verbatim (unknown units kept here). */
    val unitText: String? = null,

    /** Known unit provenance (`device`/`configured`); unknown text stays in [unitSourceText]. */
    val unitSource: EnergyUnitSource? = null,

    /** Raw unit-source text from the source, preserved verbatim. */
    val unitSourceText: String? = null,

    /** Every source `quality.flags` string verbatim, including unknown future flags. */
    val flagTexts: Set<String> = emptySet(),

    /** Typed outcome of the energy request, independent of the interval status. */
    val energySourceFailure: SourceReadFailure? = null,

    /** Typed outcome of the state request, independent of the interval status. */
    val stateSourceFailure: SourceReadFailure? = null,
)

enum class EnergyUnit { WH, KWH }

enum class ObservationQuality {
    /** Present, plausible, and usable. */
    NORMAL,

    /** Interval exists but lacks a usable value (null interval, shown as a gap). */
    MISSING_INTERVAL,

    /** Value present but flagged anomalous by the collector/dashboard rules. */
    ANOMALOUS,

    /** No sample available for the slot at all. */
    UNAVAILABLE,
}

/** Finer machine-readable hints explaining a non-normal [Observation.quality]. */
enum class QualityFlag {
    MISSING_VALUE,
    OUT_OF_BOUNDS,
    SPIKED,
    PARTIAL_INTERVAL,
    SOURCE_FAILED,
}

/** Where a sample's unit came from, mirroring the collector (`device`/`configured`). */
enum class EnergyUnitSource { DEVICE, CONFIGURED }

/**
 * Whitelisted, sanitized outcome of one ThinQ request recorded beside a sample.
 * `source.energy`/`source.state` are independent of the interval arithmetic, so
 * a failed state read never degrades an energy interval's status. Parsers retain
 * exactly these four fields and nothing else from the source block.
 */
data class SourceReadFailure(
    val ok: Boolean,
    val failureClass: String? = null,
    val errorCode: String? = null,
    val errorName: String? = null,
)

/** Where a daily figure came from, kept so Energy views never conflate the two. */
enum class DailyTotalSource {
    /** Finalized total cached from LG by the collector. */
    LG_FINALIZED,

    /** Sum of normalized local intervals, computed on device. */
    LOCAL_INTERVAL_SUM,
}

/**
 * A daily energy figure cached by the collector (LG final totals) or computed
 * locally (interval sums). Always distinguishable by [source], and
 * [finalized]/[recordedUnit] carry the remaining provenance: Energy views label
 * each measure separately and show coverage context.
 *
 * [recordedUnit] is nullable for legacy rows written before a unit existed
 * (the collector stores `unit: null` and the parser never guesses a unit);
 * unknown unit text is retained verbatim in [unitText]. The authoritative
 * decimal is kept as a string in [totalText] so string precision survives the
 * numeric projection in [energyValue].
 */
data class DailyTotal(
    val totalId: String,
    /** Local-day start (`Asia/Manila` boundary), as epoch millis. */
    val dayStartEpochMillis: Long,
    val energyValue: Double,
    val recordedUnit: EnergyUnit? = null,
    val source: DailyTotalSource,
    val finalized: Boolean,
    /** The local calendar date this total belongs to, e.g. `"2026-01-15"`. */
    val localDate: String? = null,
    /** Authoritative string decimal of the total, preserved verbatim. */
    val totalText: String? = null,
    /** Query mirror of [totalText]. */
    val totalNumber: Double? = null,
    /** Raw unit text from the source, preserved verbatim (unknown units kept here). */
    val unitText: String? = null,
    val deviceId: String? = null,
    /** When the collector fetched the finalized total from LG. */
    val fetchedAtEpochMillis: Long? = null,
)

enum class CollectorStatus { UNKNOWN, HEALTHY, DEGRADED, OFFLINE }

/**
 * Latest collector health record, normalized from the safe runtime doc at
 * `devices/{deviceId}/runtime/collector`. Only whitelisted fields are retained:
 * timestamps, the failure counters, the last sample reference, and the
 * collector version. Advisory `lease*` fields and unknown doc sections are
 * ignored by the parsers and never reach this projection.
 */
data class CollectorHealth(
    val status: CollectorStatus,
    val lastPollEpochMillis: Long? = null,
    val lastSuccessEpochMillis: Long? = null,
    val lastFailureEpochMillis: Long? = null,
    val lastFailureReason: String? = null,
    /** Reference to the last successfully written telemetry sample. */
    val lastSampleId: String? = null,
    /** Firestore path of the last successfully written sample. */
    val lastSamplePath: String? = null,
    /** Sanitized failure class of the last error, e.g. `"TRANSIENT"`. */
    val lastErrorClass: String? = null,
    /** Consecutive failed attempts since the last success (0 when healthy). */
    val consecutiveFailures: Int? = null,
    /** Consecutive rate-limited attempts, when the collector records them. */
    val consecutiveRateLimits: Int? = null,
    /** When a rate-limit hold expires, if one is in force. */
    val rateLimitedUntilEpochMillis: Long? = null,
    /** Collector binary version reported in the health doc. */
    val collectorVersion: String? = null,
)

/**
 * Identity record stored at `devices/{deviceId}` (read-only, no secrets).
 * The device id in the document is authoritative; parsers fall back to the
 * document id when the field is absent for legacy rows.
 */
data class DeviceIdentity(
    val deviceId: String,
    val alias: String? = null,
    val modelName: String? = null,
    val deviceType: String? = null,
    val updatedAtEpochMillis: Long? = null,
)