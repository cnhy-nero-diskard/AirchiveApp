package cloud.airchive.shared

import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure text-projection checks used by the History/Health route, with local synthetic rows only. */
class HistoryHealthPresentationTest {

    @Test
    fun explicitMissingAndUnavailableQualityNeverPresentsAStoredValueAsMeasured() {
        for (quality in listOf(ObservationQuality.MISSING_INTERVAL, ObservationQuality.UNAVAILABLE)) {
            val row = observation(quality = quality, energyValue = 42.0)

            assertFalse(row.hasUsableHistoryEnergy())
            val text = row.historyEnergySummaryText()
            assertTrue(text.contains("gap"), "$quality should be explained as a gap")
            assertTrue(text.contains("never"), "$quality should not be presented as a measured zero")
            assertFalse(text.contains("42 Wh"), "$quality should not imply its inconsistent source value is usable")
        }
    }

    @Test
    fun anomalousValueRemainsVisibleButIsExplicitlyExcludedFromUsableEnergy() {
        val row = observation(quality = ObservationQuality.ANOMALOUS, energyValue = 42.0)

        assertFalse(row.hasUsableHistoryEnergy())
        val text = row.historyEnergySummaryText()
        assertTrue(text.contains("42 Wh"))
        assertTrue(text.contains("anomalous"))
        assertTrue(text.contains("excluded"))
    }

    @Test
    fun anomalousUnknownUnitAndNonFiniteNormalValuesAreNotRenderedAsUsableMeasures() {
        val anomalousUnknownUnit = observation(
            quality = ObservationQuality.ANOMALOUS,
            energyValue = 42.0,
        ).copy(recordedUnit = null, unitText = "legacy-unit")
        val nonFinite = observation(energyValue = Double.NaN)

        assertFalse(anomalousUnknownUnit.hasUsableHistoryEnergy())
        val anomalousText = anomalousUnknownUnit.historyEnergySummaryText()
        assertTrue(anomalousText.contains("unit not recorded/unknown"))
        assertTrue(anomalousText.contains("anomalous"))
        assertTrue(anomalousText.contains("excluded"))

        assertFalse(nonFinite.hasUsableHistoryEnergy())
        val nonFiniteText = nonFinite.historyEnergySummaryText()
        assertTrue(nonFiniteText.contains("Non-finite"))
        assertTrue(nonFiniteText.contains("gap"))
        assertFalse(nonFiniteText.contains("n/a Wh"))
    }

    @Test
    fun stateOnlyReadFailureDoesNotInvalidateUsableEnergyOrLeakLegacyFailureDetail() {
        val row = observation(energyValue = 42.0).copy(
            sourceFailure = "state:synthetic-detail-not-for-display",
            stateSourceFailure = SourceReadFailure(ok = false, failureClass = "TRANSIENT"),
        )

        assertTrue(row.hasUsableHistoryEnergy())
        assertTrue(row.historyEnergySummaryText().contains("42 Wh"))
        val failures = row.historySourceFailureSummary().orEmpty()
        assertTrue(failures.contains("State read failed"))
        assertTrue(failures.contains("energy value is unaffected"))
        assertFalse(failures.contains("synthetic-detail-not-for-display"))
    }

    @Test
    fun typedAndLegacyEnergyFailuresBecomeGapsWithoutDisplayingFailureText() {
        val typedFailure = observation(energyValue = 42.0).copy(
            energySourceFailure = SourceReadFailure(ok = false, failureClass = "TRANSIENT"),
        )
        val legacyFailure = observation(energyValue = 42.0).copy(
            sourceFailure = "energy:synthetic-detail-not-for-display",
        )

        for (row in listOf(typedFailure, legacyFailure)) {
            assertFalse(row.hasUsableHistoryEnergy())
            assertTrue(row.historyEnergySummaryText().contains("Energy read failed"))
            assertFalse(row.historySourceFailureSummary().orEmpty().contains("synthetic-detail-not-for-display"))
        }
        assertTrue(typedFailure.historySourceFailureSummary().orEmpty().contains("Energy read failed"))
        assertTrue(legacyFailure.historySourceFailureSummary().orEmpty().contains("legacy energy-read failure"))
    }

    @Test
    fun unclassifiedLegacySourceFailureHasSafeGenericDetail() {
        val row = observation(energyValue = 42.0).copy(sourceFailure = "synthetic-private-detail")

        assertTrue(row.historySourceFailureSummary().orEmpty().contains("legacy source failure is recorded"))
        assertFalse(row.historySourceFailureSummary().orEmpty().contains("synthetic-private-detail"))

        val counters = countSampleTypes(listOf(row))
        assertTrue(counters.unclassifiedLegacyFailure == 1)
        assertTrue(counters.energyFailed == 0 && counters.stateFailed == 0)
    }

    private fun observation(
        quality: ObservationQuality = ObservationQuality.NORMAL,
        energyValue: Double? = 42.0,
    ) = Observation(
        sampleId = "history-fixture-1",
        intervalStartEpochMillis = 1_000L,
        intervalEndEpochMillis = 301_000L,
        energyValue = energyValue,
        recordedUnit = EnergyUnit.WH,
        quality = quality,
    )
}
