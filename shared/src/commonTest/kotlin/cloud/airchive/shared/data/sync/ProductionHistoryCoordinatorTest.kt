package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Policy tests for the production history graph: initial bootstrap vs the
 * incremental ten-minute gate vs manual bypass, exact partition derivation,
 * owner switching with no stale-result leakage, and safe deferral after a
 * recent denied/offline bootstrap failure.
 */
class ProductionHistoryCoordinatorTest {

    private val nowEpochMillis = 1_750_000_000_000L
    private val project = "airchive-test-project"
    private val device = "configured-ac-1"
    private val ownerA = OwnerSession(uid = "owner-a", signedInAtEpochMillis = nowEpochMillis)
    private val ownerB = OwnerSession(uid = "owner-b", signedInAtEpochMillis = nowEpochMillis)

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun partitionFor(session: OwnerSession): HistoryPartition =
        HistoryPartition(project, session.uid, device)

    private fun coordinator(
        identity: TestOwnerIdentity,
        telemetry: AirchiveTelemetryPort,
        local: FakeLocalHistory = FakeLocalHistory(),
    ) = ProductionHistoryCoordinator(
        identity = identity,
        telemetry = telemetry,
        local = local,
        firebaseProjectId = project,
        configuredDeviceId = device,
        scope = scope,
    )

