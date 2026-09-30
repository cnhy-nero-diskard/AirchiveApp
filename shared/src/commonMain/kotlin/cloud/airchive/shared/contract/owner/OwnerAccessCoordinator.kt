package cloud.airchive.shared.contract.owner

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coordinates owner sign-in/session restore with the separate Firestore
 * enrollment check. A Firebase session alone never means history is authorized:
 * the configured device identity read must succeed before [OwnerAccessState.Authorized]
 * is returned.
 */
class OwnerAccessCoordinator(
    private val identity: OwnerIdentityPort,
    private val telemetry: AirchiveTelemetryPort,
) {

    /** Restores the current Firebase session and checks its read access if present. */
    suspend fun restore(): OwnerAccessState {
        val session = identity.currentSession ?: return OwnerAccessState.SignedOut
        return verifyHistoryAccess(session)
    }

    /** Runs the platform Google sign-in and returns access-pending rather than throwing on denied reads. */
    suspend fun signIn(): OwnerAccessState = when (val result = identity.signIn()) {
        is SignInResult.Success -> verifyHistoryAccess(result.session)
        SignInResult.Cancelled -> OwnerAccessState.SignedOut
        is SignInResult.Failure -> OwnerAccessState.SignInFailed(result.reason)
    }

    /** Rechecks the device read after an operator may have enrolled the UID. */
    suspend fun retryHistoryAccess(): OwnerAccessState {
        val session = identity.currentSession ?: return OwnerAccessState.SignedOut
        return verifyHistoryAccess(session)
    }

    private suspend fun verifyHistoryAccess(session: OwnerSession): OwnerAccessState = try {
        OwnerAccessState.Authorized(session, telemetry.deviceIdentity())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (denied: TelemetryReadFailure.AccessDenied) {
        OwnerAccessState.AccessPending(session, denied.code)
    } catch (failure: TelemetryReadFailure) {
        OwnerAccessState.AccessCheckFailed(session, failure.code)
    } catch (_: Exception) {
        // A missing identity doc, network outage, or adapter failure isn't proof
        // that sign-in failed. Keep the session and offer a retry action.
        OwnerAccessState.AccessCheckFailed(session, "REMOTE_FAILURE")
    }
}

/** Renderable state of owner identity + the separate device-read authorization check. */
sealed interface OwnerAccessState {
    data object SignedOut : OwnerAccessState
    data class SignInFailed(val reason: SignInFailure) : OwnerAccessState
    data class AccessPending(val session: OwnerSession, val safeErrorCode: String) : OwnerAccessState
    data class AccessCheckFailed(val session: OwnerSession, val safeErrorCode: String) : OwnerAccessState
    data class Authorized(val session: OwnerSession, val device: DeviceIdentity) : OwnerAccessState
}
