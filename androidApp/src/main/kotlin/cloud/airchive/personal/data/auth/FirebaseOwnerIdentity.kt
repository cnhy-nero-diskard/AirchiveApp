package cloud.airchive.personal.data.auth

import android.util.Log
import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val directTaskExecutor = Executor { it.run() }

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
class FirebaseOwnerIdentity private constructor(
    private val auth: FirebaseAuth,
    private val ownerAuth: FirebaseOwnerAuthBackend,
    private val googleIdTokenSource: GoogleIdTokenSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authExchangeTimeoutMillis: Long = DEFAULT_AUTH_EXCHANGE_TIMEOUT_MILLIS,
) : OwnerIdentityPort {

    constructor(
        auth: FirebaseAuth,
        googleIdTokenSource: GoogleIdTokenSource,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        auth,
        FirebaseOwnerAuthBackend.ForFirebase(auth),
        googleIdTokenSource,
        dispatcher,
    )

    internal constructor(
        auth: FirebaseAuth,
        googleIdTokenSource: GoogleIdTokenSource,
        ownerAuth: FirebaseOwnerAuthBackend,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        authExchangeTimeoutMillis: Long = DEFAULT_AUTH_EXCHANGE_TIMEOUT_MILLIS,
    ) : this(auth, ownerAuth, googleIdTokenSource, dispatcher, authExchangeTimeoutMillis)

    init {
        require(authExchangeTimeoutMillis > 0L) { "Auth exchange timeout must be positive." }
    }

    // Activity recreation creates a new adapter around the same FirebaseAuth.
    // Exchange state must outlive this instance so a stale Firebase Task cannot
    // run concurrently with, or later evict, an exchange from the new adapter.
    private val exchangeCoordinator = OwnerAuthExchangeRegistry.coordinatorFor(auth)
    private val exchangeMutex: Mutex get() = exchangeCoordinator.exchangeMutex
    private val exchangeStateLock: Any get() = exchangeCoordinator.exchangeStateLock
    private var activeExchange: AuthExchange?
        get() = exchangeCoordinator.activeExchange
        set(value) {
            exchangeCoordinator.activeExchange = value
        }

    /** Firebase instance paired with this auth port (Firestore must use the same app). */
    val firebaseAuth: FirebaseAuth get() = auth

    override val currentSession: OwnerSession?
        get() = visibleCurrentSession()

    override fun observeSession(): Flow<OwnerSession?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(visibleCurrentSession()) }
        // Replay the current state so late collectors never wait for a change.
        trySend(visibleCurrentSession())
        ownerAuth.addAuthStateListener(listener)
        awaitClose { ownerAuth.removeAuthStateListener(listener) }
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
        val abandonedExchange = synchronized(exchangeStateLock) {
            activeExchange?.also {
                it.abandoned = true
                it.signOutInProgress = true
            }
        }
        try {
            withContext(dispatcher) { ownerAuth.signOut() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Contract: signOut never throws. A failed remote sign-out still
            // leaves the local state signed out on the next session restart.
        } finally {
            abandonedExchange?.let {
                synchronized(exchangeStateLock) { it.signOutInProgress = false }
                finishAbandonedExchangeIfComplete(it)
            }
        }
    }

    /** Exchanges a Google ID token for a Firebase session via the official provider. */
    private suspend fun exchangeIdToken(idToken: String): SignInResult {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val attempt = startExchange(credential)
            ?: return SignInResult.Failure(SignInFailure.NETWORK)
        return try {
            Log.d("AirchiveSignIn", "Exchanging Google credential with Firebase.")
            val session = try {
                withTimeoutOrNull(authExchangeTimeoutMillis) {
                    attempt.task.awaitCancellable()
                }
            } catch (cancelled: CancellationException) {
                abandonAndSignOut(attempt)
                throw cancelled
            } catch (failure: Exception) {
                if (!acceptCompletedExchange(attempt)) return SignInResult.Cancelled
                return SignInResult.Failure(classifyAuthFailure(failure))
            }

            if (session == null) {
                abandonAndSignOut(attempt)
                return SignInResult.Failure(SignInFailure.NETWORK)
            }

            if (!acceptCompletedExchange(attempt)) return SignInResult.Cancelled
            Log.d("AirchiveSignIn", "Firebase sign-in succeeded; history access check follows.")
            SignInResult.Success(session)
        } catch (failure: Throwable) {
            // Cover failures before a task is accepted without releasing an
            // exchange that has already been invalidated by sign-out/timeout.
            releaseExchangeIfStillActive(attempt)
            throw failure
        }
    }

    /** Starts an exchange while preventing overlap with an abandoned Firebase task. */
    private fun startExchange(credential: AuthCredential): AuthExchange? {
        val attempt = AuthExchange()
        // An abandoned Firebase Task may never finish. Do not let a retry hang
        // behind it; fail closed until its completion callback releases the gate.
        if (!exchangeMutex.tryLock(attempt)) return null
        try {
            synchronized(exchangeStateLock) {
                activeExchange = attempt
                attempt.task = ownerAuth.signInWithCredential(credential)
            }
            attempt.task.addOnCompleteListener(directTaskExecutor) {
                finishAbandonedExchangeIfComplete(attempt)
            }
            return attempt
        } catch (failure: Throwable) {
            synchronized(exchangeStateLock) {
                if (activeExchange === attempt) activeExchange = null
            }
            exchangeMutex.unlock(attempt)
            throw failure
        }
    }

    /**
     * Marks the attempt stale before signing out. Firebase Tasks cannot be
     * cancelled, so their completion callback signs out again before allowing
     * another exchange to start.
     */
    private suspend fun abandonAndSignOut(attempt: AuthExchange) {
        synchronized(exchangeStateLock) {
            attempt.abandoned = true
            attempt.signOutInProgress = true
        }
        try {
            withContext(NonCancellable + dispatcher) { ownerAuth.signOut() }
        } catch (_: Exception) {
            // The stale attempt remains hidden; its completion callback retries
            // the local sign-out before releasing the exchange gate.
        } finally {
            synchronized(exchangeStateLock) { attempt.signOutInProgress = false }
            finishAbandonedExchangeIfComplete(attempt)
        }
    }

    /** Called only after the waiting coroutine has received a non-timeout result. */
    private fun acceptCompletedExchange(attempt: AuthExchange): Boolean {
        val accepted = synchronized(exchangeStateLock) {
            if (activeExchange !== attempt || attempt.abandoned || attempt.finalized) {
                false
            } else {
                attempt.finalized = true
                activeExchange = null
                true
            }
        }
        if (accepted) exchangeMutex.unlock(attempt)
        return accepted
    }

    /** Cleans up an abandoned exchange once its non-cancellable Firebase Task ends. */
    private fun finishAbandonedExchangeIfComplete(attempt: AuthExchange) {
        val shouldCleanUp = synchronized(exchangeStateLock) {
            if (!attempt.abandoned || attempt.signOutInProgress || attempt.finalized ||
                attempt.cleanupStarted || !attempt.task.isComplete
            ) {
                false
            } else {
                attempt.cleanupStarted = true
                true
            }
        }
        if (!shouldCleanUp) return

        try {
            ownerAuth.signOut()
        } catch (_: Exception) {
            // Firebase signOut is local. Keep this callback non-throwing even if
            // the SDK reports an unexpected failure.
        } finally {
            synchronized(exchangeStateLock) {
                attempt.finalized = true
                if (activeExchange === attempt) activeExchange = null
            }
            exchangeMutex.unlock(attempt)
        }
    }

    private fun releaseExchangeIfStillActive(attempt: AuthExchange) {
        val release = synchronized(exchangeStateLock) {
            if (activeExchange !== attempt || attempt.finalized || attempt.abandoned) {
                false
            } else {
                attempt.finalized = true
                activeExchange = null
                true
            }
        }
        if (release) exchangeMutex.unlock(attempt)
    }

    private fun visibleCurrentSession(): OwnerSession? = synchronized(exchangeStateLock) {
        if (activeExchange?.abandoned == true) null else ownerAuth.currentSession
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

    private companion object {
        const val DEFAULT_AUTH_EXCHANGE_TIMEOUT_MILLIS: Long = 30_000L
    }
}