    @Test
    fun signedOutForegroundNeverTouchesNetworkOrCache() = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val coordinator = coordinator(identity = TestOwnerIdentity(initial = null), telemetry = telemetry)
        val outcome = coordinator.onForeground(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.SignedOut>(outcome)
        assertEquals(0, telemetry.reads)
        assertNull(coordinator.session.value)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test
    fun freshAuthorizedInstallBootstrapsThenForegroundGatesThenManualRefreshes() = runBlocking {
        val telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)
        val local = FakeLocalHistory()
        val coordinator = coordinator(
            identity = TestOwnerIdentity(initial = ownerA),
            telemetry = telemetry,
            local = local,
        )
        // First open: no coverage yet -> bounded 24h bootstrap (288 samples + 30 totals).
        val first = coordinator.onForeground(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.Bootstrapped>(first)
        val partition = partitionFor(ownerA)
        val snapshot = local.observe(partition).first()
        assertEquals(288, snapshot.observations.size)
        assertEquals(30, snapshot.dailyTotals.size)
        assertTrue(coordinator.hasInitialWindowCoverage(snapshot.coveredRanges))
        assertEquals(ownerA, coordinator.session.value)

        // A resume inside the ten-minute gate runs no network work.
        val second = coordinator.onForeground(nowEpochMillis + 60_000L)
        assertIs<ProductionRefreshOutcome.Fresh>(second)
        assertEquals(nowEpochMillis, second.lastSuccessfulSyncEpochMillis)

        // Rechecking enrollment on Activity recreation also respects freshness.
        assertIs<ProductionRefreshOutcome.Fresh>(coordinator.onAccessAuthorized(nowEpochMillis + 60_000L))

        // Manual refresh bypasses the age gate and runs the incremental streams.
        val manual = coordinator.onManualRefresh(nowEpochMillis + 60_000L)
        assertIs<ProductionRefreshOutcome.Refreshed>(manual)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test
    fun recentBootstrapFailureDefersAutoRetryThenRetriesAfterGate(): Unit = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        telemetry.failWith = TelemetryReadFailure.AccessDenied(IllegalStateException("denied"))
        val coordinator = coordinator(
            identity = TestOwnerIdentity(initial = ownerA),
            telemetry = telemetry,
        )

        val first = coordinator.onForeground(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.RefreshFailed>(first)
        assertEquals("ACCESS_DENIED", first.code)

        // A denied bootstrap less than ten minutes ago defers the auto retry
        // with zero network work on the next foreground.
        val readsBeforeDeferred = telemetry.reads
        telemetry.failWith = null
        val deferred = coordinator.onForeground(nowEpochMillis + 60_000L)
        assertIs<ProductionRefreshOutcome.DeferredAfterRecentFailure>(deferred)
        assertEquals("ACCESS_DENIED", deferred.code)
        assertEquals(readsBeforeDeferred, telemetry.reads)

        // After the ten-minute gate the auto retry runs and succeeds.
        val retried = coordinator.onForeground(nowEpochMillis + 11 * 60_000L)
        assertIs<ProductionRefreshOutcome.Bootstrapped>(retried)

        // An explicit manual refresh also retries regardless of the gate.
        val manualAfterSuccess = coordinator.onManualRefresh(nowEpochMillis + 11 * 60_000L + 1_000L)
        assertIs<ProductionRefreshOutcome.Refreshed>(manualAfterSuccess)
    }

    @Test
    fun switchingOwnerNeverMixturesPartitionsAndKeepsOldCache() = runBlocking {
        val identity = TestOwnerIdentity(initial = ownerA)
        val local = FakeLocalHistory()
        val coordinator = coordinator(identity = identity, telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis), local = local)

        coordinator.onForeground(nowEpochMillis)
        assertEquals(288, local.observe(partitionFor(ownerA)).first().observations.size)

        // Account switch: the coordinator now derives B's exact partition and B
        // starts with an empty cache; A's rows are untouched and isolated.
        identity.session = ownerB
        yield()
        val bOutcome = coordinator.onForeground(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.Bootstrapped>(bOutcome)
        assertEquals(ownerB, coordinator.session.value)

        val partitionB = partitionFor(ownerB)
        assertEquals(288, local.observe(partitionB).first().observations.size)
        assertEquals(288, local.observe(partitionFor(ownerA)).first().observations.size)

        // Clearing only the current owner's partition leaves the other owner's rows.
        assertTrue(coordinator.clearCurrentOwnerPartition())
        assertEquals(0, local.observe(partitionB).first().observations.size)
        assertEquals(288, local.observe(partitionFor(ownerA)).first().observations.size)
    }

    @Test
    fun staleForegroundResultIsDroppedAfterOwnerSwitch() = runBlocking {
        val identity = TestOwnerIdentity(initial = ownerA)
        val local = FakeLocalHistory().apply {
            replace(
                partitionFor(ownerA),
                FakeLocalHistory.syncedSnapshotFor(
                    partitionFor(ownerA),
                    coveredRanges = listOf(
                        CoveredRange(
                            fromEpochMillis = nowEpochMillis - FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS,
                            toEpochMillis = nowEpochMillis,
                        ),
                    ),
                    sync = SyncMetadata(lastSuccessfulSyncEpochMillis = nowEpochMillis),
                ),
            )
        }
        val telemetry = BlockingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val coordinator = coordinator(identity = identity, telemetry = telemetry, local = local)

        // The seeded cache is fresh, so foreground refresh would correctly
        // skip the read. Explicit refresh exercises an in-flight transition.
        val job = async { coordinator.onManualRefresh(nowEpochMillis) }
        withTimeout(5_000L) { telemetry.started.await() }
        // The owner switches while the incremental refresh is blocked mid-stream.
        identity.session = ownerB
        yield()
        telemetry.release.complete(Unit)
        val outcome = withTimeout(5_000L) { job.await() }

        assertIs<ProductionRefreshOutcome.StaleSession>(outcome)
        assertEquals(ownerB, coordinator.session.value)
        // The new owner's partition saw no rows from the stale A-owner run.
        assertEquals(0, local.observe(partitionFor(ownerB)).first().observations.size)
    }

    @Test
    fun manualRefreshRequiresSignedInOwnerToo() = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val coordinator = coordinator(identity = TestOwnerIdentity(initial = null), telemetry = telemetry)
        val outcome = coordinator.onManualRefresh(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.SignedOut>(outcome)
        assertEquals(0, telemetry.reads)
    }

    @Test
    fun deniedAndroidEntryReadsIdentityOnlyAndPreservesCache(): Unit = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        telemetry.failWith = TelemetryReadFailure.AccessDenied()
        val local = FakeLocalHistory()
        val coordinator = coordinator(TestOwnerIdentity(ownerA), telemetry, local)
        val outcome = coordinator.refreshAfterAccessCheck(nowEpochMillis)
        assertIs<ProductionRefreshOutcome.RefreshFailed>(outcome)
        assertEquals("ACCESS_DENIED", outcome.code)
        assertEquals(1, telemetry.reads) // device identity, no history pages
        assertEquals("ACCESS_DENIED", local.observe(partitionFor(ownerA)).first().sync?.lastError)
    }

    @Test
    fun historicalRangeRejectsAPreviousOwnersCapturedPartition(): Unit = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val coordinator = coordinator(TestOwnerIdentity(ownerB), telemetry)
        assertFailsWith<IllegalStateException> {
            coordinator.loadHistoricalRange(partitionFor(ownerA), nowEpochMillis - 60_000L, nowEpochMillis)
        }
        assertEquals(0, telemetry.reads)
    }

