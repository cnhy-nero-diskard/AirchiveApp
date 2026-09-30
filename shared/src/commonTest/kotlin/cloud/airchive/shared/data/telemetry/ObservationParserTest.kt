package cloud.airchive.shared.data.telemetry

import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.EnergyUnitSource
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Deterministic, credential-free parser tests built from synthetic sibling
 * Airchive document shapes. No real device access, no Firestore, no secrets.
 */
class ObservationParserTest {

    @Test
    fun parsesOrdinaryObservation() {
        val scheduled = Instant.parse("2026-01-15T00:00:00+08:00").toEpochMilliseconds()
        val observed = Instant.parse("2026-01-15T00:05:00+08:00").toEpochMilliseconds()
        val persisted = Instant.parse("2026-01-15T00:05:02.5+08:00").toEpochMilliseconds()

        val observation = parseObservation(
            documentId = "doc-id",
            data = mapOf(
                "sampleId" to "sample-2026-01-15T0005",
                "deviceId" to "demo-device-8f3a",
                "scheduledAt" to "2026-01-15T00:00:00+08:00",
                "observedAt" to "2026-01-15T00:05:00+08:00",
                "persistedAt" to "2026-01-15T00:05:02.5+08:00",
                "localDate" to "2026-01-15",
                "timezone" to "Asia/Manila",
                "completeness" to 3,
                "metadataVersion" to "2",
                "collectorVersion" to "1.4.2",
                "energy" to mapOf(
                    "property" to "WA_ENERGY",
                    "unit" to "kWh",
                    "unitSource" to "device",
                    "rawDailyTotal" to "1234.56",
                    "rawDailyTotalNumber" to 1234.56,
                    "intervalValue" to "2.10",
                    "intervalValueNumber" to 2.1,
                    "intervalSeconds" to 300.0,
                    "previous" to mapOf(
                        "sampleId" to "sample-2026-01-15T0000",
                        "observedAt" to "2026-01-15T00:00:00+08:00",
                        "localDate" to "2026-01-15",
                        "rawDailyTotal" to "1232.46",
                        "rawDailyTotalNumber" to 1232.46,
                    ),
                    "finalPreviousDayTotal" to "900.00",
                    "finalPreviousDayTotalNumber" to 900.0,
                ),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf(
                    "energy" to mapOf("ok" to true),
                    "state" to mapOf("ok" to true),
                ),
                // Top-level raw payloads: never retained, never crash.
                "state" to mapOf("power" to "on", "temperature" to 25.0),
                "raw" to mapOf("energy" to mapOf("x" to 1), "state" to mapOf("y" to 2)),
            ),
        )

        assertEquals("sample-2026-01-15T0005", observation.sampleId)
        assertEquals("demo-device-8f3a", observation.deviceId)
        // intervalSeconds (300s) is authoritative over the previous/current diff.
        assertEquals(scheduled, observation.intervalStartEpochMillis)
        assertEquals(observed, observation.intervalEndEpochMillis)
        // Original timestamps and watermarks preserved.
        assertEquals(scheduled, observation.scheduledAtEpochMillis)
        assertEquals(observed, observation.observedAtEpochMillis)
        assertEquals(persisted, observation.persistedAtEpochMillis)
        assertNull(observation.reconciledAtEpochMillis)
        // Value, precision, and provenance.
        assertEquals(2.1, observation.energyValue)
        assertEquals("2.10", observation.intervalValue)
        assertEquals(2.1, observation.intervalValueNumber)
        assertEquals(300.0, observation.intervalSeconds)
        assertEquals("1234.56", observation.rawDailyTotal)
        assertEquals(1234.56, observation.rawDailyTotalNumber)
        assertEquals("900.00", observation.finalPreviousDayTotal)
        assertEquals(900.0, observation.finalPreviousDayTotalNumber)
        assertEquals(EnergyUnit.KWH, observation.recordedUnit)
        assertEquals("kWh", observation.unitText)
        assertEquals(EnergyUnitSource.DEVICE, observation.unitSource)
        assertEquals("device", observation.unitSourceText)
        assertEquals("WA_ENERGY", observation.energyProperty)
        assertEquals("NORMAL", observation.intervalStatus)
        assertEquals(ObservationQuality.NORMAL, observation.quality)
        assertEquals(emptySet(), observation.qualityFlags)
        assertEquals(emptySet(), observation.flagTexts)
        assertEquals("2026-01-15", observation.localDate)
        assertEquals("Asia/Manila", observation.timezone)
        assertEquals(3, observation.completeness)
        assertEquals("2", observation.metadataVersion)
        assertEquals("1.4.2", observation.collectorVersion)
        assertEquals(SourceReadFailure(ok = true), observation.energySourceFailure)
        assertEquals(SourceReadFailure(ok = true), observation.stateSourceFailure)
        assertNull(observation.sourceFailure)
    }

