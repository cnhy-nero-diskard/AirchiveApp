package cloud.airchive.personal.data.auth

import android.util.Log
import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android [OwnerIdentityPort] backed by the injected [FirebaseAuth] and a
 * [GoogleIdTokenSource].
 *
 * The UID surfaced here is the value Firestore rules are keyed on: the sibling
 * `add-owner-mobile-telemetry-access` change enrolls exactly this UID. Sign-in
 * itself grants nothing — Firestore access stays deny-all until the operator
 * enrolls the UID, so the caller renders an access-pending state
 * (parent task scope) instead of assuming sign-in succeeded end-to-end.
 *
 * ## Error contract
 *
 * - [signIn] returns a typed [SignInResult]: user cancellation / no account ->
 *   [SignInResult.Cancelled]; broken connectivity -> `Failure(NETWORK)`;
 *   misconfigured/unavailable platform auth (bad or missing Web client id,
 *   invalid exchange, unsupported API) -> `Failure(PLATFORM)`; anything else ->
 *   `Failure(UNKNOWN)`. It never throws to the caller.
 * - [signOut] never throws.
 * - [observeSession] uses an [FirebaseAuth.AuthStateListener] over `callbackFlow`
 *   and replays the current session on collection.
 */
class FirebaseOwnerIdentity(
    private val auth: FirebaseAuth,
    private val googleIdTokenSource: GoogleIdTokenSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OwnerIdentityPort {

    /** Firebase instance paired with this auth port (Firestore must use the same app). */
    val firebaseAuth: FirebaseAuth get() = auth

    override val currentSession: OwnerSession?
        get() = auth.currentUser?.toOwnerSession()

    override fun observeSession(): Flow<OwnerSession?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.toOwnerSession())
        }
        // Replay the current state so late collectors never wait for a change.
        trySend(auth.currentUser?.toOwnerSession())
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signIn(): SignInResult = try {
        withContext(dispatcher) {
            when (val tokenResult = googleIdTokenSource.requestGoogleIdToken()) {
                is GoogleIdTokenResult.Cancelled -> SignInResult.Cancelled
                is GoogleIdTokenResult.Failure -> SignInResult.Failure(tokenResult.reason)
                is GoogleIdTokenResult.Success -> exchangeIdToken(tokenResult.idToken)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SignInResult.Failure(classifyAuthFailure(failure))
    }

    override suspend fun signOut() {
        try {
            withContext(dispatcher) { auth.signOut() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Contract: signOut never throws. A failed remote sign-out still
            // leaves the local state signed out on the next session restart.
        }
    }

    /** Exchanges a Google ID token for a Firebase session via the official provider. */
    private suspend fun exchangeIdToken(idToken: String): SignInResult {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return try {
            Log.d("AirchiveSignIn", "Exchanging Google credential with Firebase.")
            val authResult = withTimeoutOrNull(30_000L) {
                auth.signInWithCredential(credential).awaitCancellable()
            } ?: return SignInResult.Failure(SignInFailure.NETWORK)
            val user = authResult.user
            if (user == null) {
                SignInResult.Failure(SignInFailure.UNKNOWN)
            } else {
                Log.d("AirchiveSignIn", "Firebase sign-in succeeded; history access check follows.")
                SignInResult.Success(user.toOwnerSession())
            }
        } catch (e: ExecutionException) {
            SignInResult.Failure(classifyAuthFailure(e.cause ?: e))
        }
    }

    /**
     * Classifies the Firebase exchange failure so the UI can tell "check your
     * network" apart from "the Google provider is not configured for this app".
     */
    private fun classifyAuthFailure(error: Throwable): SignInFailure {
        var current = error
        while (current is ExecutionException && current.cause != null) {
            current = current.cause!!
        }
        return when (current) {
            is FirebaseAuthInvalidCredentialsException -> SignInFailure.PLATFORM
            is FirebaseAuthException -> when (current.errorCode) {
                "ERROR_NETWORK_REQUEST_FAILED", "ERROR_TOO_MANY_REQUESTS" -> SignInFailure.NETWORK
                "ERROR_INVALID_IDP_RESPONSE", "ERROR_INVALID_CREDENTIAL" -> SignInFailure.PLATFORM
                else -> SignInFailure.UNKNOWN
            }
            is IOException -> SignInFailure.NETWORK
            else -> SignInFailure.UNKNOWN
        }
    }
}

private fun FirebaseUser.toOwnerSession(): OwnerSession = OwnerSession(
    uid = uid,
    displayName = displayName,
    email = email,
    signedInAtEpochMillis = metadata?.lastSignInTimestamp
        ?.takeIf { it > 0L }
        ?: System.currentTimeMillis(),
)