/**
 * Outstanding-exchange state shared by every [FirebaseOwnerIdentity] wrapping
 * the same [FirebaseAuth]. Recreated adapters therefore observe the same gate,
 * abandoned attempt, and late-completion cleanup as the adapter that started
 * the exchange.
 */
private class OwnerAuthExchangeCoordinator {
    val exchangeMutex = Mutex()
    val exchangeStateLock = Any()
    var activeExchange: AuthExchange? = null
}

private class AuthExchange {
    lateinit var task: Task<OwnerSession>
    var abandoned: Boolean = false
    var signOutInProgress: Boolean = false
    var cleanupStarted: Boolean = false
    var finalized: Boolean = false
}

/**
 * Binds coordinators to their [FirebaseAuth]. Weak keys let an auth instance
 * (and its coordinator) disappear with the owning Firebase app instead of
 * leaking process-wide state; there is deliberately no unkeyed global lock.
 */
private object OwnerAuthExchangeRegistry {
    private val coordinators = WeakHashMap<FirebaseAuth, OwnerAuthExchangeCoordinator>()

    fun coordinatorFor(auth: FirebaseAuth): OwnerAuthExchangeCoordinator =
        synchronized(coordinators) {
            coordinators.getOrPut(auth) { OwnerAuthExchangeCoordinator() }
        }
}

/** Narrow auth seam for deterministic local-Task tests of late completion. */
internal interface FirebaseOwnerAuthBackend {
    val currentSession: OwnerSession?

    fun signInWithCredential(credential: AuthCredential): Task<OwnerSession>

    fun signOut()

    fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener)

    fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener)

    class ForFirebase(private val auth: FirebaseAuth) : FirebaseOwnerAuthBackend {
        override val currentSession: OwnerSession?
            get() = auth.currentUser?.toOwnerSession()

        override fun signInWithCredential(credential: AuthCredential): Task<OwnerSession> {
            val completion = TaskCompletionSource<OwnerSession>()
            auth.signInWithCredential(credential).addOnCompleteListener(directTaskExecutor) { task ->
                if (task.isCanceled) {
                    // Keep provider-task cancellation distinct from cancellation
                    // of the caller's coroutine; the latter must still abort
                    // the suspended caller, while this maps to a typed failure.
                    completion.trySetException(IllegalStateException("Firebase sign-in task was cancelled."))
                } else {
                    val failure = task.exception
                    if (failure != null) {
                        completion.trySetException(failure)
                    } else {
                        try {
                            val user = task.result?.user
                            if (user == null) {
                                completion.trySetException(IllegalStateException("Firebase sign-in returned no user."))
                            } else {
                                completion.trySetResult(user.toOwnerSession())
                            }
                        } catch (failure: Exception) {
                            completion.trySetException(failure)
                        }
                    }
                }
            }
            return completion.task
        }

        override fun signOut() = auth.signOut()

        override fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
            auth.addAuthStateListener(listener)
        }

        override fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
            auth.removeAuthStateListener(listener)
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
