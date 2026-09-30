package cloud.airchive.shared.contract.owner

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Sign-in and Firestore owner enrollment remain separate, typed outcomes. */
class OwnerAccessCoordinatorTest {

    @Test
    fun freshSignInWithDeniedFirestoreReadReturnsAccessPendingWithUid() = runBlocking {
        val identity = FakeOwnerIdentity()
        val deniedTelemetry = AccessControlledTelemetry(
            delegate = FakeAirchiveTelemetry(),
            failure = TelemetryReadFailure.AccessDenied(),
        )
        val coordinator = OwnerAccessCoordinator(identity, deniedTelemetry)

        val state = coordinator.signIn()

        val pending = assertIs<OwnerAccessState.AccessPending>(state)
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, pending.session.uid)
        assertEquals("ACCESS_DENIED", pending.safeErrorCode)
        assertEquals(pending.session, identity.currentSession)
    }

    @Test
    fun restoredSessionStaysPendingUntilOperatorEnablesTheDeviceRead() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val telemetry = AccessControlledTelemetry(FakeAirchiveTelemetry(), TelemetryReadFailure.AccessDenied())
        val coordinator = OwnerAccessCoordinator(identity, telemetry)

        assertIs<OwnerAccessState.AccessPending>(coordinator.restore())

        telemetry.failure = null
        val authorized = assertIs<OwnerAccessState.Authorized>(coordinator.retryHistoryAccess())
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, authorized.session.uid)
        assertEquals(FakeAirchiveTelemetry.DEMO_DEVICE_ID, authorized.device.deviceId)
    }

    private class AccessControlledTelemetry(
        private val delegate: AirchiveTelemetryPort,
        var failure: TelemetryReadFailure?,
    ) : AirchiveTelemetryPort by delegate {
        override suspend fun deviceIdentity() = failure?.let { throw it } ?: delegate.deviceIdentity()
    }
}
