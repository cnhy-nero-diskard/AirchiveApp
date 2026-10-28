package cloud.airchive.personal

import android.os.Bundle
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import cloud.airchive.personal.data.auth.FirebaseOwnerIdentityFactory
import cloud.airchive.personal.data.auth.OwnerAuthConfiguration
import cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetry
import cloud.airchive.personal.data.thinq.AndroidProductionThinQGraph
import cloud.airchive.shared.ProductionThinQBindings
import cloud.airchive.shared.ProductionAirchiveApp
import cloud.airchive.shared.ProductionConfigBlockedScreen
import cloud.airchive.shared.data.local.AirchiveDatabase
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.sync.ProductionHistoryCoordinator
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
 * [onResume] runs the ten-minute-gated foreground refresh; the shared UI's
 * manual-refresh callback bypasses the gate. A change of the signed-in owner
 * cancels any in-flight refresh so a stale async run for a previous session can
 * never surface for the new one (the coordinator additionally drops its result).
 */
class MainActivity : ComponentActivity() {

    private var coordinator: ProductionHistoryCoordinator? = null
    private var accountController: ProductionAccountController? = null
    private var thinQGraph: AndroidProductionThinQGraph? = null
    private var thinQBindings: ProductionThinQBindings? = null
    private var controllerScope: CoroutineScope? = null
    private var activeRefreshJob: Job? = null
    private var ownerAuthConfigurationMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

        val interfacePreferences = AndroidInterfaceDetailPreferences(applicationContext)
        setContent {
            val activeCoordinator = coordinator
            val activeAccountController = accountController
            if (activeCoordinator != null && activeAccountController != null) {
                ProductionAirchiveApp(
                    coordinator = activeCoordinator,
                    accountController = activeAccountController,
                    onManualRefresh = ::onManualRefresh,
                    ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                    thinQBindings = thinQBindings,
                    interfacePreferences = interfacePreferences,
                )
            } else {
                ProductionConfigBlockedScreen(
                    ownerAuthConfigurationMessage ?: "Firebase owner sign-in is not configured.",
                )
            }
        }
    }

    /** Android foreground entry point: first open and every later resume share the ten-minute gate. */
    override fun onResume() {
        super.onResume()
        val activeCoordinator = coordinator ?: return
        activeRefreshJob?.cancel()
        activeRefreshJob = lifecycleScope.launch {
            try {
                activeCoordinator.refreshAfterAccessCheck(System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The coordinator persists a safe error code with the cached
                // rows/watermarks; the screen renders that state without crash.
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
        thinQGraph?.close()
        thinQGraph = null
        thinQBindings = null
        super.onDestroy()
    }

    private fun buildProductionGraph(configuration: OwnerAuthConfiguration.Ready) {
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
        val telemetry = FirebaseAirchiveTelemetry.fromBuildConfig(
            auth = auth,
            firestore = FirebaseFirestore.getInstance(auth.app),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val productionThinQGraph = AndroidProductionThinQGraph(
            context = applicationContext,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            // Lifecycle partition (task 2.1): owner/project/device + generation.
            ownerUid = configuration.identity.currentSession?.uid,
            projectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
        )
        val productionCoordinator = ProductionHistoryCoordinator(
            identity = configuration.identity,
            telemetry = telemetry,
            local = local,
            firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            scope = scope,
        )

        // Cancel any in-flight refresh the moment the owner session changes
        // (sign-out, account switch, or Firebase session restore settle) and
        // invalidate the ThinQ lifecycle context with it, so the previous
        // owner's readings/profile/command feedback can never reappear
        // (OpenSpec deliver-shared-instrument-ui task 2.1).
        lifecycleScope.launch {
            productionCoordinator.session.collect {
                activeRefreshJob?.cancel()
                activeRefreshJob = null
                productionThinQGraph.onOwnerChanged(it?.uid, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)
            }
        }

        // The account controller's destructive transitions run under the same
        // lock as refresh runs, so a clear/sign-out never races an in-flight
        // refresh that could re-populate the partition being removed.
        val productionAccountController = ProductionAccountController(
            identity = configuration.identity,
            local = local,
            firebaseProjectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
            configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
            serializedAction = { action -> productionCoordinator.runExclusive(action) },
            credentialStore = productionThinQGraph.credentialStore,
            beforeOwnerTransition = {
                // Invalidate before the account controller clears the PAT or
                // changes Firebase identity. The session observer below remains
                // the backstop for external identity changes.
                productionThinQGraph.onOwnerChanged(null, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)
            },
            afterOwnerTransition = { session ->
                // If cleanup/sign-in fails and the old owner remains active,
                // re-bind only after the prior context has been cleared; the
                // next Home entry must perform a fresh matching read.
                productionThinQGraph.onOwnerChanged(session?.uid, BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID)
            },
        )

        coordinator = productionCoordinator
        accountController = productionAccountController
        thinQGraph = productionThinQGraph
        thinQBindings = productionThinQGraph.bindings()
        controllerScope = scope
    }

    private companion object {
        var historyDatabase: AirchiveDatabase? = null
    }
}
