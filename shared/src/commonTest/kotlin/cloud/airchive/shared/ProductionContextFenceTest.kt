package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.data.thinq.ThinQContextKey
import cloud.airchive.shared.data.thinq.ThinQDeviceProfilePublication
import cloud.airchive.shared.data.thinq.ThinQEntryResult
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import cloud.airchive.shared.data.thinq.ThinQRefreshFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Presentation fences for the instrument and Device details (OpenSpec
 * `deliver-shared-instrument-ui` task 2.3): pure predicates over the observable
 * context key, live state and device/profile publication.
 */
class ProductionContextFenceTest {

    private val activeKey = ThinQContextKey("owner-a", "project-1", "ac-1", credentialGeneration = 1L)
    private val supersededKey = ThinQContextKey("owner-a", "project-1", "ac-1", credentialGeneration = 2L)
    private val profile = ThinQDeviceProfile("ac-1", controls = AcControls(powerWritable = true))
    private val device = ThinQDevice("ac-1", "Test AC", online = true)
    private val live = ThinQLiveState(
        deviceId = "ac-1",
        observedAtEpochMillis = 1_700_000_000_000L,
        powerOn = true,
        targetTemperature = 25.0,
    )
    private val current = ThinQLiveRefreshState.Current(live, profileVerified = true)

    @Test
    fun homeEntryReadyRequiresASuccessfulMatchingReadForTheActiveContext() {
        assertTrue(homeEntryReady(ThinQEntryResult.Success, activeKey, activeKey, current, profile))

        // The entry callback merely returning is never readiness.
        assertFalse(homeEntryReady(null, activeKey, activeKey, current, profile), "no result is not ready")
        assertFalse(
            homeEntryReady(ThinQEntryResult.Failed, activeKey, activeKey, current, profile),
            "a failed read is never ready",
        )
        assertFalse(
            homeEntryReady(ThinQEntryResult.Unavailable, activeKey, activeKey, current, profile),
            "an unavailable read is never ready",
        )
        assertFalse(
            homeEntryReady(ThinQEntryResult.Superseded, activeKey, activeKey, current, profile),
            "a superseded read is never ready",
        )

        // The read must belong to the context that is still active.
        assertFalse(
            homeEntryReady(ThinQEntryResult.Success, supersededKey, activeKey, current, profile),
            "a read captured under a superseded context never becomes ready",
        )
        assertFalse(homeEntryReady(ThinQEntryResult.Success, activeKey, null, current, profile))

        // It must be an actually current, verified, device-matching read.
        assertFalse(
            homeEntryReady(ThinQEntryResult.Success, activeKey, activeKey, ThinQLiveRefreshState.NotRead, profile),
            "not-read is not ready",
        )
        assertFalse(
            homeEntryReady(
                ThinQEntryResult.Success,
                activeKey,
                activeKey,
                ThinQLiveRefreshState.Stale(live, ThinQRefreshFailureKind.NETWORK, failedAtEpochMillis = 1L),
                profile,
            ),
            "a stale reading is never ready",
        )
        assertFalse(
            homeEntryReady(ThinQEntryResult.Success, activeKey, activeKey, current.copy(profileVerified = false), profile),
            "an unverified profile never makes the entry ready",
        )
        assertFalse(
            homeEntryReady(ThinQEntryResult.Success, activeKey, activeKey, current, null),
            "a missing profile is never ready",
        )
        assertFalse(
            homeEntryReady(ThinQEntryResult.Success, activeKey, activeKey, current, profile.copy(deviceId = "other-ac")),
            "a mismatched profile is never ready",
        )
        assertFalse(
            homeEntryReady(
                ThinQEntryResult.Success,
                activeKey,
                activeKey,
                current.copy(state = live.copy(deviceId = "other-ac")),
                profile,
            ),
            "a mismatched live device is never ready",
        )
    }

    @Test
    fun deviceDetailsRenderOnlyPublicationsOfTheActiveContext() {
        val publication = ThinQDeviceProfilePublication(activeKey, device, profile)
        val presentCredential = ThinQCredentialState(present = true, clientId = "lg-client")

        assertTrue(deviceDetailsReady(publication, activeKey, presentCredential))
        assertFalse(
            deviceDetailsReady(publication, supersededKey, presentCredential),
            "a superseded publication never renders as the active context's details",
        )
        assertFalse(deviceDetailsReady(null, activeKey, presentCredential), "a cleared publication renders nothing")
        assertFalse(deviceDetailsReady(publication, null, presentCredential), "an unknown active context renders nothing")
        assertFalse(
            deviceDetailsReady(
                ThinQDeviceProfilePublication(activeKey, device, null),
                activeKey,
                presentCredential,
            ),
            "an offline identity without a verified profile never renders the live card",
        )
        assertFalse(
            deviceDetailsReady(publication, activeKey, ThinQCredentialState(present = false, clientId = "lg-client")),
            "an absent credential never renders an old reading as current",
        )
        assertFalse(
            deviceDetailsReady(
                publication,
                activeKey,
                ThinQCredentialState(present = false, clientId = "lg-client", unlockFailed = true),
            ),
            "a key-lost credential never renders an old reading as current",
        )
    }

    @Test
    fun setupFeedbackIsScopedToTheLifecycleContextThatProducedIt() {
        // Feedback recorded under the active context renders normally.
        assertEquals(
            "The configured AC and profile were verified.",
            productionSetupFeedback(activeKey to "The configured AC and profile were verified.", activeKey),
        )

        // After any owner/credential transition, the old attempt's outcome is
        // never shown for the new context (task 2.4).
        assertNull(
            productionSetupFeedback(activeKey to "The configured AC and profile were verified.", supersededKey),
            "stale attempt feedback must be hidden after the context changed",
        )
        assertNull(productionSetupFeedback(activeKey to "Saved.", null), "an unknown active context shows nothing")

        // A suppressed outcome (a superseded attempt records nothing) renders nothing.
        assertNull(productionSetupFeedback(null, activeKey))
    }
}
