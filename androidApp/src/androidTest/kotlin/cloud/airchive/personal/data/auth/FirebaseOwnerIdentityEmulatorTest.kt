package cloud.airchive.personal.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * LOCAL-EMULATOR-ONLY instrumentation test for [FirebaseOwnerIdentity] and
 * [FirebaseOwnerIdentityFactory] (OpenSpec task 3.1).
 *
 * ## Contract under test
 *
 *  - The adapter returns a typed [SignInResult.Success] carrying the Firebase
 *    UID (the value Firestore rules are keyed on), display name, and email for
 *    the signed-in owner.
 *  - `currentSession` restores the same UID from [FirebaseAuth.currentUser]
 *    without re-prompting, and `observeSession()` emits it and then clears it
 *    after [FirebaseOwnerIdentity.signOut].
 *  - User cancellation maps to [SignInResult.Cancelled]; unconfigured public
 *    Firebase client options map to [OwnerAuthConfiguration.MissingOptions]
 *    (actionable, never a crash).
 *
 * ## Emulator-only harness
 *
 *  - A NAMED `demo-*` [FirebaseApp] is initialized with non-secret FAKE
 *    emulator options; the apiKey/applicationId are obvious dummies and the
 *    project id is `demo-airchive-telemetry-poller`. No google-services.json, real API
 *    key, service account, or PAT is used, and the production project is never
 *    addressed.
 *  - Auth emulator is reached from the local AVD via `10.0.2.2` port 9099
 *    (see `firebase.test.json`). This test only works while that local emulator
 *    is running.
 *  - "Sign-in" uses `EmulatorFakeGoogleIdTokenSource`, a TEST-ONLY
 *    [GoogleIdTokenSource] returning an `alg: none` fake Google ID token whose
 *    claims (sub/email/name) the Auth emulator decodes into a mock user; no
 *    real Google account is ever involved.
 *
 * Requires: `firebase emulators:exec --config firebase.test.json
 * --project demo-airchive-telemetry-poller --only auth "gradlew.bat
 * :androidApp:connectedDebugAndroidTest"` plus a booted AVD.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FirebaseOwnerIdentityEmulatorTest {

    @Before
    fun setUp() {
        ensureEmulatorAuth(ApplicationProvider.getApplicationContext())
        auth.signOut()
    }

    @After
    fun tearDown() {
        auth.signOut()
    }

    // --- positive: fresh Google sign-in -------------------------------------

    @Test(timeout = 120_000L)
    fun a01_googleSignIn_returnsNonEmptyUidAndProfile() = runBlocking {
        val adapter = newAdapter(FakeTokenMode.SUCCESS)

        val result = adapter.signIn()
        val session = (result as SignInResult.Success).session
        assertTrue("expected a non-empty Firebase UID, got '${session.uid}'", session.uid.isNotBlank())
        assertEquals(FAKE_OWNER_EMAIL, session.email)
        assertEquals(FAKE_OWNER_NAME, session.displayName)
    }

    // --- session restore ----------------------------------------------------

    @Test(timeout = 120_000L)
    fun a02_currentSession_restoresSameUidWithoutReprompt() = runBlocking {
        val adapter = newAdapter(FakeTokenMode.SUCCESS)
        val session = (adapter.signIn() as SignInResult.Success).session

        // A brand-new adapter over the SAME FirebaseAuth restores the same UID
        // from currentUser; its token source must never be invoked.
        val restored = newAdapter(FakeTokenMode.UNREACHABLE).currentSession
        assertNotNull("currentSession should restore the signed-in user", restored)
        assertEquals(session.uid, restored!!.uid)
        assertEquals(session.email, restored.email)
    }

    // --- observeSession -----------------------------------------------------

    @Test(timeout = 120_000L)
    fun a03_observeSession_emitsSessionThenClearsOnSignOut() = runBlocking {
        val adapter = newAdapter(FakeTokenMode.SUCCESS)
        val emissions = Channel<OwnerSession?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Main.immediate) {
            adapter.observeSession().collect { emissions.send(it) }
        }

        // Replay of the signed-out start state.
        assertNull("expected signed-out start emission", withTimeout(30_000) { emissions.receive() })

        val session = (adapter.signIn() as SignInResult.Success).session
        val emitted = withTimeout(30_000) { emissions.awaitFirstThat { it?.uid != null } }
        assertEquals("observeSession must emit the new session", session.uid, emitted?.uid)

        adapter.signOut()
        val cleared = withTimeout(30_000) { emissions.awaitFirstThat { it == null } }
        assertNull("signOut must clear the observed session", cleared)
        assertNull(adapter.currentSession)

        collector.cancel()
    }

    // --- typed failure paths ------------------------------------------------

    @Test(timeout = 60_000L)
    fun a04_userCancellation_mapsToCancelled() = runBlocking {
        val adapter = newAdapter(FakeTokenMode.CANCELLED)
        assertEquals(SignInResult.Cancelled, adapter.signIn())
        assertNull("cancelled sign-in must leave no session", adapter.currentSession)
    }

    @Test(timeout = 60_000L)
    fun a05_signOut_neverThrowsEvenWhenSignedOut() = runBlocking {
        val adapter = newAdapter(FakeTokenMode.SUCCESS)
        adapter.signOut() // must not throw while signed out
        assertNull(adapter.currentSession)
        adapter.signIn()
        adapter.signOut() // must not throw while signed in
        assertNull(adapter.currentSession)
    }

    @Test(timeout = 60_000L)
    fun a06_explicitlyBlankClientOptions_reportsActionableMissingOptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This instrumentation build may have real (public) Firebase client
        // options configured. Exercise the unconfigured branch with explicit
        // blanks instead of coupling the test to the developer's local config.
        val state = FirebaseOwnerIdentityFactory.fromOptions(
            context,
            OwnerClientOptions(
                apiKey = "",
                applicationId = "",
                projectId = "demo-airchive-telemetry-poller",
                googleWebClientId = "",
            ),
        )

        assertTrue("expected MissingOptions, got $state", state is OwnerAuthConfiguration.MissingOptions)
        assertFalse("not configured while client options are blank", state.isConfigured)
        val missing = (state as OwnerAuthConfiguration.MissingOptions).missingKeys
        assertTrue("missing firebase.apiKey", missing.contains("firebase.apiKey"))
        assertTrue("missing firebase.applicationId", missing.contains("firebase.applicationId"))
        assertTrue("missing firebase.googleWebClientId", missing.contains("firebase.googleWebClientId"))
        assertTrue("actionable message non-blank", state.actionableMessage.isNotBlank())
    }

    @Test(timeout = 120_000L)
    fun a07_freshGoogleSignInWithDeniedFirestoreReadBecomesAccessPending() = runBlocking {
        val identity = newAdapter(FakeTokenMode.INTRUDER)
        val session = (identity.signIn() as SignInResult.Success).session
        assertTrue(session.uid.isNotBlank())

        val firestore = FirebaseFirestore.getInstance(auth.app).apply {
            useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
        }
        val telemetry = FirebaseAirchiveTelemetry.forEmulatorTest(auth, firestore)
        val access = OwnerAccessCoordinator(identity, telemetry).restore()

        val pending = access as? OwnerAccessState.AccessPending
            ?: throw AssertionError("expected access-pending after denied read, got $access")
        assertEquals(session.uid, pending.session.uid)
        assertEquals("ACCESS_DENIED", pending.safeErrorCode)
    }

    // --- harness ------------------------------------------------------------

    /** Receives channel items until [predicate] matches; a stable replacement
     *  for the `ReceiveChannel.first {}` operator removed in coroutines 1.10. */
    private suspend fun <E> ReceiveChannel<E>.awaitFirstThat(predicate: (E) -> Boolean): E {
        while (true) {
            val item = receive()
            if (predicate(item)) return item
        }
    }

    private fun newAdapter(tokenMode: FakeTokenMode): FirebaseOwnerIdentity =
        FirebaseOwnerIdentity(
            auth = auth,
            googleIdTokenSource = EmulatorFakeGoogleIdTokenSource(tokenMode),
        )

    private enum class FakeTokenMode { SUCCESS, INTRUDER, CANCELLED, UNREACHABLE }

    /**
     * TEST-ONLY [GoogleIdTokenSource]. Returns a clearly-fake `alg: none`
     * Google ID token whose claims the Auth emulator decodes — never a real
     * Google account, credential, or network.
     */
    private class EmulatorFakeGoogleIdTokenSource(
        private val mode: FakeTokenMode,
    ) : GoogleIdTokenSource {
        override suspend fun requestGoogleIdToken(): GoogleIdTokenResult = when (mode) {
            FakeTokenMode.SUCCESS -> GoogleIdTokenResult.Success(
                fakeGoogleIdToken(FAKE_OWNER_EMAIL, FAKE_GOOGLE_SUBJECT, FAKE_OWNER_NAME),
            )
            FakeTokenMode.INTRUDER -> GoogleIdTokenResult.Success(
                fakeGoogleIdToken(INTRUDER_EMAIL, "airchive-emulator-google-intruder-0001", "Emulator Intruder"),
            )
            FakeTokenMode.CANCELLED -> GoogleIdTokenResult.Cancelled
            FakeTokenMode.UNREACHABLE ->
                error("Test-only token source must not be invoked for a session restore")
        }
    }

    companion object {
        const val EMULATOR_APP_NAME: String = "demo-airchive-owner-auth"
        const val EMULATOR_PROJECT_ID: String = "demo-airchive-telemetry-poller"
        const val EMULATOR_HOST: String = "10.0.2.2"
        const val AUTH_EMULATOR_PORT: Int = 9099
        const val FIRESTORE_EMULATOR_PORT: Int = 19080

        // Fake identity claims; the emulator turns these into a mock user.
        const val FAKE_GOOGLE_SUBJECT: String = "airchive-emulator-google-owner-0001"
        // Kept separate from FirebaseAirchiveTelemetryEmulatorTest's email/
        // password account; Auth Emulator would otherwise retain a provider-only
        // user without a password between classes in one connected test run.
        const val FAKE_OWNER_EMAIL: String = "google-owner@airchive.example.invalid"
        // Distinct from FirebaseAirchiveTelemetryEmulatorTest's email/password
        // intruder account; the auth emulator retains provider-only users
        // across instrumentation classes in the same process.
        const val INTRUDER_EMAIL: String = "google-intruder@airchive.example.invalid"
        const val FAKE_OWNER_NAME: String = "Airchive Emulator Owner"
        const val FAKE_TOKEN_AUDIENCE: String = "demo-airchive-owner-web.apps.googleusercontent.com"

        @Volatile
        private var initializedAuth: FirebaseAuth? = null
        private val auth: FirebaseAuth get() = requireNotNull(initializedAuth) {
            "Auth emulator not initialized; call ensureEmulatorAuth first."
        }

        @Synchronized
        private fun ensureEmulatorAuth(context: Context): FirebaseAuth {
            initializedAuth?.let { return it }
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == EMULATOR_APP_NAME }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:123456789012:android:airchiveownerauth")
                        .setProjectId(EMULATOR_PROJECT_ID) // demo project: never production
                        .setApiKey("fake-emulator-only-api-key-not-a-secret")
                        .build(),
                    EMULATOR_APP_NAME,
                )
            val authInstance = FirebaseAuth.getInstance(app).apply {
                signOut()
                useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
            }
            initializedAuth = authInstance
            return authInstance
        }

        /**
         * Builds an `alg: none` JWT the Auth emulator decodes as a fake Google
         * id_token (it validates neither the signature nor the audience). The
         * stable `sub` keeps the mocked owner idempotent across runs.
         */
        private fun fakeGoogleIdToken(email: String, subject: String, name: String): String {
            val header = base64UrlEncode("""{"alg":"none","typ":"JWT"}""")
            val payload = base64UrlEncode(
                """
                {
                  "iss": "https://accounts.google.com",
                  "sub": "$subject",
                  "aud": "$FAKE_TOKEN_AUDIENCE",
                  "email": "$email",
                  "email_verified": true,
                  "name": "$name",
                  "iat": 1750000000,
                  "exp": 1950000000
                }
                """.trimIndent(),
            )
            // Empty signature segment per RFC 7519 for alg: none.
            return "$header.$payload."
        }

        private fun base64UrlEncode(json: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
}
