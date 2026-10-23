package cloud.airchive.personal.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.personal.BuildConfig
import cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetry
import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.data.telemetry.AirchiveReadPaths
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **OPT-IN live verification test** for the deployed owner enrollment and
 * Firestore rules (sibling `add-owner-mobile-telemetry-access` change).
 *
 * This is the only test that addresses the **production** Firestore project with
 * the **real signed-in Firebase owner** already persisted on the device for the
 * named app [FirebaseOwnerIdentityFactory.APP_NAME]. It never signs in, never
 * signs out, never registers an account, never calls `getIdToken()`, and never
 * collects or logs a UID, token, or raw payload.
 *
 * ## Opt-in (skipped by default)
 *
 * Every test first calls [requireLiveAccessInvocation]. Unless the runner is
 * invoked with `airchive.liveAccessCheck=true`, the test is **skipped via
 * [Assume]**. The parent only runs these after the backend rules are deployed
 * and the identifiers below have been reviewed.
 *
 * ## Hard guards (fail, never silently pass)
 *
 * When opted in, the test **fails** instead of running unless:
 *
 *  - runner arg `airchive.expectedOwnerUid` is present and equals the UID of the
 *    currently signed-in Firebase owner on the device;
 *  - runner arg `airchive.expectedDeviceId` is present and equals the configured
 *    [BuildConfig.AIRCHIVE_DEVICE_ID];
 *  - [BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID] is exactly
 *    `airchive-telemetry-poller` (never the `demo-*` emulator project or any
 *    other project);
 *  - the owner stack is configured ([OwnerAuthConfiguration.Ready]) and a live
 *    signed-in session exists. A missing session, blank UID, wrong UID, wrong
 *    device, or wrong project is a hard failure with a sanitized message.
 *
 * ## Behaviors verified
 *
 *  1. `ownerIdentityGetFromServerSucceeds` – `devices/{deviceId}` identity read
 *     succeeds for the enrolled owner and resolves to the configured device.
 *  2. `boundedTelemetryAndDailyTotalLimitOneQueriesSucceed` – bounded
 *     `telemetry` page (`limit=1`) and `dailyTotals` list (`limit=1`) succeed;
 *     empty results are allowed.
 *  3. `runtimeCollectorGetFromServerSucceeds` – `runtime/collector` read
 *     succeeds (an absent doc still yields the honest `UNKNOWN` heartbeat).
 *  4. `unauthenticatedIdentityGetFromServerIsDenied` – a **temporary named
 *     [FirebaseApp]** initialized with the same public options but **no
 *     signed-in user** must get `FirebaseFirestoreException.Code.PERMISSION_DENIED`
 *     on the same identity read, then that temporary app is **deleted**.
 *  5. `noopOwnerWriteToIdentityFieldIsDeniedAndUnchanged` – the signed-in owner
 *     performs an explicitly NO-OP update of an existing stable device-identity
 *     field (same field, same value, `update`), which must be denied
 *     with `PERMISSION_DENIED`; the field is re-read from the server afterwards
 *     and must be unchanged. No document is ever created and the value is only
 *     ever written back to itself, so even a (misconfigured-rules) permission
 *     error would leave production data untouched.
 *
 * ## Schema field choice for the no-op write probe
 *
 * `devices/{deviceId}` always carries a `deviceId` field: the sibling Python
 * collector (`put_device_record`, `src/airchive/storage/store.py`) writes the
 * document id mirror as `deviceId` on every discovery, and the shared Android
 * parser treats `deviceId` as the authoritative field. The probe therefore
 * uses only `deviceId`, verifies that it matches the configured ID, and fails
 * closed if it is absent or differs. It never probes mutable alias/model fields.
 *
 * ## Server reads
 *
 * All authorization probes explicitly use `Source.SERVER`. Disk persistence
 * is also disabled for this test process. Adapter projections are exercised
 * separately after the corresponding server-backed request succeeds.
 */
@RunWith(AndroidJUnit4::class)
class LiveOwnerAccessVerificationTest {

    /** The one temporary named app this class creates; deleted after the probe. */
    private var temporaryUnauthenticatedApp: FirebaseApp? = null

    @After
    fun tearDown() {
        temporaryUnauthenticatedApp?.let { app ->
            try {
                serverFirestores.remove(app.name)
                app.delete()
            } catch (_: Exception) {
                // Best-effort cleanup of a probe-only app; never mask the test outcome.
            }
            temporaryUnauthenticatedApp = null
        }
    }

    // --- live reads as the enrolled, signed-in owner -------------------------

    @Test(timeout = 90_000L)
    fun ownerIdentityGetFromServerSucceeds() = runBlocking {
        val harness = requireLiveAccessInvocation(context())

        val snapshot = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            harness.firestore.document(AirchiveReadPaths.identityDocument(harness.deviceId))
                .get(Source.SERVER).awaitCancellable()
        }
        assertTrue("Configured identity document must exist on the server.", snapshot.exists())
        val device = withTimeout(NETWORK_TIMEOUT_MILLIS) { harness.telemetry.deviceIdentity() }

