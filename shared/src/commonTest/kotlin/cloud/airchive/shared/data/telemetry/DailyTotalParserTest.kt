package cloud.airchive.shared.data.telemetry

import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deterministic daily-total parser tests over synthetic sibling document shapes. */
class DailyTotalParserTest {

    @Test
    fun preservesDecimalStringAndConvertsLocalDayStart() {
        val dayStart = Instant.parse("2026-01-14T16:00:00Z").toEpochMilliseconds()
        val fetchedAt = Instant.parse("2026-01-15T23:59:00+08:00").toEpochMilliseconds()

        val total = parseDailyTotal(
            documentId = "2026-01-15",
            data = mapOf(
                "localDate" to "2026-01-15",
                "total" to "18.40",
                "totalNumber" to 18.4,
                "unit" to "kWh",
                "fetchedAt" to "2026-01-15T23:59:00+08:00",
                "deviceId" to "demo-device-8f3a",
                "raw" to mapOf("lg" to mapOf("x" to 1)),
            ),
        )

        assertEquals("2026-01-15", total.totalId)
        assertEquals("2026-01-15", total.localDate)
        // Authoritative decimal string survives the numeric projection verbatim.
        assertEquals("18.40", total.totalText)
        assertEquals(18.4, total.energyValue)
        assertEquals(18.4, total.totalNumber)
        assertEquals(EnergyUnit.KWH, total.recordedUnit)
        assertEquals("kWh", total.unitText)
        assertEquals(dayStart, total.dayStartEpochMillis, "Asia/Manila day start must be 2026-01-14T16:00:00Z")
        assertEquals(fetchedAt, total.fetchedAtEpochMillis)
        assertEquals("demo-device-8f3a", total.deviceId)
        assertEquals(DailyTotalSource.LG_FINALIZED, total.source)
        assertTrue(total.finalized)
    }

    @Test
    fun keepsLegacyMissingUnitAbsent() {
        val total = parseDailyTotal(
            documentId = "2026-01-16",
            data = mapOf(
                "localDate" to "2026-01-16",
                "total" to "19.0",
                // unit absent: legacy row, never guessed.
            ),
        )
        assertNull(total.recordedUnit)
        assertNull(total.unitText)
        assertEquals("19.0", total.totalText)
        assertEquals(19.0, total.energyValue)
        assertNull(total.totalNumber)
    }

    @Test
    fun fallsBackToDocumentIdForLocalDate() {
        val dayStart = Instant.parse("2026-01-16T16:00:00Z").toEpochMilliseconds()

        val total = parseDailyTotal(
            documentId = "2026-01-17",
            data = mapOf(
                "total" to "17.25",
                "unit" to "kWh",
            ),
        )
        assertEquals("2026-01-17", total.localDate)
        assertEquals("2026-01-17", total.totalId)
        assertEquals(dayStart, total.dayStartEpochMillis)
    }

    @Test
    fun retainsUnknownUnitText() {
        val total = parseDailyTotal(
            documentId = "2026-01-18",
            data = mapOf(
                "localDate" to "2026-01-18",
                "total" to "500.5",
                "unit" to "VA",
            ),
        )
        assertNull(total.recordedUnit)
        assertEquals("VA", total.unitText)
    }

    @Test
    fun usesAuthoritativeTotalStringAndRejectsMissingValue() {
        val roundedMirror = parseDailyTotal(
            documentId = "2026-01-20",
            data = mapOf(
                "localDate" to "2026-01-20",
                "total" to "21.7594",
                "totalNumber" to 21.76,
                "unit" to "kWh",
            ),
        )
        assertEquals("21.7594", roundedMirror.totalText)
        assertEquals(21.7594, roundedMirror.energyValue)
        assertEquals(21.76, roundedMirror.totalNumber)

        assertFailsWith<IllegalArgumentException> {
            parseDailyTotal(documentId = "2026-01-21", data = mapOf("unit" to "kWh"))
        }
    }

    @Test
    fun parsesDailyTotalJson() {
        val json = """
            {
              "localDate": "2026-01-19",
              "total": "21.759",
              "totalNumber": 21.759,
              "unit": "kWh",
              "fetchedAt": "2026-01-19T23:58:00+08:00",
              "deviceId": "demo-device-json",
              "raw": {"nope": 1}
            }
        """.trimIndent()

        val total = parseDailyTotalJson(json, documentId = "2026-01-19")

        assertEquals("21.759", total.totalText)
        assertEquals(21.759, total.energyValue)
        assertEquals("2026-01-19", total.localDate)
        assertEquals(EnergyUnit.KWH, total.recordedUnit)
    }
}
