package cloud.airchive.personal

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import cloud.airchive.personal.data.auth.FirebaseOwnerIdentityFactory
import cloud.airchive.personal.data.auth.OwnerAuthConfiguration
import cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetry
import cloud.airchive.personal.data.firebase.FirebaseOwnerFirestore
import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.ProductionAirchiveApp
import cloud.airchive.shared.ProductionConfigBlockedScreen
import cloud.airchive.shared.data.local.AirchiveDatabase
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.OwnerTransition
import cloud.airchive.shared.data.sync.ProductionHistoryCoordinator
import cloud.airchive.shared.data.sync.ProductionRefreshOutcome
import cloud.airchive.shared.data.sync.ForegroundRefreshBackoff
import cloud.airchive.shared.data.update.AppUpdateBindings
import cloud.airchive.shared.data.update.AppUpdateResumeCheckScheduler
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android entry point for the production history flow.
 *
 * [onCreate] builds the real graph — Firebase owner identity, the read-only
 * Firebase telemetry adapter, the durable Room history projection, and the
 * [ProductionHistoryCoordinator] that owns the exact (project, owner UID,
 * configured device) partition and the foreground/manual sync policy. It never
 * instantiates [cloud.airchive.shared.fakes.FakeProviders]: the demo graph is
 * used only by tests/previews. The iOS scaffold is explicit about its pending
 * native adapters. When the nonsecret Firebase client options are
 * missing, the app renders an actionable configuration screen instead of any
 * fake/demo fallback.
 *
 * [onResume] starts foreground refresh; the shared UI's
 * manual-refresh callback bypasses the gate. A change of the signed-in owner
 * cancels any in-flight refresh so a stale async run for a previous session can
 * never surface for the new one (the coordinator additionally drops its result).
 */
class MainActivity : ComponentActivity() {

    private var coordinator: ProductionHistoryCoordinator? = null
    private var accountController: ProductionAccountController? = null
    private var airchiveApplication: AirchiveApplication? = null
    private var thinQBindings: ProductionThinQBindings? = null
    private var appUpdateBindings: AppUpdateBindings? = null
    private var appUpdateResumeCheckScheduler: AppUpdateResumeCheckScheduler? = null
    private var controllerScope: CoroutineScope? = null
    private var activeRefreshJob: Job? = null
    private var foregroundHistoryJob: Job? = null
    private var foregroundDeviceJob: Job? = null
    private var foregroundAppUpdateJob: Job? = null
    private var historyScheduleKey: String? = null
    private var historyBackoff = ForegroundRefreshBackoff()
    private var historyNextAt = 0L
    private var deviceScheduleKey: cloud.airchive.shared.data.thinq.ThinQContextKey? = null
    private var deviceBackoff = ForegroundRefreshBackoff()
    private var deviceNextAt = 0L
    private var ownerAuthConfigurationMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val processGraphOwner = application as AirchiveApplication
        airchiveApplication = processGraphOwner
        appUpdateBindings = processGraphOwner.appUpdateBindings()
        val configuration = try {
            FirebaseOwnerIdentityFactory.fromBuildConfig(this)
        } catch (_: Exception) {
            OwnerAuthConfiguration.MissingOptions(setOf("Firebase client initialization"))
        }
        when (configuration) {
            is OwnerAuthConfiguration.Ready -> buildProductionGraph(configuration)

            is OwnerAuthConfiguration.MissingOptions -> {
                ownerAuthConfigurationMessage = configuration.actionableMessage
            }
        }

