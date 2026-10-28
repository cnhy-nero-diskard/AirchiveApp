package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.data.onboarding.ProductionAccountController
import cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Setup-attempt fence at the protected-store commit (OpenSpec
 * `deliver-shared-instrument-ui` task 2.4, design D2).
 *
 * Every test drives the real [ThinQContextCoordinator] over in-memory fakes
 * only — no real credential, Firebase, Keystore or HTTP client is constructed,
 * and every token is a synthetic `synthetic-*` fixture marker. The verification
 * round trip is held open with a [NetworkStep] so an account change, removal,
 * explicit cancellation, coroutine cancellation or a newer attempt can
 * deterministically interleave with an in-flight verification.
 *
 * The properties proven here:
 *
 *  - the final attempt/context check and the protected-store save are one
 *    boundary transition: a stale/cancelled/superseded attempt records **zero**
 *    save calls and never advances the credential generation, publishes a
 *    profile, or becomes success feedback;
 *  - a valid current attempt atomically saves the verified candidate, advances
 *    the generation (even with identical masked metadata), resets live/command
 *    state to unread/idle, publishes its verified profile under the resulting
 *    key, and returns a success whose masked credential agrees with the store;
 *  - a same-owner failed verification/save preserves the prior working
 *    token/context;
 *  - concurrent account (history-locked), profile and setup work never
 *    deadlock: LG verification runs with no lock held and the boundary is only
 *    re-entered for the short commit.
 */
class ThinQSetupAttemptFenceTest {

    private class Fixture(ownerUid: String? = "owner-a") {
        val store = CountingCredentialStore()
        val verifier = GateableVerifier()
        val port = ScriptedThinQPort()

        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = verifier,
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerUid,
            projectId = TEST_PROJECT_ID,
        )

