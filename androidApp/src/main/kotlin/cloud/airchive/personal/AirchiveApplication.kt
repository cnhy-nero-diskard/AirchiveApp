package cloud.airchive.personal

import android.app.Application
import android.content.Intent
import cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStore
import cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences
import cloud.airchive.personal.data.thinq.AndroidProductionThinQGraph
import cloud.airchive.shared.data.update.AppUpdateBindings
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process owner for command work that must survive Activity recreation. */
class AirchiveApplication : Application() {

    val installationPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidInterfaceDetailPreferences(applicationContext)
    }

    private val credentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidEncryptedThinQCredentialStore(applicationContext)
    }

    private val _thinQGraph = MutableStateFlow<AndroidProductionThinQGraph?>(null)
    val thinQGraph: StateFlow<AndroidProductionThinQGraph?> = _thinQGraph.asStateFlow()

    /** Process-lifetime scope keeps an explicit update download alive across Activity recreation. */
    private val appUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appUpdateGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppUpdateGraph(
            context = applicationContext,
            preferences = { installationPreferences },
            appScope = appUpdateScope,
        )
    }

    /** Debug builds never create the updater or its HTTP client. */
    fun appUpdateBindings(): AppUpdateBindings? = appUpdateGraph.bindings()

    fun handleAppUpdateInstallStatus(statusIntent: Intent) {
        appUpdateGraph.handleInstallStatus(statusIntent)
    }

    @Synchronized
    fun getOrCreateThinQGraph(ownerUid: String?): AndroidProductionThinQGraph {
        return _thinQGraph.value ?: newThinQGraph(ownerUid).also { _thinQGraph.value = it }
    }

    /** Replaces a graph only after OwnerTransition has invalidated and cleared its owner. */
    @Synchronized
    fun replaceThinQGraph(
        invalidatedGraph: AndroidProductionThinQGraph,
        ownerUid: String?,
    ): AndroidProductionThinQGraph? {
        if (_thinQGraph.value !== invalidatedGraph) return _thinQGraph.value
        val replacement = newThinQGraph(ownerUid)
        _thinQGraph.value = replacement
        invalidatedGraph.close()
        return replacement
    }

    private fun newThinQGraph(ownerUid: String?) = AndroidProductionThinQGraph(
        context = applicationContext,
        configuredDeviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
        ownerUid = ownerUid,
        projectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
        credentialStore = credentialStore,
        engine = CIO.create(),
    )
}
