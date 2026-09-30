package cloud.airchive.personal.data.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.airchive.personal.BuildConfig
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.DocumentSnapshot
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * LOCAL-EMULATOR-ONLY instrumentation test for [FirebaseAirchiveTelemetry]
 * (OpenSpec task 2.3).
 *
 * ## Contract under test
 *
 * The Android read adapter must:
 *  - use EXACTLY the four configured path shapes derived from the single
 *    configured device id (identity GET, bounded telemetry LIST, bounded
 *    daily-total LIST, collector-health GET),
 *  - page `1..250` and expose no write method / no listener,
 *  - require a signed-in FirebaseAuth user, with Firestore attaching that
 *    user's ID token to every read.
 *
 * ## Emulator-only harness
 *
 *  - A NAMED [FirebaseApp] is initialized with non-secret FAKE emulator
 *    options for the `demo-airchive-telemetry-poller` project. The adapter's
 *    emulator-only factory selects the same `demo-*` id; production uses the
 *    separately verified BuildConfig project. The apiKey is an obvious dummy.
 *    No google-services.json, real API key, service account, or PAT is used.
 *  - Auth + Firestore emulators are reached from the local AVD via
 *    `10.0.2.2` on ports 9099 / 19080 (see `firebase.test.json`). This test
 *    only works against that local emulator.
 *  - Sign-in uses clearly-labeled fake `example.invalid` accounts only:
 *    the fixture owner and an intruder. Real user accounts are never used.
 *  - The rules fixture (`tests/firebase/firestore.rules`) permits only the one
 *    owner fixture + the one device fixture; it does NOT replace the sibling
 *    UID-enrolled production rules (`add-owner-mobile-telemetry-access`).
 *
 * Expected results:
 *  - owner-account empty reads on all four shapes succeed (identity GET is
 *    authorized but the doc is absent -> the adapter's documented fail-closed
 *    missing-doc error),
 *  - intruder account, other device, unlisted paths, unbounded/oversized
 *    lists, and every direct client write are denied (PERMISSION_DENIED).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FirebaseAirchiveTelemetryEmulatorTest {

    private lateinit var adapter: FirebaseAirchiveTelemetry

    @Before
    fun setUp() {
        ensureEmulatorsInitialized(ApplicationProvider.getApplicationContext())
        adapter = FirebaseAirchiveTelemetry.forEmulatorTest(auth, firestore)
    }

    @After
    fun tearDown() {
        auth.signOut()
    }

    // --- positive: authorized empty reads ----------------------------------

    @Test(timeout = 90_000L)
    fun a01_owner_authorizedEmptyReads_succeed() = runBlocking {
        ensureSignedIn(OWNER_EMAIL)

        // Guard rails so a config drift fails loudly instead of silently.
        assertEquals(FIXTURE_DEVICE_ID, BuildConfig.AIRCHIVE_DEVICE_ID)
        assertEquals(FIXTURE_PROJECT_ID, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)

        // latestObservations: bounded list on empty telemetry -> empty page.
        val latest = adapter.latestObservations(
            sinceEpochMillis = System.currentTimeMillis() - DAY_MILLIS,
            pageSize = 250,
        )
        assertTrue("expected empty latest-observations page", latest.items.isEmpty())
        assertNull(latest.nextCursor)

        // observations: selected-range bounded list on empty telemetry.
        val historical = adapter.observations(
            fromEpochMillis = System.currentTimeMillis() - 2 * DAY_MILLIS,
            toEpochMillis = System.currentTimeMillis() - DAY_MILLIS,
            pageSize = 250,
        )
        assertTrue("expected empty historical-observations page", historical.items.isEmpty())
        assertNull(historical.nextCursor)

        // latestFinalizedTotals: bounded list on empty dailyTotals.
        assertTrue("expected empty daily totals", adapter.latestFinalizedTotals(limit = 30).isEmpty())

        // collectorHealth: authorized GET succeeds; absent doc -> honest UNKNOWN.
        assertEquals(CollectorStatus.UNKNOWN, adapter.collectorHealth().status)

        // deviceIdentity: the authorized GET succeeds (rules allow it) but the
        // identity document has not been written yet -> documented fail-closed
        // error rather than a fabricated identity.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter.deviceIdentity() }
        }
        assertTrue(
            "unexpected deviceIdentity error: ${error.message}",
            error.message.orEmpty().contains("Device identity document not found"),
        )
    }

    // --- negative: every denied shape --------------------------------------

    @Test(timeout = 90_000L)
    fun a02_otherTestAccount_isDenied() = runBlocking {
        ensureSignedIn(INTRUDER_EMAIL)
        // Adapter read (requires a signed-in user; intruder is signed in, but
        // rules do not recognize the email) and raw identity GET both denied.
        assertDenied { adapter.deviceIdentity() }
        assertDenied {
            awaitDocument(firestore.document(identityDocumentPath()).get())
        }
    }

    @Test(timeout = 90_000L)
    fun a03_otherDevice_isDenied() = runBlocking {
        ensureSignedIn(OWNER_EMAIL)
        assertDenied {
            awaitDocument(firestore.document("devices/$OTHER_DEVICE_ID").get())
        }
        assertDenied {
            awaitQuery(
                firestore.collection("devices/$OTHER_DEVICE_ID/telemetry").limit(1).get(),
            )
        }
    }

    @Test(timeout = 90_000L)
    fun a04_metadataAndUnlistedPaths_areDenied() = runBlocking {
        ensureSignedIn(OWNER_EMAIL)
        assertDenied {
            awaitDocument(firestore.document("devices/$FIXTURE_DEVICE_ID/metadata/whatever").get())
        }
        assertDenied {
            awaitDocument(firestore.document("devices/$FIXTURE_DEVICE_ID/reconciliation/whatever").get())
        }
        assertDenied {
            awaitDocument(firestore.document("devices/$FIXTURE_DEVICE_ID/runtime/other").get())
        }
    }

    @Test(timeout = 90_000L)
    fun a05_unboundedAndOversizedLists_areDenied() = runBlocking<Unit> {
        ensureSignedIn(OWNER_EMAIL)
        val telemetry = firestore.collection("devices/$FIXTURE_DEVICE_ID/telemetry")
        val dailyTotals = firestore.collection("devices/$FIXTURE_DEVICE_ID/dailyTotals")

        // No .limit() at all -> request.query.limit == null -> rules deny.
        assertDenied { awaitQuery(telemetry.get()) }
        assertDenied { awaitQuery(dailyTotals.get()) }

        // Oversized -> exceeds the 1..250 contract bound -> rules deny.
        assertDenied { awaitQuery(telemetry.limit(251).get()) }
        assertDenied { awaitQuery(dailyTotals.limit(251).get()) }

        // Sanity: an IN-bounds query is still allowed for the owner.
        awaitQuery(telemetry.limit(1).get()) // must not throw

        // The adapter refuses oversized limits before issuing any SDK request.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter.latestObservations(sinceEpochMillis = 0L, pageSize = 251) }
        }
    }

    @Test(timeout = 90_000L)
    fun a06_directClientWrites_areDenied() = runBlocking {
        ensureSignedIn(OWNER_EMAIL)
        // set() into an allowed-shape path, the identity doc, and a delete.
        assertDenied {
            awaitVoid(
                firestore
                    .document("devices/$FIXTURE_DEVICE_ID/telemetry/evil-sample")
                    .set(mapOf("observedAt" to FieldValue.serverTimestamp())),
            )
        }
        assertDenied {
            awaitVoid(
                firestore.document("devices/$FIXTURE_DEVICE_ID").set(mapOf("alias" to "hacked")),
            )
        }
        assertDenied {
            awaitVoid(firestore.document("devices/$FIXTURE_DEVICE_ID/dailyTotals/2026-01-01").delete())
        }
    }

    // --- shared emulator app (initialized once per process) ----------------

    private fun ensureSignedIn(email: String): FirebaseUser {
        auth.signOut()
        return try {
            await(auth.createUserWithEmailAndPassword(email, TEST_PASSWORD)).user!!
        } catch (alreadyExists: Exception) {
            // A reused emulator may already have this fake account: sign in.
            await(auth.signInWithEmailAndPassword(email, TEST_PASSWORD)).user!!
        }
    }

    private suspend fun assertDenied(operation: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            operation()
        } catch (e: Throwable) {
            failure = e
        }
        val cause = unwrap(requireNotNull(failure) { "Expected PERMISSION_DENIED, but the operation succeeded." })
        if (cause is FirebaseFirestoreException) {
            assertTrue(
                "Expected PERMISSION_DENIED, got ${cause.code}: ${cause.message}",
                cause.code == FirebaseFirestoreException.Code.PERMISSION_DENIED,
            )
            return
        }
        // Resilience fallback for SDK-version differences in exception mapping.
        val text = (cause.message ?: "").lowercase()
        assertTrue(
            "Expected permission-denied, got: $cause",
            text.contains("permission denied") || "permission_denied" in text,
        )
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is java.util.concurrent.CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun identityDocumentPath(): String = "devices/$FIXTURE_DEVICE_ID"

    private inline fun <reified T> await(task: Task<T>): T =
        try {
            Tasks.await(task)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private fun awaitDocument(task: Task<DocumentSnapshot>): DocumentSnapshot = await(task)

    private fun awaitQuery(task: Task<QuerySnapshot>): QuerySnapshot = await(task)

    private fun awaitVoid(task: Task<Void>): Void = await(task)

    companion object {
        const val FIXTURE_PROJECT_ID: String = "airchive-telemetry-poller"
        const val FIXTURE_DEVICE_ID: String =
            "d7dc30c9698797295acbb90ee543447738db8f7b3d5cff230eb66978a855be3a"
        const val OTHER_DEVICE_ID: String = "another-device-0001"

        /** Clearly-labeled fake accounts — never real users. */
        const val OWNER_EMAIL: String = "owner@airchive.example.invalid"
        const val INTRUDER_EMAIL: String = "intruder@airchive.example.invalid"
        /** Emulator-only fixture password; not a real account credential. */
        const val TEST_PASSWORD: String = "airchive-emulator-test-pw"

        const val EMULATOR_APP_NAME: String = "airchive-emulator-test-app"
        const val EMULATOR_PROJECT_ID: String = FirebaseAirchiveTelemetry.EMULATOR_TEST_PROJECT_ID
        const val EMULATOR_HOST: String = "10.0.2.2"
        const val AUTH_EMULATOR_PORT: Int = 9099
        const val FIRESTORE_EMULATOR_PORT: Int = 19080

        private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

        private val auth: FirebaseAuth get() = requireNotNull(initializedAuth)
        private val firestore: FirebaseFirestore get() = requireNotNull(initializedFirestore)

        @Volatile
        private var initializedApp: FirebaseApp? = null
        @Volatile
        private var initializedAuth: FirebaseAuth? = null
        @Volatile
        private var initializedFirestore: FirebaseFirestore? = null

        @Synchronized
        private fun ensureEmulatorsInitialized(context: Context) {
            if (initializedApp != null) return

            val app = FirebaseApp.getApps(context).firstOrNull { it.name == EMULATOR_APP_NAME }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:123456789012:android:airchiveemulatortest")
                        .setProjectId(EMULATOR_PROJECT_ID) // demo project: never addresses production
                        .setApiKey("fake-emulator-only-api-key-not-a-secret")
                        .build(),
                    EMULATOR_APP_NAME,
                )

            val firestoreInstance = FirebaseFirestore.getInstance(app).apply {
                // No local persistence: every assertion hits the live emulator.
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(false)
                    .build()
                useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
            }
            val authInstance = FirebaseAuth.getInstance(app).apply {
                signOut()
                useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
            }

            initializedApp = app
            initializedAuth = authInstance
            initializedFirestore = firestoreInstance
        }
    }
}
