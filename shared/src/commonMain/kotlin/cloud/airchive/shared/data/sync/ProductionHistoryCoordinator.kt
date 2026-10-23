package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production owner-history graph for the Android app (replaces the demo
 * [cloud.airchive.shared.fakes.FakeProviders] composition in MainActivity).
 *
 * This is the single wiring point for the real history-only flow:
 *
 *  - **Exact partition.** Every read, sync, and local write is keyed by
 *    `(firebaseProjectId, current owner UID, configuredDeviceId)` — computed
 *    from the live [OwnerIdentityPort] session (platform adapter on Android,
 *    deterministic fake in tests) and never from a demo fixture.
 *  - **Cache-first and durable.** The UI observes [local] directly, so a Room-
 *    backed cache renders before any network work and survives restart/offline
 *    for the same owner.
 *  - **Open/resume policy.** [onForeground] bootstraps the initial 24-hour +
 *    up-to-30 daily-total window once (via [FirstLoadSyncCoordinator]) and then
 *    delegates to the ten-minute [ForegroundRefreshController] gate.
 *    [onManualRefresh] always attempts work and bypasses the age gate.
 *  - **No ThinQ coupling.** History loading never requires a ThinQ credential;
 *    the ac command stack is intentionally not constructed here (all ThinQ
 *    control setup is deferred in production).
 *  - **Stale-result suppression.** Every asynchronous refresh captures the
 *    current session revision and re-checks it after the run. If the owner
 *    signed out or switched accounts mid-run, the result is dropped
 *    ([ProductionRefreshOutcome.StaleSession]) instead of surfacing as a
 *    success for the wrong session. Because refreshes and destructive
 *    account/cache actions share the same [operationMutex], a clear/sign-out
 *    can never interleave with an in-flight refresh that would re-populate a
 *    partition that is being removed.
 *
 * No fake/demo fallback exists in this class: with a missing or denied
 * configuration the caller simply does not construct it (see MainActivity) and
 * renders the actionable configuration message instead.
 *
 * @param scope coroutine scope used only to observe identity session changes;
 *   the owner/activity cancels it when the graph is torn down.
 */