    @Test
    fun keepsLegacyMissingUnitAbsent() {
        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-legacy-unit",
                "scheduledAt" to "2026-01-15T00:05:00+08:00",
                "observedAt" to "2026-01-15T00:05:00+08:00",
                "localDate" to "2026-01-15",
                "timezone" to "Asia/Manila",
                "energy" to mapOf(
                    "property" to "WA_ENERGY",
                    "rawDailyTotal" to "1200.00",
                    "rawDailyTotalNumber" to 1200.0,
                    "intervalValue" to "1.10",
                    "intervalValueNumber" to 1.1,
                    "intervalSeconds" to 300.0,
                    // unit / unitSource deliberately absent.
                ),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )

        assertNull(observation.recordedUnit, "legacy rows without a unit must stay unit-less, not guessed")
        assertNull(observation.unitText)
        assertNull(observation.unitSource)
        assertNull(observation.unitSourceText)
        assertEquals(1.1, observation.energyValue)
    }

    @Test
    fun distinguishesNullIntervalFromMeasuredZero() {
        // Source reports no usable interval: energy is null, never a fabricated zero.
        val missing = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-null-interval",
                "scheduledAt" to "2026-01-15T00:10:00+08:00",
                "observedAt" to "2026-01-15T00:10:00+08:00",
                "energy" to mapOf(
                    "rawDailyTotal" to "1500.00",
                    "rawDailyTotalNumber" to 1500.0,
                    "intervalSeconds" to 300.0,
                ),
                "quality" to mapOf("intervalStatus" to "ENERGY_UNAVAILABLE", "flags" to listOf("DEVICE_OFFLINE")),
                "source" to mapOf(
                    "energy" to mapOf("ok" to false, "failureClass" to "RATE_LIMITED", "errorCode" to "1310", "errorName" to "EXCEEDED_API_CALLS"),
                    "state" to mapOf("ok" to true),
                ),
            ),
        )
        assertNull(missing.energyValue)
        assertEquals(ObservationQuality.MISSING_INTERVAL, missing.quality)
        assertEquals(setOf(QualityFlag.MISSING_VALUE), missing.qualityFlags)
        assertEquals(setOf("DEVICE_OFFLINE"), missing.flagTexts)
        assertEquals("ENERGY_UNAVAILABLE", missing.intervalStatus)
        assertEquals(
            SourceReadFailure(ok = false, failureClass = "RATE_LIMITED", errorCode = "1310", errorName = "EXCEEDED_API_CALLS"),
            missing.energySourceFailure,
        )
        assertEquals("energy:EXCEEDED_API_CALLS", missing.sourceFailure)

        // A measured zero is a legitimate value and stays a usable interval.
        val zero = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-zero-interval",
                "scheduledAt" to "2026-01-15T00:15:00+08:00",
                "observedAt" to "2026-01-15T00:15:00+08:00",
                "energy" to mapOf(
                    "unit" to "kWh",
                    "intervalValue" to "0", // precise string, no number mirror
                    "intervalSeconds" to 300.0,
                ),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals(0.0, zero.energyValue)
        assertEquals("0", zero.intervalValue)
        assertEquals(ObservationQuality.NORMAL, zero.quality)
        assertEquals(emptySet(), zero.qualityFlags)
    }

    @Test
    fun retainsUnknownFlagsAndUnknownStatusText() {
        // Known anomalous flag plus an unknown future flag: both retain, quality elevates.
        val partial = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-partial",
                "scheduledAt" to "2026-01-15T00:20:00+08:00",
                "observedAt" to "2026-01-15T00:20:00+08:00",
                "energy" to mapOf("intervalValue" to "3.25", "intervalValueNumber" to 3.25, "intervalSeconds" to 300.0),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to listOf("PARTIAL_OBSERVATION", "FUTURE_FLAG_XYZ")),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals(ObservationQuality.ANOMALOUS, partial.quality)
        assertEquals(setOf(QualityFlag.PARTIAL_INTERVAL), partial.qualityFlags)
        assertEquals(setOf("PARTIAL_OBSERVATION", "FUTURE_FLAG_XYZ"), partial.flagTexts)

        // Unknown status text is retained verbatim and treated as non-anomalous.
        val unknownStatus = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-unknown-status",
                "scheduledAt" to "2026-01-15T00:25:00+08:00",
                "observedAt" to "2026-01-15T00:25:00+08:00",
                "energy" to mapOf("intervalValue" to "1.10", "intervalValueNumber" to 1.1),
                "quality" to mapOf("intervalStatus" to "NEXT_GEN_STATUS", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals("NEXT_GEN_STATUS", unknownStatus.intervalStatus)
        assertEquals(ObservationQuality.NORMAL, unknownStatus.quality)

        // A resolved rollover keeps its informational flag and status without drama.
        val resolved = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-resolved-rollover",
                "scheduledAt" to "2026-01-15T00:30:00+08:00",
                "observedAt" to "2026-01-15T00:30:00+08:00",
                "energy" to mapOf("intervalValue" to "1.25", "intervalValueNumber" to 1.25),
                "quality" to mapOf("intervalStatus" to "DAY_ROLLOVER_RESOLVED", "flags" to listOf("RECONCILED")),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals("DAY_ROLLOVER_RESOLVED", resolved.intervalStatus)
        assertEquals(setOf("RECONCILED"), resolved.flagTexts)
        assertEquals(ObservationQuality.NORMAL, resolved.quality)
    }

    @Test
    fun separatesTypedSourceFailuresFromIntervalStatus() {
        // State read failed while energy stayed healthy: interval quality is untouched.
        val stateFailed = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-state-failed",
                "scheduledAt" to "2026-01-15T00:35:00+08:00",
                "observedAt" to "2026-01-15T00:35:00+08:00",
                "energy" to mapOf("intervalValue" to "1.50", "intervalValueNumber" to 1.5, "intervalSeconds" to 300.0),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf(
                    "energy" to mapOf("ok" to true),
                    "state" to mapOf("ok" to false, "failureClass" to "DEVICE_OFFLINE", "errorCode" to "1222", "errorName" to "NOT_CONNECTED_DEVICE"),
                ),
            ),
        )
        assertEquals(ObservationQuality.NORMAL, stateFailed.quality)
        assertEquals(emptySet(), stateFailed.qualityFlags)
        assertEquals("NORMAL", stateFailed.intervalStatus)
        assertEquals(SourceReadFailure(ok = true), stateFailed.energySourceFailure)
        assertEquals(
            SourceReadFailure(ok = false, failureClass = "DEVICE_OFFLINE", errorCode = "1222", errorName = "NOT_CONNECTED_DEVICE"),
            stateFailed.stateSourceFailure,
        )
        assertEquals("state:NOT_CONNECTED_DEVICE", stateFailed.sourceFailure)
    }

    @Test
    fun derivesIntervalFromPreviousObservedTimestampWhenIntervalSecondsAbsent() {
        val previous = Instant.parse("2026-01-15T01:55:00+08:00").toEpochMilliseconds()
        val current = Instant.parse("2026-01-15T02:10:00+08:00").toEpochMilliseconds()

        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-coarse-gap",
                "scheduledAt" to "2026-01-15T02:10:00+08:00",
                "observedAt" to "2026-01-15T02:10:00+08:00",
                "energy" to mapOf(
                    "intervalValue" to "4.00",
                    "intervalValueNumber" to 4.0,
                    "previous" to mapOf(
                        "sampleId" to "sample-coarse-gap-prev",
                        "observedAt" to "2026-01-15T01:55:00+08:00",
                        "localDate" to "2026-01-15",
                        "rawDailyTotal" to "2400.00",
                        "rawDailyTotalNumber" to 2400.0,
                    ),
                ),
                "quality" to mapOf("intervalStatus" to "COARSE_INTERVAL", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals(previous, observation.intervalStartEpochMillis)
        assertEquals(current, observation.intervalEndEpochMillis)
        assertNull(observation.intervalSeconds)
        // A coarse interval is expected after a gap: usable, not anomalous.
        assertEquals(ObservationQuality.NORMAL, observation.quality)
    }

    @Test
    fun fallsBackToScheduledTimeAndDefaultInterval() {
        val scheduled = Instant.parse("2026-01-15T00:00:00+08:00").toEpochMilliseconds()

        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-new-baseline",
                "scheduledAt" to "2026-01-15T00:00:00+08:00",
                "energy" to mapOf("intervalValue" to "0.75", "intervalValueNumber" to 0.75),
                "quality" to mapOf("intervalStatus" to "NEW_BASELINE", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals(scheduled, observation.intervalEndEpochMillis)
        assertEquals(scheduled - FALLBACK_INTERVAL_MILLIS, observation.intervalStartEpochMillis)
        assertEquals(scheduled, observation.scheduledAtEpochMillis)
        assertNull(observation.observedAtEpochMillis)
        assertEquals(ObservationQuality.NORMAL, observation.quality)
    }

    @Test
    fun acceptsEpochMillisTimestamps() {
        val scheduled = Instant.parse("2026-01-15T00:00:00+08:00").toEpochMilliseconds()
        val observed = scheduled + 300_000L

        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-epoch-millis",
                "scheduledAt" to scheduled,
                "observedAt" to observed,
                "persistedAt" to observed + 2_500L,
                "energy" to mapOf("intervalValue" to "5.00", "intervalValueNumber" to 5.0, "intervalSeconds" to 300L),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals(scheduled, observation.scheduledAtEpochMillis)
        assertEquals(scheduled, observation.intervalStartEpochMillis)
        assertEquals(observed, observation.intervalEndEpochMillis)
    }

    @Test
    fun parsesPreciseStringIntervalValueWithoutNumberMirror() {
        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-precise-string",
                "scheduledAt" to "2026-01-15T00:40:00+08:00",
                "observedAt" to "2026-01-15T00:40:00+08:00",
                "energy" to mapOf(
                    "unit" to "Wh",
                    "rawDailyTotal" to "123456.7890123456789",
                    "intervalValue" to "12.345678901234568",
                    "intervalSeconds" to 300.0,
                ),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
                "source" to mapOf("energy" to mapOf("ok" to true), "state" to mapOf("ok" to true)),
            ),
        )
        assertEquals("12.345678901234568", observation.intervalValue)
        assertEquals(12.345678901234568, observation.energyValue)
        assertEquals(EnergyUnit.WH, observation.recordedUnit)
    }

    @Test
    fun usesAuthoritativeIntervalStringWhenNumberMirrorIsRounded() {
        val observation = parseObservation(
            documentId = null,
            data = mapOf(
                "sampleId" to "sample-rounded-mirror",
                "scheduledAt" to "2026-01-15T00:45:00+08:00",
                "observedAt" to "2026-01-15T00:45:00+08:00",
                "energy" to mapOf(
                    "intervalValue" to "12.3456",
                    "intervalValueNumber" to 12.35,
                    "intervalSeconds" to 300.0,
                ),
                "quality" to mapOf("intervalStatus" to "NORMAL", "flags" to emptyList<String>()),
            ),
        )

        assertEquals("12.3456", observation.intervalValue)
        assertEquals(12.3456, observation.energyValue)
        assertEquals(12.35, observation.intervalValueNumber)
    }

    @Test
    fun parsesObservationJsonIgnoringRawPayloads() {
        val json = """
            {
              "sampleId": "sample-json",
              "deviceId": "demo-device-json",
              "scheduledAt": "2026-01-15T00:00:00+08:00",
              "observedAt": "2026-01-15T00:05:00+08:00",
              "localDate": "2026-01-15",
              "timezone": "Asia/Manila",
              "completeness": 2,
              "metadataVersion": "2",
              "collectorVersion": "1.4.2",
              "energy": {
                "unit": "kWh",
                "unitSource": "configured",
                "rawDailyTotal": "111.11", "rawDailyTotalNumber": 111.11,
                "intervalValue": "0.85", "intervalValueNumber": 0.85,
                "intervalSeconds": 300
              },
              "quality": {"intervalStatus": "NORMAL", "flags": []},
              "source": {
                "energy": {"ok": true},
                "state": {"ok": false, "failureClass": "TRANSPORT", "errorCode": null, "errorName": "connection reset"}
              },
              "state": {"power": "on"},
              "raw": {"energy": {"foo": 1}},
              "futureUnknownField": {"anything": 1}
            }
        """.trimIndent()

        val observation = parseObservationJson(json, documentId = "doc-id")

        assertEquals("sample-json", observation.sampleId)
        assertEquals("demo-device-json", observation.deviceId)
        assertEquals(EnergyUnit.KWH, observation.recordedUnit)
        assertEquals(EnergyUnitSource.CONFIGURED, observation.unitSource)
        assertEquals(2, observation.completeness)
        assertEquals("0.85", observation.intervalValue)
        // State source failure carried without degrading the energy interval.
        assertEquals(
            SourceReadFailure(ok = false, failureClass = "TRANSPORT", errorName = "connection reset"),
            observation.stateSourceFailure,
        )
        assertEquals(SourceReadFailure(ok = true), observation.energySourceFailure)
        assertEquals(ObservationQuality.NORMAL, observation.quality)
        assertEquals("state:connection reset", observation.sourceFailure)
        // Unknown keys and raw/state payload blocks are ignored, not surfaced.
        assertEquals(emptySet(), observation.flagTexts)
    }
}
