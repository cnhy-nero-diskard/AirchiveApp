package cloud.airchive.shared

import cloud.airchive.shared.contract.thinq.*
import cloud.airchive.shared.data.thinq.*
import cloud.airchive.shared.data.energy.*
import cloud.airchive.shared.contract.history.*
import cloud.airchive.shared.contract.telemetry.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.*

class HomeInstrumentTest {
    private val state = ThinQLiveState("ac", 100, true, 25.0)
    private val profile = ThinQDeviceProfile("ac", controls = AcControls(powerWritable = true))
    private val temperatureCapability = TemperatureCapability(
        min = 18.0,
        max = 30.0,
        step = 1.0,
        unit = TemperatureUnit.CELSIUS,
    )

    @Test fun controlsRequireTheSameVerifiedLiveDeviceAndNoPendingCommand() {
        assertTrue(homeControlsEnabled(ThinQLiveRefreshState.Current(state, true), profile, AcCommandUiState.Idle))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Current(state, false), profile, AcCommandUiState.Idle))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Current(state, true), profile.copy(deviceId = "other"), AcCommandUiState.Idle))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Current(state, true), null, AcCommandUiState.Idle))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Current(state, true), profile,
            AcCommandUiState.Sending(AcCommand.SetPower(101, false))))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Stale(state, ThinQRefreshFailureKind.NETWORK, 101), profile, AcCommandUiState.Idle))
        assertFalse(homeControlsEnabled(ThinQLiveRefreshState.Loading(ThinQLiveRefreshState.Current(state, true)), profile, AcCommandUiState.Idle))
    }

    @Test fun energyReadoutHandlesRecordedZeroLargeAndSignedValuesWithoutIntegerOverflow() {
        assertEquals("0.00", instrumentNumber(0.0))
        assertEquals("1.24", instrumentNumber(1.24))
        assertEquals("−1.24", instrumentNumber(-1.24))
        assertEquals("30000000.00", instrumentNumber(30_000_000.0))
        assertEquals("—", instrumentNumber(Double.NaN))
    }

    @Test fun sameContextObservationRefreshPreservesDirtyDraftAndRecomputesApplyState() {
        val draft = TemperatureDraft(26.0, TemperatureUnit.CELSIUS)

        val beforeRefresh = resolveTemperatureDraft(draft, observed = 25.0, capability = temperatureCapability)
        assertEquals(TemperatureDraftStatus.DIRTY, beforeRefresh.status)
        assertEquals(26.0, beforeRefresh.displayValue)
        assertTrue(beforeRefresh.canApply)

        val afterRefresh = resolveTemperatureDraft(draft, observed = 27.0, capability = temperatureCapability)
        assertEquals(TemperatureDraftStatus.DIRTY, afterRefresh.status)
        assertEquals(26.0, afterRefresh.displayValue)
        assertEquals(27.0, afterRefresh.observedValue, "the newly confirmed observation remains separately identifiable")
        assertTrue(afterRefresh.canApply)

        val observedMatch = resolveTemperatureDraft(draft, observed = 26.0, capability = temperatureCapability)
        assertEquals(TemperatureDraftStatus.MATCHES_OBSERVATION, observedMatch.status)
        assertEquals(26.0, observedMatch.displayValue)
        assertFalse(observedMatch.canApply, "a matching observation does not mean an unapplied draft should be resubmitted")
    }

    @Test fun changedProfileInvalidatesUnsupportedDraftWithoutClampingOrSubstitution() {
        val draft = TemperatureDraft(26.0, TemperatureUnit.CELSIUS)
        val stillSupported = resolveTemperatureDraft(
            draft,
            observed = 25.0,
            capability = TemperatureCapability(20.0, 28.0, 2.0, TemperatureUnit.CELSIUS),
        )
        assertEquals(TemperatureDraftStatus.DIRTY, stillSupported.status)
        assertEquals(26.0, stillSupported.displayValue)
        assertTrue(stillSupported.canApply)

        val unsupportedProfiles = listOf(
            TemperatureCapability(18.0, 25.0, 1.0, TemperatureUnit.CELSIUS),
            TemperatureCapability(18.0, 30.0, 3.0, TemperatureUnit.CELSIUS),
            TemperatureCapability(64.0, 86.0, 1.0, TemperatureUnit.FAHRENHEIT),
            TemperatureCapability(18.0, 30.0, 1.0, TemperatureUnit.CELSIUS, excludedValues = setOf(26.0)),
        )

        unsupportedProfiles.forEach { changedCapability ->
            val resolved = resolveTemperatureDraft(draft, observed = 25.0, capability = changedCapability)
            assertEquals(TemperatureDraftStatus.UNSUPPORTED, resolved.status)
            assertEquals(draft, resolved.unsupportedDraft)
            assertNull(resolved.displayValue, "unsupported draft values are never clamped or substituted")
            assertFalse(resolved.canApply, "an unsupported profile value must never be applied")
        }
    }

    @Test fun absentDraftAfterOwnerOrCredentialContextChangeCannotEnableApply() {
        val ownerA = ThinQContextKey("synthetic-owner-a", "synthetic-project", "synthetic-ac", 0L)
        val ownerB = ownerA.copy(ownerUid = "synthetic-owner-b", credentialGeneration = 1L)
        val replacement = ownerA.copy(credentialGeneration = 1L)
        assertNotEquals(ownerA, ownerB)
        assertNotEquals(ownerA, replacement)

        // ProductionRouteHost keys the shell-held draft to this complete key;
        // either transition supplies an empty draft to the same resolver.
        listOf(ownerB, replacement).forEach { nextContext ->
            assertNotEquals(ownerA, nextContext)
            val afterInvalidation = resolveTemperatureDraft(
                draft = null,
                observed = 25.0,
                capability = temperatureCapability,
            )
            assertEquals(TemperatureDraftStatus.NONE, afterInvalidation.status)
            assertFalse(afterInvalidation.canApply)
        }
    }

    @Test fun chartKeepsPartialCoverageGapsAndMeasuredZeroAndFreshnessIgnoresTrailingGap() {
        val start = Instant.parse("2026-10-23T16:00:00Z").toEpochMilliseconds()
        fun sample(slot: Int, value: Double?) = Observation("$slot", start + slot * 300_000L,
            start + (slot + 1) * 300_000L, value, EnergyUnit.WH)
        val history = HistorySnapshot(HistoryPartition("p", "o", "ac"),
            listOf(sample(0, 0.0), sample(1, 20.0), sample(72, null)), emptyList(), emptyList())
        val day = presentEnergyHistory(history, EnergyPeriodType.DAY, LocalDate.parse("2026-10-24"), start + 73 * 300_000L).days.single()
        val groups = instrumentEnergyGroups(day)
        assertEquals(20.0, groups[0].energyWh)
        assertEquals(2, groups[0].usable)
        assertEquals(36, groups[0].expected)
        assertNull(groups[1].energyWh)
        assertEquals(1, groups[2].expected)
        assertNull(groups[2].energyWh)
        assertEquals(0, groups[3].expected)
        assertEquals(start + 2 * 300_000L, latestUsableEnergyPoint(day)?.intervalEndEpochMillis)
    }
}