        val interfacePreferences = processGraphOwner.installationPreferences
        setContent {
            val activeCoordinator = coordinator
            val activeAccountController = accountController
            if (activeCoordinator != null && activeAccountController != null) {
                val graph = airchiveApplication?.thinQGraph?.collectAsState()?.value
                ProductionAirchiveApp(
                    coordinator = activeCoordinator,
                    accountController = activeAccountController,
                    onManualRefresh = ::onManualRefresh,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    thinQBindings = graph?.bindings() ?: thinQBindings,
                    interfacePreferences = interfacePreferences,
                    appUpdateBindings = appUpdateBindings,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                )
            } else {
                ProductionConfigBlockedScreen(
                    ownerAuthConfigurationMessage ?: "Firebase owner sign-in is not configured.",
                )
            }
        }
    }

    /** Timers live only while resumed; context changes cancel their current work. */
    override fun onResume() {
        super.onResume()
        appUpdateBindings?.actions?.recheckInstallPermission()
        startAppUpdateResumeCheck()
        val activeCoordinator = coordinator ?: return
        val app = airchiveApplication ?: return
        foregroundHistoryJob = lifecycleScope.launch {
            activeCoordinator.session.collectLatest { session ->
                if (session == null) return@collectLatest
                if (historyScheduleKey != session.uid) {
                    historyScheduleKey = session.uid
                    historyBackoff = ForegroundRefreshBackoff()
                    historyNextAt = 0L
                }
                while (true) {
                    delay((historyNextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    val failed = try {
                        when (activeCoordinator.refreshAfterAccessCheck(System.currentTimeMillis())) {
                            is ProductionRefreshOutcome.RefreshFailed,
                            is ProductionRefreshOutcome.DeferredAfterRecentFailure -> true
                            else -> false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { true }
                    historyNextAt = SystemClock.elapsedRealtime() + historyBackoff.nextDelay(failed)
                }
            }
        }
        foregroundDeviceJob = lifecycleScope.launch {
            app.thinQGraph.collectLatest { graph ->
                graph ?: return@collectLatest
                graph.coordinator.contextKey.collectLatest { context ->
                    if (context.ownerUid == null) return@collectLatest
                    if (deviceScheduleKey != context) {
                        deviceScheduleKey = context
                        deviceBackoff = ForegroundRefreshBackoff()
                        // Home entry performs the first read; do not duplicate it.
                        deviceNextAt = SystemClock.elapsedRealtime() + ForegroundRefreshBackoff.BASE_MILLIS
                    }
                    while (true) {
                        delay((deviceNextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        var failed = false
                        val result = try {
                            graph.coordinator.refreshScheduledState(context)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { failed = true; null }
                        failed = failed || result is ThinQLiveRefreshState.Stale || result is ThinQLiveRefreshState.Unavailable
                        deviceNextAt = SystemClock.elapsedRealtime() + deviceBackoff.nextDelay(failed)
                    }
                }
            }
        }
    }

    override fun onPause() {
        foregroundAppUpdateJob?.cancel()
        foregroundAppUpdateJob = null
        foregroundHistoryJob?.cancel()
        foregroundHistoryJob = null
        foregroundDeviceJob?.cancel()
        foregroundDeviceJob = null
        super.onPause()
    }

    private fun startAppUpdateResumeCheck() {
        val bindings = appUpdateBindings ?: return
        val scheduler = appUpdateResumeCheckScheduler ?: AppUpdateResumeCheckScheduler(
            preferences = bindings.preferences,
            check = bindings.service::check,
            nowEpochMillis = System::currentTimeMillis,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        ).also { appUpdateResumeCheckScheduler = it }
        foregroundAppUpdateJob?.cancel()
        foregroundAppUpdateJob = lifecycleScope.launch {
            while (true) {
                val waitMillis = scheduler.delayUntilNextAttemptMillis() ?: break
                if (waitMillis > 0L) delay(waitMillis)
                val succeeded = scheduler.attemptIfDue() ?: break
                if (succeeded) break
            }
        }
    }

    /** Explicit refresh action for the shared UI to invoke; it bypasses the age gate. */
    fun onManualRefresh() {
        val activeCoordinator = coordinator ?: return
        activeRefreshJob?.cancel()
        activeRefreshJob = lifecycleScope.launch {
            try {
                activeCoordinator.refreshAfterAccessCheck(System.currentTimeMillis(), manual = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The cache-first UI observes the persisted failure state.
            }
        }
    }

    override fun onDestroy() {
        activeRefreshJob?.cancel()
        activeRefreshJob = null
        controllerScope?.cancel()
        controllerScope = null
        thinQBindings = null
        super.onDestroy()
    }

    private fun buildProductionGraph(configuration: OwnerAuthConfiguration.Ready) {
        val processGraphOwner = application as AirchiveApplication
        airchiveApplication = processGraphOwner
        // Reuse one database per process rather than opening a new connection
        // pool on every Activity recreation. No Activity/Context is retained.
        val database = synchronized(MainActivity::class.java) {
            historyDatabase ?: openAirchiveDatabase(
                name = getDatabasePath(AirchiveDatabase.DATABASE_NAME).absolutePath,
                migrations = AirchiveDatabase.MIGRATIONS.asList(),
            ).also { historyDatabase = it }
        }
        val local = RoomLocalHistoryPort(database)
        val auth = configuration.identity.firebaseAuth
        val ownerFirestore = synchronized(MainActivity::class.java) {
            historyFirestore ?: FirebaseOwnerFirestore(auth.app).also { historyFirestore = it }
        }
        val telemetry = FirebaseAirchiveTelemetry.fromBuildConfig(
            auth = auth,
            firestoreProvider = ownerFirestore::get,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // The graph and its command owner survive Activity recreation. A
        // successful OwnerTransition is the only path that replaces it.
        val productionThinQGraph = processGraphOwner.getOrCreateThinQGraph(
            ownerUid = configuration.identity.currentSession?.uid,
        )
        val productionCoordinator = ProductionHistoryCoordinator(
            identity = configuration.identity,
            telemetry = telemetry,
            local = local,
            firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            scope = scope,
        )

        // The account controller's destructive transitions run under the same
        // lock as refresh runs, so a clear/sign-out never races an in-flight
        // refresh that could re-populate the partition being removed.
        var explicitlyTransitioningOwnerUid: String? = null
        val productionAccountController = ProductionAccountController(
            identity = configuration.identity,
            local = local,
            firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            serializedAction = { action -> productionCoordinator.runExclusive(action) },
            credentialStore = productionThinQGraph.credentialStore,
            beforeOwnerTransition = {
                explicitlyTransitioningOwnerUid = configuration.identity.currentSession?.uid
                // Invalidate before the account controller clears the PAT or
                // changes Firebase identity. The session observer below remains
                // the backstop for external identity changes.
                processGraphOwner.thinQGraph.value?.onOwnerChanged(null, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)
            },
            afterOwnerTransition = { session ->
                val ownerBeforeAction = explicitlyTransitioningOwnerUid
                explicitlyTransitioningOwnerUid = null
                // If identity did not change (for example, Firebase sign-out
                // failed after local cleanup), restore that now-empty context.
                // A new UID is rebound only by OwnerTransition after Firestore
                // and the previous owner's partition have also been cleared.
                if (session != null && session.uid == ownerBeforeAction) {
                    processGraphOwner.thinQGraph.value?.onOwnerChanged(session.uid, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)
                }
            },
        )

        var transitionGraph = productionThinQGraph
        val ownerTransition = OwnerTransition(
            local = local,
            credentialStore = productionThinQGraph.credentialStore,
            thinQ = transitionGraph.coordinator,
            documentCache = ownerFirestore,
            firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            currentSession = { configuration.identity.currentSession },
            serializedAction = { action -> productionCoordinator.runExclusive(action) },
            reportOutcome = productionAccountController::reportOwnerTransitionOutcome,
        )

        // Every identity change reaches the same serialized cleanup path,
        // including Firebase sign-out/session replacement outside Settings.
        lifecycleScope.launch {
            var previous = productionCoordinator.session.value
            productionCoordinator.session.collect { next ->
                if (previous?.uid != next?.uid) {
                    activeRefreshJob?.cancel()
                    activeRefreshJob = null
                    val failure = ownerTransition.onSessionChanged(previous, next)
                    if (failure == null && previous?.uid != next?.uid && previous != null) {
                        processGraphOwner.replaceThinQGraph(transitionGraph, next?.uid)?.let { replacement ->
                            transitionGraph = replacement
                            ownerTransition.bindThinQContextCoordinator(replacement.coordinator)
                        }
                    }
                }
                previous = next
            }
        }

        coordinator = productionCoordinator
        accountController = productionAccountController
        thinQBindings = productionThinQGraph.bindings()
        controllerScope = scope
    }

    private companion object {
        var historyDatabase: AirchiveDatabase? = null
        var historyFirestore: FirebaseOwnerFirestore? = null
    }
}
