package cloud.airchive.personal.preview

import android.content.Context
import androidx.compose.runtime.Composable
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import cloud.airchive.shared.ProductionAirchiveApp
import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupCoordinator
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.data.sync.ProductionHistoryCoordinator
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeThinQClient
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug/test-only composition of the **actual** production shell
 * ([ProductionAirchiveApp]) over five in-memory fake ports.
 *
 * This fixture exists so the real owner/history/credential/ThinQ shell code
 * paths can be rendered and driven deterministically in isolated tests. It is
 * deliberately, unmistakably synthetic:
 *
 *  - Owner identity, Airchive telemetry, local history, the ThinQ credential
 *    store/verifier, and the ThinQ client are the shared `cloud.airchive.shared.fakes`
 *    implementations wrapped by the per-operation counters and deferred-response
 *    gates below — no live account, device, or network is ever contacted.
 *  - The shell receives an injectable [ControlledFixtureClock] through the
 *    `nowProvider` seam instead of the system clock.
 *  - The interface-detail preference uses its own dedicated file,
 *    [FIXTURE_INTERFACE_PREFERENCES_FILE], distinct from both the production
 *    `airchive_interface` and the legacy preview `airchive_preview_interface`.
 *
 * This fixture provably never constructs the production Android graph. The
 * concrete classes it never touches are recorded in
 * [neverConstructedProductionGraph]: no Firebase identity/telemetry adapter or
 * `FirebaseApp` is initialized, no Android Keystore-backed
 * `AndroidEncryptedThinQCredentialStore` (nor its
 * `noBackupFilesDir/thinq` credential files) is created, and no
 * `KtorThinQClient`/CIO engine or Room database is opened. The androidTest
 * smoke test asserts those artifacts stay absent while this shell renders.
 */
class SharedInstrumentShellFixture(context: Context) {

    /** Injectable controlled clock replacing the shell's system clock reads. */
    val clock = ControlledFixtureClock(FIXTURE_NOW_EPOCH_MILLIS)

    /** Per-operation counters for every fake-port operation the shell performs. */
    val counters = FixtureOperationCounters()

    /** Deferred-response gate for the fake owner sign-in round trip. */
    val ownerSignInGate = DeferredResponseGate()

    /** Deferred-response gate for every fake ThinQ operation. */
    val thinQGate = DeferredResponseGate()

    /** Deferred-response gate for the fake PAT verification round trip. */
    val credentialVerificationGate = DeferredResponseGate()