    @Test
    fun fullyCachedHistoricalRangeDoesNotProbeRemoteAuthorization(): Unit = runBlocking {
        val telemetry = CountingTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val local = FakeLocalHistory()
        val coordinator = coordinator(TestOwnerIdentity(ownerA), telemetry, local)
        local.upsertCoveredRange(partitionFor(ownerA), CoveredRange(nowEpochMillis - 86_400_000L, nowEpochMillis))
        val result = coordinator.loadHistoricalRange(partitionFor(ownerA), nowEpochMillis - 3_600_000L, nowEpochMillis)
        assertTrue(result.fullyCovered)
        assertEquals(0, result.remotePagesFetched)
        assertEquals(0, telemetry.reads)
    }

    @Test
    fun cacheClearWaitsForHistoricalRangeThenRemovesItsRows(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)
        val telemetry = object : AirchiveTelemetryPort by fixture {
            override suspend fun observations(fromEpochMillis: Long, toEpochMillis: Long, pageSize: Int, cursor: String?): TelemetryPage<Observation> {
                started.complete(Unit)
                release.await()
                return fixture.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
            }
        }
        val local = FakeLocalHistory()
        val coordinator = coordinator(TestOwnerIdentity(ownerA), telemetry, local)
        val load = async { coordinator.loadHistoricalRange(partitionFor(ownerA), nowEpochMillis - 3_600_000L, nowEpochMillis) }
        withTimeout(5_000L) { started.await() }
        val clear = async { coordinator.clearCurrentOwnerPartition() }
        yield()
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        withTimeout(5_000L) { load.await(); assertTrue(clear.await()) }
        assertTrue(local.observe(partitionFor(ownerA)).first().observations.isEmpty())
        assertTrue(local.observe(partitionFor(ownerA)).first().coveredRanges.isEmpty())
    }

    /** Deterministic switchable owner identity for partition-transition tests. */
    private class TestOwnerIdentity(initial: OwnerSession?) : OwnerIdentityPort {
        private val sessionFlow = MutableStateFlow<OwnerSession?>(initial)
        var session: OwnerSession?
            get() = sessionFlow.value
            set(value) {
                sessionFlow.value = value
            }

        override val currentSession: OwnerSession? get() = sessionFlow.value
        override fun observeSession(): Flow<OwnerSession?> = sessionFlow.asStateFlow()
        override suspend fun signIn(): SignInResult =
            sessionFlow.value?.let { SignInResult.Success(it) } ?: SignInResult.Failure(SignInFailure.UNKNOWN)

        override suspend fun signOut() {
            sessionFlow.value = null
        }
    }

    /** Counts every telemetry read so "no network work" is assertable. */
    private class CountingTelemetry(private val delegate: AirchiveTelemetryPort) : AirchiveTelemetryPort by delegate {

        var reads: Int = 0
            private set

        var failWith: Throwable? = null

        override suspend fun latestObservations(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            reads += 1
            failWith?.let { throw it }
            return delegate.latestObservations(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun observations(
            fromEpochMillis: Long,
            toEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            reads += 1
            failWith?.let { throw it }
            return delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSincePersistedAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            reads += 1
            failWith?.let { throw it }
            return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSinceReconciledAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            reads += 1
            failWith?.let { throw it }
            return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> {
            reads += 1
            failWith?.let { throw it }
            return delegate.latestFinalizedTotals(limit)
        }

        override suspend fun collectorHealth(): CollectorHealth {
            reads += 1
            failWith?.let { throw it }
            return delegate.collectorHealth()
        }

        override suspend fun deviceIdentity(): DeviceIdentity {
            reads += 1
            failWith?.let { throw it }
            return delegate.deviceIdentity()
        }
    }

    /** Telemetry whose first stream read blocks until the test releases it. */
    private class BlockingTelemetry(private val delegate: AirchiveTelemetryPort) : AirchiveTelemetryPort by delegate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var blocked = false

        private suspend fun awaitGate() {
            if (!blocked) {
                blocked = true
                started.complete(Unit)
                release.await()
            }
        }

        override suspend fun changedSincePersistedAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            awaitGate()
            return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
        }

        override suspend fun changedSinceReconciledAt(
            sinceEpochMillis: Long,
            pageSize: Int,
            cursor: String?,
        ): TelemetryPage<Observation> {
            return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
        }
    }
}
