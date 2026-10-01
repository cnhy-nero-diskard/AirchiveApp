package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.FanSpeed
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Deterministic tests for [ThinQLiveStateController] (OpenSpec task 4.3), driven
 * by a thin scripted [ThinQPort] fake. Proves: device entry triggers a live
 * request; explicit refresh re-reads despite a recent Current; the adapter's
 * observedAt is never replaced by the injected handset clock; a first-read
 * failure yields Unavailable (never fake/current values); a later failure keeps
 * the old state as Stale with an unchanged timestamp and never as current;
 * cancellation propagates without an error state; concurrent refreshes serialize
 * without mixed snapshots; failure categories are stable and contain no raw
 * exception messages; and current-only command gating needs a separately
 * verified profile.
 */
class ThinQLiveStateControllerTest {

    private val deviceId = "ac-device-1"
    private val handsetNow = 1_800_000_000_000L
    private val adapterReadAt = 1_795_000_000_000L

    private fun liveState(observedAt: Long) = ThinQLiveState(
        deviceId = deviceId,
        observedAtEpochMillis = observedAt,
        powerOn = true,
        targetTemperature = 25.0,
        mode = AcOperatingMode.COOL,
        fanSpeed = FanSpeed.LOW,
    )

    // ------------------------------------------------- entry triggers a read

    @Test
    fun deviceEntryTriggersADirectLiveRequestForExactlyTheConfiguredDevice() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also { it.enqueueResult(liveState(100L)) }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.onDeviceOpened()