        assertTrue(
            "Device identity read must resolve to the configured device.",
            device.deviceId == harness.deviceId,
        )
    }

    @Test(timeout = 90_000L)
    fun boundedTelemetryAndDailyTotalLimitOneQueriesSucceed() = runBlocking {
        val harness = requireLiveAccessInvocation(context())

        withTimeout(NETWORK_TIMEOUT_MILLIS) {
            harness.firestore.collection("devices/${harness.deviceId}/telemetry")
                .orderBy("observedAt").limit(1).get(Source.SERVER).awaitCancellable()
            harness.firestore.collection("devices/${harness.deviceId}/dailyTotals")
                .orderBy("localDate").limit(1).get(Source.SERVER).awaitCancellable()
        }
        // Bounded telemetry burst, limit 1. Empty is an honest result.
        val page = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            harness.telemetry.latestObservations(sinceEpochMillis = 0L, pageSize = 1)
        }
        assertTrue(
            "Bounded telemetry page must contain at most one item (empty is allowed).",
            page.items.size <= 1,
        )

        // Bounded daily-total list, limit 1. Empty is an honest result.
        val totals = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            harness.telemetry.latestFinalizedTotals(limit = 1)
        }
        assertTrue(
            "Bounded daily-total list must contain at most one item (empty is allowed).",
            totals.size <= 1,
        )
    }

    @Test(timeout = 90_000L)
    fun runtimeCollectorGetFromServerSucceeds() = runBlocking {
        val harness = requireLiveAccessInvocation(context())

        val snapshot = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            harness.firestore.document(AirchiveReadPaths.collectorHealthDocument(harness.deviceId))
                .get(Source.SERVER).awaitCancellable()
        }
        assertTrue("Collector health document must exist on the server.", snapshot.exists())
        val health = withTimeout(NETWORK_TIMEOUT_MILLIS) { harness.telemetry.collectorHealth() }

        assertTrue(
            "Runtime collector read must return one of the bounded status projections.",
            health.status in KNOWN_COLLECTOR_STATUSES,
        )
    }

    // --- denied access: unauthenticated read ---------------------------------

    @Test(timeout = 90_000L)
    fun unauthenticatedIdentityGetFromServerIsDenied() = runBlocking {
        val appContext = context()
        val harness = requireLiveAccessInvocation(appContext)

        val probeApp = createUnauthenticatedProbeApp(appContext)
        temporaryUnauthenticatedApp = probeApp
        val probeFirestore = serverFirestore(probeApp)
        val identityPath = AirchiveReadPaths.identityDocument(harness.deviceId)

        val failure = try {
            withTimeout(NETWORK_TIMEOUT_MILLIS) {
                probeFirestore.document(identityPath).get(Source.SERVER).awaitCancellable()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (caught: Exception) {
            unwrap(caught)
        }
        val denied = failure as? FirebaseFirestoreException
        assertTrue(
            "An unauthenticated identity read must be denied by the rules.",
            denied != null,
        )
        assertTrue(
            "The unauthenticated identity read must be denied with the PERMISSION_DENIED code.",
            denied!!.code == FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )

        try {
            serverFirestores.remove(probeApp.name)
            probeApp.delete()
        } finally {
            temporaryUnauthenticatedApp = null
        }
    }

    // --- denied access: no-op owner write ------------------------------------

    @Test(timeout = 90_000L)
    fun noopOwnerWriteToIdentityFieldIsDeniedAndUnchanged() = runBlocking {
        val harness = requireLiveAccessInvocation(context())
        val document = harness.firestore.document(AirchiveReadPaths.identityDocument(harness.deviceId))

        // Read the existing identity document from the server first.
        val before = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            document.get(Source.SERVER).awaitCancellable()
        }
        assertTrue(
            "The device identity document must exist for the no-op write probe.",
            before.exists(),
        )
        val data = before.data ?: emptyMap()
        val field = "deviceId"
        val fieldValue = requireNotNull(data[field]) {
            "A present device-identity schema field has a null value; cannot perform the no-op write probe."
        }
        check(fieldValue == harness.deviceId) { "Identity field must match the configured device before a no-op probe." }

        // Explicit NO-OP update of the same existing field to its identical
        // value. Unlike merge-set, update cannot create a missing document.
        // Only touches an existing
        // stable field with its own current value.
        val writeFailure = try {
            withTimeout(NETWORK_TIMEOUT_MILLIS) {
                document.update(field, fieldValue).awaitCancellable()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (caught: Exception) {
            unwrap(caught)
        }
        val denied = writeFailure as? FirebaseFirestoreException
        assertTrue(
            "An owner write to the device identity document must be denied.",
            denied != null,
        )
        assertTrue(
            "The denied owner write must carry the PERMISSION_DENIED code.",
            denied!!.code == FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )

        // Re-read from the server to confirm the field is unchanged.
        val after = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            document.get(Source.SERVER).awaitCancellable()
        }
        assertTrue(
            "The device identity document must still exist after the denied write.",
            after.exists(),
        )
        assertTrue(
            "The probed device-identity field must be unchanged after the denied no-op write.",
            after.data?.get(field) == fieldValue,
        )
    }

    // --- harness -------------------------------------------------------------

    private fun requireLiveAccessInvocation(context: Context): LiveHarness {
        val args = InstrumentationRegistry.getArguments()

        // The ONLY skip condition: the explicit opt-in must be present and exactly 'true'.
        Assume.assumeTrue(
            "Live access check is opt-in: runner arg '$ARG_LIVE_ACCESS_CHECK' must be exactly 'true'.",
            args.getString(ARG_LIVE_ACCESS_CHECK) == "true",
        )

        val expectedOwnerUid = args.getString(ARG_EXPECTED_OWNER_UID)?.trim().orEmpty()
        check(expectedOwnerUid.isNotBlank()) {
            "Runner arg '$ARG_EXPECTED_OWNER_UID' is required for the live access check."
        }
        val expectedDeviceId = args.getString(ARG_EXPECTED_DEVICE_ID)?.trim().orEmpty()
        check(expectedDeviceId.isNotBlank()) {
            "Runner arg '$ARG_EXPECTED_DEVICE_ID' is required for the live access check."
        }
        check(BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID.trim() == EXPECTED_PROJECT_ID) {
            "Live access check requires the '$EXPECTED_PROJECT_ID' project; this build targets another project."
        }
        check(BuildConfig.AIRCHIVE_DEVICE_ID.trim() == expectedDeviceId) {
            "Runner arg '$ARG_EXPECTED_DEVICE_ID' does not match the configured device."
        }

        val configuration = FirebaseOwnerIdentityFactory.fromBuildConfig(context)
        val ready = configuration as? OwnerAuthConfiguration.Ready
            ?: error("Live access check requires configured Firebase owner sign-in; configure the public mobile client options first.")
        val session = ready.identity.currentSession
        check(session != null) {
            "Live access check requires a live signed-in Firebase owner session on this device; " +
                "open the app, sign in, and rerun the check."
        }
        check(session.uid == expectedOwnerUid) {
            "Runner arg '$ARG_EXPECTED_OWNER_UID' does not match the current signed-in owner."
        }

        val auth = ready.identity.firebaseAuth
        val firestore = serverFirestore(auth.app)
        val telemetry = FirebaseAirchiveTelemetry.fromBuildConfig(auth = auth, firestore = firestore)
        return LiveHarness(auth = auth, firestore = firestore, telemetry = telemetry, deviceId = expectedDeviceId)
    }

    /** Server-only Firestore instance, created once per FirebaseApp name. */
    private fun serverFirestore(app: FirebaseApp): FirebaseFirestore =
        serverFirestores.computeIfAbsent(app.name) {
            FirebaseFirestore.getInstance(app).apply {
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(false)
                    .build()
            }
        }

    /**
     * A temporary named app with the exact same public BuildConfig options but
     * no signed-in user (and never any auth operation), used only to prove that
     * the rules deny an unauthenticated visitor. Deleted immediately after.
     */
    private fun createUnauthenticatedProbeApp(context: Context): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == UNAUTHENTICATED_PROBE_APP_NAME }?.let { return it }
        return FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.AIRCHIVE_FIREBASE_API_KEY.trim())
                .setApplicationId(BuildConfig.AIRCHIVE_FIREBASE_APPLICATION_ID.trim())
                .setProjectId(BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID.trim())
                .build(),
            UNAUTHENTICATED_PROBE_APP_NAME,
        )
    }

    /** Unwraps task-wrapping exceptions, preserving the underlying Firestore failure. */
    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class LiveHarness(
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val telemetry: FirebaseAirchiveTelemetry,
        val deviceId: String,
    )

    companion object {
        const val ARG_LIVE_ACCESS_CHECK: String = "airchive.liveAccessCheck"
        const val ARG_EXPECTED_OWNER_UID: String = "airchive.expectedOwnerUid"
        const val ARG_EXPECTED_DEVICE_ID: String = "airchive.expectedDeviceId"
        const val EXPECTED_PROJECT_ID: String = "airchive-telemetry-poller"
        const val UNAUTHENTICATED_PROBE_APP_NAME: String = "airchive-live-unauth-probe"
        const val NETWORK_TIMEOUT_MILLIS: Long = 30_000L

        private val KNOWN_COLLECTOR_STATUSES: Set<CollectorStatus> = CollectorStatus.entries.toSet()

        private val serverFirestores = ConcurrentHashMap<String, FirebaseFirestore>()
    }
}
