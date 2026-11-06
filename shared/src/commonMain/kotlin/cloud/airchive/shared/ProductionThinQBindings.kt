package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.data.thinq.AcCommandController
import cloud.airchive.shared.data.thinq.AcCommandDispatchResult
import cloud.airchive.shared.data.thinq.ThinQContextCoordinator
import cloud.airchive.shared.data.thinq.ThinQContextKey
import cloud.airchive.shared.data.thinq.ThinQCredentialRemovalOutcome
import cloud.airchive.shared.data.thinq.ThinQDeviceProfilePublication
import cloud.airchive.shared.data.thinq.ThinQEntryResult
import cloud.airchive.shared.data.thinq.ThinQLiveStateController
import cloud.airchive.shared.data.thinq.ThinQSetupOutcome
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
    /**
     * Fenced setup returning the outcome **bound to its exact lifecycle
     * context** (task 2.4): label any setup feedback only by
     * [ThinQSetupOutcome.contextKey], never by sampling the active key
     * afterwards, so a stale attempt's outcome can never be attributed to a
     * newer context.
     */
    val setupCredential: suspend (rawToken: String, country: String) -> ThinQSetupOutcome,
    /**
     * Explicit cancel-attempt hook (task 2.4) for setup-form/route
     * cancellation: supersedes only the in-flight setup attempt, which can
     * then never save its candidate or advance/publish anything. No-op by
     * default for single-context preview graphs.
     */
    val cancelSetupAttempt: suspend () -> Unit = {},
    val onDeviceOpened: suspend () -> ThinQEntryResult,
    /**
     * Explicit user "Refresh device state" action (task 3.2): forced
     * same-context profile-capability refresh — refetches the device
     * list/profile and re-reads live state under the captured active context,
     * even when a valid same-context publication already exists, so changed
     * LG units/bounds/steps become visible without a context change. Runs
     * only on explicit user action (never from route entry, a timer, or any
     * polling path) and never changes the credential generation. Defaults to
     * the [onDeviceOpened] entry path for preview/manual callers.
     */
    val refreshDeviceProfile: suspend () -> ThinQEntryResult = { onDeviceOpened() },
    val refreshLiveState: suspend () -> Unit,
    val removeCredential: suspend () -> ThinQCredentialRemovalOutcome,
    val clearCachedDeviceProfile: suspend () -> Unit,
    /** Synchronous non-queueing tap dispatch to the graph-owned command scope. */
    val dispatchCommand: (AcCommand, ThinQContextKey?) -> AcCommandDispatchResult = { _, _ ->
        AcCommandDispatchResult.ScopeClosed
    },
)
