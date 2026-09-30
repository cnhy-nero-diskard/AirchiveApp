package cloud.airchive.personal.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import cloud.airchive.shared.contract.owner.SignInFailure
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [GoogleIdTokenSource] backed by the current official Firebase
 * Android Google sign-in pattern: AndroidX Credential Manager +
 * [GetGoogleIdOption] -> `CredentialManager.getCredential()` ->
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
 * Threading: Credential Manager must be driven from the main thread while
 * [GoogleIdTokenCredential.createFrom] refuses to run on it, so the dialog call
 * and the token parse are dispatched onto the appropriate dispatchers.
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

    private val credentialManager: CredentialManager = CredentialManager.create(context)

    override suspend fun requestGoogleIdToken(): GoogleIdTokenResult {
        // Credential Manager must be reached from the main thread.
        val response = try {
            withContext(Dispatchers.Main.immediate) { requestOnMainThread() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (none: NoCredentialException) {
            // No saved account was chosen: same user-visible outcome as dismissal.
            return GoogleIdTokenResult.Cancelled
        } catch (e: GetCredentialException) {
            return GoogleIdTokenResult.Failure(SignInFailure.PLATFORM, e)
        } catch (e: Exception) {
            return GoogleIdTokenResult.Failure(classifyFailure(e), e)
        }
        if (response == null) return GoogleIdTokenResult.Cancelled
        // createFrom parses the provider response and requires a background thread;
        // Firebase Auth validates the token when it is exchanged.
        return withContext(Dispatchers.Default) { parseOnBackground(response) }
    }

    private suspend fun requestOnMainThread(): GetCredentialResponse? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webOAuthClientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
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
            // GoogleIdTokenParsingException (audience/nonce mismatch) or the
            // main-thread guard: both are platform/config problems.
            return GoogleIdTokenResult.Failure(SignInFailure.PLATFORM, e)
        }
        return GoogleIdTokenResult.Success(idToken)
    }
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