    /** Dedicated display preference namespace; never `airchive_interface`. */
    val interfacePreferences =
        AndroidInterfaceDetailPreferences(context, FIXTURE_INTERFACE_PREFERENCES_FILE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ownerIdentity =
        CountingOwnerIdentity(FakeOwnerIdentity(), counters, ownerSignInGate)
    private val telemetry = CountingTelemetry(FakeAirchiveTelemetry(FIXTURE_NOW_EPOCH_MILLIS), counters)
    private val localHistory = CountingLocalHistory(FakeLocalHistory(), counters)
    private val credentialStore = CountingCredentialStore(FakeThinQCredentialStore(), counters)
    private val verifier = CountingCredentialVerifier(
        FakeThinQCredentialVerifier(FakeAirchiveTelemetry.DEMO_DEVICE_ID),
        counters,
        credentialVerificationGate,
    )
    private val thinQ = CountingThinQClient(
        FakeThinQClient(FakeAirchiveTelemetry.DEMO_DEVICE_ID, FIXTURE_NOW_EPOCH_MILLIS),
        counters,
        thinQGate,
    )

    /** Production owner/history shell graph, wired to the fake ports only. */
    val coordinator = ProductionHistoryCoordinator(
        identity = ownerIdentity,
        telemetry = telemetry,
        local = localHistory,
        firebaseProjectId = FIXTURE_PROJECT_ID,
        configuredDeviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
        scope = scope,
    )

    /** Production Settings account actions over the same fake ports. */
    val accountController = ProductionAccountController(
        identity = ownerIdentity,
        local = localHistory,
        firebaseProjectId = FIXTURE_PROJECT_ID,
        configuredDeviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
        serializedAction = { action -> coordinator.runExclusive(action) },
        credentialStore = credentialStore,
    )

    private val credentialSetup = ThinQCredentialSetupCoordinator(
        credentialStore = credentialStore,
        verifier = verifier,
        configuredDeviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    private val mutableDevice = MutableStateFlow<ThinQDevice?>(null)
    private val mutableProfile = MutableStateFlow<ThinQDeviceProfile?>(null)

    val liveStateController = ThinQLiveStateController(
        thinQ = thinQ,
        configuredDeviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
        nowEpochMillis = clock::now,
        hasVerifiedProfile = { mutableProfile.value?.deviceId == FakeAirchiveTelemetry.DEMO_DEVICE_ID },
    )

    val commandController = AcCommandController(
        thinQ = thinQ,
        configuredDeviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
        nowEpochMillis = clock::now,
    )

    private val bindings = ProductionThinQBindings(
        credentialState = credentialStore.observe(),
        clientId = credentialStore.clientId,
        device = mutableDevice.asStateFlow(),
        profile = mutableProfile.asStateFlow(),
        liveStateController = liveStateController,
        commandController = commandController,
        setupCredential = ::setupCredential,
        onDeviceOpened = ::onDeviceOpened,
        refreshLiveState = { liveStateController.refresh() },
        removeCredential = ::removeCredential,
        clearCachedDeviceProfile = ::clearCachedDeviceProfile,
    )

    /** Runtime implementation types actually wired into the shell graph. */
    val constructedGraph: List<String> = listOf(
        ownerIdentity.javaClass.simpleName,
        ownerIdentity.delegateTypeName,
        telemetry.javaClass.simpleName,
        telemetry.delegateTypeName,
        localHistory.javaClass.simpleName,
        localHistory.delegateTypeName,
        credentialStore.javaClass.simpleName,
        credentialStore.delegateTypeName,
        verifier.javaClass.simpleName,
        verifier.delegateTypeName,
        thinQ.javaClass.simpleName,
        thinQ.delegateTypeName,
        coordinator.javaClass.simpleName,
        accountController.javaClass.simpleName,
        credentialSetup.javaClass.simpleName,
        liveStateController.javaClass.simpleName,
        commandController.javaClass.simpleName,
        bindings.javaClass.simpleName,
    )

    /** Production adapter/runtime types that must never be present in this graph. */
    val neverConstructedProductionGraph: List<String> = listOf(
        "FirebaseOwnerIdentityFactory",
        "FirebaseAuthOwnerIdentity",
        "FirebaseAirchiveTelemetry",
        "AndroidEncryptedThinQCredentialStore",
        "KtorThinQClient",
        "CIOEngine",
        "RoomLocalHistoryPort",
        "AirchiveDatabase",
    )

    /** Renders the real production shell over the fake ports and controlled clock. */
    @Composable
    fun Shell() {
        ProductionAirchiveApp(
            coordinator = coordinator,
            accountController = accountController,
            thinQBindings = bindings,
            interfacePreferences = interfacePreferences,
            nowProvider = clock::now,
        )
    }

    /** Releases the coordinator's session-observation scope; call from onDestroy. */
    fun close() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Fake ThinQ bindings (same shape as the production Android graph,
    // but every operation stays on the in-memory fake client).
    // ------------------------------------------------------------------

    /** Verify-before-save setup through the fake verifier and in-memory store. */
    private suspend fun setupCredential(rawToken: String, country: String): ThinQCredentialSetupResult {
        val result = credentialSetup.setup(rawToken, country)
        if (result is ThinQCredentialSetupResult.Success) {
            mutableDevice.value = result.device
            mutableProfile.value = result.profile.takeIf {
                it.deviceId == FakeAirchiveTelemetry.DEMO_DEVICE_ID
            }
        }
        return result
    }

    /**
     * Device-route entry: mirrors the production graph's shape (no ThinQ call
     * without a stored credential) but reads only the fake client.
     */
    private suspend fun onDeviceOpened() {
        if (credentialStore.readToken().isNullOrBlank()) {
            clearCachedDeviceProfile()
            return
        }
        try {
            val device = thinQ.listDevices()
                .firstOrNull { it.deviceId == FakeAirchiveTelemetry.DEMO_DEVICE_ID }
            if (device == null) {
                clearCachedDeviceProfile()
                return
            }
            if (device.online == false) {
                mutableDevice.value = device
                mutableProfile.value = null
                return
            }
            val profile = thinQ.profile(FakeAirchiveTelemetry.DEMO_DEVICE_ID)
            if (profile.deviceId != FakeAirchiveTelemetry.DEMO_DEVICE_ID) {
                clearCachedDeviceProfile()
                return
            }
            mutableDevice.value = device
            mutableProfile.value = profile
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            clearCachedDeviceProfile()
            return
        }
        liveStateController.onDeviceOpened()
    }

    private suspend fun removeCredential() {
        credentialStore.clear()
        clearCachedDeviceProfile()
    }

    private fun clearCachedDeviceProfile() {
        mutableDevice.value = null
        mutableProfile.value = null
    }

    companion object {
        /**
         * Dedicated interface-detail preference file — distinct from both the
         * production `airchive_interface` and the legacy `airchive_preview_interface`.
         */
        const val FIXTURE_INTERFACE_PREFERENCES_FILE = "airchive_shell_fixture_interface"

        /** Synthetic project id used for every fixture history partition. */
        const val FIXTURE_PROJECT_ID = "airchive-fixture-project"

        /** Fixed clock baseline so all rendered ages are deterministic. */
        const val FIXTURE_NOW_EPOCH_MILLIS = 1_750_000_000_000L
    }
}

/**
 * Injectable controlled clock for the fixture: the shell's `nowProvider` seam
 * reads only this value, so tests can freeze or advance time deterministically.
 */
class ControlledFixtureClock(startEpochMillis: Long) {
    private val current = AtomicLong(startEpochMillis)

    fun now(): Long = current.get()

    fun advanceBy(deltaMillis: Long) {
        current.addAndGet(deltaMillis)
    }
}

/** Per-operation counters for every fake-port operation the shell can perform. */
class FixtureOperationCounters {
    val ownerSignInAttempts = AtomicInteger()
    val ownerSignOuts = AtomicInteger()
    val telemetryReads = AtomicInteger()
    val deviceIdentityReads = AtomicInteger()
    val localHistoryObserves = AtomicInteger()
    val localHistoryWrites = AtomicInteger()
    val thinQDeviceListReads = AtomicInteger()
    val thinQProfileReads = AtomicInteger()
    val thinQLiveStateReads = AtomicInteger()
    val thinQCommandSubmissions = AtomicInteger()
    val credentialSaves = AtomicInteger()
    val credentialTokenReads = AtomicInteger()
    val credentialClears = AtomicInteger()
    val credentialVerifications = AtomicInteger()
}

/**
 * Deferred-response gate: while [hold] is in effect, gated fake-port
 * operations suspend until [release]; an open gate is a transparent pass-
 * through. Waiters are resumed deterministically in registration order.
 */
class DeferredResponseGate {
    private val lock = Any()
    private var open = true
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    val isOpen: Boolean get() = synchronized(lock) { open }

    fun hold() {
        synchronized(lock) { open = false }
    }

    fun release() {
        val pending = synchronized(lock) {
            open = true
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { it.complete(Unit) }
    }

    suspend fun awaitTurn() {
        val waiter = synchronized(lock) {
            if (open) return
            CompletableDeferred<Unit>().also { waiters += it }
        }
        waiter.await()
    }
}

// ---------------------------------------------------------------------------
// Counting/delegating fake-port wrappers (one per production port boundary).
// ---------------------------------------------------------------------------

private class CountingOwnerIdentity(
    private val delegate: OwnerIdentityPort,
    private val counters: FixtureOperationCounters,
    private val gate: DeferredResponseGate,
) : OwnerIdentityPort {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override val currentSession: OwnerSession? get() = delegate.currentSession
    override fun observeSession(): Flow<OwnerSession?> = delegate.observeSession()

    override suspend fun signIn(): SignInResult {
        counters.ownerSignInAttempts.incrementAndGet()
        gate.awaitTurn()
        return delegate.signIn()
    }

    override suspend fun signOut() {
        counters.ownerSignOuts.incrementAndGet()
        delegate.signOut()
    }
}

private class CountingTelemetry(
    private val delegate: AirchiveTelemetryPort,
    private val counters: FixtureOperationCounters,
) : AirchiveTelemetryPort {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        counters.telemetryReads.incrementAndGet()
        return delegate.latestObservations(sinceEpochMillis, pageSize, cursor)
    }

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        counters.telemetryReads.incrementAndGet()
        return delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
    }

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        counters.telemetryReads.incrementAndGet()
        return delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)
    }

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        counters.telemetryReads.incrementAndGet()
        return delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)
    }

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> {
        counters.telemetryReads.incrementAndGet()
        return delegate.latestFinalizedTotals(limit)
    }

    override suspend fun collectorHealth(): CollectorHealth {
        counters.telemetryReads.incrementAndGet()
        return delegate.collectorHealth()
    }

    override suspend fun deviceIdentity(): DeviceIdentity {
        counters.telemetryReads.incrementAndGet()
        counters.deviceIdentityReads.incrementAndGet()
        return delegate.deviceIdentity()
    }
}

