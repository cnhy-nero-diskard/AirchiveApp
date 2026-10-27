package cloud.airchive.personal.preview

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import cloud.airchive.shared.*
import cloud.airchive.shared.contract.credentials.ThinQCredential
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.fakes.FakeProviders
import kotlinx.coroutines.flow.MutableStateFlow

/** Real route composables over isolated in-memory ports. Never creates the production graph. */
@Composable
internal fun InstrumentRoutePreview(route: Int, history: HistorySnapshot, now: Long, onHome: () -> Unit, onSettings: () -> Unit) {
    val providers = remember { FakeProviders(now, "preview-ac") }
    val session by providers.ownerIdentity.observeSession().collectAsState(initial = null)
    val device = remember { MutableStateFlow<ThinQDevice?>(null) }
    val profile = remember { MutableStateFlow<ThinQDeviceProfile?>(null) }
    val credential = remember { MutableStateFlow(ThinQCredentialState(true, "preview-client",
        ThinQCredential("•••• demo", "PH", now))) }
    val credentialState by credential.collectAsState()
    val live = remember { ThinQLiveStateController(providers.thinQ, "preview-ac", hasVerifiedProfile = { profile.value != null }) }
    val commands = remember { AcCommandController(providers.thinQ, "preview-ac") }
    val account = remember { ProductionAccountController(providers.ownerIdentity, providers.localHistory, "preview", "preview-ac") }
    val bindings = remember { ProductionThinQBindings(credential, "preview-client", device, profile, live, commands,
        setupCredential = { _, _ -> ThinQCredentialSetupResult.Failure.InvalidToken(credential.value) },
        onDeviceOpened = {
            device.value = providers.thinQ.listDevices().single()
            profile.value = providers.thinQ.profile("preview-ac")
            live.onDeviceOpened()
        }, refreshLiveState = { live.refresh() },
        removeCredential = { credential.value = credential.value.copy(present = false, credential = null) },
        clearCachedDeviceProfile = { profile.value = null; device.value = null }) }
    var access by remember { mutableStateOf<OwnerAccessState>(OwnerAccessState.SignedOut) }
    LaunchedEffect(Unit) {
        providers.ownerIdentity.signIn()
        access = OwnerAccessState.Authorized(providers.ownerIdentity.currentSession!!, providers.telemetry.deviceIdentity())
    }
    when (route) {
        1 -> ProductionDeviceRoute(history, "preview-ac", (access as? OwnerAccessState.Authorized)?.device,
            bindings, credentialState, onHome, onSettings)
        2 -> EnergyRoute(history, providers.telemetry, providers.localHistory, history.partition)
        3 -> HistoryHealthRoute(history, now, onRefresh = {})
        4 -> ProductionSettingsRoute(account, access, session, history.partition, history,
            presentHistorySync(history, now), false, now, {}, {}, bindings, credentialState)
        else -> Text("Choose a preview destination")
    }
}
