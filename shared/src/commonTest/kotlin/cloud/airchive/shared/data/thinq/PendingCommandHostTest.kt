package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.commandSlotIsNeutralBusy
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Graph-lifetime pending-command ownership (OpenSpec task 2.5). The shared
 * controller's existing mutex is the sole device slot: context invalidation
 * clears only the old presentation, never the in-flight slot; a new context's
 * tap is rejected without a port call or queued retry. All ports are synthetic.
 */
class PendingCommandHostTest {

    @Test
    fun oldContextCommandKeepsGlobalSlotAndNewTapIsNeutralNonQueuedBusy() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(SEED_TOKEN, "PH")
        val port = ScriptedThinQPort()
        val commandStep = NetworkStep()
        port.onSetState = { command ->
            commandStep.hold()
            testOutcome(TEST_DEVICE_ID, command)
        }
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = "command-owner-a",
            projectId = TEST_PROJECT_ID,
            commandScope = this,
        )
        val oldKey = coordinator.contextKey.value
        val oldCommand = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_100L, powerOn = false)

        assertEquals(AcCommandDispatchResult.Scheduled, coordinator.dispatchCommand(oldCommand, oldKey))
        withTimeout(COMMAND_TIMEOUT_MS) { commandStep.awaitEntered() }
        assertEquals(AcCommandSlotState.Occupied(oldKey), coordinator.commandController.slotState.value)
        assertIs<AcCommandUiState.Sending>(coordinator.commandController.state.value)

        // Superseding the presentation does not cancel or free the device slot.
        coordinator.onOwnerChanged("command-owner-b", TEST_PROJECT_ID)
        val newKey = coordinator.contextKey.value
        assertEquals(AcCommandUiState.Idle, coordinator.commandController.state.value,
            "the old target/outcome is hidden immediately on owner change")
        assertEquals(AcCommandSlotState.Occupied(oldKey), coordinator.commandController.slotState.value,
            "the old request still owns the device-global slot")
        assertEquals(true, commandSlotIsNeutralBusy(coordinator.commandController.slotState.value, newKey))

        val newCommand = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_200L, powerOn = true)
        assertEquals(
            AcCommandDispatchResult.IgnoredWhilePending,
            coordinator.dispatchCommand(newCommand, newKey),
            "a new-context tap is dropped immediately, never queued behind the old operation",
        )
        assertEquals(1, port.commandCalls, "the new context cannot send a second command")

        commandStep.release()
        withTimeout(COMMAND_TIMEOUT_MS) {
            coordinator.commandController.slotState.first { it == AcCommandSlotState.Idle }
        }
        assertEquals(1, port.commandCalls, "freeing the slot never auto-submits the dropped tap")
        assertEquals(AcCommandUiState.Idle, coordinator.commandController.state.value,
            "the old confirmation/outcome is fenced and cannot reappear in the new context")
        assertNull(coordinator.deviceProfile.value)
    }

    @Test
    fun stableHostScopeIsIndependentOfRouteScopedWork() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(SEED_TOKEN, "PH")
        val port = ScriptedThinQPort()
        val commandStep = NetworkStep()
        port.onSetState = { command ->
            commandStep.hold()
            testOutcome(TEST_DEVICE_ID, command)
        }
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = "stable-command-owner",
            projectId = TEST_PROJECT_ID,
            commandScope = this,
        )
        val routeWork = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val key = coordinator.contextKey.value

        val routeJob = routeWork.launch {
            coordinator.dispatchCommand(
                AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_300L, powerOn = false),
                key,
            )
            awaitCancellation()
        }
        withTimeout(COMMAND_TIMEOUT_MS) { commandStep.awaitEntered() }
        routeJob.cancelAndJoin()
        routeWork.cancel()

        assertEquals(1, port.commandCalls, "disposing unrelated route work does not cancel the graph-owned request")
        commandStep.release()
        withTimeout(COMMAND_TIMEOUT_MS) {
            coordinator.commandController.slotState.first { it == AcCommandSlotState.Idle }
        }
        assertEquals(1, port.commandCalls, "returning to a route does not resend the request")
        assertIs<AcCommandUiState.Confirmed>(coordinator.commandController.state.value)
        Unit
    }

    @Test
    fun hostAppliesTheExistingCommandObservationWithoutAnAdditionalLiveRequest() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(SEED_TOKEN, "PH")
        val port = ScriptedThinQPort()
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = "command-owner",
            projectId = TEST_PROJECT_ID,
            commandScope = this,
        )
        assertEquals(ThinQEntryResult.Success, coordinator.onDeviceOpened())
        val liveReadsBeforeCommand = port.liveCalls
        val context = coordinator.contextKey.value

        assertEquals(
            AcCommandDispatchResult.Scheduled,
            coordinator.dispatchCommand(
                AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_400L, powerOn = false),
                context,
            ),
        )
        withTimeout(COMMAND_TIMEOUT_MS) {
            coordinator.commandController.slotState.first { it == AcCommandSlotState.Idle }
        }

        val live = assertIs<ThinQLiveRefreshState.Current>(coordinator.liveStateController.state.value)
        assertEquals(false, live.state.powerOn, "the host applied the command's already-returned observed state")
        assertEquals(liveReadsBeforeCommand, port.liveCalls,
            "the command outcome is consumed without an extra LG live read")
        assertEquals(1, port.commandCalls)
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