class ProductionHistoryCoordinator(
    private val identity: OwnerIdentityPort,
    val telemetry: AirchiveTelemetryPort,
    val local: LocalHistoryPort,
    val firebaseProjectId: String,
    val configuredDeviceId: String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    /** Google session + device-read authorization check for the signed-in owner. */
    val access: OwnerAccessCoordinator = OwnerAccessCoordinator(identity, telemetry)

    /** Current owner session, updated whenever the identity port reports a change. */
    private val _session = MutableStateFlow<OwnerSession?>(identity.currentSession)
    val session: StateFlow<OwnerSession?> = _session.asStateFlow()

    /** True while any foreground/manual/bootstrap sync run is in flight. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Serializes every sync run with every confirmed account/cache mutation, so
     * a clear/sign-out can never race an in-flight refresh. Refresh runs hold
     * this mutex for their whole duration; the account controller runs its
     * destructive transitions through the same mutex.
     */
    private val operationMutex = Mutex()

    private val runtimeMutex = Mutex()
    private val runtimes = mutableMapOf<HistoryPartition, PartitionRuntime>()

    /** Monotonic counter of identity-session emissions used to detect stale runs. */
    private var sessionRevision = 0L
    private var activeRefreshes = 0

    init {
        scope.launch {
            identity.observeSession().collect { updated ->
                sessionRevision += 1
                _session.value = updated
            }
        }
    }

    /** The exact cache key for [session]: fixed project + UID + configured device. */
    fun partitionFor(session: OwnerSession): HistoryPartition =
        HistoryPartition(firebaseProjectId, session.uid, configuredDeviceId)

    /** True once a durable full 24-hour initial-window range is on record. */
    fun hasInitialWindowCoverage(coveredRanges: List<CoveredRange>): Boolean =
        coveredRanges.any { it.toEpochMillis - it.fromEpochMillis >= FirstLoadSyncCoordinator.INITIAL_WINDOW_MILLIS }

    /** Empty cached projection used by the UI before any owner session exists. */
    fun emptySnapshot(): HistorySnapshot = HistorySnapshot(
        partition = HistoryPartition(firebaseProjectId, ownerUid = "", deviceId = configuredDeviceId),
        observations = emptyList(),
        dailyTotals = emptyList(),
        coveredRanges = emptyList(),
        sync = SyncMetadata(),
    )

    /**
     * App-open/resume entry point. Bootstraps the initial window when nothing
     * is covered yet (deferring a recent failed attempt for the same ten-minute
     * cadence as the incremental gate), otherwise runs the incremental
     * [ForegroundRefreshController] gate.
     */
    suspend fun onForeground(nowEpochMillis: Long): ProductionRefreshOutcome = operationMutex.withLock {
        withRefreshTracking { refresh(nowEpochMillis, manual = false) }
    }

    /** Explicit user refresh; bypasses every age gate and always performs work. */
    suspend fun onManualRefresh(nowEpochMillis: Long): ProductionRefreshOutcome = operationMutex.withLock {
        withRefreshTracking { refresh(nowEpochMillis, manual = true) }
    }

    /** Enrollment retry may start a failed bootstrap, but must not force an
     * incremental read of an already-fresh cache just because access was checked. */
    suspend fun onAccessAuthorized(nowEpochMillis: Long): ProductionRefreshOutcome = operationMutex.withLock {
        withRefreshTracking { refresh(nowEpochMillis, manual = false, retryBootstrap = true) }
    }

    /** Android entry points check enrollment before issuing history queries. */
    suspend fun refreshAfterAccessCheck(nowEpochMillis: Long, manual: Boolean = false): ProductionRefreshOutcome = operationMutex.withLock {
        withRefreshTracking { checkedRefresh(nowEpochMillis, manual) }
    }

    private suspend fun checkedRefresh(nowEpochMillis: Long, manual: Boolean): ProductionRefreshOutcome {
        val session = identity.currentSession ?: return ProductionRefreshOutcome.SignedOut
        return when (val checked = access.restore()) {
            is OwnerAccessState.Authorized -> {
                if (identity.currentSession?.uid != checked.session.uid) return ProductionRefreshOutcome.StaleSession
                refresh(nowEpochMillis, manual)
            }
            else -> {
                if (identity.currentSession?.uid != session.uid) return ProductionRefreshOutcome.StaleSession
                val code = when (checked) {
                    is OwnerAccessState.AccessPending -> checked.safeErrorCode
                    is OwnerAccessState.AccessCheckFailed -> checked.safeErrorCode
                    else -> "ACCESS_DENIED"
                }
                val partition = partitionFor(session)
                val previous = local.observe(partition).first().sync ?: SyncMetadata()
                local.recordSync(partition, previous.copy(lastError = code, lastErrorEpochMillis = nowEpochMillis))
                ProductionRefreshOutcome.RefreshFailed(code)
            }
        }
    }

    /** Selected older ranges share the clear/sign-out lock with regular sync. */
    suspend fun loadHistoricalRange(
        partition: HistoryPartition,
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ): HistoricalRangeResult = operationMutex.withLock {
        val session = identity.currentSession
        check(session != null && partitionFor(session) == partition) { "History owner changed; select the range again." }
        val coverage = local.observe(partition).first().coveredRanges
        if (HistoricalRangeLoader.uncoveredRanges(fromEpochMillis, toEpochMillis, coverage).isEmpty()) {
            // Revisiting a fully cached range needs neither history queries nor
            // an authorization probe; no remote data will be downloaded.
            return HistoricalRangeLoader(telemetry, local, partition).loadHistoricalRange(fromEpochMillis, toEpochMillis)
        }
        val checked = access.restore()
        if (checked !is OwnerAccessState.Authorized) throw TelemetryReadFailure.AccessDenied()
        check(identity.currentSession?.uid == session.uid) { "History owner changed during access verification." }
        HistoricalRangeLoader(telemetry, local, partition).loadHistoricalRange(fromEpochMillis, toEpochMillis)
    }

    /**
     * Runs [block] under the same lock that serializes sync runs. The confirmed
     * account/cache transitions use this so a partition is never cleared or
     * re-created while a refresh for it is in flight.
     */
    suspend fun <T> runExclusive(block: suspend () -> T): T = operationMutex.withLock { block() }

    /** Removes the current owner's exact partition (rows + sync markers). */
    suspend fun clearCurrentOwnerPartition(): Boolean = operationMutex.withLock {
        val session = _session.value ?: return false
        val partition = partitionFor(session)
        runtimeMutex.withLock { runtimes.remove(partition) }
        try {
            local.clear(partition)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun <T> withRefreshTracking(block: suspend () -> T): T {
        activeRefreshes += 1
        _isRefreshing.value = true
        try {
            return block()
        } finally {
            activeRefreshes = (activeRefreshes - 1).coerceAtLeast(0)
            _isRefreshing.value = activeRefreshes > 0
        }
    }

    private suspend fun refresh(nowEpochMillis: Long, manual: Boolean, retryBootstrap: Boolean = false): ProductionRefreshOutcome {
        val session = identity.currentSession ?: return ProductionRefreshOutcome.SignedOut
        val runRevision = sessionRevision
        val partition = partitionFor(session)
        val runtime = runtimeFor(partition)
        val snapshot = local.observe(partition).first()

        val outcome = if (hasInitialWindowCoverage(snapshot.coveredRanges)) {
            runIncremental(runtime, nowEpochMillis, manual)
        } else {
            val bootstrapDeferred = !manual && !retryBootstrap && bootstrapDeferredRecently(snapshot, nowEpochMillis)
            if (bootstrapDeferred) {
                ProductionRefreshOutcome.DeferredAfterRecentFailure(snapshot.sync?.lastError.orEmpty())
            } else {
                runBootstrap(runtime, nowEpochMillis)
            }
        }

        // A session change during the run is detected afterwards: the revision
        // moved or the live identity no longer matches the session we started
        // with. The run's outcome is then dropped so it can never be reported
        // for the wrong owner.
        return if (sessionRevision == runRevision && identity.currentSession?.uid == session.uid) {
            outcome
        } else {
            ProductionRefreshOutcome.StaleSession
        }
    }

    private fun bootstrapDeferredRecently(snapshot: HistorySnapshot, nowEpochMillis: Long): Boolean {
        val error = snapshot.sync?.lastError ?: return false
        val errorAt = snapshot.sync?.lastErrorEpochMillis ?: return false
        return nowEpochMillis - errorAt < ForegroundRefreshController.MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS
    }

    private suspend fun runBootstrap(runtime: PartitionRuntime, nowEpochMillis: Long): ProductionRefreshOutcome = try {
        ProductionRefreshOutcome.Bootstrapped(runtime.firstLoad.syncFirstLoad(nowEpochMillis))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        ProductionRefreshOutcome.RefreshFailed(TelemetryReadFailure.classify(failure).code)
    }

    private suspend fun runIncremental(
        runtime: PartitionRuntime,
        nowEpochMillis: Long,
        manual: Boolean,
    ): ProductionRefreshOutcome = try {
        if (manual) {
            ProductionRefreshOutcome.Refreshed(runtime.foreground.onManualRefresh(nowEpochMillis))
        } else {
            when (val outcome = runtime.foreground.onForeground(nowEpochMillis)) {
                is ForegroundRefreshOutcome.Refreshed -> ProductionRefreshOutcome.Refreshed(outcome.result)
                is ForegroundRefreshOutcome.SkippedFresh ->
                    ProductionRefreshOutcome.Fresh(outcome.lastSuccessfulSyncEpochMillis)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        ProductionRefreshOutcome.RefreshFailed(TelemetryReadFailure.classify(failure).code)
    }

    private suspend fun runtimeFor(partition: HistoryPartition): PartitionRuntime = runtimeMutex.withLock {
        runtimes.getOrPut(partition) {
            val incremental = IncrementalSyncCoordinator(telemetry, local, partition)
            PartitionRuntime(
                foreground = ForegroundRefreshController(local, partition, incremental::syncIncremental),
                firstLoad = FirstLoadSyncCoordinator(telemetry, local, partition),
            )
        }
    }

    private class PartitionRuntime(
        val foreground: ForegroundRefreshController,
        val firstLoad: FirstLoadSyncCoordinator,
    )
}

/**
 * Result of one production sync entry point. [SignedOut] means no owner session
 * exists, [StaleSession] means the owner changed while the run was in flight
 * and the outcome was suppressed, [Refreshed]/[Bootstrapped] describe successful
 * incremental/initial runs, [Fresh] is the skip-a-fresh-foreground outcome,
 * [DeferredAfterRecentFailure] intentionally did no network (a bootstrap
 * attempt failed less than ten minutes ago), and [RefreshFailed] carries the
 * stable [TelemetryReadFailure.code] the coordinator already persisted.
 */
sealed interface ProductionRefreshOutcome {
    data object SignedOut : ProductionRefreshOutcome
    data object StaleSession : ProductionRefreshOutcome
    data class Fresh(val lastSuccessfulSyncEpochMillis: Long) : ProductionRefreshOutcome
    data class Refreshed(val result: IncrementalSyncResult) : ProductionRefreshOutcome
    data class Bootstrapped(val result: FirstLoadResult) : ProductionRefreshOutcome
    data class DeferredAfterRecentFailure(val code: String) : ProductionRefreshOutcome
    data class RefreshFailed(val code: String) : ProductionRefreshOutcome
}
