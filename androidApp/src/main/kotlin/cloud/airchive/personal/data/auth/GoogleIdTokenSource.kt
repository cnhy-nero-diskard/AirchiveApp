package cloud.airchive.personal.data.auth

import cloud.airchive.shared.contract.owner.SignInFailure

/**
 * Narrow boundary around "give me a Google ID token" for the owner sign-in
 * flow.
 *
 * Production is backed by the AndroidX Credential Manager
 * ([CredentialManagerGoogleIdTokenSource]). Local instrumentation tests inject
 * a clearly-marked emulator-only fake that never touches a real Google
 * account (see `FirebaseOwnerIdentityEmulatorTest`).
 *
 * Contract: the implementation suspends until the interactive dialog is
 * finished and returns a typed [GoogleIdTokenResult] instead of throwing, so
 * [FirebaseOwnerIdentity] can distinguish user cancellation from network and
 * platform/config failures.
 */
fun interface GoogleIdTokenSource {

    /**
     * Runs the interactive Google ID token retrieval and suspends until the
     * owner finishes the dialog.
     */
    suspend fun requestGoogleIdToken(): GoogleIdTokenResult
}

/** Typed outcome of [GoogleIdTokenSource.requestGoogleIdToken]. */
sealed interface GoogleIdTokenResult {

    /** A usable Google ID token, ready for `GoogleAuthProvider.getCredential`. */
    data class Success(val idToken: String) : GoogleIdTokenResult

    /** The owner closed the platform dialog without choosing an account. */
    data object Cancelled : GoogleIdTokenResult

    /** The platform or network rejected the request; [reason] is already typed. */
    data class Failure(val reason: SignInFailure, val cause: Throwable? = null) : GoogleIdTokenResult
}