package cloud.airchive.shared.data.telemetry

import cloud.airchive.shared.contract.telemetry.CollectorStatus
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Deterministic device-identity and collector-health parser tests. */
class DeviceAndHealthParserTest {

    // --- devices/{deviceId} identity ---------------------------------------

    @Test
    fun parsesDeviceIdentity() {
        val updatedAt = Instant.parse("2026-01-01T12:00:00+08:00").toEpochMilliseconds()

        val identity = parseDeviceIdentity(
            documentId = "doc-id",
            data = mapOf(
                "deviceId" to "demo-device-8f3a",
                "alias" to "Living Room AC",
                "modelName" to "WP-03ACFQH",
                "deviceType" to "AC",
                "updatedAt" to "2026-01-01T12:00:00+08:00",
            ),
        )

        assertEquals("demo-device-8f3a", identity.deviceId)
        assertEquals("Living Room AC", identity.alias)
        assertEquals("WP-03ACFQH", identity.modelName)
        assertEquals("AC", identity.deviceType)
        assertEquals(updatedAt, identity.updatedAtEpochMillis)
    }

    @Test
    fun fallsBackToDocumentIdForDeviceId() {
        val identity = parseDeviceIdentity(
            documentId = "d7dc30c9-existing-doc-id",
            data = mapOf("alias" to "No id field legacy row"),
        )
        assertEquals("d7dc30c9-existing-doc-id", identity.deviceId)
    }

    // --- devices/{deviceId}/runtime/collector health ------------------------

    @Test
    fun parsesHealthyCollectorHealth() {
        val attempt = Instant.parse("2026-01-15T00:05:00+08:00").toEpochMilliseconds()
        val success = Instant.parse("2026-01-15T00:04:58+08:00").toEpochMilliseconds()

        val health = parseCollectorHealth(
            mapOf(
                "lastAttemptAt" to "2026-01-15T00:05:00+08:00",
                "lastSuccessAt" to "2026-01-15T00:04:58+08:00",
                "lastSampleId" to "sample-2026-01-15T0005",
                "lastSamplePath" to "devices/demo-device-8f3a/telemetry/sample-x",
                "consecutiveFailures" to 0,
                "consecutiveRateLimits" to 0,
                "collectorVersion" to "1.4.2",
            ),
        )

        assertEquals(CollectorStatus.HEALTHY, health.status)
        assertEquals(attempt, health.lastPollEpochMillis)
        assertEquals(success, health.lastSuccessEpochMillis)
        assertEquals("sample-2026-01-15T0005", health.lastSampleId)
        assertEquals("devices/demo-device-8f3a/telemetry/sample-x", health.lastSamplePath)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(0, health.consecutiveRateLimits)
        assertEquals("1.4.2", health.collectorVersion)
        assertNull(health.lastFailureEpochMillis)
        assertNull(health.lastErrorClass)
    }

    @Test
    fun parsesDegradedCollectorHealth() {
        val errorAt = Instant.parse("2026-01-15T00:05:00+08:00").toEpochMilliseconds()
        val rateLimitedUntil = Instant.parse("2026-01-15T00:15:00+08:00").toEpochMilliseconds()

        val health = parseCollectorHealth(
            mapOf(
                "lastAttemptAt" to "2026-01-15T00:05:00+08:00",
                "lastSuccessAt" to "2026-01-14T23:55:00+08:00",
                "lastErrorAt" to "2026-01-15T00:05:00+08:00",
                "lastErrorClass" to "TRANSIENT",
                "lastErrorMessage" to "request timed out",
                "consecutiveFailures" to 3,
                "consecutiveRateLimits" to 1,
                "rateLimitedUntil" to "2026-01-15T00:15:00+08:00",
            ),
        )

        assertEquals(CollectorStatus.DEGRADED, health.status)
        assertEquals(3, health.consecutiveFailures)
        assertEquals(1, health.consecutiveRateLimits)
        assertEquals(errorAt, health.lastFailureEpochMillis)
        assertEquals("TRANSIENT", health.lastErrorClass)
        assertEquals("request timed out", health.lastFailureReason)
        assertEquals(rateLimitedUntil, health.rateLimitedUntilEpochMillis)
    }

    @Test
    fun parsesOfflineCollectorHealth() {
        val health = parseCollectorHealth(
            mapOf(
                "lastAttemptAt" to "2026-01-15T00:05:00+08:00",
                "lastErrorAt" to "2026-01-15T00:05:00+08:00",
                "lastErrorClass" to "TRANSPORT",
                "lastErrorMessage" to "connection refused",
                "consecutiveFailures" to 5,
            ),
        )

        assertEquals(CollectorStatus.OFFLINE, health.status)
        assertEquals(5, health.consecutiveFailures)
        assertEquals("TRANSPORT", health.lastErrorClass)
        assertNull(health.lastSuccessEpochMillis)
    }

    @Test
    fun ignoresLeaseAndUnknownHealthFields() {
        val health = parseCollectorHealth(
            mapOf(
                "lastAttemptAt" to "2026-01-15T00:05:00+08:00",
                "lastSuccessAt" to "2026-01-15T00:00:00+08:00",
                "consecutiveFailures" to 0,
                "leaseHolder" to "job-abc-123",
                "leaseUntil" to "2026-01-15T00:09:00+08:00",
                "futureHealthField" to mapOf("x" to 1),
            ),
        )

        assertEquals(CollectorStatus.HEALTHY, health.status)
        assertEquals(0, health.consecutiveFailures)
        // Lease and unknown fields never surface on the projection.
        assertNull(health.rateLimitedUntilEpochMillis)
        assertNull(health.lastSampleId)
    }

    @Test
    fun emptyHealthIsUnknown() {
        val health = parseCollectorHealth(emptyMap())
        assertEquals(CollectorStatus.UNKNOWN, health.status)
        assertNull(health.lastPollEpochMillis)
        assertNull(health.lastSuccessEpochMillis)
        assertNull(health.consecutiveFailures)
    }

    @Test
    fun parsesCollectorHealthJson() {
        val json = """
            {
              "lastAttemptAt": "2026-01-15T00:05:00+08:00",
              "lastSuccessAt": "2026-01-15T00:04:58+08:00",
              "lastSampleId": "sample-a",
              "consecutiveFailures": 0,
              "collectorVersion": "1.4.2",
              "leaseHolder": "job-ignored"
            }
        """.trimIndent()

        val health = parseCollectorHealthJson(json)
        assertEquals(CollectorStatus.HEALTHY, health.status)
        assertEquals("sample-a", health.lastSampleId)
        assertEquals("1.4.2", health.collectorVersion)
        assertNull(health.lastErrorClass)
    }
}
