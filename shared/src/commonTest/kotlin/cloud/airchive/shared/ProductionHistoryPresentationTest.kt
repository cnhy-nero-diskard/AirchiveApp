package cloud.airchive.shared

import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Honest-label tests for the production surfaces: the configured AC keeps the
 * generic title, identity lines come only from sampled data, access copy is
 * real (no simulated sign-in claims), and no captured production string carries
 * a demo/fake/simulated marker.
 */
class ProductionHistoryPresentationTest {

    private val owner = OwnerSession(uid = "owner-uid", signedInAtEpochMillis = 1_750_000_000_000L)

    @Test
    fun configuredAcTitleIsGenericUntilIdentityIsSampled() {
        assertEquals("Configured AC", productionConfiguredAcTitle())
    }

    @Test
    fun previousOwnersFlowValueIsHiddenBeforeNewPartitionEmits() {
        val oldPartition = HistoryPartition("project", "old-owner", "ac")
        val newPartition = oldPartition.copy(ownerUid = "new-owner")
        val empty = HistorySnapshot(oldPartition.copy(ownerUid = ""), emptyList(), emptyList(), emptyList())
        val oldValue = empty.copy(partition = oldPartition, sync = SyncMetadata(lastSuccessfulSyncEpochMillis = 123L))
        val transitional = productionHistoryForPartition(oldValue, newPartition, empty)
        assertEquals(newPartition, transitional.partition)
        assertEquals(null, transitional.sync)
        assertTrue(transitional.observations.isEmpty())
        assertEquals(empty, productionHistoryForPartition(oldValue, null, empty))
        assertEquals(oldValue, productionHistoryForPartition(oldValue, oldPartition, empty))
    }

    @Test
    fun oldOwnerAccessCallbackCannotAuthorizeNewSessionOrSignedOutScreen() {
        val oldAccess = OwnerAccessState.Authorized(owner, DeviceIdentity(deviceId = "ac"))
        assertEquals(OwnerAccessState.SignedOut, productionAccessForSession(oldAccess, owner.copy(uid = "new-owner")))
        assertEquals(OwnerAccessState.SignedOut, productionAccessForSession(oldAccess, null))
        assertEquals(oldAccess, productionAccessForSession(oldAccess, owner))
    }

    @Test
    fun identityLinesComeOnlyFromSampledData() {
        assertEquals(emptyList(), productionSampledIdentityLines(null))

        val identity = DeviceIdentity(
            deviceId = "configured-ac-1",
            alias = "Living Room AC",
            modelName = "ACX-500",
            deviceType = "air-conditioner",
            updatedAtEpochMillis = 1_750_000_000_000L,
        )
        val lines = productionSampledIdentityLines(identity)
        assertTrue(lines.any { it.contains("Living Room AC") })
        assertTrue(lines.any { it.contains("ACX-500") })
        assertTrue(lines.any { it.contains("air-conditioner") })

        // No "online"/"model ..." invented labels; only what was sampled.
        assertFalse(lines.any { it.contains("online", ignoreCase = true) })
    }

    @Test
    fun accessMessagesAreRealAndNeverSimulated() {
        assertEquals(true, productionAccessMessage(OwnerAccessState.SignedOut).contains("signed out"))
        assertTrue(productionAccessMessage(OwnerAccessState.AccessPending(owner, "ACCESS_DENIED")).contains("pending"))
        assertTrue(productionAccessMessage(OwnerAccessState.AccessCheckFailed(owner, "OFFLINE")).contains("verified"))
        val authorized = productionAccessMessage(OwnerAccessState.Authorized(owner, DeviceIdentity(deviceId = "ac-1")))
        assertTrue(authorized.contains("verified"))
        assertTrue(authorized.contains(owner.uid))
    }

    @Test
    fun signInFailureReasonsAreFixedAndDistinct() {
        assertEquals("the account chooser was closed", productionSignInFailureReason(SignInFailure.USER_CANCELLED))
        assertEquals("no connection to the sign-in service", productionSignInFailureReason(SignInFailure.NETWORK))
        assertEquals("Google sign-in is not configured for this build", productionSignInFailureReason(SignInFailure.PLATFORM))
        assertEquals("an unexpected sign-in failure", productionSignInFailureReason(SignInFailure.UNKNOWN))
    }

    @Test
    fun noProductionCopyCarriesDemoFakeOrSimulatedMarkers() {
        val captured = buildList {
            add(productionConfiguredAcTitle())
            addAll(productionSampledIdentityLines(
                DeviceIdentity(deviceId = "ac-1", alias = "Room AC", modelName = "ACX", deviceType = "air-conditioner"),
            ))
            add(productionAccessMessage(OwnerAccessState.SignedOut))
            add(productionAccessMessage(OwnerAccessState.SignInFailed(SignInFailure.NETWORK)))
            add(productionAccessMessage(OwnerAccessState.AccessPending(owner, "ACCESS_DENIED")))
            add(productionAccessMessage(OwnerAccessState.AccessCheckFailed(owner, "REMOTE_FAILURE")))
            add(productionAccessMessage(OwnerAccessState.Authorized(owner, DeviceIdentity(deviceId = "ac-1"))))
            SignInFailure.entries.forEach { add(productionSignInFailureReason(it)) }
        }
        val forbidden = Regex("(?i)(simulat|demo|fake)")
        assertFalse(captured.any { forbidden.containsMatchIn(it) }, "production copy must stay free of demo markers: $captured")
    }
}
