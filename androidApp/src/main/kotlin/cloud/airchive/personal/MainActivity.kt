package cloud.airchive.personal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import cloud.airchive.shared.AirchiveApp
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.data.sync.ForegroundRefreshController
import cloud.airchive.shared.data.sync.IncrementalSyncCoordinator
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeProviders
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var demoProviders: FakeProviders
    private lateinit var foregroundRefresh: ForegroundRefreshController

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
        setContent {
            AirchiveApp(
                providers = demoProviders,
                onManualRefresh = ::onManualRefresh,
            )
        }
    }

    /** Android foreground entry point: first open and every later resume share the ten-minute gate. */
    override fun onResume() {
        super.onResume()
        if (!::foregroundRefresh.isInitialized) return
        lifecycleScope.launch {
            foregroundRefresh.onForeground(System.currentTimeMillis())
        }
    }

    /** Explicit refresh action for the shared UI to invoke; it bypasses the age gate. */
    fun onManualRefresh() {
        if (!::foregroundRefresh.isInitialized) return
        lifecycleScope.launch {
            foregroundRefresh.onManualRefresh(System.currentTimeMillis())
        }
    }
}
