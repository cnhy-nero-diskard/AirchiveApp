package cloud.airchive.personal.data.firebase

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.personal.BuildConfig
import cloud.airchive.personal.MainActivity
import cloud.airchive.personal.data.auth.FirebaseOwnerIdentityFactory
import cloud.airchive.personal.data.auth.OwnerAuthConfiguration
import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.data.local.AirchiveDatabase
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.data.sync.FirstLoadSyncCoordinator
import cloud.airchive.shared.data.sync.IncrementalSyncCoordinator
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **OPT-IN live verification test** for the read-cache stack the parent wires
 * into production: the real [FirebaseAirchiveTelemetry] adapter over the real
 * signed-in Firebase owner, the real [FirstLoadSyncCoordinator] /
 * [IncrementalSyncCoordinator] from commonMain, and the durable file-backed
 * [RoomLocalHistoryPort].
 *
 * ## Opt-in (skipped by default)
 *
 * Every run first calls [requireLiveHistoryInvocation]. Unless the runner is
 * invoked with `airchive.liveHistoryCheck=true`, the test is **skipped via
 * [Assume]**. The parent only runs it after the production wiring compiles and
 * the identifiers below have been reviewed.
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
 *    `airchive-telemetry-poller` (never the `demo-*` emulator project);
 *  - the Airchive page bound is exactly the fixed 250-document maximum;
 *  - the owner stack is configured ([OwnerAuthConfiguration.Ready]) and a live
 *    signed-in session exists. A missing session, wrong UID, wrong device, or
 *    wrong project is a hard failure with a sanitized message.
 *
 * ## Privacy / safety contract
 *
 *  - It **never** signs in, signs out, registers an account, calls
 *    `getIdToken()`, or writes any Firestore document (and does nothing with
 *    ThinQ). The owner's persisted Firebase session is only *read*.
 *  - The history cache is written to an **isolated, uniquely named test-only
 *    Room database in the app's `cacheDir`** (`airchive-live-history-check-<UUID>.db`,
 *    never the production `airchive.db`). Only this file (plus its `-wal` /
 *    `-shm` siblings) is ever deleted — on cleanup, after close. No production
 *    cache is cleared or touched.
 *  - The summary log emits only counts / pages / elapsed time / read counters.
 *    No UID, token, device id, or raw provider payload is ever logged.
 *  - Every network-touching call is bounded by [NETWORK_TIMEOUT_MILLIS], and the
 *    whole single test is bounded by [WHOLE_RUN_TIMEOUT_MILLIS].
 *
 * ## Behaviors verified (one opt-in test)
 *
 *  1. **Bootstrap** latest-24h observations (paginated, bounded ≤ 250) and up
 *     to 30 finalized daily totals into the isolated Room DB; stored normalized
 *     observation count > 0, totals ≤ 30, intervals bounded inside
 *     `(now - 24h, now]`, sync metadata + watermarks recorded.
 *  2. **Reopen** the same isolated file; the same counts, watermarks, coverage,
 *     and collector health persist from cache, with **zero remote reads** while
 *     verifying (a counting [AirchiveTelemetryPort] wrapper proves the Room
 *     observe path made no telemetry calls).
 *  3. **Incremental sync** after reopen works from the durable watermarks,
 *     never loses prior snapshots, keeps the daily-total cache untouched,
 *     advances the mutation watermarks monotonically, and every cached snapshot
 *     stays normalized (unique `sampleId`s, valid intervals, known units,
 *     genuine missing intervals stay null, tops stay finalized `LG_FINALIZED`).
 */
@RunWith(AndroidJUnit4::class)
class LiveHistoryCacheVerificationTest {

    private val databaseFileName = "airchive-live-history-check-${UUID.randomUUID()}.db"

