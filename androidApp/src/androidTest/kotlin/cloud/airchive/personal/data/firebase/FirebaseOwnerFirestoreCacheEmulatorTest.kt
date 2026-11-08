package cloud.airchive.personal.data.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.airchive.personal.data.tasks.awaitCancellable
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local Firebase Emulator test for the process-wide owner document-cache reset. */
@RunWith(AndroidJUnit4::class)
class FirebaseOwnerFirestoreCacheEmulatorTest {

    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var ownerFirestore: FirebaseOwnerFirestore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = FirebaseApp.getApps(context).firstOrNull { it.name == EMULATOR_APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:airchiveownercachetest")
                    .setProjectId(EMULATOR_PROJECT_ID)
                    .setApiKey("fake-owner-cache-emulator-api-key-not-a-secret")
                    .build(),
                EMULATOR_APP_NAME,
            )
        auth = FirebaseAuth.getInstance(app).apply {
            useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
            signOut()
        }
        FirebaseFirestore.getInstance(app).useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
        ownerFirestore = FirebaseOwnerFirestore(app)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        auth.signOut()
        try {
            ownerFirestore.clear()
        } catch (_: Exception) {
            // Best-effort cleanup after a failed emulator assertion.
        }
    }

    @Test(timeout = 90_000L)
    fun signOutClearsCachedDocumentAndTheProviderLazilyCreatesAUsableInstance(): Unit = runBlocking {
        signInFixtureOwner()
        val firstInstance = ownerFirestore.get()
        val documentId = UUID.randomUUID().toString()
        val document = firstInstance.collection(CACHE_TEST_COLLECTION).document(documentId)

        document.set(mapOf("value" to CACHE_VALUE)).awaitCancellable()
        val serverBeforeSignOut = document.get(Source.SERVER).awaitCancellable()
        assertTrue("fixture document is present before sign-out", serverBeforeSignOut.exists())
        assertEquals(CACHE_VALUE, serverBeforeSignOut.getString("value"))

        auth.signOut()
        assertNull("Firebase auth is signed out before the cache reset", auth.currentUser)
        ownerFirestore.clear()

        val secondInstance = ownerFirestore.get()
        assertNotSame("a terminated Firestore instance is replaced lazily", firstInstance, secondInstance)
        secondInstance.useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
        val cacheRead = runCatching {
            secondInstance.collection(CACHE_TEST_COLLECTION).document(documentId)
                .get(Source.CACHE)
                .awaitCancellable()
        }.getOrNull()
        assertTrue("the old owner's document is absent from the cleared SDK cache", cacheRead == null || !cacheRead.exists())

        signInFixtureOwner()
        val serverAfterSignIn = secondInstance.collection(CACHE_TEST_COLLECTION).document(documentId)
            .get(Source.SERVER)
            .awaitCancellable()
        assertTrue("a new server read works after the cache was cleared", serverAfterSignIn.exists())
        assertEquals(CACHE_VALUE, serverAfterSignIn.getString("value"))
        serverAfterSignIn.reference.delete().awaitCancellable()
    }

    private suspend fun signInFixtureOwner() {
        try {
            auth.createUserWithEmailAndPassword(OWNER_EMAIL, TEST_PASSWORD).awaitCancellable()
        } catch (_: Exception) {
            auth.signInWithEmailAndPassword(OWNER_EMAIL, TEST_PASSWORD).awaitCancellable()
        }
        assertNotNull("the emulator fixture owner is signed in", auth.currentUser)
    }

    companion object {
        private const val EMULATOR_APP_NAME = "airchive-owner-cache-emulator-test-app"
        private const val EMULATOR_PROJECT_ID = "demo-airchive-telemetry-poller"
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val AUTH_EMULATOR_PORT = 9099
        private const val FIRESTORE_EMULATOR_PORT = 19080
        private const val OWNER_EMAIL = "owner@airchive.example.invalid"
        private const val TEST_PASSWORD = "airchive-emulator-test-pw"
        private const val CACHE_TEST_COLLECTION = "ownerTransitionCache"
        private const val CACHE_VALUE = "owner-cache-test-fixture"
    }
}
