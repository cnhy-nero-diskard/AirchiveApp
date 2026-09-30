package cloud.airchive.personal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import cloud.airchive.personal.data.auth.FirebaseOwnerIdentityFactory
import cloud.airchive.personal.data.auth.OwnerAuthConfiguration
import cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetry
import cloud.airchive.shared.AirchiveApp
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.data.sync.ForegroundRefreshController
import cloud.airchive.shared.data.sync.IncrementalSyncCoordinator
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeProviders
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var demoProviders: FakeProviders
    private lateinit var foregroundRefresh: ForegroundRefreshController
    private var ownerAccessController: OwnerAccessCoordinator? = null
    private var ownerAuthConfigurationMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openedAt = System.currentTimeMillis()
        demoProviders = FakeProviders(nowEpochMillis = openedAt)
        val partition = HistoryPartition(
            firebaseProjectId = "demo-firebase-project",
            ownerUid = FakeOwnerIdentity.DEFAULT_SESSION.uid,
            deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
        )
        val incrementalSync = IncrementalSyncCoordinator(
            telemetry = demoProviders.telemetry,
            local = demoProviders.localHistory,
            partition = partition,
        )
        foregroundRefresh = ForegroundRefreshController(
            local = demoProviders.localHistory,
            partition = partition,
            refresh = incrementalSync::syncIncremental,
        )

        val configuration = try {
            FirebaseOwnerIdentityFactory.fromBuildConfig(this)
        } catch (_: Exception) {
            OwnerAuthConfiguration.MissingOptions(setOf("Firebase client initialization"))
        }
        when (configuration) {
            is OwnerAuthConfiguration.Ready -> {
                val auth = configuration.identity.firebaseAuth
                val telemetry = FirebaseAirchiveTelemetry.fromBuildConfig(
                    auth = auth,
                    firestore = FirebaseFirestore.getInstance(auth.app),
                )
                ownerAccessController = OwnerAccessCoordinator(configuration.identity, telemetry)
            }

            is OwnerAuthConfiguration.MissingOptions -> {
                ownerAuthConfigurationMessage = configuration.actionableMessage
            }
        }

        setContent {
            AirchiveApp(
                providers = demoProviders,
                ownerAccessController = ownerAccessController,
                ownerAuthConfigurationMessage = ownerAuthConfigurationMessage,
                onManualRefresh = ::onManualRefresh,
            )
        }
    }

    /** Android foreground entry point: first open and every later resume share the ten-minute gate. */
    override fun onResume() {
        super.onResume()
        if (!::foregroundRefresh.isInitialized) return
        lifecycleScope.launch {
            try {
                foregroundRefresh.onForeground(System.currentTimeMillis())
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
        if (!::foregroundRefresh.isInitialized) return
        lifecycleScope.launch {
            try {
                foregroundRefresh.onManualRefresh(System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The cache-first UI observes the persisted failure state.
            }
        }
    }
}
