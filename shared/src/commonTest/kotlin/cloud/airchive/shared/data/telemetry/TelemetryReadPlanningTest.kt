package cloud.airchive.shared.data.telemetry

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic tests for the SDK-free read planning helpers: cursor
 * encoding/decoding, sort-field planning, page-limit and totals-limit validation, and the four fixed
 * allowed Firestore path shapes.
 */
class TelemetryReadPlanningTest {

    // --- cursor encoding / decoding ----------------------------------------

    @Test
    fun cursorRoundTripsForEveryReadStream() {
        for (kind in TelemetryReadKind.entries) {
            val cursor = TelemetryReadCursor(
                kind = kind,
                sortSeconds = 1_735_725_600L,
                sortNanos = 123_456_789,
                documentId = "2026-01-15T00-05-00-sample",
            )
            assertEquals(cursor, TelemetryReadCursor.decode(cursor.encode()), "round trip for $kind")
        }
    }

    @Test
    fun incrementalStreamsUseTheirOwnAscendingServerTimestampFields() {
        assertEquals(AirchiveReadPaths.FIELD_OBSERVED_AT, anchorField(TelemetryReadKind.NEWEST))
        assertEquals(AirchiveReadPaths.FIELD_OBSERVED_AT, anchorField(TelemetryReadKind.OLDEST))
        assertEquals(AirchiveReadPaths.FIELD_PERSISTED_AT, anchorField(TelemetryReadKind.PERSISTED_AT))
        assertEquals(AirchiveReadPaths.FIELD_RECONCILED_AT, anchorField(TelemetryReadKind.RECONCILED_AT))

        assertEquals(false, readsAscending(TelemetryReadKind.NEWEST))
        assertEquals(true, readsAscending(TelemetryReadKind.OLDEST))
        assertEquals(true, readsAscending(TelemetryReadKind.PERSISTED_AT))
        assertEquals(true, readsAscending(TelemetryReadKind.RECONCILED_AT))
    }

    @Test
    fun cursorRoundTripsDocumentsWithSeparatorsAndBackslashes() {
        val cursor = TelemetryReadCursor(
            kind = TelemetryReadKind.NEWEST,
            sortSeconds = 1L,
            sortNanos = 0,
            documentId = "a|b\\c|d",
        )
        val encoded = cursor.encode()
        // The raw document id must not appear unsplit in the encoded form's last field.
        val decoded = TelemetryReadCursor.decode(encoded)
        assertEquals("a|b\\c|d", decoded.documentId)
        assertEquals(cursor, decoded)
    }

    @Test
    fun decodeRejectsUnknownVersion() {
        val cursor = TelemetryReadCursor(TelemetryReadKind.NEWEST, 1L, 0, "doc-1")
        val encoded = cursor.encode()
        assertFailsWith<IllegalArgumentException> {
            TelemetryReadCursor.decode("airchiveCursorV2$encoded".removePrefix("airchiveCursorV1"))
        }
    }

    @Test
    fun decodeRejectsUnknownDirection() {
        val encoded = mutableListOf("airchiveCursorV1", "SIDEWAYS", "1", "0", "doc").joinToString("|")
        assertFailsWith<IllegalArgumentException> { TelemetryReadCursor.decode(encoded) }
    }

    @Test
    fun decodeRejectsMalformedPositions() {
        assertFailsWith<IllegalArgumentException> {
            TelemetryReadCursor.decode("airchiveCursorV1|NEWEST|not-a-number|0|doc")
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryReadCursor.decode("airchiveCursorV1|NEWEST|1|not-a-number|doc")
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryReadCursor.decode("airchiveCursorV1|NEWEST") // truncated
        }
    }

    @Test
    fun decodeRejectsOutOfRangeNanosAndBlankDocumentIds() {
        val encoded = mutableListOf("airchiveCursorV1", "NEWEST", "1", "1000000000", "doc").joinToString("|")
        assertFailsWith<IllegalArgumentException> { TelemetryReadCursor.decode(encoded) }

        val blankDoc = mutableListOf("airchiveCursorV1", "NEWEST", "1", "0", "").joinToString("|")
        assertFailsWith<IllegalArgumentException> { TelemetryReadCursor.decode(blankDoc) }
    }

    @Test
    fun synthesizedDurableCursorDecodesToIdenticalFields() {
        // Simulating an adapter persisting full-precision Timestamp seconds/nanos.
        val persisted = "airchiveCursorV1|OLDEST|1735725600|0|2026-01-01T00-00-00"
        val decoded = TelemetryReadCursor.decode(persisted)
        assertEquals(TelemetryReadKind.OLDEST, decoded.kind)
        assertEquals(1_735_725_600L, decoded.sortSeconds)
        assertEquals(0, decoded.sortNanos)
        assertEquals("2026-01-01T00-00-00", decoded.documentId)
        assertEquals(persisted, decoded.encode())
    }

    // --- page-size and totals-limit validation ------------------------------

    @Test
    fun pageSizeAllowsExactlyOneToTwoFifty() {
        assertEquals(250, AirchiveTelemetryPort.MAX_PAGE_SIZE)
        AirchiveTelemetryPort.requireValidPageSize(1)
        AirchiveTelemetryPort.requireValidPageSize(250)
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidPageSize(0) }
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidPageSize(251) }
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidPageSize(-1) }
    }

    @Test
    fun totalsLimitAllowsExactlyOneToTwoFifty() {
        assertEquals(250, AirchiveTelemetryPort.MAX_TOTALS_LIMIT)
        AirchiveTelemetryPort.requireValidTotalsLimit(1)
        AirchiveTelemetryPort.requireValidTotalsLimit(250)
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidTotalsLimit(0) }
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidTotalsLimit(251) }
        assertFailsWith<IllegalArgumentException> { AirchiveTelemetryPort.requireValidTotalsLimit(Int.MAX_VALUE) }
    }

    // --- fixed allowed paths ------------------------------------------------

    @Test
    fun fixedPathsMatchTheFourApprovedShapes() {
        val deviceId = "d7dc30c9698797295acbb90ee543447738db8f7b3d5cff230eb66978a855be3a"
        assertEquals("devices/$deviceId", AirchiveReadPaths.identityDocument(deviceId))
        // `.../telemetry/{sampleId}` and `.../dailyTotals/{localDate}` are collections below the device doc.
        assertEquals("devices/$deviceId/telemetry", AirchiveReadPaths.telemetryCollection(deviceId))
        assertEquals("devices/$deviceId/telemetry/sample-1", AirchiveReadPaths.telemetryDocument(deviceId, "sample-1"))
        assertEquals("devices/$deviceId/dailyTotals", AirchiveReadPaths.dailyTotalsCollection(deviceId))
        assertEquals("devices/$deviceId/runtime/collector", AirchiveReadPaths.collectorHealthDocument(deviceId))
    }

    @Test
    fun deviceSegmentRejectsInputThatCouldChangeThePath() {
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.validateDeviceSegment("") }
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.validateDeviceSegment("   ") }
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.validateDeviceSegment("a/b") }
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.validateDeviceSegment("a b") }
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.telemetryDocument("valid-id", "sample/child") }
        assertFailsWith<IllegalArgumentException> { AirchiveReadPaths.collectorHealthDocument("/") }
        assertEquals("demo-ac-id", AirchiveReadPaths.validateDeviceSegment("demo-ac-id"))
        assertTrue(AirchiveReadPaths.validateDeviceSegment("valid-id") == "valid-id")
    }
}