private class CountingLocalHistory(
    private val delegate: LocalHistoryPort,
    private val counters: FixtureOperationCounters,
) : LocalHistoryPort {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override fun observe(
        partition: HistoryPartition,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
    ): Flow<HistorySnapshot> {
        counters.localHistoryObserves.incrementAndGet()
        return delegate.observe(partition, fromEpochMillis, toEpochMillis)
    }

    override suspend fun upsertObservations(partition: HistoryPartition, observations: List<Observation>) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.upsertObservations(partition, observations)
    }

    override suspend fun upsertObservationsAndRecordSync(
        partition: HistoryPartition,
        observations: List<Observation>,
        metadata: SyncMetadata,
    ) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.upsertObservationsAndRecordSync(partition, observations, metadata)
    }

    override suspend fun upsertDailyTotals(partition: HistoryPartition, totals: List<DailyTotal>) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.upsertDailyTotals(partition, totals)
    }

    override suspend fun upsertCollectorHealth(partition: HistoryPartition, health: CollectorHealth) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.upsertCollectorHealth(partition, health)
    }

    override suspend fun upsertCoveredRange(partition: HistoryPartition, range: CoveredRange) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.upsertCoveredRange(partition, range)
    }

    override suspend fun recordSync(partition: HistoryPartition, metadata: SyncMetadata) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.recordSync(partition, metadata)
    }

    override suspend fun clear(partition: HistoryPartition) {
        counters.localHistoryWrites.incrementAndGet()
        delegate.clear(partition)
    }
}

