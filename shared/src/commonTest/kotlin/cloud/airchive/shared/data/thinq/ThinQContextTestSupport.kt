package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared deterministic doubles for the lifecycle/context tests (OpenSpec
 * `deliver-shared-instrument-ui` tasks 2.1–2.3).
 *
 * Everything here is in-memory: no real credential, no Firebase, no Keystore
 * and no HTTP client is ever constructed. Synthetic tokens are clearly marked
 * `synthetic-*` markers, never anything resembling a real PAT.
 */

internal const val TEST_DEVICE_ID = "lifecycle-ac-1"
internal const val TEST_PROJECT_ID = "lifecycle-project-1"
internal const val SEED_TOKEN = "synthetic-seed-pat-1234"

internal fun testDevice(deviceId: String = TEST_DEVICE_ID, online: Boolean = true): ThinQDevice =
    ThinQDevice(deviceId = deviceId, alias = "Test AC", online = online)

internal fun testProfile(deviceId: String = TEST_DEVICE_ID): ThinQDeviceProfile =
    ThinQDeviceProfile(deviceId = deviceId, controls = AcControls(powerWritable = true))

internal fun testLive(deviceId: String = TEST_DEVICE_ID): ThinQLiveState =
    ThinQLiveState(
        deviceId = deviceId,
        observedAtEpochMillis = 1_700_000_000_000L,
        powerOn = true,
        targetTemperature = 25.0,
    )

internal fun testOutcome(deviceId: String, command: AcCommand): CommandOutcome =
    CommandOutcome(
        commandId = "lifecycle-test-command",
        deviceId = deviceId,
        command = command,
        status = CommandStatus.CONFIRMED,
        submittedAtEpochMillis = command.requestedAtEpochMillis,
        observedAtEpochMillis = command.requestedAtEpochMillis + 100L,
        observedState = testLive(deviceId).copy(powerOn = false),
    )

/**
 * One gated LG step: the port call suspends between [hold]'s enter and
 * [release], so a test can deterministically interleave invalidation with a
 * suspended list/profile/live/setState request.
 */
internal class NetworkStep {
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    /** Body of the gated port call: signals entry, then suspends until released. */
    suspend fun hold() {
        entered.complete(Unit)
        released.await()
    }

    suspend fun awaitEntered() {
        entered.await()
    }

    fun release() {
        released.complete(Unit)
    }
}

/**
 * Scripted [ThinQPort]: per-operation hooks and call counters over plain
 * in-memory fixtures. With no hook set an operation returns the default
 * device/profile/live/outcome immediately.
 */
internal class ScriptedThinQPort(private val deviceId: String = TEST_DEVICE_ID) : ThinQPort {

    var onListDevices: (suspend () -> List<ThinQDevice>)? = null
    var onProfile: (suspend () -> ThinQDeviceProfile)? = null
    var onLiveState: (suspend () -> ThinQLiveState)? = null
    var onSetState: (suspend (AcCommand) -> CommandOutcome)? = null

    var listCalls = 0
        private set
    var profileCalls = 0
        private set
    var liveCalls = 0
        private set
    var commandCalls = 0
        private set

    override suspend fun listDevices(): List<ThinQDevice> {
        listCalls++
        return onListDevices?.invoke() ?: listOf(testDevice(deviceId))
    }

    override suspend fun profile(deviceId: String): ThinQDeviceProfile {
        profileCalls++
        return onProfile?.invoke() ?: testProfile(deviceId)
    }

    override suspend fun liveState(deviceId: String): ThinQLiveState {
        liveCalls++
        return onLiveState?.invoke() ?: testLive(deviceId)
    }

    override suspend fun setState(command: AcCommand): CommandOutcome {
        commandCalls++
        return onSetState?.invoke(command) ?: testOutcome(deviceId, command)
    }
}

/** Verifier that accepts the configured device for any synthetic token. */
internal fun testVerifier(deviceId: String = TEST_DEVICE_ID): FakeThinQCredentialVerifier =
    FakeThinQCredentialVerifier(deviceId)

/**
 * Owner identity whose [signIn] switches from [initial] to [nextAccount];
 * used to model account switches through the real
 * `ProductionAccountController` (as MainActivity's session collector observes).
 */
internal class SwitchingIdentity(
    initial: OwnerSession,
    private val nextAccount: OwnerSession,
) : OwnerIdentityPort {
    private val session = MutableStateFlow<OwnerSession?>(initial)

    override val currentSession: OwnerSession? get() = session.value
    override fun observeSession(): Flow<OwnerSession?> = session.asStateFlow()

    override suspend fun signIn(): SignInResult {
        session.value = nextAccount
        return SignInResult.Success(nextAccount)
    }

    override suspend fun signOut() {
        session.value = null
    }
}
