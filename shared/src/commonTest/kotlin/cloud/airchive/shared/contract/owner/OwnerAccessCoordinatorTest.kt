package cloud.airchive.shared.contract.owner

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Sign-in and Firestore owner enrollment remain separate, typed outcomes. */
class OwnerAccessCoordinatorTest {

    /** Injected device-read deadline for fast, deterministic timeout tests (<=50ms). */
    private val fastReadTimeoutMillis = 50L

    /** Fail-fast guard for deferred handshakes, far above [fastReadTimeoutMillis]. */
    private val testGuardMillis = 5_000L

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

    @Test
    fun nonPositiveDeviceReadTimeoutIsRejected() {
        val identity = FakeOwnerIdentity()
        val telemetry = FakeAirchiveTelemetry()
        assertFailsWith<IllegalArgumentException> {
            OwnerAccessCoordinator(identity, telemetry, deviceReadTimeoutMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            OwnerAccessCoordinator(identity, telemetry, deviceReadTimeoutMillis = -1L)
        }
    }

    @Test
    fun hungDeviceReadDuringSignInTimesOutToCheckFailedKeepingSignedInSession() = runBlocking {
        val identity = FakeOwnerIdentity()
        val coordinator = OwnerAccessCoordinator(
            identity,
            HungIdentityReadTelemetry(),
            deviceReadTimeoutMillis = fastReadTimeoutMillis,
        )

        // A bounded read must return a typed state, never throw, sign out, or
        // report sign-in failure: the state carries only the session + timeout code.
        val failed = assertIs<OwnerAccessState.AccessCheckFailed>(
            withTimeout(testGuardMillis) { coordinator.signIn() },
        )
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, failed.session.uid)
        assertEquals(OwnerAccessCoordinator.DEVICE_READ_TIMEOUT_CODE, failed.safeErrorCode)
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION, identity.currentSession)
    }

    @Test
    fun hungDeviceReadDuringRestoreTimesOutKeepingSignedInSession() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val coordinator = OwnerAccessCoordinator(
            identity,
            HungIdentityReadTelemetry(),
            deviceReadTimeoutMillis = fastReadTimeoutMillis,
        )

        val failed = assertIs<OwnerAccessState.AccessCheckFailed>(
            withTimeout(testGuardMillis) { coordinator.restore() },
        )
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, failed.session.uid)
        assertEquals(OwnerAccessCoordinator.DEVICE_READ_TIMEOUT_CODE, failed.safeErrorCode)
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION, identity.currentSession)
    }

    @Test
    fun hungDeviceReadDuringRetryTimesOutKeepingSignedInSession() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val coordinator = OwnerAccessCoordinator(
            identity,
            HungIdentityReadTelemetry(),
            deviceReadTimeoutMillis = fastReadTimeoutMillis,
        )

        val failed = assertIs<OwnerAccessState.AccessCheckFailed>(
            withTimeout(testGuardMillis) { coordinator.retryHistoryAccess() },
        )
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, failed.session.uid)
        assertEquals(OwnerAccessCoordinator.DEVICE_READ_TIMEOUT_CODE, failed.safeErrorCode)
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION, identity.currentSession)
    }

    @Test
    fun outerCancellationWhileDeviceReadHangsPropagatesInsteadOfTimingOut() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val hung = HungIdentityReadTelemetry()
        val coordinator = OwnerAccessCoordinator(identity, hung, deviceReadTimeoutMillis = testGuardMillis)
        val outcome = CompletableDeferred<Result<OwnerAccessState>>()

        val job = launch {
            outcome.complete(runCatching { coordinator.restore() })
        }
        // Wait until the read has truly entered the hung suspend, then cancel the
        // outer call: real cancellation must escape the timeout wrapper untouched
        // (and must never be converted into AccessCheckFailed or a timeout).
        withTimeout(testGuardMillis) { hung.enteredRead.await() }
        job.cancel()
        withTimeout(testGuardMillis) { job.join() }

        val result = withTimeout(testGuardMillis) { outcome.await() }
        assertTrue(result.isFailure, "expected a thrown CancellationException, got '$result'")
        assertIs<CancellationException>(result.exceptionOrNull())
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION, identity.currentSession)
    }

    @Test
    fun successfulReadStillAuthorizesUnderFastTimeout() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val coordinator = OwnerAccessCoordinator(
            identity,
            FakeAirchiveTelemetry(),
            deviceReadTimeoutMillis = fastReadTimeoutMillis,
        )

        val authorized = assertIs<OwnerAccessState.Authorized>(
            withTimeout(testGuardMillis) { coordinator.retryHistoryAccess() },
        )
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, authorized.session.uid)
        assertEquals(FakeAirchiveTelemetry.DEMO_DEVICE_ID, authorized.device.deviceId)
    }

    @Test
    fun deniedReadStillReturnsAccessPendingUnderFastTimeout() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val denied = AccessControlledTelemetry(FakeAirchiveTelemetry(), TelemetryReadFailure.AccessDenied())
        val coordinator = OwnerAccessCoordinator(identity, denied, deviceReadTimeoutMillis = fastReadTimeoutMillis)

        val pending = assertIs<OwnerAccessState.AccessPending>(
            withTimeout(testGuardMillis) { coordinator.restore() },
        )
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, pending.session.uid)
        assertEquals("ACCESS_DENIED", pending.safeErrorCode)
    }

    private class AccessControlledTelemetry(
        private val delegate: AirchiveTelemetryPort,
        var failure: TelemetryReadFailure?,
    ) : AirchiveTelemetryPort by delegate {
        override suspend fun deviceIdentity() = failure?.let { throw it } ?: delegate.deviceIdentity()
    }

    /** Device read that suspends forever but is cancellation-cooperative. */
    private class HungIdentityReadTelemetry(
        private val delegate: AirchiveTelemetryPort = FakeAirchiveTelemetry(),
    ) : AirchiveTelemetryPort by delegate {
        /** Completes once [deviceIdentity] has entered its hang, for deterministic cancellation timing. */
        val enteredRead = CompletableDeferred<Unit>()

        override suspend fun deviceIdentity(): DeviceIdentity {
            enteredRead.complete(Unit)
            awaitCancellation() // Never returns; ended only by the read timeout or real cancellation.
        }
    }
}