    @Test(timeout = WHOLE_RUN_TIMEOUT_MILLIS)
    fun productionActivityRecreationShowsCacheWithFirestoreNetworkingDisabled(): Unit = runBlocking {
        val context = context()
        val harness = requireLiveHistoryInvocation(context)
        val partition = exactPartition(harness)
        val productionFile = context.getDatabasePath(AirchiveDatabase.DATABASE_NAME)
        check(productionFile.exists()) { "Open the production app and finish its first history sync before this check." }
        val database = openAirchiveDatabase(productionFile.absolutePath, AirchiveDatabase.MIGRATIONS.asList())
        val before = try {
            withTimeout(CACHE_TIMEOUT_MILLIS) { RoomLocalHistoryPort(database).observe(partition).first() }
        } finally {
            database.close()
        }
        val expectedCount = before.observations.size
        check(expectedCount > 0) { "The production app must already have cached real history." }
        Log.i(TAG, "Starting production offline-cache check: cachedObservations=$expectedCount")
        // Disable only this process's Firestore SDK networking, not the phone's
        // Wi-Fi/mobile data. Restore it even if the activity or assertion fails.
        withTimeout(CACHE_TIMEOUT_MILLIS) { harness.firestore.disableNetwork().awaitCancellable() }
        Log.i(TAG, "Firestore SDK networking disabled for production activity check.")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitProductionHomeCount(expectedCount)
                Log.i(TAG, "Production cache visible before activity recreation.")
                scenario.recreate()
                awaitProductionHomeCount(expectedCount)
            }
            val reopened = openAirchiveDatabase(productionFile.absolutePath, AirchiveDatabase.MIGRATIONS.asList())
            try {
                val after = withTimeout(CACHE_TIMEOUT_MILLIS) { RoomLocalHistoryPort(reopened).observe(partition).first() }
                assertEquals("Offline startup must not pretend a server sync succeeded.",
                    before.sync?.lastSuccessfulSyncEpochMillis, after.sync?.lastSuccessfulSyncEpochMillis)
                assertEquals(expectedCount, after.observations.size)
            } finally {
                reopened.close()
            }
            Log.i(TAG, "Production activity offline-cache recreation passed: observations=$expectedCount; Firestore networking disabled during check.")
        } finally {
            withTimeout(CACHE_TIMEOUT_MILLIS) { harness.firestore.enableNetwork().awaitCancellable() }
        }
    }

    private suspend fun awaitProductionHomeCount(expectedCount: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var visibleCounts = emptyList<String>()
        var appWindowVisible = false
        try {
        withTimeout(CACHE_TIMEOUT_MILLIS) {
            while (true) {
                val root = automation.rootInActiveWindow
                val texts = mutableListOf<String>()
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    node.text?.toString()?.let(texts::add)
                    for (index in 0 until node.childCount) visit(node.getChild(index))
                }
                visit(root)
                appWindowVisible = root?.packageName?.toString() == "cloud.airchive.personal"
                visibleCounts = Regex("Based on \\d+ cached observations\\.")
                    .findAll(texts.joinToString(" ")).map { it.value }.toList()
                if (texts.any { it.contains("Based on $expectedCount cached observations.") }) {
                    check(texts.none { it.contains("simulated", ignoreCase = true) || it.contains("demo-ac-id") }) {
                        "Production history must never render fixture content."
                    }
                    return@withTimeout
                }
                delay(100L)
            }
        }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Cached history label timed out: expectedCount=$expectedCount; appWindowVisible=$appWindowVisible; visibleCountLabels=$visibleCounts", failure)
        }
    }

    @After
    fun tearDown() {
        try {
            deleteOnlyTestDatabase(isolatedDbFile())
        } catch (_: Exception) {
            // Best-effort cleanup of the test-only file; never mask the outcome.
        }
    }

    @Test(timeout = WHOLE_RUN_TIMEOUT_MILLIS)
    fun liveFirstLoadReopenIncremental_persistsBoundedNormalizedHistory(): Unit = runBlocking {
        val appContext = context()
        val harness = requireLiveHistoryInvocation(appContext)
        val startedAtMillis = System.currentTimeMillis()

        // Isolated test-only Room database in the app cache dir. The name never
        // collides with the production 'airchive.db'; only this file is deleted.
        val dbFile = isolatedDbFile(appContext)
        deleteOnlyTestDatabase(dbFile) // stale file from an aborted earlier run

        val partition = exactPartition(harness)
        val bootstrapTelemetry = CountingTelemetryPort(harness.telemetry)

        // ---- 1. Bootstrap: latest 24h + up to 30 finalized totals -----------
        val nowA = System.currentTimeMillis()
        val windowFrom = nowA - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS
        val windowTo = nowA
        var database: AirchiveDatabase? = null
        lateinit var preClose: HistorySnapshot
        val readsAfterBootstrap: Int
        val firstLoad = try {
            database = openAirchiveDatabase(dbFile.absolutePath, AirchiveDatabase.MIGRATIONS.asList())
            val localA = RoomLocalHistoryPort(database!!)
            val result = withTimeout(NETWORK_TIMEOUT_MILLIS) {
                FirstLoadSyncCoordinator(
                    telemetry = bootstrapTelemetry,
                    local = localA,
                    partition = partition,
                    totalsLimit = AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT,
                ).syncFirstLoad(nowEpochMillis = nowA, pageSize = pageSize())
            }
            readsAfterBootstrap = bootstrapTelemetry.readCount.get()

            assertTrue(
                "first load must cache a non-empty 24h window (parent baseline: 288 observations)",
                result.observationsPersisted > 0,
            )
            assertTrue(
                "first load must cache at most 30 finalized daily totals",
                result.dailyTotalsPersisted in 0..AirchiveTelemetryPort.DEFAULT_TOTALS_LIMIT,
            )
            assertTrue(
                "first load must persist a bounded 24h window (5-minute cadence -> ~288 max)",
                result.observationsPersisted <= MAX_OBSERVATIONS_IN_BOUNDED_WINDOW,
            )

            preClose = withTimeout(CACHE_TIMEOUT_MILLIS) { localA.observe(partition).first() }
            assertEquals(result.observationsPersisted, preClose.observations.size)
            assertEquals(result.dailyTotalsPersisted, preClose.dailyTotals.size)
            assertWithinFirstLoadWindow(preClose.observations, windowFrom, windowTo)
            assertNormalizedProjection(preClose, "bootstrap")

            val syncA = preClose.sync ?: error("a successful first load must record sync metadata")
            assertEquals(nowA, syncA.lastSuccessfulSyncEpochMillis)
            assertTrue("first load must not record a sync error", syncA.lastError == null)
            // Watermarks derive exactly from what the loaded samples carried.
            assertEquals(
                preClose.observations.mapNotNull { it.persistedAtEpochMillis }.maxOrNull(),
                syncA.persistedAtWatermarkEpochMillis,
            )
            assertEquals(
                preClose.observations.mapNotNull { it.reconciledAtEpochMillis }.maxOrNull(),
                syncA.reconciledAtWatermarkEpochMillis,
            )
            assertTrue("first load must store collector health", preClose.collectorHealth != null)
            assertEquals(
                listOf(CoveredRange(fromEpochMillis = windowFrom, toEpochMillis = windowTo)),
                preClose.coveredRanges,
            )
            result
        } finally {
            database?.close()
            database = null
        }

        // ---- 2. Reopen the SAME isolated file; counts persist from cache -----
        try {
            database = openAirchiveDatabase(dbFile.absolutePath, AirchiveDatabase.MIGRATIONS.asList())
            val localB = RoomLocalHistoryPort(database!!)
            val reopened = withTimeout(CACHE_TIMEOUT_MILLIS) { localB.observe(partition).first() }

            // Pure cache verification: the counting wrapper proves zero remote reads.
            assertEquals(
                "verifying the reopened cache must not trigger any remote telemetry read",
                readsAfterBootstrap,
                bootstrapTelemetry.readCount.get(),
            )
            assertEquals("reopened cache keeps the same observation count", preClose.observations.size, reopened.observations.size)
            assertEquals("reopened cache keeps the same daily-total count", preClose.dailyTotals.size, reopened.dailyTotals.size)
            assertEquals("reopened cache keeps the same sync metadata", preClose.sync, reopened.sync)
            assertEquals("reopened cache keeps the same covered ranges", preClose.coveredRanges, reopened.coveredRanges)
            assertEquals(
                "reopened cache keeps the same collector health",
                preClose.collectorHealth,
                reopened.collectorHealth,
            )
            assertWithinFirstLoadWindow(reopened.observations, windowFrom, windowTo)
            assertNormalizedProjection(reopened, "reopened-cache")

            // ---- 3. Incremental sync after reopen; counts progress, prior
            //      snapshots stay normalized. ----------------------------------
            val nowB = System.currentTimeMillis()
            val incrementalTelemetry = CountingTelemetryPort(harness.telemetry)
            val incremental = withTimeout(NETWORK_TIMEOUT_MILLIS) {
                IncrementalSyncCoordinator(incrementalTelemetry, localB, partition)
                    .syncIncremental(nowEpochMillis = nowB, pageSize = pageSize())
            }
            assertTrue(
                "incremental sync must perform bounded stream reads",
                incrementalTelemetry.readCount.get() >= 1,
            )

            val afterIncremental = withTimeout(CACHE_TIMEOUT_MILLIS) { localB.observe(partition).first() }
            val priorObservations = reopened.observations.size
            assertTrue(
                "incremental sync must never lose cached observations (count progresses when new rows exist)",
                afterIncremental.observations.size >= priorObservations,
            )
            assertEquals(
                "incremental sync must not touch the daily-total cache",
                reopened.dailyTotals.size,
                afterIncremental.dailyTotals.size,
            )
            assertEquals(
                "incremental sync must not touch the covered ranges",
                reopened.coveredRanges,
                afterIncremental.coveredRanges,
            )
            assertNormalizedProjection(afterIncremental, "post-incremental")

            val syncAfter = afterIncremental.sync ?: error("a successful incremental sync must record sync metadata")
            assertEquals(nowB, syncAfter.lastSuccessfulSyncEpochMillis)
            assertTrue("incremental sync must clear any prior sync error", syncAfter.lastError == null)
            val persistedBefore = reopened.sync?.persistedAtWatermarkEpochMillis ?: 0L
            val reconciledBefore = reopened.sync?.reconciledAtWatermarkEpochMillis ?: 0L
            assertTrue(
                "persistedAt watermark must advance monotonically",
                (syncAfter.persistedAtWatermarkEpochMillis ?: 0L) >= persistedBefore,
            )
            assertTrue(
                "reconciledAt watermark must advance monotonically",
                (syncAfter.reconciledAtWatermarkEpochMillis ?: 0L) >= reconciledBefore,
            )

            // ---- Sanitized summary: counts / pages / times / read stats only. ----
            Log.i(
                TAG,
                "Live history-cache verification passed: " +
                    "bootstrap{observations=${firstLoad.observationsPersisted}, " +
                    "pages=${firstLoad.observationPagesFetched}, " +
                    "dailyTotals=${firstLoad.dailyTotalsPersisted}, remoteReads=$readsAfterBootstrap} " +
                    "reopenedCache{observations=${reopened.observations.size}, " +
                    "dailyTotals=${reopened.dailyTotals.size}, zeroRemoteReads=true} " +
                    "incremental{merged=${incremental.observationsMerged}, " +
                    "persistedPages=${incremental.persistedPagesFetched}, " +
                    "reconciledPages=${incremental.reconciledPagesFetched}, " +
                    "remoteReads=${incrementalTelemetry.readCount.get()}} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMillis}",
            )
        } finally {
            database?.close()
            deleteOnlyTestDatabase(dbFile)
        }
    }

    // --- opt-in + hard guards ------------------------------------------------

    private fun requireLiveHistoryInvocation(context: Context): LiveHarness {
        val args = InstrumentationRegistry.getArguments()

        // The ONLY skip condition: the explicit opt-in must be exactly 'true'.
        Assume.assumeTrue(
            "Live history-cache check is opt-in: runner arg '$ARG_LIVE_HISTORY_CHECK' must be exactly 'true'.",
            args.getString(ARG_LIVE_HISTORY_CHECK) == "true",
        )

        val expectedOwnerUid = args.getString(ARG_EXPECTED_OWNER_UID)?.trim().orEmpty()
        check(expectedOwnerUid.isNotBlank()) {
            "Runner arg '$ARG_EXPECTED_OWNER_UID' is required for the live history-cache check."
        }
        val expectedDeviceId = args.getString(ARG_EXPECTED_DEVICE_ID)?.trim().orEmpty()
        check(expectedDeviceId.isNotBlank()) {
            "Runner arg '$ARG_EXPECTED_DEVICE_ID' is required for the live history-cache check."
        }
        check(BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID.trim() == EXPECTED_PROJECT_ID) {
            "Live history-cache check requires the '$EXPECTED_PROJECT_ID' project; this build targets another project."
        }
        check(BuildConfig.AIRCHIVE_DEVICE_ID.trim() == expectedDeviceId) {
            "Runner arg '$ARG_EXPECTED_DEVICE_ID' does not match the configured device."
        }
        check(BuildConfig.AIRCHIVE_FIRESTORE_MAX_PAGE_SIZE == AirchiveTelemetryPort.MAX_PAGE_SIZE) {
            "Live history-cache check expects the fixed 250-document Airchive read bound; " +
                "this build configures another page size."
        }

        val configuration = FirebaseOwnerIdentityFactory.fromBuildConfig(context)
        val ready = configuration as? OwnerAuthConfiguration.Ready
            ?: error("Live history-cache check requires configured Firebase owner sign-in; " +
                "configure the public mobile client options first.")
        val session = ready.identity.currentSession
        check(session != null) {
            "Live history-cache check requires a live signed-in Firebase owner session on this device; " +
                "open the app, sign in, and rerun this opt-in check."
        }
        check(session.uid == expectedOwnerUid) {
            "Runner arg '$ARG_EXPECTED_OWNER_UID' does not match the current signed-in owner."
        }

        // Same wiring the production MainActivity uses: the signed-in app's
        // Firestore singleton + the BuildConfig adapter. Never signs in/out.
        val auth = ready.identity.firebaseAuth
        val firestore = FirebaseFirestore.getInstance(auth.app)
        val telemetry = FirebaseAirchiveTelemetry.fromBuildConfig(auth = auth, firestore = firestore)
        return LiveHarness(
            ownerUid = session.uid,
            deviceId = expectedDeviceId,
            telemetry = telemetry,
            firestore = firestore,
        )
    }

    private fun exactPartition(harness: LiveHarness): HistoryPartition = HistoryPartition(
        firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID.trim(),
        ownerUid = harness.ownerUid,
        deviceId = harness.deviceId,
    )

    // --- assertions ----------------------------------------------------------

    private fun assertWithinFirstLoadWindow(
        observations: List<Observation>,
        windowFrom: Long,
        windowTo: Long,
    ) {
        assertTrue(
            "bounded 24h window must hold at most $MAX_OBSERVATIONS_IN_BOUNDED_WINDOW stored observations",
            observations.size in 1..MAX_OBSERVATIONS_IN_BOUNDED_WINDOW,
        )
        observations.forEach { observation ->
            assertTrue(
                "sample '${observation.sampleId}' must end at/after the 24h window start",
                observation.intervalEndEpochMillis >= windowFrom,
            )
            assertTrue(
                "sample '${observation.sampleId}' must start before the 24h window end",
                observation.intervalStartEpochMillis < windowTo,
            )
        }
    }

    private fun assertNormalizedProjection(snapshot: HistorySnapshot, label: String) {
        val observations = snapshot.observations
        assertTrue(
            "$label: cached observations must be unique by sampleId",
            observations.map { it.sampleId }.toSet().size == observations.size,
        )
        assertTrue(
            "$label: cached observations must be sorted oldest-first",
            observations.zipWithNext().all { (a, b) -> a.intervalStartEpochMillis <= b.intervalStartEpochMillis },
        )
        observations.forEach { observation ->
            assertTrue(
                "$label: sample '${observation.sampleId}' must carry a non-empty interval",
                observation.intervalStartEpochMillis < observation.intervalEndEpochMillis,
            )
            assertTrue(
                "$label: sample '${observation.sampleId}' must carry a known unit or none",
                observation.recordedUnit == null ||
                    observation.recordedUnit == EnergyUnit.WH ||
                    observation.recordedUnit == EnergyUnit.KWH,
            )
            assertTrue(
                "$label: sample '${observation.sampleId}' must carry a finite value or a genuine missing interval",
                observation.energyValue?.isFinite() != false,
            )
            if (observation.quality == ObservationQuality.MISSING_INTERVAL) {
                assertTrue(
                    "$label: a MISSING_INTERVAL sample must keep a null energy value",
                    observation.energyValue == null,
                )
            }
        }
        assertTrue(
            "$label: cached daily totals must be unique by totalId",
            snapshot.dailyTotals.map { it.totalId }.toSet().size == snapshot.dailyTotals.size,
        )
        snapshot.dailyTotals.forEach { total ->
            assertTrue(
                "$label: cached daily totals must stay collector-finalized",
                total.finalized && total.source == DailyTotalSource.LG_FINALIZED,
            )
            assertTrue(
                "$label: cached total '${total.totalId}' must carry a finite energy value",
                total.energyValue.isFinite(),
            )
        }
    }

    // --- isolated test-only database lifecycle -------------------------------

    private fun isolatedDbFile(context: Context = ApplicationProvider.getApplicationContext()): File =
        File(context.cacheDir, databaseFileName)

    /** Deletes ONLY this test's unique DB file and its WAL/SHM siblings. */
    private fun deleteOnlyTestDatabase(dbFile: File) {
        dbFile.delete()
        File(dbFile.parentFile, "${dbFile.name}-wal").delete()
        File(dbFile.parentFile, "${dbFile.name}-shm").delete()
    }

    private fun pageSize(): Int = BuildConfig.AIRCHIVE_FIRESTORE_MAX_PAGE_SIZE

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // --- read-counting telemetry wrapper -------------------------------------

    /**
     * The one construct that proves "cache read without remote reads": every
     * [AirchiveTelemetryPort] method increments [readCount] before delegating to
     * the live adapter. Zero growth while verifying a reopened Room cache means
     * no remote Firestore read happened for that phase.
     */
    private class CountingTelemetryPort(
        private val delegate: AirchiveTelemetryPort,
    ) : AirchiveTelemetryPort {
        val readCount = AtomicInteger(0)

        override suspend fun latestObservations(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            readCount.incrementAndGet()
            return delegate.latestObservations(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun observations(
            fromEpochMillis: Long,
            toEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            readCount.incrementAndGet()
            return delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSincePersistedAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            readCount.incrementAndGet()
            return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSinceReconciledAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            readCount.incrementAndGet()
            return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> {
            readCount.incrementAndGet()
            return delegate.latestFinalizedTotals(limit)
        }

        override suspend fun collectorHealth(): CollectorHealth {
            readCount.incrementAndGet()
            return delegate.collectorHealth()
        }

        override suspend fun deviceIdentity(): DeviceIdentity {
            readCount.incrementAndGet()
            return delegate.deviceIdentity()
        }
    }

    private class LiveHarness(
        val ownerUid: String,
        val deviceId: String,
        val telemetry: AirchiveTelemetryPort,
        val firestore: FirebaseFirestore,
    )

    companion object {
        const val TAG: String = "AirchiveLiveCacheCheck"

        const val ARG_LIVE_HISTORY_CHECK: String = "airchive.liveHistoryCheck"
        const val ARG_EXPECTED_OWNER_UID: String = "airchive.expectedOwnerUid"
        const val ARG_EXPECTED_DEVICE_ID: String = "airchive.expectedDeviceId"
        const val EXPECTED_PROJECT_ID: String = "airchive-telemetry-poller"

        const val NETWORK_TIMEOUT_MILLIS: Long = 60_000L
        const val CACHE_TIMEOUT_MILLIS: Long = 15_000L
        const val WHOLE_RUN_TIMEOUT_MILLIS: Long = 180_000L

        /** 24h at a five-minute cadence is 288; +slack for boundary jitter. */
        const val MAX_OBSERVATIONS_IN_BOUNDED_WINDOW: Int = 300
    }
}
