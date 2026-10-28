package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Lifecycle/context boundary behavior (OpenSpec `deliver-shared-instrument-ui`
 * tasks 2.1/2.3, design D2) — the safe replacement for the pre-fix baseline
 * tests that intentionally asserted unsafe state retention.
 *
 * Verifies that the opaque owner/project/device + credential-generation key is
 * partitioned without credential material, that every invalidation trigger
 * (owner switch, sign-out, removal, replacement, protected-key loss) advances
 * it and clears presentation, that mask/timestamp equality never hides a
 * replacement while a failed same-owner replacement preserves the working
 * context, and that late old-context reads/command outcomes are discarded
 * instead of repopulating state. No real credential, Firebase, Keystore or
 * HTTP client is involved.
 */
class ThinQLifecycleInvalidationTest {

    private class Fixture(ownerUid: String? = "owner-a") {
        val store = FakeThinQCredentialStore()
        val port = ScriptedThinQPort()
        val verifier: FakeThinQCredentialVerifier = testVerifier()

        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = verifier,
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerUid,
            projectId = TEST_PROJECT_ID,
        )
    }

    // ------------------------------------------------------------- the key

    @Test
    fun contextKeyIsPartitionedByOwnerProjectDeviceAndGenerationWithoutCredentialMaterial() = runBlocking {
        val fixture = Fixture(ownerUid = "owner-a")
        fixture.store.save("synthetic-secret-marker-1234", "PH")

        val initial = fixture.coordinator.contextKey.value
        assertEquals(ThinQContextKey("owner-a", TEST_PROJECT_ID, TEST_DEVICE_ID, 0L), initial)
        val rendered = initial.toString()
        assertFalse(rendered.contains("synthetic", ignoreCase = true), "the key never carries the token")
        assertFalse(rendered.contains("1234"), "the key never carries token material")
        assertFalse(rendered.contains("pat", ignoreCase = true), "the key never carries credential material")

        fixture.coordinator.clearCachedDeviceProfile()
        val advanced = fixture.coordinator.contextKey.value
        assertEquals(
            initial.copy(credentialGeneration = 1L),
            advanced,
            "invalidation advances only the generation; the partition stays owner/project/device scoped",
        )
    }

    // ------------------------------------------------- invalidation triggers

    @Test
    fun ownerSwitchInvalidatesTheContextAndClearsPresentation() = runBlocking {
        val fixture = Fixture(ownerUid = "owner-a")
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        assertNotNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current)

        val before = fixture.coordinator.contextKey.value
        fixture.coordinator.onOwnerChanged("owner-b", TEST_PROJECT_ID)
        val after = fixture.coordinator.contextKey.value

        assertEquals("owner-b", after.ownerUid)
        assertTrue(after.credentialGeneration > before.credentialGeneration, "owner change advances the generation")
        assertNull(fixture.coordinator.deviceProfile.value, "the old owner's device/profile presentation is cleared")
        assertTrue(
            fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "the old owner's reading is cleared, not retained",
        )
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)

        // Idempotent: an unchanged partition never churns the generation.
        fixture.coordinator.onOwnerChanged("owner-b", TEST_PROJECT_ID)
        assertEquals(after, fixture.coordinator.contextKey.value)
    }

    @Test
    fun signOutInvalidatesTheOwnerPartitionAndClearsPresentation() = runBlocking {
        val fixture = Fixture(ownerUid = "owner-a")
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())

        fixture.coordinator.onOwnerChanged(null, TEST_PROJECT_ID)

        val key = fixture.coordinator.contextKey.value
        assertEquals(null, key.ownerUid, "sign-out moves the partition to the signed-out owner")
        assertTrue(key.credentialGeneration > 0L)
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
    }

    @Test
    fun credentialRemovalInvalidatesAndClearsTheProtectedStore() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val before = fixture.coordinator.contextKey.value

        val removal = fixture.coordinator.removeCredential()

        val after = fixture.coordinator.contextKey.value
        assertTrue(after.credentialGeneration > before.credentialGeneration, "removal advances the generation")
        assertEquals(after, removal.contextKey, "removal feedback is bound to its own resulting generation")
        assertTrue(removal.removed)
        assertEquals(null, fixture.store.readToken(), "the protected store is cleared")
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)

        // The Settings "no saved credential" path invalidates too (task 2.1).
        fixture.coordinator.clearCachedDeviceProfile()
        assertTrue(
            fixture.coordinator.contextKey.value.credentialGeneration > after.credentialGeneration,
            "clearing an absent credential's presentation also advances the generation",
        )
    }

    // ------------------------------------------------------ replacement rules

    @Test
    fun identicalMaskAndTimestampReplacementStillSupersedesTheWorkingContext() = runBlocking {
        val fixture = Fixture()
        fixture.store.save("synthetic-old-pat-1234", "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val live = fixture.coordinator.liveStateController
        assertTrue(live.state.value is ThinQLiveRefreshState.Current)

        val stateBefore = fixture.store.observe().first()
        val keyBefore = fixture.coordinator.contextKey.value

        // Same country, same last-four mask, same creation timestamp: the
        // observable credential state is byte-identical after the save.
        val result = fixture.coordinator.setupCredential("synthetic-new-pat-1234", "PH")

        assertIs<ThinQCredentialSetupResult.Success>(result)
        assertEquals(
            stateBefore,
            fixture.store.observe().first(),
            "same country, mask, client id and timestamp are not a context identity",
        )
        val after = fixture.coordinator.contextKey.value
        assertTrue(
            after.credentialGeneration > keyBefore.credentialGeneration,
            "mask/timestamp equality must not hide the replacement from the context key",
        )
        assertTrue(
            live.state.value is ThinQLiveRefreshState.NotRead,
            "the new credential context starts unread; the pre-replacement reading is gone",
        )
        assertEquals(
            after,
            fixture.coordinator.deviceProfile.value?.contextKey,
            "the verified profile belongs to the resulting generation, not the superseded one",
        )
    }

    @Test
    fun failedSameOwnerReplacementPreservesTheWorkingContext() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        fixture.verifier.result = ThinQVerificationResult.InvalidToken
        val keyBefore = fixture.coordinator.contextKey.value
        val publicationBefore = fixture.coordinator.deviceProfile.value

        val result = fixture.coordinator.setupCredential("synthetic-rejected-pat-5678", "PH")

        assertIs<ThinQCredentialSetupResult.Failure.InvalidToken>(result)
        assertEquals(
            keyBefore,
            fixture.coordinator.contextKey.value,
            "a failed same-owner replacement must not advance the generation",
        )
        assertEquals(publicationBefore, fixture.coordinator.deviceProfile.value, "the verified presentation survives")
        assertTrue(
            fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current,
            "the working credential context stays intact",
        )
        assertEquals(SEED_TOKEN, fixture.store.readToken(), "the previously working token is untouched")
    }

    // ------------------------------------------------------ protected-key loss

    @Test
    fun protectedKeyLossFromObservableStateInvalidatesTheContext() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val before = fixture.coordinator.contextKey.value

        fixture.coordinator.onCredentialState(
            ThinQCredentialState(present = false, clientId = fixture.store.clientId, unlockFailed = true),
        )

        assertTrue(
            fixture.coordinator.contextKey.value.credentialGeneration > before.credentialGeneration,
            "protected-key loss advances the generation",
        )
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
    }

    @Test
    fun protectedKeyLossDetectedAtEntryInvalidatesWithoutAnyLgCall() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val before = fixture.coordinator.contextKey.value
        val callsBefore = fixture.port.listCalls + fixture.port.profileCalls + fixture.port.liveCalls

        // The saved record is present but its protected key can no longer read it.
        fixture.store.keyUnavailable = true

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Unavailable, result)
        assertTrue(fixture.coordinator.contextKey.value.credentialGeneration > before.credentialGeneration)
        assertNull(fixture.coordinator.deviceProfile.value, "the old protected-key context is cleared")
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertEquals(
            callsBefore,
            fixture.port.listCalls + fixture.port.profileCalls + fixture.port.liveCalls,
            "an unreadable credential never reaches LG",
        )
    }

    @Test
    fun credentialStateObservationInvalidatesOnProtectedKeyLoss() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val before = fixture.coordinator.contextKey.value

        val watcher = fixture.coordinator.startCredentialStateObservation(this)
        yield() // the initial present-state emission must not invalidate
        assertEquals(before, fixture.coordinator.contextKey.value)

        fixture.store.keyUnavailable = true
        fixture.store.clear() // the fake models key loss as an unlockFailed emission
        yield()

        assertTrue(
            fixture.coordinator.contextKey.value.credentialGeneration > before.credentialGeneration,
            "an observed unlock failure invalidates the context",
        )
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        watcher.cancel()
    }

    // ------------------------------------------- late old-context completions

    @Test
    fun ownerSwitchDuringADeferredReadDiscardsTheLateOldOwnerResult() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(SEED_TOKEN, "PH")
        val ownerA = OwnerSession("lifecycle-owner-a", signedInAtEpochMillis = 1L)
        val ownerB = OwnerSession("lifecycle-owner-b", signedInAtEpochMillis = 2L)
        val identity = SwitchingIdentity(ownerA, ownerB)
        val account = ProductionAccountController(
            identity = identity,
            local = FakeLocalHistory(),
            firebaseProjectId = TEST_PROJECT_ID,
            configuredDeviceId = TEST_DEVICE_ID,
            credentialStore = store,
        )
        val port = ScriptedThinQPort()
        val liveStep = NetworkStep()
        port.onLiveState = {
            liveStep.hold()
            testLive()
        }
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerA.uid,
            projectId = TEST_PROJECT_ID,
        )

        // MainActivity's wiring: the owner session drives the lifecycle partition.
        val sessionWatcher = launch {
            identity.observeSession().collect { coordinator.onOwnerChanged(it?.uid, TEST_PROJECT_ID) }
        }

        account.requestChangeAccount()
        val entry = async { coordinator.onDeviceOpened() }
        liveStep.awaitEntered()

        val switched = account.changeAccountConfirmed()
        assertIs<OwnerSettingsActionResult.Success>(switched)
        assertEquals(ownerB, identity.currentSession)
        assertEquals(null, store.readToken(), "the old PAT was removed during the account change")
        yield() // let the session watcher apply the owner invalidation

        assertEquals(ownerB.uid, coordinator.contextKey.value.ownerUid, "the new owner's partition is active")
        assertNull(coordinator.deviceProfile.value, "the old owner's device/profile presentation is cleared")
        assertTrue(
            coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "the old owner's presentation is cleared before the deferred read completes",
        )

        liveStep.release()
        assertEquals(ThinQEntryResult.Superseded, entry.await())
        assertTrue(
            coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "the late old-owner read is discarded; it never becomes current state for the new owner",
        )
        sessionWatcher.cancel()
    }

    @Test
    fun credentialRemovalDoesNotPublishADeferredOldCommandOutcome() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        val setStateStep = NetworkStep()
        fixture.port.onSetState = { command ->
            setStateStep.hold()
            testOutcome(TEST_DEVICE_ID, command)
        }
        val command = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_000L, powerOn = false)
        val keyAtTap = fixture.coordinator.contextKey.value

        val submission = async { fixture.coordinator.commandController.submit(command, keyAtTap) }
        setStateStep.awaitEntered()

        fixture.coordinator.removeCredential()

        setStateStep.release()
        assertEquals(
            AcCommandSubmitResult.Submitted,
            submission.await(),
            "the already-dispatched command keeps its honest path: it may have been sent and is never resent",
        )
        assertTrue(
            fixture.coordinator.commandController.state.value is AcCommandUiState.Idle,
            "the late old-context outcome must not become presentation for the active context",
        )
        assertEquals(1, fixture.port.commandCalls, "the command reached the port exactly once and was never resent")
        assertEquals(null, fixture.store.readToken())
    }

    @Test
    fun lateCommandConfirmationCannotRepublishLiveStateAfterContextInvalidation() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val oldKey = fixture.coordinator.contextKey.value
        val command = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_100L, powerOn = false)
        val lateOutcome = testOutcome(TEST_DEVICE_ID, command)

        fixture.coordinator.removeCredential()
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)

        // Models submitAndPresentCommand receiving a confirmation after the
        // context changed; it must pass the tap-time key to this publication.
        fixture.coordinator.liveStateController.acceptCommandOutcome(lateOutcome, oldKey)

        assertTrue(
            fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "a late confirmation read cannot revive live state for the active context",
        )
    }

    @Test
    fun scheduledCommandSupersededBeforeDispatchIsNeverSent() = runBlocking {
        val fixture = Fixture()
        fixture.store.save(SEED_TOKEN, "PH")
        val command = AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_000L, powerOn = false)
        val keyAtTap = fixture.coordinator.contextKey.value

        // The replacement is verified and saved while the tapped command is
        // still scheduled: its captured context is now superseded.
        val replacement = fixture.coordinator.setupCredential("synthetic-scheduled-pat-5678", "PH")
        assertIs<ThinQCredentialSetupResult.Success>(replacement)

        val result = fixture.coordinator.commandController.submit(command, keyAtTap)

        assertEquals(AcCommandSubmitResult.IgnoredStaleContext, result)
        assertEquals(
            0,
            fixture.port.commandCalls,
            "stale scheduled work never dispatches and never borrows the replacement credential",
        )
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)
    }
}
