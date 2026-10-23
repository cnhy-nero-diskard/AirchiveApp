package cloud.airchive.personal.data.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a manually completed local Firebase Task; no emulator or project access is needed. */
@RunWith(AndroidJUnit4::class)
class FirebaseOwnerIdentityExchangeTest {
    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("1:123456789012:android:authreviewtest")
                .setProjectId("demo-airchive-auth-review")
                .setApiKey("fake-local-auth-review-api-key")
                .build(),
            TEST_APP_NAME,
        )
        auth = FirebaseAuth.getInstance(app)
    }

    @After
    fun tearDown() {
        app.delete()
    }

    @Test(timeout = 5_000L)
    fun timedOutExchangeCompletedAfterAccountChangeCleanupStaysSignedOut() = runBlocking {
        val backend = ManualOwnerAuthBackend(auth)
        backend.restoreSession(priorSession)
        val identity = newIdentity(backend, timeoutMillis = 50L)
        val emissions = Channel<OwnerSession?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Unconfined) {
            identity.observeSession().collect { emissions.send(it) }
        }
        assertEquals(priorSession, withTimeout(1_000L) { emissions.receive() })

        val signIn = async { identity.signIn() }
        withTimeout(1_000L) { backend.exchangeStarted.await() }
        val timedOutTask = withTimeout(1_000L) { backend.nextTask() }
        assertEquals(
            SignInResult.Failure(SignInFailure.NETWORK),
            withTimeout(1_000L) { signIn.await() },
        )

        // A never-completed stale task must not leave a retry suspended behind
        // the auth-operation gate.
        assertEquals(
            SignInResult.Failure(SignInFailure.NETWORK),
            withTimeout(1_000L) { identity.signIn() },
        )

        // ProductionAccountController performs this sign-out cleanup after the
        // failed exchange and before it reports the account-change result.
        identity.signOut()
        assertNull(identity.currentSession)

        backend.completeLate(timedOutTask, lateSession)

        assertNull("late Firebase completion must not restore the owner", identity.currentSession)
        assertNoAuthenticatedEmission(emissions)
        collector.cancel()
    }

    @Test(timeout = 5_000L)
    fun explicitSignOutWhileExchangeIsOutstandingFencesItsLateCompletion() = runBlocking {
        val backend = ManualOwnerAuthBackend(auth)
        val identity = newIdentity(backend, timeoutMillis = 2_000L)
        val emissions = Channel<OwnerSession?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Unconfined) {
            identity.observeSession().collect { emissions.send(it) }
        }
        assertNull(withTimeout(1_000L) { emissions.receive() })

        val signIn = async { identity.signIn() }
        withTimeout(1_000L) { backend.exchangeStarted.await() }
        val outstandingTask = withTimeout(1_000L) { backend.nextTask() }

        withTimeout(1_000L) { identity.signOut() }
        assertNull(identity.currentSession)

        backend.completeLate(outstandingTask, lateSession)

        assertEquals(SignInResult.Cancelled, withTimeout(1_000L) { signIn.await() })
        assertNull("sign-out must remain effective after task completion", identity.currentSession)
        assertNoAuthenticatedEmission(emissions)
        collector.cancel()
    }

    @Test(timeout = 5_000L)
    fun aRetryCanStartAfterAnAbandonedTaskHasBeenCleanedUp() = runBlocking {
        val backend = ManualOwnerAuthBackend(auth)
        val identity = newIdentity(backend, timeoutMillis = 50L)

        val firstSignIn = async { identity.signIn() }
        withTimeout(1_000L) { backend.exchangeStarted.await() }
        val staleTask = withTimeout(1_000L) { backend.nextTask() }
        assertEquals(SignInResult.Failure(SignInFailure.NETWORK), firstSignIn.await())

        // It is fail-fast while the abandoned operation is still unresolved.
        assertEquals(SignInResult.Failure(SignInFailure.NETWORK), identity.signIn())
        backend.completeLate(staleTask, lateSession)

        val retry = async { identity.signIn() }
        val retryTask = withTimeout(1_000L) { backend.nextTask() }
        backend.completeLate(retryTask, retriedSession)

        assertEquals(SignInResult.Success(retriedSession), withTimeout(1_000L) { retry.await() })
        assertEquals(retriedSession, identity.currentSession)
    }

    private fun newIdentity(
        backend: ManualOwnerAuthBackend,
        timeoutMillis: Long,
    ): FirebaseOwnerIdentity = FirebaseOwnerIdentity(
        auth = auth,
        googleIdTokenSource = GoogleIdTokenSource {
            GoogleIdTokenResult.Success("local-test-google-id-token")
        },
        ownerAuth = backend,
        dispatcher = Dispatchers.IO,
        authExchangeTimeoutMillis = timeoutMillis,
    )

    private fun assertNoAuthenticatedEmission(emissions: Channel<OwnerSession?>) {
        var observedSession = false
        while (true) {
            val next = emissions.tryReceive()
            if (next.isFailure) break
            if (next.getOrNull() != null) observedSession = true
        }
        assertFalse("an abandoned exchange must never emit an authenticated session", observedSession)
    }

    private class ManualOwnerAuthBackend(
        private val firebaseAuth: FirebaseAuth,
    ) : FirebaseOwnerAuthBackend {
        private val pendingTasks = Channel<TaskCompletionSource<OwnerSession>>(Channel.UNLIMITED)
        private val listeners = mutableSetOf<FirebaseAuth.AuthStateListener>()

        val exchangeStarted = CompletableDeferred<Unit>()

        @Volatile
        override var currentSession: OwnerSession? = null
            private set

        override fun signInWithCredential(credential: AuthCredential): Task<OwnerSession> {
            val task = TaskCompletionSource<OwnerSession>()
            pendingTasks.trySend(task)
            exchangeStarted.complete(Unit)
            return task.task
        }

        suspend fun nextTask(): TaskCompletionSource<OwnerSession> = pendingTasks.receive()

        fun restoreSession(session: OwnerSession) {
            currentSession = session
            notifyAuthStateChanged()
        }

        override fun signOut() {
            currentSession = null
            notifyAuthStateChanged()
        }

        override fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
            synchronized(listeners) { listeners += listener }
        }

        override fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
            synchronized(listeners) { listeners -= listener }
        }

        fun completeLate(task: TaskCompletionSource<OwnerSession>, session: OwnerSession) {
            // Firebase updates currentUser and notifies auth listeners before
            // completing the Task that the caller's coroutine is awaiting.
            currentSession = session
            notifyAuthStateChanged()
            task.setResult(session)
        }

        private fun notifyAuthStateChanged() {
            val snapshot = synchronized(listeners) { listeners.toList() }
            snapshot.forEach { it.onAuthStateChanged(firebaseAuth) }
        }
    }

    companion object {
        private const val TEST_APP_NAME = "demo-airchive-auth-exchange-review"
        private val lateSession = OwnerSession(
            uid = "late-owner-session",
            displayName = "Late Test Owner",
            email = "late-owner@example.invalid",
            signedInAtEpochMillis = 1L,
        )
        private val priorSession = OwnerSession(
            uid = "prior-owner-session",
            displayName = "Prior Test Owner",
            email = "prior-owner@example.invalid",
            signedInAtEpochMillis = 2L,
        )
        private val retriedSession = OwnerSession(
            uid = "retried-owner-session",
            displayName = "Retried Test Owner",
            email = "retried-owner@example.invalid",
            signedInAtEpochMillis = 3L,
        )
    }
}
