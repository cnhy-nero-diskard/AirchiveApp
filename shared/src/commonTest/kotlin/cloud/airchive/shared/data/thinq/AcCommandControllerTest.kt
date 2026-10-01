package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Deterministic tests for [AcCommandController] (OpenSpec task 4.4), driven by
 * a thin scripted [ThinQPort] fake that never touches a real LG account,
 * Firestore, or any credential. Proves every progress/result state, retention
 * of [AcCommandUiState.Accepted] without any re-send, duplicate-tap rejection
 * while in flight (the port is invoked exactly once), concurrent rejection,
 * cancellation leaving [AcCommandUiState.Unconfirmed] while rethrowing,
 * Ktor-like network/timeout ambiguity, explicit FAILED/UNCONFIRMED passthrough,
 * the wrong-device safe failure, the unexpected-SENDING fail-safe, the injected
 * handset clock, and that no exception message or token text ever leaks into a
 * published state.
 */
class AcCommandControllerTest {

    private val deviceId = "ac-device-1"
    private val nowMillis = 1_800_000_000_000L

    // ---------------------------------------------------------------- fixtures

    private fun powerOn(requestedAt: Long = 0L) = AcCommand.SetPower(
        requestedAtEpochMillis = requestedAt,
        powerOn = true,
    )

    private fun outcome(
        command: AcCommand = powerOn(),
        status: CommandStatus,
        failure: CommandFailure? = null,
        deviceId: String = this.deviceId,
        message: String? = null,
    ) = CommandOutcome(
        commandId = "fake-cmd-1",
        deviceId = deviceId,
        command = command,
        status = status,
        submittedAtEpochMillis = 100L,
        failure = failure,
        message = message,
    )

    private fun controller(port: ThinQPort) = AcCommandController(
        thinQ = port,
        configuredDeviceId = deviceId,
        nowEpochMillis = { nowMillis },
    )

    // ----------------------------------------------------------- initial state

    @Test
    fun initialStateIsIdle() = runBlocking {
        val controller = controller(ScriptedCommandPort())
        assertEquals(AcCommandUiState.Idle, controller.state.value)
    }

    // ------------------------------------------- Sending published before call

