package cloud.airchive.shared.data.local

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.EnergyUnitSource
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure, credential-free unit tests for the durable-store projection logic that
 * needs no live database: coverage merging and entity ↔ domain mapping.
 */
class RoomLocalHistoryMappingTest {

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-1",
        deviceId = "device-8f3a",
    )

    @Test
    fun observationRoundTripPreservesNullablesAndTypedValues() {
        val source = Observation(
            sampleId = "sample-2026-01-15T0005",
            deviceId = "device-8f3a",
            intervalStartEpochMillis = 1_000L,
            intervalEndEpochMillis = 1_300L,
            // Null interval energy must survive as null, never become zero.
            energyValue = null,
            // Legacy missing unit stays absent.
            recordedUnit = null,
            intervalStatus = "ENERGY_UNAVAILABLE",
            quality = ObservationQuality.MISSING_INTERVAL,
            qualityFlags = setOf(QualityFlag.MISSING_VALUE),
            sourceFailure = "energy:EXCEEDED_API_CALLS",
            persistedAtEpochMillis = 1_400L,
            reconciledAtEpochMillis = 1_500L,
            scheduledAtEpochMillis = 1_000L,
            observedAtEpochMillis = 1_300L,
            localDate = "2026-01-15",
            timezone = "Asia/Manila",
            completeness = 2,
            metadataVersion = "2",
            collectorVersion = "1.4.2",
            energyProperty = "WA_ENERGY",
            rawDailyTotal = "1234.56",
            rawDailyTotalNumber = 1234.56,
            intervalValue = "2.10",
            intervalValueNumber = 2.1,
            intervalSeconds = 300.0,
            finalPreviousDayTotal = "900.00",
            finalPreviousDayTotalNumber = 900.0,
            unitText = null,
            unitSource = null,
            unitSourceText = null,
            // Unknown future flag text survives verbatim.
            flagTexts = setOf("DEVICE_OFFLINE", "FUTURE_FLAG_XYZ"),
            energySourceFailure = SourceReadFailure(
                ok = false,
                failureClass = "RATE_LIMITED",
                errorCode = "1310",
                errorName = "EXCEEDED_API_CALLS",
            ),
            stateSourceFailure = SourceReadFailure(ok = true),
        )

        val entity = source.toEntity(partition)
        assertEquals(partition.firebaseProjectId, entity.firebaseProjectId)
        assertEquals(partition.ownerUid, entity.ownerUid)
        assertEquals(partition.deviceId, entity.deviceId)
        assertEquals("MISSING_INTERVAL", entity.quality)
        assertEquals("MISSING_VALUE", entity.qualityFlags)
        assertEquals("DEVICE_OFFLINE\u001FFUTURE_FLAG_XYZ", entity.flagTexts)

        assertEquals(source, entity.toDomain(partition))
    }

    @Test
    fun observationRoundTripKeepsEmptyFlagsEmpty() {
        val source = Observation(
            sampleId = "sample-clean",
            intervalStartEpochMillis = 0L,
            intervalEndEpochMillis = 300_000L,
            energyValue = 0.85,
            recordedUnit = EnergyUnit.KWH,
            quality = ObservationQuality.NORMAL,
            unitText = "kWh",
            unitSource = EnergyUnitSource.CONFIGURED,
            unitSourceText = "configured",
        )
        assertEquals(source, source.toEntity(partition).toDomain(partition))
    }

    @Test
    fun dailyTotalRoundTripPreservesProvenance() {
        val source = DailyTotal(
            totalId = "daily:2026-01-15",
            dayStartEpochMillis = 1_000L,
            energyValue = 12.34,
            // Legacy row without a unit stays unit-less.
            recordedUnit = null,
            source = DailyTotalSource.LG_FINALIZED,
            finalized = true,
            localDate = "2026-01-15",
            totalText = "12.34",
            totalNumber = 12.34,
            unitText = null,
            deviceId = "device-8f3a",
            fetchedAtEpochMillis = 2_000L,
        )
        assertEquals(source, source.toEntity(partition).toDomain(partition))
    }

    @Test
    fun collectorHealthRoundTripPreservesCounters() {
        val source = CollectorHealth(
            status = CollectorStatus.DEGRADED,
            lastPollEpochMillis = 1_000L,
            lastSuccessEpochMillis = 900L,
            lastFailureEpochMillis = 1_000L,
            lastFailureReason = "transient",
            lastSampleId = "sample-last",
            lastSamplePath = "devices/d/t/telemetry/sample-last",
            lastErrorClass = "TRANSIENT",
            consecutiveFailures = 2,
            consecutiveRateLimits = 1,
            rateLimitedUntilEpochMillis = 2_000L,
            collectorVersion = "1.4.2",
        )
        assertEquals(source, source.toEntity(partition).toDomain())
    }

    @Test
    fun syncMetadataRoundTripPreservesWatermarks() {
        val source = SyncMetadata(
            lastSuccessfulSyncEpochMillis = 1_000L,
            lastErrorEpochMillis = null,
            lastError = null,
            persistedAtWatermarkEpochMillis = 900L,
            // The v2-only watermark survives a plain round trip.
            reconciledAtWatermarkEpochMillis = 950L,
        )
        assertEquals(source, source.toEntity(partition).toDomain())
    }

    @Test
    fun coverageMergeJoinsOverlappingAndAdjacentRanges() {
        assertEquals(
            listOf(CoveredRange(0L, 1_000L)),
            normalizeCoveredRanges(
                listOf(CoveredRange(0L, 500L), CoveredRange(500L, 1_000L)),
            ),
        )
        assertEquals(
            listOf(CoveredRange(0L, 1_200L)),
            normalizeCoveredRanges(
                listOf(
                    CoveredRange(500L, 1_000L),
                    CoveredRange(0L, 600L),
                    CoveredRange(800L, 1_200L),
                ),
            ),
        )
        assertEquals(
            listOf(CoveredRange(0L, 500L), CoveredRange(2_000L, 3_000L)),
            normalizeCoveredRanges(
                listOf(CoveredRange(2_000L, 3_000L), CoveredRange(0L, 500L)),
            ),
        )
        assertEquals(emptyList(), normalizeCoveredRanges(emptyList()))
    }

    @Test
    fun unknownStoredEnumNamesFallBackSafely() {
        // A legacy/corrupt row keeps its raw enum-text but must still decode.
        val entity = Observation(
            sampleId = "x",
            intervalStartEpochMillis = 0L,
            intervalEndEpochMillis = 1L,
            energyValue = 1.0,
            quality = ObservationQuality.NORMAL,
        ).toEntity(partition).copy(
            quality = "SOME_FUTURE_QUALITY",
            qualityFlags = "MISSING_VALUE\u001FUNKNOWN_FLAG",
            recordedUnit = "watts",
        )
        val decoded = entity.toDomain(partition)
        assertEquals(ObservationQuality.UNAVAILABLE, decoded.quality)
        assertEquals(setOf(QualityFlag.MISSING_VALUE), decoded.qualityFlags)
        assertEquals(null, decoded.recordedUnit)
    }
}