        suspend fun seedCredential(token: String = SEED_TOKEN) {
            store.save(token, "PH")
        }
    }

    // ------------------------------------ (a) account change during verification

    @Test
    fun accountChangeDuringVerificationSupersedesTheAttemptWithoutSavingOrPublishing() = runBlocking {
        val fixture = Fixture(ownerUid = "setup-owner-a")
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls
        val identity = SwitchingIdentity(
            OwnerSession("setup-owner-a", signedInAtEpochMillis = 1L),
            OwnerSession("setup-owner-b", signedInAtEpochMillis = 2L),
        )
        // The real Settings account path: history partition clear, then the PAT
        // clear, then the identity change — with the lifecycle invalidation
        // hooks MainActivity wires around it.
        val account = ProductionAccountController(
            identity = identity,
            local = FakeLocalHistory(),
            firebaseProjectId = TEST_PROJECT_ID,
            configuredDeviceId = TEST_DEVICE_ID,
            credentialStore = fixture.store,
            beforeOwnerTransition = { fixture.coordinator.onOwnerChanged(null, TEST_PROJECT_ID) },
            afterOwnerTransition = { fixture.coordinator.onOwnerChanged(it?.uid, TEST_PROJECT_ID) },
        )

        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val attempt = async { fixture.coordinator.setupCredential("synthetic-late-pat-7777", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }

        account.requestChangeAccount()
        assertIs<OwnerSettingsActionResult.Success>(
            withTimeout(SETUP_TIMEOUT_MS) { account.changeAccountConfirmed() },
        )

        verifyStep.release()
        assertIs<ThinQCredentialSetupResult.Superseded>(
            withTimeout(SETUP_TIMEOUT_MS) { attempt.await() },
            "the delayed verified candidate must be refused after the account changed",
        )

        assertEquals(
            savesBefore,
            fixture.store.saveCalls,
            "a superseded attempt must record zero protected-store saves",
        )
        assertEquals("setup-owner-b", fixture.coordinator.contextKey.value.ownerUid)
        assertTrue(
            fixture.coordinator.contextKey.value.credentialGeneration > keyBefore.credentialGeneration,
            "only the account transition advanced the generation — never the stale attempt",
        )
        assertNull(fixture.coordinator.deviceProfile.value, "no verified profile is published for the stale attempt")
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)
        assertNull(fixture.store.readToken(), "the outgoing PAT was removed by the account change, not saved by the attempt")
    }

    // ------------------------------------- (b) credential removal during verification

    @Test
    fun credentialRemovalDuringVerificationSupersedesTheAttemptWithoutSaving() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls
        val clearsBefore = fixture.store.clearCalls

        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val attempt = async { fixture.coordinator.setupCredential("synthetic-removed-pat-3333", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }

        fixture.coordinator.removeCredential()

        verifyStep.release()
        assertIs<ThinQCredentialSetupResult.Superseded>(withTimeout(SETUP_TIMEOUT_MS) { attempt.await() })

        assertEquals(savesBefore, fixture.store.saveCalls, "the stale attempt must never re-save after the removal")
        assertEquals(clearsBefore + 1, fixture.store.clearCalls, "exactly the removal cleared the store")
        assertNull(fixture.store.readToken())
        assertTrue(fixture.coordinator.contextKey.value.credentialGeneration > keyBefore.credentialGeneration)
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)
    }

    // -------------------------- (c) explicit cancellation / newer attempt supersede

    @Test
    fun explicitCancelHookSupersedesOnlyTheInFlightAttemptAndANextAttemptCommits() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls

        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val cancelledAttempt = async { fixture.coordinator.setupCredential("synthetic-cancel-pat-4444", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }

        // Explicit form/route cancellation of the in-flight attempt.
        fixture.coordinator.cancelSetupAttempt()

        verifyStep.release()
        assertIs<ThinQCredentialSetupResult.Superseded>(withTimeout(SETUP_TIMEOUT_MS) { cancelledAttempt.await() })
        assertEquals(savesBefore, fixture.store.saveCalls, "the cancelled attempt never saves")
        assertEquals(
            keyBefore,
            fixture.coordinator.contextKey.value,
            "cancellation supersedes the attempt without advancing the generation",
        )
        assertEquals(SEED_TOKEN, fixture.store.readToken(), "the working token survives the cancelled attempt")

        // The next attempt for the same owner starts fresh and commits.
        fixture.verifier.onVerify = null
        val fresh = assertIs<ThinQCredentialSetupResult.Success>(
            fixture.coordinator.setupCredential("synthetic-fresh-pat-5555", "PH"),
        )
        assertEquals(savesBefore + 1, fixture.store.saveCalls)
        assertEquals(
            keyBefore.credentialGeneration + 1L,
            fixture.coordinator.contextKey.value.credentialGeneration,
            "exactly the fresh commit advanced the generation — the cancelled attempt contributed nothing",
        )
        assertEquals(fixture.coordinator.contextKey.value, fixture.coordinator.deviceProfile.value?.contextKey)
        assertEquals(fixture.store.observe().first(), fresh.credential, "the success mask agrees with the store")
    }

    @Test
    fun newerAttemptSupersedesTheStillVerifyingOlderOneWithoutDoubleSaving() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls

        val firstStep = NetworkStep()
        fixture.verifier.onVerify = { firstStep.hold() }
        val first = async { fixture.coordinator.setupCredential("synthetic-first-pat-6666", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { firstStep.awaitEntered() }

        val secondStep = NetworkStep()
        fixture.verifier.onVerify = { secondStep.hold() }
        val second = async { fixture.coordinator.setupCredential("synthetic-second-pat-7777", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { secondStep.awaitEntered() }

        firstStep.release()
        assertIs<ThinQCredentialSetupResult.Superseded>(
            withTimeout(SETUP_TIMEOUT_MS) { first.await() },
            "the older attempt was superseded by the newer one, even for the unchanged owner",
        )
        assertEquals(savesBefore, fixture.store.saveCalls, "the superseded older attempt records zero saves")

        secondStep.release()
        assertIs<ThinQCredentialSetupResult.Success>(withTimeout(SETUP_TIMEOUT_MS) { second.await() })
        assertEquals(savesBefore + 1, fixture.store.saveCalls, "only the current attempt saves, exactly once")
        assertEquals("synthetic-second-pat-7777", fixture.store.readToken())
        assertEquals(
            keyBefore.credentialGeneration + 1L,
            fixture.coordinator.contextKey.value.credentialGeneration,
            "exactly one generation transition happened — no double advance from the stale attempt",
        )
        assertEquals(fixture.coordinator.contextKey.value, fixture.coordinator.deviceProfile.value?.contextKey)
    }

    @Test
    fun coroutineCancellationSupersedesOnlyThatAttemptAndALaterAttemptCommits() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls

        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val cancelled = async { fixture.coordinator.setupCredential("synthetic-coroutine-cancel-pat-8888", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }

        // Cancelling the coroutine supersedes exactly this attempt: it never
        // reaches the commit gate, and nothing else is invalidated.
        cancelled.cancelAndJoin()
        verifyStep.release()

        assertEquals(savesBefore, fixture.store.saveCalls, "the cancelled coroutine's attempt never saves")
        assertEquals(keyBefore, fixture.coordinator.contextKey.value)
        assertEquals(SEED_TOKEN, fixture.store.readToken())

        fixture.verifier.onVerify = null
        assertIs<ThinQCredentialSetupResult.Success>(
            fixture.coordinator.setupCredential("synthetic-after-cancel-pat-9999", "PH"),
        )
        assertEquals(savesBefore + 1, fixture.store.saveCalls)
        assertEquals(
            keyBefore.credentialGeneration + 1L,
            fixture.coordinator.contextKey.value.credentialGeneration,
            "the later attempt commits exactly once after the cancelled one",
        )
    }

    // -------------------- (d) valid current commit activates the new generation

    @Test
    fun validCurrentCommitAtomicallyActivatesTheNewGenerationProfileAndUnreadLiveState() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential("synthetic-working-pat-1111")
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current)
        val keyBefore = fixture.coordinator.contextKey.value
        val savesBefore = fixture.store.saveCalls
        val committedToken = "synthetic-committed-pat-2222"

        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val attempt = async { fixture.coordinator.setupCredential(committedToken, "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }
        verifyStep.release()

        val success = assertIs<ThinQCredentialSetupResult.Success>(withTimeout(SETUP_TIMEOUT_MS) { attempt.await() })

        // One atomic transition: save + generation advance for the unchanged
        // owner/project/device partition.
        val newKey = fixture.coordinator.contextKey.value
        assertEquals(keyBefore.copy(credentialGeneration = keyBefore.credentialGeneration + 1L), newKey)
        assertEquals(savesBefore + 1, fixture.store.saveCalls, "the verified candidate saved exactly once")

        // Live/command presentation resets to unread/idle for the new context.
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertTrue(fixture.coordinator.commandController.state.value is AcCommandUiState.Idle)

        // The verified profile is published under the resulting key.
        val publication = assertNotNull(fixture.coordinator.deviceProfile.value)
        assertEquals(newKey, publication.contextKey)
        assertEquals(TEST_DEVICE_ID, publication.device.deviceId)
        assertEquals(TEST_DEVICE_ID, publication.profile?.deviceId)

        // The returned masked credential agrees with the store and the new
        // context; the raw token never appears in the result.
        assertEquals(fixture.store.observe().first(), success.credential)
        assertEquals(committedToken.takeLast(4), assertNotNull(success.credential.credential).maskedToken.takeLast(4))
        assertFalse(success.toString().contains(committedToken))
    }

    // --------------------- (e) same-owner failed replacement retains the working one

    @Test
    fun sameOwnerFailedVerificationRetainsTheWorkingCredentialAndContext() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val keyBefore = fixture.coordinator.contextKey.value
        val publicationBefore = fixture.coordinator.deviceProfile.value
        val savesBefore = fixture.store.saveCalls
        fixture.verifier.result = ThinQVerificationResult.InvalidToken

        val result = fixture.coordinator.setupCredential("synthetic-rejected-pat-3333", "PH")

        assertIs<ThinQCredentialSetupResult.Failure.InvalidToken>(result)
        assertEquals(keyBefore, fixture.coordinator.contextKey.value, "a failed same-owner verification never advances the generation")
        assertEquals(publicationBefore, fixture.coordinator.deviceProfile.value, "the verified presentation survives")
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current)
        assertEquals(savesBefore, fixture.store.saveCalls, "a failed verification never reaches the protected-store save")
        assertEquals(SEED_TOKEN, fixture.store.readToken(), "the previously working token is untouched")
    }

    @Test
    fun sameOwnerFailedStoreSaveRetainsTheWorkingCredentialAndContext() = runBlocking {
        val delegate = FakeThinQCredentialStore()
        delegate.save(SEED_TOKEN, "PH")
        val store = SaveFailingCredentialStore(delegate)
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = ScriptedThinQPort(),
            verifier = GateableVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = "owner-a",
            projectId = TEST_PROJECT_ID,
        )
        assertEquals(ThinQEntryResult.Success, coordinator.onDeviceOpened())
        val keyBefore = coordinator.contextKey.value
        val publicationBefore = coordinator.deviceProfile.value

        // The gate passes (the attempt is current), the save runs inside the
        // commit section, and it fails: no generation advance, no reset.
        val result = coordinator.setupCredential("synthetic-store-fail-pat-4444", "PH")

        assertIs<ThinQCredentialSetupResult.Failure.StoreSaveFailure>(result)
        assertEquals(keyBefore, coordinator.contextKey.value, "a failed same-owner save never advances the generation")
        assertEquals(publicationBefore, coordinator.deviceProfile.value)
        assertTrue(coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current)
        assertEquals(SEED_TOKEN, delegate.readToken(), "the failed replacement kept the working token")
    }

    // ------------------------- (f) concurrent account/profile/history interaction

    @Test
    fun concurrentVerificationAccountHistoryAndProfileWorkDoNotDeadlock() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val savesBefore = fixture.store.saveCalls

        // A second Device entry suspended in the LG live read (its cached
        // device/profile publication already matches the captured context).
        val liveStep = NetworkStep()
        fixture.port.onLiveState = {
            liveStep.hold()
            testLive()
        }
        val entry = async { fixture.coordinator.onDeviceOpened() }
        withTimeout(SETUP_TIMEOUT_MS) { liveStep.awaitEntered() }

        // A setup attempt suspended in LG verification.
        val verifyStep = NetworkStep()
        fixture.verifier.onVerify = { verifyStep.hold() }
        val setup = async { fixture.coordinator.setupCredential("synthetic-concurrent-pat-9012", "PH") }
        withTimeout(SETUP_TIMEOUT_MS) { verifyStep.awaitEntered() }

        // A confirmed sign-out holding the history operation lock (outermost,
        // as ProductionHistoryCoordinator.runExclusive wires it) while its
        // partition clear is suspended mid-operation.
        val historyOperationLock = Mutex()
        val clearStep = NetworkStep()
        val local = object : LocalHistoryPort by FakeLocalHistory() {
            override suspend fun clear(partition: HistoryPartition) {
                clearStep.hold()
            }
        }
        val account = ProductionAccountController(
            identity = SwitchingIdentity(
                OwnerSession("concurrent-owner-a", signedInAtEpochMillis = 1L),
                OwnerSession("concurrent-owner-b", signedInAtEpochMillis = 2L),
            ),
            local = local,
            firebaseProjectId = TEST_PROJECT_ID,
            configuredDeviceId = TEST_DEVICE_ID,
            serializedAction = { action -> historyOperationLock.withLock { action() } },
            credentialStore = fixture.store,
            beforeOwnerTransition = { fixture.coordinator.onOwnerChanged(null, TEST_PROJECT_ID) },
            afterOwnerTransition = { fixture.coordinator.onOwnerChanged(it?.uid, TEST_PROJECT_ID) },
        )
        account.requestSignOut()
        val signOut = async { account.signOutConfirmed() }
        withTimeout(SETUP_TIMEOUT_MS) { clearStep.awaitEntered() }

        try {
            // Every lifecycle/profile/command operation completes promptly while
            // the history lock is held, the live read is suspended and the
            // verification is suspended: no lock is ever held across LG I/O.
            withTimeout(CONCURRENCY_TIMEOUT_MS) {
                awaitAll(
                    async { fixture.coordinator.onOwnerChanged("concurrent-owner-b", TEST_PROJECT_ID) },
                    async { fixture.coordinator.clearCachedDeviceProfile() },
                    async { fixture.coordinator.removeCredential() },
                    async { fixture.coordinator.refreshLiveState() },
                    async { fixture.coordinator.cancelSetupAttempt() },
                    async {
                        fixture.coordinator.commandController.submit(
                            AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_300L, powerOn = false),
                        )
                    },
                )
            }
        } finally {
            verifyStep.release()
            clearStep.release()
            liveStep.release()
        }

        // All interleaved work finished (no deadlock) and every superseded
        // outcome was dropped instead of published.
        assertIs<ThinQCredentialSetupResult.Superseded>(withTimeout(SETUP_TIMEOUT_MS) { setup.await() })
        assertIs<OwnerSettingsActionResult.Success>(withTimeout(SETUP_TIMEOUT_MS) { signOut.await() })
        assertEquals(ThinQEntryResult.Superseded, withTimeout(SETUP_TIMEOUT_MS) { entry.await() })
        assertEquals(
            savesBefore,
            fixture.store.saveCalls,
            "the superseded concurrent attempt recorded zero saves",
        )
    }

    private companion object {
        /** Bounded wait so a hung commit/refusal fails instead of hanging. */
        const val SETUP_TIMEOUT_MS = 5_000L

        /** Bounded wait so a real lock-order regression fails instead of hanging. */
        const val CONCURRENCY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Store whose protected replacement always fails while everything else
 * delegates to the in-memory fake — exercises a gate-passing save failure
 * inside the commit section without a real Keystore.
 */
private class SaveFailingCredentialStore(
    private val delegate: FakeThinQCredentialStore,
) : ThinQCredentialStore by delegate {

    override suspend fun save(rawToken: String, accountCountry: String): SaveThinQResult =
        SaveThinQResult.Failure(ThinQCredentialSaveFailure.UNKNOWN)
}
