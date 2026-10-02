package cloud.airchive.personal.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import cloud.airchive.shared.contract.owner.SignInFailure
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production [GoogleIdTokenSource] backed by the current official Firebase
 * Android Google sign-in pattern: AndroidX Credential Manager +
 * [GetSignInWithGoogleOption] -> `CredentialManager.getCredential()` ->
 * [GoogleIdTokenCredential.createFrom] -> ID token, which the caller exchanges
 * with `GoogleAuthProvider.getCredential(idToken, null)` +
 * `FirebaseAuth.signInWithCredential`.
 *
 * Requires a non-empty Web OAuth client ID (the OAuth 2.0 client id of the
 * Firebase **web** app configured in the console): it is the audience Google
 * mints the ID token for. Firebase Auth verifies that token when it is exchanged,
 * so a blank audience value must fail fast here rather than surface later as a
 * confusing sign-in error.
 *
 * Uses Google's explicit-button flow, not the passive bottom-sheet prompt.
 * Credential Manager is driven from the main thread, and token parsing is
 * dispatched to a background thread. An unreturned chooser is cancelled after
 * two minutes so it cannot leave the app's sign-in action busy indefinitely.
 *
 * Outcome mapping: user dismissal / no saved account -> [GoogleIdTokenResult.Cancelled];
 * network -> `Failure(NETWORK)`; everything else (unsupported API level,
 * security error, audience/nonce mismatch) -> `Failure(PLATFORM)`.
 */
class CredentialManagerGoogleIdTokenSource(
    private val context: Context,
    webOAuthClientId: String,
) : GoogleIdTokenSource {

    private val webOAuthClientId: String = webOAuthClientId.trim().also {
        require(it.isNotEmpty()) {
            "A non-empty Google Web OAuth client ID is required for Credential Manager sign-in " +
                "(config key firebase.googleWebClientId)."
        }
    }

    private val credentialManager: CredentialManager = CredentialManager.create(context.applicationContext)

    override suspend fun requestGoogleIdToken(): GoogleIdTokenResult {
        // Credential Manager must be reached from the main thread.
        val response = try {
            Log.d("AirchiveSignIn", "Opening explicit Google account chooser.")
            withTimeoutOrNull(120_000L) {
                withContext(Dispatchers.Main.immediate) { requestOnMainThread() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (none: NoCredentialException) {
            // No saved account was chosen: same user-visible outcome as dismissal.
            return GoogleIdTokenResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w("AirchiveSignIn", "Google chooser failed: ${e.javaClass.simpleName}")
            return GoogleIdTokenResult.Failure(SignInFailure.PLATFORM, e)
        } catch (e: Exception) {
            return GoogleIdTokenResult.Failure(classifyFailure(e), e)
        }
        if (response == null) {
            Log.d("AirchiveSignIn", "Google chooser cancelled or timed out.")
            return GoogleIdTokenResult.Cancelled
        }
        Log.d("AirchiveSignIn", "Google chooser returned a credential.")
        // Parse off the UI thread. Firebase Auth validates the token on exchange.
        return withContext(Dispatchers.Default) { parseOnBackground(response) }
    }

    private suspend fun requestOnMainThread(): GetCredentialResponse? {
        val request = createGoogleSignInRequest(webOAuthClientId)
        return try {
            credentialManager.getCredential(context, request)
        } catch (cancelled: GetCredentialCancellationException) {
            // User pressed back / dismissed the account chooser.
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: GetCredentialException) {
            // Allow the caller to classify (no-credential there becomes Cancelled).
            throw e
        }
    }

    private fun parseOnBackground(response: GetCredentialResponse): GoogleIdTokenResult {
        val idToken = try {
            GoogleIdTokenCredential.createFrom(response.credential.data).idToken
        } catch (e: Exception) {
            // Firebase will validate the parsed token; malformed provider data
            // is a platform failure, not an authenticated owner session.
            return GoogleIdTokenResult.Failure(SignInFailure.PLATFORM, e)
        }
        return GoogleIdTokenResult.Success(idToken)
    }
}

/** An explicit button must use SIWG, not the suppressible passive Google-ID prompt. */
internal fun createGoogleSignInRequest(webOAuthClientId: String): GetCredentialRequest {
    require(webOAuthClientId.isNotBlank()) { "Google Web OAuth client ID must not be blank." }
    return GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(webOAuthClientId).build())
        .build()
}

/** Classifies a non-cancellation Credential Manager failure as network or platform. */
internal fun classifyFailure(error: Throwable): SignInFailure {
    var current = error
    while (current is java.util.concurrent.ExecutionException && current.cause != null) {
        current = current.cause!!
    }
    return when (current) {
        is IOException -> SignInFailure.NETWORK
        is GetCredentialException,
        is GoogleIdTokenParsingException,
        -> SignInFailure.PLATFORM
        else -> SignInFailure.UNKNOWN
    }
}
