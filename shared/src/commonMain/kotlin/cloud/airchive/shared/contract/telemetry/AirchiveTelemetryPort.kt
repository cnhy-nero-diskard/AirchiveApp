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

    companion object {
        /** Maximum documents per Firestore query, per the Airchive access contract. */
        const val MAX_PAGE_SIZE = 250

        /** Bounded initial total-count default (30 finalized daily totals). */
        const val DEFAULT_TOTALS_LIMIT = 30

        /** Enforces the page bound `1..MAX_PAGE_SIZE` on every read. */
        fun requireValidPageSize(pageSize: Int) {
            require(pageSize in 1..MAX_PAGE_SIZE) {
                "pageSize must be in 1..$MAX_PAGE_SIZE, got $pageSize"
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
 * legacy rows that lacked a unit (defaulted by parsers elsewhere). [intervalStatus]
 * carries the source's own status text (informational, never the raw payload),
 * [quality] is the UI-level summary, [qualityFlags] explain *why* that summary
 * applies, and [sourceFailure] names which source failed and why. The
 * server-managed mutation timestamps `persistedAt`/`reconciledAt` stay nullable
 * so incremental sync can merge by [sampleId]. No raw payload fields exist here.
 */
data class Observation(
    val sampleId: String,
    val intervalStartEpochMillis: Long,
    val intervalEndEpochMillis: Long,
    val energyValue: Double? = null,
    val recordedUnit: EnergyUnit? = null,
    /** Source's own interval status text, e.g. `"OK"` / `"MISSING"` / `"RECOVERED"`. */
    val intervalStatus: String? = null,
    val quality: ObservationQuality = ObservationQuality.NORMAL,
    val qualityFlags: Set<QualityFlag> = emptySet(),
    val sourceFailure: String? = null,
    val persistedAtEpochMillis: Long? = null,
    val reconciledAtEpochMillis: Long? = null,
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
 */
data class DailyTotal(
    val totalId: String,
    /** Local-day start (`Asia/Manila` boundary), as epoch millis. */
    val dayStartEpochMillis: Long,
    val energyValue: Double,
    val recordedUnit: EnergyUnit,
    val source: DailyTotalSource,
    val finalized: Boolean,
)

enum class CollectorStatus { UNKNOWN, HEALTHY, DEGRADED, OFFLINE }

data class CollectorHealth(
    val status: CollectorStatus,
    val lastPollEpochMillis: Long? = null,
    val lastSuccessEpochMillis: Long? = null,
    val lastFailureEpochMillis: Long? = null,
    val lastFailureReason: String? = null,
)