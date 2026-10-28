package cloud.airchive.personal.preview

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import cloud.airchive.shared.*
import cloud.airchive.shared.contract.credentials.ThinQCredential
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.history.HistorySnapshot
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.data.sync.presentHistorySync
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQDeviceProfilePublication
import cloud.airchive.shared.data.thinq.ThinQEntryResult
import cloud.airchive.shared.data.thinq.ThinQCredentialRemovalOutcome
import cloud.airchive.shared.data.thinq.ThinQLifecycleBoundary
import cloud.airchive.shared.data.thinq.ThinQLiveRefreshState
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.thinq.PendingCommandHost
import cloud.airchive.shared.data.thinq.ThinQSetupOutcome
import cloud.airchive.shared.fakes.FakeProviders
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/** Real route composables over isolated in-memory ports. Never creates the production graph. */
@Composable
internal fun InstrumentRoutePreview(route: Int, history: HistorySnapshot, now: Long, onHome: () -> Unit, onSettings: () -> Unit) {
    val providers = remember { FakeProviders(now, "preview-ac") }
    val session by providers.ownerIdentity.observeSession().collectAsState(initial = null)
    // Single-context preview lifecycle: the key never advances, but its shape
    // (publication carrying its context key) matches the production bindings.
    val previewLifecycle = remember { ThinQLifecycleBoundary(null, "preview", "preview-ac") }
    val deviceProfile = remember { MutableStateFlow<ThinQDeviceProfilePublication?>(null) }
    val credential = remember { MutableStateFlow(ThinQCredentialState(true, "preview-client",
        ThinQCredential("•••• demo", "PH", now))) }
    val credentialState by credential.collectAsState()
    val live = remember { ThinQLiveStateController(providers.thinQ, "preview-ac",
        hasVerifiedProfile = { deviceProfile.value?.profile != null }) }
    val commands = remember { AcCommandController(providers.thinQ, "preview-ac") }
    val commandScope = rememberCoroutineScope()
    val commandHost = remember(commands, live) { PendingCommandHost(commandScope, commands, live) }
    val account = remember { ProductionAccountController(providers.ownerIdentity, providers.localHistory, "preview", "preview-ac") }
    val bindings = remember {
        ProductionThinQBindings(
            credentialState = credential,
            clientId = "preview-client",
            contextKey = previewLifecycle.key,
            deviceProfile = deviceProfile,
            liveStateController = live,
            commandController = commands,
            setupCredential = { _, _ ->
                ThinQSetupOutcome(
                    ThinQCredentialSetupResult.Failure.InvalidToken(credential.value),
                    previewLifecycle.current(),
                )
            },
            cancelSetupAttempt = {},
            onDeviceOpened = {
                try {
                    val device = providers.thinQ.listDevices().single()
                    val profile = providers.thinQ.profile("preview-ac")
                    deviceProfile.value = ThinQDeviceProfilePublication(previewLifecycle.current(), device, profile)
                    live.onDeviceOpened()
                    if (live.state.value is ThinQLiveRefreshState.Current) {
                        ThinQEntryResult.Success
                    } else {
                        ThinQEntryResult.Failed
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ThinQEntryResult.Failed
                }
            },
            refreshLiveState = { live.refresh() },
            removeCredential = {
                credential.value = credential.value.copy(present = false, credential = null)
                ThinQCredentialRemovalOutcome(previewLifecycle.current(), removed = true)
            },
            clearCachedDeviceProfile = { deviceProfile.value = null },
            dispatchCommand = commandHost::dispatch,
        )
    }
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
