package cloud.airchive.shared.data.telemetry

/**
 * Pure, deterministic planning helpers for the four allowed Airchive Firestore
 * read shapes.
 *
 * No Firebase/network dependency: the Android Firestore adapter, the future iOS
 * adapter, and their tests share these same path and cursor rules so paging and
 * path-shape semantics match across platforms and are directly unit-testable.
 */
object AirchiveReadPaths {

    /** Root collection under which every permitted document lives. */
    const val DEVICES_COLLECTION: String = "devices"

    /** Firestore `Timestamp` field the newest/oldest bounded history reads sort on. */
    const val FIELD_OBSERVED_AT: String = "observedAt"

    /** Firestore `Timestamp` field the incremental `persistedAt` stream sorts on. */
    const val FIELD_PERSISTED_AT: String = "persistedAt"

    /** Firestore `Timestamp` field the incremental `reconciledAt` stream sorts on. */
    const val FIELD_RECONCILED_AT: String = "reconciledAt"

    /** `devices/{deviceId}` — the AC identity document (single `get`). */
    fun identityDocument(deviceId: String): String =
        "devices/${validateDeviceSegment(deviceId)}"

    /** `devices/{deviceId}/telemetry/{sampleId}` — sampled observations (list). */
    fun telemetryCollection(deviceId: String): String =
        "devices/${validateDeviceSegment(deviceId)}/telemetry"

    /** `devices/{deviceId}/telemetry/{sampleId}` — a bounded cursor-anchor GET. */
    fun telemetryDocument(deviceId: String, sampleId: String): String =
        "${telemetryCollection(deviceId)}/${validateDeviceSegment(sampleId)}"

    /** `devices/{deviceId}/dailyTotals/{localDate}` — finalized daily totals (list). */
    fun dailyTotalsCollection(deviceId: String): String =
        "devices/${validateDeviceSegment(deviceId)}/dailyTotals"

    /** `devices/{deviceId}/runtime/collector` — collector health (single `get`). */
    fun collectorHealthDocument(deviceId: String): String =
        "devices/${validateDeviceSegment(deviceId)}/runtime/collector"

    /**
     * Validates that [deviceId] is a safe single Firestore path segment:
     * non-blank, no `/`, and no whitespace. Returns it unchanged on success.
     *
     * This is the enforcement point for the "no arbitrary path/device input"
     * boundary: adapters build paths only from the fixed configured device id,
     * and never accept a path or device segment from callers.
     */
    fun validateDeviceSegment(deviceId: String): String {
        require(deviceId.isNotBlank()) {
            "Configured device id must not be blank."
        }
        require('/' !in deviceId) {
            "Configured device id must not contain '/': it maps to exactly one path segment."
        }
        require(deviceId.none(Char::isWhitespace)) {
            "Configured device id must not contain whitespace."
        }
        return deviceId
    }
}

/**
 * The read direction of an observation paging cursor. Two kinds map to the two
 * ordered history queries the contract exposes (newest-first latest burst and
 * oldest-first selected-range burst, both anchored on `observedAt`); the other
 * two map to the incremental `persistedAt`/`reconciledAt` mutation streams
 * (both oldest-first, anchored on the respective server `Timestamp` field).
 */
enum class TelemetryReadKind {
    /** [AirchiveTelemetryPort.latestObservations]: newest-first by `observedAt`. */
    NEWEST,

    /** [AirchiveTelemetryPort.observations]: oldest-first by `observedAt`. */
    OLDEST,

    /** [AirchiveTelemetryPort.changedSincePersistedAt]: oldest-first by `persistedAt`. */
    PERSISTED_AT,

    /** [AirchiveTelemetryPort.changedSinceReconciledAt]: oldest-first by `reconciledAt`. */
    RECONCILED_AT,
}

/**
 * The exact Firestore `Timestamp` field a [TelemetryReadKind] orders on and
 * anchors its durable cursor at. Adapters use this single mapping for both the
 * query `orderBy` key and the resumed cursor-anchor field, so a persisted-at
 * cursor can never be resumed against an observed-at query and vice versa.
 */
fun anchorField(kind: TelemetryReadKind): String = when (kind) {
    TelemetryReadKind.NEWEST,
    TelemetryReadKind.OLDEST,
    -> AirchiveReadPaths.FIELD_OBSERVED_AT

    TelemetryReadKind.PERSISTED_AT -> AirchiveReadPaths.FIELD_PERSISTED_AT
    TelemetryReadKind.RECONCILED_AT -> AirchiveReadPaths.FIELD_RECONCILED_AT
}

