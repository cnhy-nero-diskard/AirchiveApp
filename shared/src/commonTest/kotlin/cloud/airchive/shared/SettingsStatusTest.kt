package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStatusTest {
    private val owner = OwnerSession(uid = "owner-uid", signedInAtEpochMillis = 1_750_000_000_000L)

    @Test fun googleAccountIsOkOnlyWhileSignedIn() {
        assertEquals(InstrumentStatus.OK, googleAccountStatus(signedIn = true))
        assertEquals(InstrumentStatus.NEUTRAL, googleAccountStatus(signedIn = false))
    }

    @Test fun acConnectionNeedsAttentionOnlyWhenAnOwnerCouldFixIt() {
        val saved = ThinQCredentialState(present = true, clientId = "lg")
        val missing = ThinQCredentialState(present = false, clientId = "lg")
        val locked = ThinQCredentialState(present = true, clientId = "lg", unlockFailed = true)

        assertEquals(InstrumentStatus.NEUTRAL, acConnectionStatus(null, ownerContextReady = false, credentialState = saved))
        assertEquals(InstrumentStatus.NEUTRAL, acConnectionStatus("uid", ownerContextReady = false, credentialState = saved))
        assertEquals(InstrumentStatus.OK, acConnectionStatus("uid", ownerContextReady = true, credentialState = saved))
        assertEquals(InstrumentStatus.ATTENTION, acConnectionStatus("uid", ownerContextReady = true, credentialState = missing))
        assertEquals(InstrumentStatus.ATTENTION, acConnectionStatus("uid", ownerContextReady = true, credentialState = locked))
    }

    @Test fun historyAccessIsOkOnlyWhenAuthorized() {
        assertEquals(InstrumentStatus.OK,
            historyAccessStatus(OwnerAccessState.Authorized(owner, DeviceIdentity(deviceId = "ac"))))
        assertEquals(InstrumentStatus.NEUTRAL, historyAccessStatus(OwnerAccessState.SignedOut))
        assertEquals(InstrumentStatus.ATTENTION, historyAccessStatus(OwnerAccessState.SignInFailed(SignInFailure.NETWORK)))
        assertEquals(InstrumentStatus.ATTENTION, historyAccessStatus(OwnerAccessState.AccessPending(owner, "ACCESS_DENIED")))
        assertEquals(InstrumentStatus.ATTENTION, historyAccessStatus(OwnerAccessState.AccessCheckFailed(owner, "OFFLINE")))
    }

    @Test fun historyStorageStatusPrefersTheRecordedSyncError() {
        assertEquals(InstrumentStatus.ATTENTION, historyStorageStatus(hasCachedHistory = true, hasSyncError = true))
        assertEquals(InstrumentStatus.ATTENTION, historyStorageStatus(hasCachedHistory = false, hasSyncError = true))
        assertEquals(InstrumentStatus.OK, historyStorageStatus(hasCachedHistory = true, hasSyncError = false))
        assertEquals(InstrumentStatus.NEUTRAL, historyStorageStatus(hasCachedHistory = false, hasSyncError = false))
    }

    @Test fun historyStorageSummaryStatesCountAndSyncRecencyWhenKnown() {
        assertEquals("No history cached yet", historyStorageSummary(false, 0, null))
        assertEquals("1 cached observation", historyStorageSummary(true, 1, null))
        assertEquals("288 cached observations", historyStorageSummary(true, 288, null))
        assertEquals("288 cached observations · synced 12 minutes ago", historyStorageSummary(true, 288, 12 * 60_000L))
        assertEquals("2 cached observations · synced less than a minute ago", historyStorageSummary(true, 2, 5_000L))
    }
}
