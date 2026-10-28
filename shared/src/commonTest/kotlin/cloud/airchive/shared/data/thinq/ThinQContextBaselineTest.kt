package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Reproduces pre-lifecycle-boundary state retention. These tests intentionally
 * assert the unsafe baseline so the subsequent lifecycle implementation can
 * replace them with assertions that old context state/results are discarded.
 * No platform credential, network, Firebase, or household device is involved.
 */
class ThinQContextBaselineTest {
    private val deviceId = "baseline-ac-1"
    private val oldRead = ThinQLiveState(
        deviceId = deviceId,
        observedAtEpochMillis = 1_700_000_000_000L,
        powerOn = true,
        targetTemperature = 25.0,
        mode = AcOperatingMode.COOL,
        fanSpeed = FanSpeed.LOW,
    )

    @Test
    fun replacementWithIdenticalMaskedMetadataLeavesTheOldLiveReadPublished() = runBlocking {
        val credentials = FakeThinQCredentialStore()
        credentials.save("synthetic-old-pat-marker-1234", "PH")
        val before = credentials.observe().first()
        val port = ScriptedPort(deviceId).apply { enqueueLive { oldRead } }
        val live = ThinQLiveStateController(port, deviceId, hasVerifiedProfile = { true })
        live.onDeviceOpened()
        val oldPresentation = live.state.value

        credentials.save("synthetic-new-pat-marker-1234", "PH")

        assertEquals(before, credentials.observe().first(), "same country, mask, client id and timestamp are not a context identity")
        assertEquals(oldPresentation, live.state.value, "the baseline controller has no credential invalidation boundary")
    }

    @Test
    fun removingTheCredentialDoesNotClearThePreviousLiveReadAtBaseline() = runBlocking {
        val credentials = FakeThinQCredentialStore()
        credentials.save("synthetic-pat-marker-1234", "PH")
        val port = ScriptedPort(deviceId).apply { enqueueLive { oldRead } }
        val live = ThinQLiveStateController(port, deviceId, hasVerifiedProfile = { true })
        live.onDeviceOpened()
        val oldPresentation = live.state.value

        credentials.clear()

        assertEquals(oldPresentation, live.state.value, "credential removal currently leaves the disposable controller untouched")
    }

    @Test
    fun failedFirstReadAfterReplacementRetainsThePriorContextAsStaleAtBaseline() = runBlocking {
        val credentials = FakeThinQCredentialStore()
        credentials.save("synthetic-old-pat-marker-1234", "PH")
        val port = ScriptedPort(deviceId).apply {
            enqueueLive { oldRead }
            enqueueLive { throw ThinQClientException.Network }
        }
        val live = ThinQLiveStateController(port, deviceId, hasVerifiedProfile = { true })
        live.onDeviceOpened()
        credentials.save("synthetic-new-pat-marker-5678", "PH")

        live.refresh()

        val stale = assertIs<ThinQLiveRefreshState.Stale>(live.state.value)
        assertEquals(oldRead, stale.lastKnownState, "a failed new-context read currently retains the old-context value")
    }

    @Test
    fun accountSwitchDuringADeferredReadDoesNotFenceItsLateOldOwnerResultAtBaseline() = runBlocking {
        val ownerA = OwnerSession("baseline-owner-a", signedInAtEpochMillis = 1L)
        val ownerB = OwnerSession("baseline-owner-b", signedInAtEpochMillis = 2L)
        val identity = SwitchingIdentity(ownerA, ownerB)
        val credentials = FakeThinQCredentialStore()
        credentials.save("synthetic-owner-a-pat-1234", "PH")
        val local = FakeLocalHistory()
        val account = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = "baseline-project",
            configuredDeviceId = deviceId,
            credentialStore = credentials,
        )
        account.requestChangeAccount()

        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ThinQLiveState>()
        val port = ScriptedPort(deviceId).apply {
            enqueueLive {
                entered.complete(Unit)
                response.await()
            }
        }
        val live = ThinQLiveStateController(port, deviceId, hasVerifiedProfile = { true })
        val oldReadJob = async { live.onDeviceOpened() }
        entered.await()

        val switched = account.changeAccountConfirmed()
        assertEquals(ownerB, identity.currentSession)
        assertIs<OwnerSettingsActionResult.Success>(switched)
        assertEquals(null, credentials.readToken(), "the old PAT was removed during account change")

        response.complete(oldRead)
        oldReadJob.await()

        assertEquals(ownerB.uid, identity.currentSession?.uid)
        assertEquals(
            oldRead,
            assertIs<ThinQLiveRefreshState.Current>(live.state.value).state,
            "the baseline live controller publishes a late read after the owner and credential changed",
        )
    }

    @Test
    fun credentialRemovalDoesNotFenceADeferredOldCommandOutcomeAtBaseline() = runBlocking {
        val credentials = FakeThinQCredentialStore()
        credentials.save("synthetic-old-command-pat-1234", "PH")
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<CommandOutcome>()
        val port = ScriptedPort(deviceId).apply {
            enqueueCommand {
                entered.complete(Unit)
                response.await()
            }
        }
        val commands = AcCommandController(port, deviceId)
        val command = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_000L, powerOn = false)
        val submission = async { commands.submit(command) }
        entered.await()

        credentials.clear()
        response.complete(
            CommandOutcome(
                commandId = "baseline-old-command",
                deviceId = deviceId,
                command = command,
                status = CommandStatus.CONFIRMED,
                submittedAtEpochMillis = command.requestedAtEpochMillis,
                observedAtEpochMillis = command.requestedAtEpochMillis + 100L,
                observedState = oldRead.copy(powerOn = false),
            ),
        )
        submission.await()

        val confirmed = assertIs<AcCommandUiState.Confirmed>(commands.state.value)
        assertEquals("baseline-old-command", confirmed.outcome.commandId)
        assertEquals(command, confirmed.outcome.command,
            "the baseline command controller publishes its late old-context outcome")
    }

    private class ScriptedPort(private val deviceId: String) : ThinQPort {
        private val liveResponses = ArrayDeque<suspend () -> ThinQLiveState>()
        private val commandResponses = ArrayDeque<suspend (AcCommand) -> CommandOutcome>()

        fun enqueueLive(response: suspend () -> ThinQLiveState) { liveResponses.addLast(response) }
        fun enqueueCommand(response: suspend (AcCommand) -> CommandOutcome) { commandResponses.addLast(response) }

        override suspend fun listDevices(): List<ThinQDevice> = listOf(ThinQDevice(deviceId, "Synthetic AC", online = true))
        override suspend fun profile(deviceId: String): ThinQDeviceProfile =
            error("Profile was not expected in this controller-level baseline test")

        override suspend fun liveState(deviceId: String): ThinQLiveState = liveResponses.removeFirst().invoke()
        override suspend fun setState(command: AcCommand): CommandOutcome = commandResponses.removeFirst().invoke(command)
    }

    private class SwitchingIdentity(
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
}