/** Whether a [TelemetryReadKind] orders its query oldest-first (ascending). */
fun readsAscending(kind: TelemetryReadKind): Boolean = when (kind) {
    TelemetryReadKind.NEWEST -> false
    TelemetryReadKind.OLDEST,
    TelemetryReadKind.PERSISTED_AT,
    TelemetryReadKind.RECONCILED_AT,
    -> true
}

/**
 * Opaque, durable Firestore page cursor for observation reads.
 *
 * The cursor encodes the full resume position: the read direction/stream, the
 * exact sort key of the last returned document (Firestore `Timestamp` seconds +
 * nanos, so no sub-millisecond precision is lost and resume cannot skip or
 * repeat a server row), and the last document id as the tie-breaker. It may be
 * persisted and resumed later, matching the durable-sync contract.
 *
 * The anchor field is implied by the kind (see [anchorField]); encoding is
 * deterministic and independent of Firebase; [encode] and [decode] are
 * symmetric. The payload concatenates `version|kind|seconds|nanos|docId`, with
 * the document id escaped so separators and backslashes inside real Firestore
 * document ids survive round trips.
 */
data class TelemetryReadCursor(
    val kind: TelemetryReadKind,
    val sortSeconds: Long,
    val sortNanos: Int,
    val documentId: String,
) {

    /** Returns the durable string form of this cursor position. */
    fun encode(): String = buildString {
        append(VERSION)
        append(SEPARATOR)
        append(kind.name)
        append(SEPARATOR)
        append(sortSeconds)
        append(SEPARATOR)
        append(sortNanos)
        append(SEPARATOR)
        append(escape(documentId))
    }

    companion object {
        const val VERSION: String = "airchiveCursorV1"
        private const val SEPARATOR: Char = '|'

        /** Decodes the durable string form, rejecting anything malformed. */
        fun decode(cursor: String): TelemetryReadCursor {
            val first = cursor.indexOf(SEPARATOR)
            val second = cursor.indexOf(SEPARATOR, first + 1)
            val third = cursor.indexOf(SEPARATOR, second + 1)
            val fourth = cursor.indexOf(SEPARATOR, third + 1)
            require(first > 0 && second > first && third > second && fourth > third) {
                "Malformed telemetry cursor '$cursor'."
            }
            require(cursor.substring(0, first) == VERSION) {
                "Unknown telemetry cursor '$cursor'."
            }
            val kind = when (cursor.substring(first + 1, second)) {
                TelemetryReadKind.NEWEST.name -> TelemetryReadKind.NEWEST
                TelemetryReadKind.OLDEST.name -> TelemetryReadKind.OLDEST
                TelemetryReadKind.PERSISTED_AT.name -> TelemetryReadKind.PERSISTED_AT
                TelemetryReadKind.RECONCILED_AT.name -> TelemetryReadKind.RECONCILED_AT
                else -> throw IllegalArgumentException(
                    "Unknown telemetry cursor direction in '$cursor'.",
                )
            }
            val seconds = cursor.substring(second + 1, third).toLongOrNull()
            val nanos = cursor.substring(third + 1, fourth).toIntOrNull()
            require(seconds != null && nanos != null) {
                "Malformed telemetry cursor position in '$cursor'."
            }
            require(nanos in 0 until 1_000_000_000) {
                "Malformed telemetry cursor nanoseconds in '$cursor'."
            }
            val documentId = unescape(cursor.substring(fourth + 1))
            require(documentId.isNotEmpty()) {
                "Malformed telemetry cursor document id in '$cursor'."
            }
            return TelemetryReadCursor(
                kind = kind,
                sortSeconds = seconds,
                sortNanos = nanos,
                documentId = documentId,
            )
        }

        private fun escape(text: String): String = buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '\\' -> append("\\\\")
                    SEPARATOR -> append("\\|")
                    else -> append(ch)
                }
            }
        }

        private fun unescape(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                if (ch == '\\' && index + 1 < text.length) {
                    when (text[index + 1]) {
                        '\\' -> {
                            append('\\')
                            index += 2
                            continue
                        }
                        SEPARATOR -> {
                            append(SEPARATOR)
                            index += 2
                            continue
                        }
                    }
                }
                append(ch)
                index += 1
            }
        }
    }
}