    @Test
    fun sendingIsPublishedBeforeThePortCall() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue { awaitCancellation() } }
        val controller = controller(port)
        val request = powerOn(requestedAt = 42L)

        val submission = async { controller.submit(request) }
        port.awaitSetStateEntered()

        val sending = assertIs<AcCommandUiState.Sending>(controller.state.value)
        assertEquals(request, sending.command, "Sending must carry the exact command before the port answers")

        submission.cancel()
        assertFailsWith<CancellationException> { submission.await() }
        assertEquals(1, port.setStateCalls.size, "Sending is published and the port was called exactly once")
    }

    // ------------------------------------------------ Accepted retained, no send

    @Test
    fun acceptedMapsToAcceptedAndIsRetainedWithoutAnyResend() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue(outcome(status = CommandStatus.ACCEPTED)) }
        val controller = controller(port)
        val request = powerOn()

        val result = controller.submit(request)

        assertEquals(AcCommandSubmitResult.Submitted, result)
        val accepted = assertIs<AcCommandUiState.Accepted>(controller.state.value)
        assertEquals(CommandStatus.ACCEPTED, accepted.outcome.status)
        assertEquals(request, accepted.outcome.command)
        assertEquals(1, port.setStateCalls.size, "acceptance must never trigger a second port call")

        yield()
        assertIs<AcCommandUiState.Accepted>(controller.state.value, "Accepted stays accepted without an explicit event")
        assertEquals(1, port.setStateCalls.size, "nothing may be auto-resent after acceptance")
    }

    // ---------------------------------------------------------- terminal states

    @Test
    fun confirmedMapsToConfirmedCarryingThePortOutcome() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue(outcome(status = CommandStatus.CONFIRMED, message = "confirmed by fresh state read"))
        }
        val controller = controller(port)
        val request = powerOn(requestedAt = 7L)

        val result = controller.submit(request)

        assertEquals(AcCommandSubmitResult.Submitted, result)
        val confirmed = assertIs<AcCommandUiState.Confirmed>(controller.state.value)
        assertEquals(CommandStatus.CONFIRMED, confirmed.outcome.status)
        assertEquals("fake-cmd-1", confirmed.outcome.commandId)
        assertEquals(request, confirmed.outcome.command)
        assertEquals(1, port.setStateCalls.size)
        assertEquals(0, port.otherMethodCalls, "the controller never reads profiles/live state or lists devices")
    }

    @Test
    fun failedMapsToFailedPreservingTheClassifiedPortFailure() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue(outcome(status = CommandStatus.FAILED, failure = CommandFailure.NO_CONTROL_AUTHORITY))
        }
        val controller = controller(port)
        val request = powerOn()

        controller.submit(request)

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.NO_CONTROL_AUTHORITY, failed.outcome.failure)
        assertEquals(request, failed.outcome.command)
        assertEquals(1, port.setStateCalls.size)
    }

    @Test
    fun failedOutcomeWithoutAFailureIsGradedUnknownWithFixedSafeText() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue(outcome(status = CommandStatus.FAILED)) }
        val controller = controller(port)

        controller.submit(powerOn())

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.UNKNOWN, failed.outcome.failure)
        assertEquals(MESSAGE_MISSING_FAILURE, failed.outcome.message)
    }

    @Test
    fun unconfirmedMapsToUnconfirmedPreservingThePortFailure() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue(outcome(status = CommandStatus.UNCONFIRMED, failure = CommandFailure.TIMEOUT))
        }
        val controller = controller(port)

        controller.submit(powerOn())

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(CommandFailure.TIMEOUT, unconfirmed.outcome.failure)
        assertEquals(1, port.setStateCalls.size)
    }

    @Test
    fun unexpectedSendingFromThePortFailsSafelyToUnknownFailure() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue(outcome(status = CommandStatus.SENDING)) }
        val controller = controller(port)
        val request = powerOn()

        controller.submit(request)

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.UNKNOWN, failed.outcome.failure)
        assertEquals(MESSAGE_UNEXPECTED_SENDING, failed.outcome.message)
        assertEquals(deviceId, failed.outcome.deviceId, "the fail-safe outcome is bound to the configured device")
        assertEquals(request, failed.outcome.command)
        assertEquals(1, port.setStateCalls.size)
    }

    // ---------------------------------------------- duplicate tap while pending

    @Test
    fun duplicateTapWhileInFlightIsIgnoredAndThePortIsInvokedExactlyOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val port = ScriptedCommandPort().also {
            it.enqueue { gate.await(); outcome(status = CommandStatus.CONFIRMED) }
        }
        val controller = controller(port)
        val first = async { controller.submit(powerOn()) }
        port.awaitSetStateEntered()

        val duplicate = controller.submit(powerOn())
        assertEquals(AcCommandSubmitResult.IgnoredWhilePending, duplicate)
        assertIs<AcCommandUiState.Sending>(controller.state.value, "the in-flight submission is untouched")
        assertEquals(1, port.setStateCalls.size, "the duplicate tap must never reach the port")

        gate.complete(Unit)
        assertEquals(AcCommandSubmitResult.Submitted, first.await())
        assertEquals(1, port.setStateCalls.size, "still exactly one port invocation after the first finishes")
        val confirmed = assertIs<AcCommandUiState.Confirmed>(controller.state.value)
        assertEquals(CommandStatus.CONFIRMED, confirmed.outcome.status)
    }

    @Test
    fun concurrentSubmissionsAreRejectedDeterministicallyNeverBothInFlight() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val port = ScriptedCommandPort().also {
            it.enqueue { gate.await(); outcome(status = CommandStatus.CONFIRMED) }
        }
        val controller = controller(port)

        val first = async { controller.submit(powerOn()) }
        val second = async { controller.submit(powerOn()) }
        port.awaitSetStateEntered()

        val secondResult = second.await()
        assertEquals(AcCommandSubmitResult.IgnoredWhilePending, secondResult, "only one submission may hold the flight lock")

        gate.complete(Unit)
        assertEquals(AcCommandSubmitResult.Submitted, first.await())
        assertEquals(1, port.setStateCalls.size)
        assertEquals(1, port.maxActiveCommands, "the controller's mutex must keep commands strictly one-at-a-time")
    }

    @Test
    fun aNewSubmissionIsAllowedAfterATerminalState() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue(outcome(status = CommandStatus.CONFIRMED))
            it.enqueue(outcome(status = CommandStatus.ACCEPTED))
        }
        val controller = controller(port)

        controller.submit(powerOn())
        assertIs<AcCommandUiState.Confirmed>(controller.state.value)

        val result = controller.submit(powerOn())

        assertEquals(AcCommandSubmitResult.Submitted, result, "one-at-a-time only excludes the in-flight window")
        assertEquals(2, port.setStateCalls.size)
        val accepted = assertIs<AcCommandUiState.Accepted>(controller.state.value)
        assertEquals(CommandStatus.ACCEPTED, accepted.outcome.status)
    }

    // -------------------------------------------------------------- cancellation

    @Test
    fun cancellationLeavesUnconfirmedAndRethrows() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue { awaitCancellation() } }
        val controller = controller(port)
        val request = powerOn()

        val submission = async { controller.submit(request) }
        port.awaitSetStateEntered()
        assertIs<AcCommandUiState.Sending>(controller.state.value)

        submission.cancel()
        assertFailsWith<CancellationException> { submission.await() }

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(
            controller.state.value,
            "cancellation must leave UNCONFIRMED, never a phantom Sending",
        )
        assertEquals(CommandFailure.UNKNOWN, unconfirmed.outcome.failure)
        assertEquals(MESSAGE_CANCELLED, unconfirmed.outcome.message)
        assertEquals(request, unconfirmed.outcome.command, "the possibly-sent command is retained for refresh guidance")
        assertEquals(1, port.setStateCalls.size)
    }

    @Test
    fun aCancelledSubmissionDoesNotLatchAndNextSubmitWorks() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue { awaitCancellation() }
            it.enqueue(outcome(status = CommandStatus.CONFIRMED))
        }
        val controller = controller(port)

        val cancelled = async { controller.submit(powerOn()) }
        port.awaitSetStateEntered()
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)

        val retried = controller.submit(powerOn())
        assertEquals(AcCommandSubmitResult.Submitted, retried, "an explicitly requested retry is allowed after cancellation")
        assertEquals(2, port.setStateCalls.size)
        val confirmed = assertIs<AcCommandUiState.Confirmed>(controller.state.value)
        assertEquals(CommandStatus.CONFIRMED, confirmed.outcome.status)
    }

    // ----------------------------------- Ktor-like ambiguous network/timeout

    @Test
    fun networkExceptionIsUnconfirmedWithNetworkFailureAndNoResend() = runBlocking {
        val port = ScriptedCommandPort().also {
            it.enqueue { throw ThinQClientException.Network }
            it.enqueue(outcome(status = CommandStatus.CONFIRMED))
        }
        val controller = controller(port)
        val request = powerOn()

        val result = controller.submit(request)

        assertEquals(AcCommandSubmitResult.Submitted, result)
        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(CommandFailure.NETWORK, unconfirmed.outcome.failure)
        assertEquals(MESSAGE_AMBIGUOUS_NETWORK, unconfirmed.outcome.message)
        assertEquals(request, unconfirmed.outcome.command)
        assertEquals(1, port.setStateCalls.size, "a network ambiguity is never auto-resent")

        val retried = controller.submit(powerOn())
        assertEquals(AcCommandSubmitResult.Submitted, retried)
        assertEquals(2, port.setStateCalls.size, "only an explicit new submission reaches the port again")
        val confirmed = assertIs<AcCommandUiState.Confirmed>(controller.state.value)
        assertEquals(CommandStatus.CONFIRMED, confirmed.outcome.status)
    }

    @Test
    fun timeoutExceptionIsUnconfirmedWithTimeoutFailure() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue { throw ThinQClientException.Timeout } }
        val controller = controller(port)

        controller.submit(powerOn())

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(CommandFailure.TIMEOUT, unconfirmed.outcome.failure)
        assertEquals(MESSAGE_AMBIGUOUS_TIMEOUT, unconfirmed.outcome.message)
        assertEquals(1, port.setStateCalls.size)
    }

    // -------------------------------------------------- typed ThinQ error mapping

    @Test
    fun explicitTypedThinQErrorsMapToFailedWithStableFailures() = runBlocking {
        val cases = listOf(
            ThinQClientException.MissingCredential to CommandFailure.TOKEN_REJECTED,
            ThinQClientException.InvalidCredential to CommandFailure.TOKEN_REJECTED,
            ThinQClientException.UnsupportedCountry to CommandFailure.UNKNOWN,
            ThinQClientException.MalformedResponse to CommandFailure.UNKNOWN,
            ThinQClientException.RemoteFailure(ThinQRemoteError.NoControlAuthority) to CommandFailure.NO_CONTROL_AUTHORITY,
            ThinQClientException.RemoteFailure(ThinQRemoteError.RateLimited) to CommandFailure.RATE_LIMITED,
            ThinQClientException.RemoteFailure(ThinQRemoteError.DeviceUnavailable) to CommandFailure.DEVICE_UNAVAILABLE,
            ThinQClientException.RemoteFailure(ThinQRemoteError.InvalidRequest) to CommandFailure.INVALID_REQUEST,
            ThinQClientException.RemoteFailure(ThinQRemoteError.Transient) to CommandFailure.UNKNOWN,
        )
        for ((cause, expected) in cases) {
            val port = ScriptedCommandPort().also { it.enqueue { throw cause } }
            val controller = controller(port)
            controller.submit(powerOn())

            val failed = assertIs<AcCommandUiState.Failed>(controller.state.value, "failed state for $cause")
            assertEquals(expected, failed.outcome.failure, "stable failure for $cause")
            assertEquals(MESSAGE_TYPED_FAILURE, failed.outcome.message, "fixed safe text for $cause")
            assertEquals(1, port.setStateCalls.size, "exactly one invocation for $cause")
        }
    }

    // ------------------------------------------- generic exception, no leaks

    @Test
    fun genericExceptionBecomesUnconfirmedUnknownWithoutLeakingTheMessage() = runBlocking {
        val secret = "synthetic-pat.example.invalid-zz9a leaked on the wire"
        val port = ScriptedCommandPort().also { it.enqueue { throw IllegalStateException(secret) } }
        val controller = controller(port)

        controller.submit(powerOn())

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(CommandFailure.UNKNOWN, unconfirmed.outcome.failure)
        assertEquals(MESSAGE_AMBIGUOUS_GENERIC, unconfirmed.outcome.message)
        assertFalse(secret in unconfirmed.outcome.message.orEmpty())
        assertFalse(secret in controller.state.value.toString(), "a published state must never render the exception message")
        assertFalse(secret in unconfirmed.outcome.toString())
        assertEquals(1, port.setStateCalls.size)
    }

    @Test
    fun typedRemoteFailureTextStaysOutOfThePublishedState() = runBlocking {
        val rawServerText = "raw lg server text for synthetic-pat.example.invalid-qq1b"
        val port = ScriptedCommandPort().also {
            it.enqueue { throw ThinQClientException.RemoteFailure(ThinQRemoteError.InvalidRequest) }
        }
        val controller = controller(port)

        controller.submit(powerOn())

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.INVALID_REQUEST, failed.outcome.failure)
        assertFalse(rawServerText in failed.outcome.message.orEmpty())
        assertFalse(rawServerText in failed.outcome.toString())
        assertFalse(rawServerText in controller.state.value.toString())
    }

    // -------------------------------------------------------- wrong device

    @Test
    fun outcomeForAWrongDeviceIsReplacedBySafeFailedUnknownOutcome() = runBlocking {
        val otherDevice = "some-other-device"
        val request = powerOn()
        val port = ScriptedCommandPort().also {
            it.enqueue(outcome(status = CommandStatus.CONFIRMED, deviceId = otherDevice))
        }
        val controller = controller(port)

        controller.submit(request)

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.UNKNOWN, failed.outcome.failure)
        assertEquals(MESSAGE_UNEXPECTED_OUTCOME, failed.outcome.message)
        assertEquals(deviceId, failed.outcome.deviceId, "the rejected outcome is re-bound to the configured device")
        assertEquals(request, failed.outcome.command, "the requested command is still reported for refresh guidance")
        assertEquals(CommandStatus.FAILED, failed.outcome.status)
        assertEquals(1, port.setStateCalls.size)
    }

    @Test
    fun outcomeForADifferentCommandIsRejectedEvenWhenDeviceMatches() = runBlocking {
        val requested = powerOn()
        val mismatched = AcCommand.SetPower(requestedAtEpochMillis = 0L, powerOn = false)
        val port = ScriptedCommandPort(echoSubmittedCommand = false).also {
            it.enqueue(outcome(command = mismatched, status = CommandStatus.CONFIRMED))
        }
        val controller = controller(port)

        controller.submit(requested)

        val failed = assertIs<AcCommandUiState.Failed>(controller.state.value)
        assertEquals(CommandFailure.UNKNOWN, failed.outcome.failure)
        assertEquals(MESSAGE_UNEXPECTED_OUTCOME, failed.outcome.message)
        assertEquals(deviceId, failed.outcome.deviceId)
        assertEquals(requested, failed.outcome.command)
        assertEquals(1, port.setStateCalls.size)
    }

    // --------------------------------------------------- injected handset clock

    @Test
    fun controllerGeneratedOutcomesUseTheInjectedHandsetClockWhenNoCommandTimestamp() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue { throw ThinQClientException.Timeout } }
        val controller = controller(port)

        controller.submit(powerOn(requestedAt = 0L))

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(nowMillis, unconfirmed.outcome.submittedAtEpochMillis, "the injected clock stamps the outcome")
    }

    @Test
    fun commandTimestampIsPreferredOverTheHandsetClockForGeneratedOutcomes() = runBlocking {
        val port = ScriptedCommandPort().also { it.enqueue { throw ThinQClientException.Timeout } }
        val controller = controller(port)

        controller.submit(powerOn(requestedAt = 123L))

        val unconfirmed = assertIs<AcCommandUiState.Unconfirmed>(controller.state.value)
        assertEquals(123L, unconfirmed.outcome.submittedAtEpochMillis)
        assertNotEquals(nowMillis, unconfirmed.outcome.submittedAtEpochMillis)
    }

    // ------------------------------------------------------------- construction

    @Test
    fun blankConfiguredDeviceIdIsRejectedAtConstruction() {
        val port = ScriptedCommandPort()
        assertFailsWith<IllegalArgumentException> {
            AcCommandController(port, "   ", nowEpochMillis = { nowMillis })
        }
        assertTrue(port.setStateCalls.isEmpty())
    }

    @Test
    fun unsafeConfiguredDevicePathIsRejectedAtConstruction() {
        val port = ScriptedCommandPort()
        assertFailsWith<IllegalArgumentException> {
            AcCommandController(port, "../other-device", nowEpochMillis = { nowMillis })
        }
        assertTrue(port.setStateCalls.isEmpty())
    }

    // --------------------------------------------------------------- port fakes

    private abstract class CommandPortStub : ThinQPort {
        override suspend fun listDevices(): List<ThinQDevice> = throw UnsupportedOperationException()
        override suspend fun profile(deviceId: String): ThinQDeviceProfile = throw UnsupportedOperationException()
        override suspend fun liveState(deviceId: String): ThinQLiveState = throw UnsupportedOperationException()
    }

    /**
     * Scripted [ThinQPort] recording every [setState] call and replaying a
     * queue of response blocks. Blocks run inside [setState], so a suspending
     * block keeps the command "in flight" exactly as a real port would.
     */
    private class ScriptedCommandPort(
        private val echoSubmittedCommand: Boolean = true,
    ) : CommandPortStub() {
        val setStateCalls = mutableListOf<AcCommand>()
        var otherMethodCalls = 0
            private set
        var activeCommands = 0
            private set
        var maxActiveCommands = 0
            private set

        private val queue = ArrayDeque<suspend () -> CommandOutcome>()
        var onSetStateEnter: (() -> Unit)? = null

        fun enqueue(result: CommandOutcome) { queue.addLast { result } }
        fun enqueue(block: suspend () -> CommandOutcome) { queue.addLast(block) }

        /** Blocks until the next setState has entered and is now in flight. */
        suspend fun awaitSetStateEntered() {
            val entered = CompletableDeferred<Unit>()
            onSetStateEnter = { entered.complete(Unit) }
            entered.await()
        }

        override suspend fun setState(command: AcCommand): CommandOutcome {
            setStateCalls += command
            activeCommands += 1
            if (activeCommands > maxActiveCommands) maxActiveCommands = activeCommands
            onSetStateEnter?.invoke()
            onSetStateEnter = null
            return try {
                val response = queue.removeFirstOrNull()
                    ?: error("ScriptedCommandPort: no queued setState response for submission ${setStateCalls.size}")
                // Normally the adapter echoes the submitted command. One test
                // disables this to exercise the controller's defensive check.
                val outcome = response()
                if (echoSubmittedCommand) outcome.copy(command = command) else outcome
            } finally {
                activeCommands -= 1
            }
        }

        override suspend fun listDevices(): List<ThinQDevice> {
            otherMethodCalls++
            throw UnsupportedOperationException("the command controller must never list devices")
        }

        override suspend fun profile(deviceId: String): ThinQDeviceProfile {
            otherMethodCalls++
            throw UnsupportedOperationException("the command controller must never read a profile")
        }

        override suspend fun liveState(deviceId: String): ThinQLiveState {
            otherMethodCalls++
            throw UnsupportedOperationException("the command controller must never read live state")
        }
    }
}
