package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.ThinQContextCoordinator
import cloud.airchive.shared.data.thinq.ThinQContextKey
import cloud.airchive.shared.data.thinq.ThinQDeviceProfilePublication
import cloud.airchive.shared.data.thinq.ThinQEntryResult
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-owned production ThinQ adapters exposed to the shared UI. This
 * boundary carries only masked credential state, typed domain objects, and
 * narrow operations; it does not expose `readToken()` or raw PAT data.
 *
 * ## Lifecycle (OpenSpec tasks 2.1–2.3)
 *
 * [contextKey] is the active opaque lifecycle context and [deviceProfile] is
 * published atomically together with the key it was read under. The UI fences
 * entry and Device-details rendering by comparing publication keys with
 * [contextKey], so a superseded context's device/profile/reading can never be
 * presented under the active one; [onDeviceOpened] reports an explicit
 * [ThinQEntryResult] so Home readiness comes from a successful matching read
 * for that context rather than from the callback merely returning.
 *
 * See [ThinQContextCoordinator] for the owning implementation.
 */
data class ProductionThinQBindings(
    val credentialState: Flow<ThinQCredentialState>,
    val clientId: String,
    /** Active lifecycle context; changes synchronously with every invalidation. */
    val contextKey: StateFlow<ThinQContextKey>,
    /** Device/profile published atomically under their [ThinQDeviceProfilePublication.contextKey]. */
    val deviceProfile: StateFlow<ThinQDeviceProfilePublication?>,
    val liveStateController: ThinQLiveStateController,
    val commandController: AcCommandController,
    val setupCredential: suspend (rawToken: String, country: String) -> ThinQCredentialSetupResult,
    val onDeviceOpened: suspend () -> ThinQEntryResult,
    val refreshLiveState: suspend () -> Unit,
    val removeCredential: suspend () -> Unit,
    val clearCachedDeviceProfile: suspend () -> Unit,
)
