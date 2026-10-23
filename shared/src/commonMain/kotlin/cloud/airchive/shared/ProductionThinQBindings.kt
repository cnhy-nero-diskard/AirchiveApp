package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-owned production ThinQ adapters exposed to the shared UI. This
 * boundary carries only masked credential state, typed domain objects, and
 * narrow operations; it does not expose `readToken()` or raw PAT data.
 */
data class ProductionThinQBindings(
    val credentialState: Flow<ThinQCredentialState>,
    val clientId: String,
    val device: StateFlow<ThinQDevice?>,
    val profile: StateFlow<ThinQDeviceProfile?>,
    val liveStateController: ThinQLiveStateController,
    val commandController: AcCommandController,
    val setupCredential: suspend (rawToken: String, country: String) -> ThinQCredentialSetupResult,
    val onDeviceOpened: suspend () -> Unit,
    val refreshLiveState: suspend () -> Unit,
    val removeCredential: suspend () -> Unit,
    val clearCachedDeviceProfile: () -> Unit,
)
