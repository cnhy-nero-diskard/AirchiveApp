package cloud.airchive.shared.contract.owner

import kotlinx.coroutines.flow.Flow

/**
 * Identity boundary for the single owner's Google sign-in session.
 *
 * The Android adapter backs this with Firebase Auth. The iOS target is expected
 * to provide an equivalent native Firebase Auth adapter before production use.
 * The in-memory [FakeOwnerIdentity] keeps a deterministic, fixed session so the
 * complete test flow runs without a live account or network. Clearing
 * owner-bound local data after sign-out is orchestrated by the caller (see
 * [LocalHistoryPort] and the credential store), not by this port.
 */
interface OwnerIdentityPort {

    /** Currently signed-in owner session, or `null` when signed out. */
    val currentSession: OwnerSession?

    /** Emits the current session and every later sign-in/sign-out change. */
    fun observeSession(): Flow<OwnerSession?>

    /**
     * Starts the interactive platform sign-in flow and suspends until the owner
     * finishes the dialog. Returns a typed [SignInResult] instead of throwing,
     * so callers can distinguish success, cancellation, and failure.
     */
    suspend fun signIn(): SignInResult

    /** Ends the current session. Never throws. */
    suspend fun signOut()
}

/** The signed-in owner. [uid] is the value Firestore rules are keyed on. */
data class OwnerSession(
    val uid: String,
    val displayName: String? = null,
    val email: String? = null,
    val signedInAtEpochMillis: Long,
)

sealed interface SignInResult {
    data class Success(val session: OwnerSession) : SignInResult
    data object Cancelled : SignInResult
    data class Failure(val reason: SignInFailure) : SignInResult
}

enum class SignInFailure {
    /** Interactive dialog was closed without choosing an account. */
    USER_CANCELLED,

    /** No connectivity or the platform auth backend could not be reached. */
    NETWORK,

    /** The platform auth provider misconfigured or unavailable. */
    PLATFORM,

    UNKNOWN,
}
