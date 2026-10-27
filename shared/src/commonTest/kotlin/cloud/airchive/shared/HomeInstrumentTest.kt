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