private class CountingThinQClient(
    private val delegate: ThinQPort,
    private val counters: FixtureOperationCounters,
    private val gate: DeferredResponseGate,
) : ThinQPort {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override suspend fun listDevices(): List<ThinQDevice> {
        counters.thinQDeviceListReads.incrementAndGet()
        gate.awaitTurn()
        return delegate.listDevices()
    }

    override suspend fun profile(deviceId: String): ThinQDeviceProfile {
        counters.thinQProfileReads.incrementAndGet()
        gate.awaitTurn()
        return delegate.profile(deviceId)
    }

    override suspend fun liveState(deviceId: String): ThinQLiveState {
        counters.thinQLiveStateReads.incrementAndGet()
        gate.awaitTurn()
        return delegate.liveState(deviceId)
    }

    override suspend fun setState(command: AcCommand): CommandOutcome {
        counters.thinQCommandSubmissions.incrementAndGet()
        gate.awaitTurn()
        return delegate.setState(command)
    }
}

private class CountingCredentialStore(
    private val delegate: ThinQCredentialStore,
    private val counters: FixtureOperationCounters,
) : ThinQCredentialStore {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override val clientId: String get() = delegate.clientId
    override fun observe(): Flow<ThinQCredentialState> = delegate.observe()

    override suspend fun save(rawToken: String, accountCountry: String): SaveThinQResult {
        counters.credentialSaves.incrementAndGet()
        return delegate.save(rawToken, accountCountry)
    }

    override suspend fun readToken(): String? {
        counters.credentialTokenReads.incrementAndGet()
        return delegate.readToken()
    }

    override suspend fun clear() {
        counters.credentialClears.incrementAndGet()
        delegate.clear()
    }
}

private class CountingCredentialVerifier(
    private val delegate: ThinQCredentialVerifier,
    private val counters: FixtureOperationCounters,
    private val gate: DeferredResponseGate,
) : ThinQCredentialVerifier {
    val delegateTypeName: String get() = delegate.javaClass.simpleName

    override suspend fun verify(request: ThinQCredentialVerificationRequest): ThinQVerificationResult {
        counters.credentialVerifications.incrementAndGet()
        gate.awaitTurn()
        return delegate.verify(request)
    }
}