        assertEquals(listOf(deviceId), port.liveStateCalls, "only the fixed configured id may be requested")
        assertEquals(0, port.otherMethodCalls, "entry must never touch profile/command/list methods")
        val current = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(100L, current.state.observedAtEpochMillis)
    }

    // --------------------------------------- explicit refresh bypasses cache

    @Test
    fun explicitRefreshIssuesANewFreshRequestDespiteARecentCurrentRead() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(100L))
            it.enqueueResult(liveState(200L))
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })
        controller.onDeviceOpened()
        assertIs<ThinQLiveRefreshState.Current>(controller.state.value)

        controller.refresh()

        assertEquals(2, port.liveStateCalls.size, "an explicit refresh must bypass any cached/freshness gate")
        val current = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(200L, current.state.observedAtEpochMillis)
    }

    @Test
    fun loadingPublishesThePriorSnapshotWhileARefreshIsInFlight() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also { it.enqueueResult(liveState(100L)) }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })
        controller.onDeviceOpened()
        val prior = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)

        val gate = CompletableDeferred<Unit>()
        port.enqueueResult { gate.await(); liveState(200L) }
        val refreshed = async { controller.refresh() }
        port.awaitLiveStateEntered()
        val loading = assertIs<ThinQLiveRefreshState.Loading>(controller.state.value)
        val previous = assertIs<ThinQLiveRefreshState.Current>(loading.previous)
        assertEquals(prior.state, previous.state)

        gate.complete(Unit)
        refreshed.await()
        assertEquals(200L, assertIs<ThinQLiveRefreshState.Current>(controller.state.value).state.observedAtEpochMillis)
    }

    // ----------------------------------------- observedAt is the adapter time

    @Test
    fun observedAtComesFromTheAdapterReadNotTheControllerClock() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(adapterReadAt))
            it.enqueueResult(liveState(adapterReadAt + 1))
            it.enqueueFailure(ThinQClientException.Network)
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.onDeviceOpened()
        val current = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(adapterReadAt, current.state.observedAtEpochMillis, "read time is the adapter's, passed through")
        assertNotEquals(handsetNow, current.state.observedAtEpochMillis, "the handset clock must never be stamped on a read")

        controller.refresh()
        val fresh = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(adapterReadAt + 1, fresh.state.observedAtEpochMillis)

        controller.refresh()
        val stale = assertIs<ThinQLiveRefreshState.Stale>(controller.state.value)
        assertEquals(adapterReadAt + 1, stale.lastKnownState.observedAtEpochMillis, "retained timestamp stays the adapter's")
        assertEquals(handsetNow, stale.failedAtEpochMillis, "only the failure label uses the handset clock")
    }

    // ------------------------------------------------------ first-read failure

    @Test
    fun firstReadNetworkFailureYieldsUnavailableAndNeverFakeCurrentValues() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also { it.enqueueFailure(ThinQClientException.Network) }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.onDeviceOpened()

        val state = assertIs<ThinQLiveRefreshState.Unavailable>(controller.state.value)
        assertEquals(ThinQRefreshFailureKind.NETWORK, state.errorKind)
        assertFalse(controller.state.value is ThinQLiveRefreshState.Current, "a failed first read is never fake current")
        assertFalse(controller.state.value is ThinQLiveRefreshState.Stale, "nothing was ever read to retain")
    }

    @Test
    fun mismatchedDeviceResponseIsUnavailableInsteadOfStickingInLoading() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(adapterReadAt).copy(deviceId = "other-device"))
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.onDeviceOpened()

        val unavailable = assertIs<ThinQLiveRefreshState.Unavailable>(controller.state.value)
        assertEquals(ThinQRefreshFailureKind.MALFORMED_RESPONSE, unavailable.errorKind)
        assertEquals(listOf(deviceId), port.liveStateCalls)
    }

    @Test
    fun explicitRefreshAfterUnavailableStaysUnavailableUntilASuccess() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueFailure(ThinQClientException.Timeout)
            it.enqueueFailure(ThinQClientException.Timeout)
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })
        controller.onDeviceOpened()
        controller.refresh()
        assertIs<ThinQLiveRefreshState.Unavailable>(controller.state.value)
        assertEquals(2, port.liveStateCalls.size, "each refresh is a fresh attempt, even after failures")
    }

    // ---------------------------------------------------- success then failure

    @Test
    fun failedReadAfterSuccessRetainsLastStateOnlyAsStaleWithUnchangedTimestamp() = runBlocking {
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(adapterReadAt))
            it.enqueueFailure(ThinQClientException.Timeout)
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.onDeviceOpened()
        controller.refresh()

        assertFalse(controller.state.value is ThinQLiveRefreshState.Current, "a retained state must never be presented as current")
        val state = assertIs<ThinQLiveRefreshState.Stale>(controller.state.value)
        assertEquals(liveState(adapterReadAt), state.lastKnownState, "the retained snapshot is exactly the last success")
        assertEquals(adapterReadAt, state.lastKnownState.observedAtEpochMillis, "retained timestamp is unchanged")
        assertEquals(ThinQRefreshFailureKind.TIMEOUT, state.errorKind)
    }

    // ------------------------------------------------------- command gating

    @Test
    fun commandGatingIsNeverEnabledExceptForCurrentWithAVerifiedProfile() = runBlocking {
        val unverifiedPort = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(100L))
            it.enqueueFailure(ThinQClientException.Network)
        }
        val unverified = ThinQLiveStateController(unverifiedPort, deviceId, nowEpochMillis = { handsetNow }) // no verified profile
        assertFalse(unverified.state.value.canIssueCommands, "NotRead never enables commands")

        unverified.onDeviceOpened()
        val current = assertIs<ThinQLiveRefreshState.Current>(unverified.state.value)
        assertEquals(100L, current.state.observedAtEpochMillis)
        assertFalse(current.canIssueCommands, "a live-state read alone never enables controls")
        assertFalse(current.profileVerified)

        unverified.refresh()
        val stale = assertIs<ThinQLiveRefreshState.Stale>(unverified.state.value)
        assertFalse(stale.canIssueCommands, "Stale never enables commands")
        assertFalse(unverified.state.value.canIssueCommands)

        val verifiedPort = ScriptedThinQPort(deviceId).also { it.enqueueResult(liveState(100L)) }
        val verified = ThinQLiveStateController(verifiedPort, deviceId, hasVerifiedProfile = { true })
        verified.onDeviceOpened()
        val verifiedCurrent = assertIs<ThinQLiveRefreshState.Current>(verified.state.value)
        assertTrue(verifiedCurrent.profileVerified)
        assertTrue(verifiedCurrent.canIssueCommands, "Current enables commands only with a separately verified profile")
    }

    // -------------------------------------------------------- cancellation

    @Test
    fun cancellationPropagatesAndNeverPersistsAnErrorState() = runBlocking {
        val port = GatePort(deviceId)
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        val deferred = async { controller.onDeviceOpened() }
        port.awaitEntered()
        assertIs<ThinQLiveRefreshState.Loading>(controller.state.value)

        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }

        assertEquals(
            ThinQLiveRefreshState.NotRead,
            controller.state.value,
            "cancellation restores the pre-flight snapshot; it is never an Unavailable/Stale error state",
        )
        assertFalse(controller.state.value is ThinQLiveRefreshState.Loading, "no phantom Loading survives cancellation")
    }

    @Test
    fun cancellationWhileWaitingForTheMutexPropagatesWithoutChangingState() = runBlocking {
        val port = GatePort(deviceId)
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        val first = async { controller.refresh() }
        port.awaitEntered()
        assertIs<ThinQLiveRefreshState.Loading>(controller.state.value)

        val second = async { controller.refresh() }
        yieldOnce()
        second.cancel()
        assertFailsWith<CancellationException> { second.await() }

        val untouched = assertIs<ThinQLiveRefreshState.Loading>(controller.state.value, "the first in-flight read is untouched")
        assertEquals(null, untouched.previous, "the in-flight read still started from NotRead")
        assertFalse(controller.state.value is ThinQLiveRefreshState.Unavailable)

        first.cancel()
        assertFailsWith<CancellationException> { first.await() }
        assertEquals(
            ThinQLiveRefreshState.NotRead,
            controller.state.value,
            "cancel while waiting never leaves a stuck Loading or an error state",
        )
    }

    // ------------------------------------------- concurrency serialization

    @Test
    fun concurrentRefreshCallsSerializeAndNeverMixSnapshots() = runBlocking {
        val port = ConcurrentPort(deviceId)
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        val first = async { controller.refresh() }
        val second = async { controller.refresh() }
        first.await()
        second.await()

        assertEquals(2, port.liveStateCalls)
        assertEquals(1, port.maxActive, "the controller's mutex must serialize the two live reads")
        val current = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(port.lastObservedAtEpochMillis, current.state.observedAtEpochMillis,
            "the published snapshot equals the final serialized read, never a mix")
    }

    @Test
    fun entryAndRefreshInterleavedStaySerializedAndFinishAsCurrent() = runBlocking {
        val port = ConcurrentPort(deviceId)
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        val entry = async { controller.onDeviceOpened() }
        val refresh = async { controller.refresh() }
        entry.await()
        refresh.await()

        assertEquals(2, port.liveStateCalls)
        assertEquals(1, port.maxActive)
        val current = assertIs<ThinQLiveRefreshState.Current>(controller.state.value)
        assertEquals(deviceId, current.state.deviceId, "final snapshot is a coherent single read, not a mix")
    }

    // ------------------------------------------------ failure kind mapping

    @Test
    fun everyThinQFailureClassifiesToAStableKind() {
        val cases = listOf(
            ThinQClientException.MissingCredential to ThinQRefreshFailureKind.NO_CREDENTIAL,
            ThinQClientException.InvalidCredential to ThinQRefreshFailureKind.INVALID_CREDENTIAL,
            ThinQClientException.Network to ThinQRefreshFailureKind.NETWORK,
            ThinQClientException.Timeout to ThinQRefreshFailureKind.TIMEOUT,
            ThinQClientException.UnsupportedCountry to ThinQRefreshFailureKind.UNSUPPORTED_COUNTRY,
            ThinQClientException.MalformedResponse to ThinQRefreshFailureKind.MALFORMED_RESPONSE,
            ThinQClientException.RemoteFailure(ThinQRemoteError.InvalidToken) to ThinQRefreshFailureKind.INVALID_TOKEN,
            ThinQClientException.RemoteFailure(ThinQRemoteError.WrongAccount) to ThinQRefreshFailureKind.WRONG_ACCOUNT,
            ThinQClientException.RemoteFailure(ThinQRemoteError.DeviceUnavailable) to ThinQRefreshFailureKind.DEVICE_UNAVAILABLE,
            ThinQClientException.RemoteFailure(ThinQRemoteError.RateLimited) to ThinQRefreshFailureKind.RATE_LIMITED,
            ThinQClientException.RemoteFailure(ThinQRemoteError.NoControlAuthority) to ThinQRefreshFailureKind.NO_CONTROL_AUTHORITY,
            ThinQClientException.RemoteFailure(ThinQRemoteError.Transient) to ThinQRefreshFailureKind.TRANSIENT,
            ThinQClientException.RemoteFailure(ThinQRemoteError.InvalidRequest) to ThinQRefreshFailureKind.INVALID_REQUEST,
            ThinQClientException.RemoteFailure(ThinQRemoteError.MalformedOrUnknown) to ThinQRefreshFailureKind.UNKNOWN,
            IllegalStateException("opaque generic failure") to ThinQRefreshFailureKind.UNKNOWN,
        )
        for ((cause, expected) in cases) {
            assertEquals(expected, classifyRefreshFailure(cause), "classification for $cause")
        }
    }

    @Test
    fun readFailureSurfacesTheStableKindForEachCategory() = runBlocking {
        val cases = listOf(
            ThinQClientException.MissingCredential to ThinQRefreshFailureKind.NO_CREDENTIAL,
            ThinQClientException.InvalidCredential to ThinQRefreshFailureKind.INVALID_CREDENTIAL,
            ThinQClientException.UnsupportedCountry to ThinQRefreshFailureKind.UNSUPPORTED_COUNTRY,
            ThinQClientException.RemoteFailure(ThinQRemoteError.RateLimited) to ThinQRefreshFailureKind.RATE_LIMITED,
            ThinQClientException.RemoteFailure(ThinQRemoteError.DeviceUnavailable) to ThinQRefreshFailureKind.DEVICE_UNAVAILABLE,
        )
        for ((cause, expected) in cases) {
            val port = ScriptedThinQPort(deviceId).also { it.enqueueFailure(cause) }
            val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })
            controller.refresh()
            val state = assertIs<ThinQLiveRefreshState.Unavailable>(controller.state.value)
            assertEquals(expected, state.errorKind, "surfaced kind for $cause")
        }
    }

    // --------------------------------------------- no raw messages ever

    @Test
    fun errorStatesNeverContainRawExceptionMessagesOrTokens() = runBlocking {
        val secretMessage = "synthetic-pat.example.invalid-7f3d leaked on the wire"
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueFailure(IllegalStateException(secretMessage))
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })

        controller.refresh()

        val state = assertIs<ThinQLiveRefreshState.Unavailable>(controller.state.value)
        assertEquals(ThinQRefreshFailureKind.UNKNOWN, state.errorKind)
        val stateText = state.toString()
        assertFalse(secretMessage in stateText, "a failure snapshot must never render the raw exception message")
        assertFalse(secretMessage in state.errorKind.name)
        assertFalse(secretMessage in controller.state.value.toString())
    }

    @Test
    fun remoteFailureTextStaysOutOfThePublishedState() = runBlocking {
        val rawServerText = "raw lg server text for synthetic-pat.example.invalid-zz9a"
        val port = ScriptedThinQPort(deviceId).also {
            it.enqueueResult(liveState(adapterReadAt))
            it.enqueueFailure(ThinQClientException.RemoteFailure(ThinQRemoteError.InvalidToken))
        }
        val controller = ThinQLiveStateController(port, deviceId, nowEpochMillis = { handsetNow })
        controller.onDeviceOpened()
        controller.refresh()

        val state = assertIs<ThinQLiveRefreshState.Stale>(controller.state.value)
        assertEquals(ThinQRefreshFailureKind.INVALID_TOKEN, state.errorKind)
        assertFalse(rawServerText in state.toString())
        assertFalse(rawServerText in state.lastKnownState.toString())
    }

    // ------------------------------------------------------ construction guard

    @Test
    fun blankConfiguredDeviceIdIsRejectedAtConstruction() {
        val port = ScriptedThinQPort(deviceId)
        assertFailsWith<IllegalArgumentException> {
            ThinQLiveStateController(port, "   ")
        }
        assertEquals(emptyList(), port.liveStateCalls)
    }

    @Test
    fun unsafeConfiguredDevicePathIsRejectedAtConstruction() {
        val port = ScriptedThinQPort(deviceId)
        assertFailsWith<IllegalArgumentException> {
            ThinQLiveStateController(port, "../other-device")
        }
        assertEquals(emptyList(), port.liveStateCalls)
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun yieldOnce() = kotlinx.coroutines.yield()

    /** Scripted [ThinQPort] that records calls and replays a queue of responses/failures. */
    private class ScriptedThinQPort(private val deviceId: String) : ThinQPortStub() {
        val liveStateCalls = mutableListOf<String>()
        var otherMethodCalls = 0
        private val queue = ArrayDeque<suspend () -> ThinQLiveState>()
        var onLiveStateEnter: (() -> Unit)? = null

        fun enqueueResult(state: ThinQLiveState) { queue.addLast { state } }
        fun enqueueResult(block: suspend () -> ThinQLiveState) { queue.addLast(block) }
        fun enqueueFailure(cause: Exception) { queue.addLast { throw cause } }

        suspend fun awaitLiveStateEntered() {
            val entered = CompletableDeferred<Unit>()
            onLiveStateEnter = { entered.complete(Unit) }
            entered.await()
        }

        override suspend fun liveState(deviceId: String): ThinQLiveState {
            liveStateCalls += deviceId
            onLiveStateEnter?.invoke()
            onLiveStateEnter = null
            val response = queue.removeFirstOrNull()
                ?: error("ScriptedThinQPort: no scripted liveState response for call ${liveStateCalls.size}")
            return response()
        }

        override suspend fun listDevices(): List<ThinQDevice> { otherMethodCalls++; throw UnsupportedOperationException("listDevices is not used by the controller") }
        override suspend fun profile(deviceId: String): ThinQDeviceProfile { otherMethodCalls++; throw UnsupportedOperationException("profile is not used by the controller") }
        override suspend fun setState(command: AcCommand): CommandOutcome { otherMethodCalls++; throw UnsupportedOperationException("setState is not used by the controller") }
    }

    /** Suspends inside liveState until the caller cancels, then lets cancellation propagate. */
    private class GatePort(private val deviceId: String) : ThinQPortStub() {
        private val entered = CompletableDeferred<Unit>()
        suspend fun awaitEntered() = entered.await()

        override suspend fun liveState(deviceId: String): ThinQLiveState {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    /** Counts concurrent entries and stamps a unique observedAt per completed read. */
    private class ConcurrentPort(private val deviceId: String) : ThinQPortStub() {
        var liveStateCalls = 0
        var active = 0
        var maxActive = 0
        var lastObservedAtEpochMillis = 0L

        override suspend fun liveState(deviceId: String): ThinQLiveState {
            liveStateCalls += 1
            active += 1
            if (active > maxActive) maxActive = active
            delay(30)
            active -= 1
            lastObservedAtEpochMillis += 10
            return ThinQLiveState(
                deviceId = deviceId,
                observedAtEpochMillis = lastObservedAtEpochMillis,
                powerOn = true,
            )
        }
    }

    /** Minimal base so single-method-focus fakes only implement liveState. */
    private abstract class ThinQPortStub : ThinQPort {
        override suspend fun listDevices(): List<ThinQDevice> = throw UnsupportedOperationException()
        override suspend fun profile(deviceId: String): ThinQDeviceProfile = throw UnsupportedOperationException()
        override suspend fun setState(command: AcCommand): CommandOutcome = throw UnsupportedOperationException()
    }
}
