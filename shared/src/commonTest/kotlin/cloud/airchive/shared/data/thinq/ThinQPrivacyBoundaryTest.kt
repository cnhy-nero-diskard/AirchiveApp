package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.InMemoryInterfaceDetailPreferences
import cloud.airchive.shared.InterfaceDetail
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import cloud.airchive.shared.productionCredentialStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Synthetic-only check across the non-secret projections used by UI/diagnostics. */
class ThinQPrivacyBoundaryTest {

    @Test
    fun syntheticPatMarkerNeverEntersHistoryPreferencesDiagnosticsOrCapturedSafeRecords() = runBlocking {
        val marker = "synthetic-task27-pat-marker-5831"
        val ownerUid = "privacy-owner-a"
        val store = FakeThinQCredentialStore()
        val verifier = FakeThinQCredentialVerifier(TEST_DEVICE_ID)
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = ScriptedThinQPort(),
            verifier = verifier,
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerUid,
            projectId = TEST_PROJECT_ID,
        )

        val setup = coordinator.setupCredentialDetailed(marker, "PH")
        assertIs<cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult.Success>(setup.result)
        val credentialState = store.observe().first()
        val credential = assertNotNull(credentialState.credential)
        val verificationRequest = assertNotNull(verifier.lastRequest)

        val partition = HistoryPartition(TEST_PROJECT_ID, ownerUid, TEST_DEVICE_ID)
        val localHistory = FakeLocalHistory()
        localHistory.upsertObservations(
            partition,
            listOf(
                Observation(
                    sampleId = "privacy-sample-1",
                    intervalStartEpochMillis = 1_700_000_000_000L,
                    intervalEndEpochMillis = 1_700_000_300_000L,
                    energyValue = 12.5,
                    recordedUnit = EnergyUnit.WH,
                ),
            ),
        )
        localHistory.upsertCoveredRange(partition, CoveredRange(1_700_000_000_000L, 1_700_000_300_000L))
        localHistory.recordSync(partition, SyncMetadata(lastSuccessfulSyncEpochMillis = 1_700_000_300_000L))
        val history = localHistory.observe(partition).first()
        val preferences = InMemoryInterfaceDetailPreferences().also {
            it.select(InterfaceDetail.DETAILED)
        }
        val diagnostics = credentialState.productionCredentialStatus()

        // A captured test log records only the same safe representations used
        // by setup results and diagnostics; rawToken is never passed to it.
        val capturedSafeLogs = listOf(
            "credential-verification=$verificationRequest",
            "credential-setup=$setup",
            "credential-state=$credentialState",
            "local-diagnostics=$diagnostics",
            "history-snapshot=$history",
            "interface-preference=${preferences.detail.value}",
            "context-key=${coordinator.contextKey.value}",
            "device-profile=${coordinator.deviceProfile.value}",
        )
        val allSafeOutput = capturedSafeLogs.joinToString("\n")

        assertFalse(marker in verificationRequest.toString(), "verification request representation is redacted")
        assertFalse(marker in setup.toString(), "the setup result never exposes plaintext")
        assertFalse(marker in credentialState.toString(), "observable credential state is masked only")
        assertFalse(marker in diagnostics, "Settings diagnostics show a masked status, not plaintext")
        assertFalse(marker in history.toString(), "history rows and sync metadata never contain PAT text")
        assertFalse(marker in preferences.detail.value.toString(), "display preferences contain no PAT data")
        assertFalse(marker in allSafeOutput, "captured UI/diagnostic log projections omit plaintext")
        assertTrue(credential.maskedToken.endsWith(marker.takeLast(4)), "only the documented masked suffix is visible")
        assertEquals(marker, store.readToken(), "the raw marker exists only in the in-memory synthetic store")

        val removal = coordinator.removeCredential()
        assertTrue(removal.removed)
        assertNull(store.readToken(), "ended setup state clears the synthetic credential")
        assertNull(coordinator.deviceProfile.value)
        assertTrue(coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertEquals(AcCommandUiState.Idle, coordinator.commandController.state.value)
    }
}
