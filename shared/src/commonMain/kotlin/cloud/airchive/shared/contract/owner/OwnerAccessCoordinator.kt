package cloud.airchive.shared.contract.owner

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates owner sign-in/session restore with the separate Firestore
 * enrollment check. A Firebase session alone never means history is authorized:
 * the configured device identity read must succeed before [OwnerAccessState.Authorized]
 * is returned.
 *
 * The device identity read is bounded by [deviceReadTimeoutMillis]: a hung or
 * zombie adapter read can no longer stall the owner UI indefinitely. The bound
 * is scoped to the device read only — never to the interactive platform sign-in
 * dialog or the local session restore.
 */
class OwnerAccessCoordinator(
    private val identity: OwnerIdentityPort,
    private val telemetry: AirchiveTelemetryPort,
    private val deviceReadTimeoutMillis: Long = DEFAULT_DEVICE_READ_TIMEOUT_MILLIS,
) {

    init {
        require(deviceReadTimeoutMillis > 0) {
            "deviceReadTimeoutMillis must be positive, got $deviceReadTimeoutMillis"
        }
    }

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

    private suspend fun verifyHistoryAccess(session: OwnerSession): OwnerAccessState {
        return try {
            // Bound the device read so a stuck adapter becomes a typed, retryable
            // state instead of a permanent UI stall. withTimeoutOrNull yields null
            // only for its own timeout; a real external cancellation still throws
            // out of this function and is never recorded as a check failure.
            val device = withTimeoutOrNull(deviceReadTimeoutMillis) {
                telemetry.deviceIdentity()
            }
            if (device == null) {
                OwnerAccessState.AccessCheckFailed(session, DEVICE_READ_TIMEOUT_CODE)
            } else {
                OwnerAccessState.Authorized(session, device)
            }
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

    companion object {
        /** Default deadline for the server-backed device-identity authorization check. */
        const val DEFAULT_DEVICE_READ_TIMEOUT_MILLIS = 30_000L

        /** Stable code returned when the bounded device read exceeds its deadline. */
        const val DEVICE_READ_TIMEOUT_CODE = "TIMEOUT"
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
