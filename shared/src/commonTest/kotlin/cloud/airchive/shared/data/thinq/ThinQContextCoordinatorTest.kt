package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerSession
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Restructured Device entry (OpenSpec `deliver-shared-instrument-ui` task 2.2,
 * design D2): snapshot eligibility, list/profile/live network work with **no**
 * lifecycle or profile mutex held, and conditional publication only for the
 * still-current context.
 *
 * The suspended-step tests prove that invalidation completes while each LG
 * network step is suspended (it never waits on a network-held lock) and that
 * the late result of the superseded context is dropped instead of publishing.
 * Everything runs against in-memory fakes — no credential, Firebase, Keystore
 * or HTTP client is constructed.
 */
class ThinQContextCoordinatorTest {

    private class Fixture(ownerUid: String? = "owner-a") {
        val store = FakeThinQCredentialStore()
        val port = ScriptedThinQPort()

        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerUid,
            projectId = TEST_PROJECT_ID,
        )

        suspend fun seedCredential(token: String = SEED_TOKEN) {
            store.save(token, "PH")
        }
    }

    @Test
    fun entryPublishesMatchingDeviceProfileAndLiveUnderTheCapturedContext() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Success, result)
        assertEquals(1, fixture.port.listCalls)
        assertEquals(1, fixture.port.profileCalls)
        assertEquals(1, fixture.port.liveCalls)
        val publication = assertNotNull(fixture.coordinator.deviceProfile.value)
        assertEquals(fixture.coordinator.contextKey.value, publication.contextKey)
        assertEquals(TEST_DEVICE_ID, publication.device.deviceId)
        assertEquals(TEST_DEVICE_ID, publication.profile?.deviceId)
        val live = fixture.coordinator.liveStateController.state.value
        assertTrue(live is ThinQLiveRefreshState.Current)
        assertTrue(live.canIssueCommands, "the verified profile was published before the live read")
        assertEquals(TEST_DEVICE_ID, live.state.deviceId)
    }

    @Test
    fun entryWithoutCredentialMakesNoLgCallAndDoesNotChurnTheGeneration() = runBlocking {
        val fixture = Fixture() // no credential saved

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Unavailable, result)
        assertEquals(0, fixture.port.listCalls)
        assertEquals(0, fixture.port.profileCalls)
        assertEquals(0, fixture.port.liveCalls)
        assertNull(fixture.coordinator.deviceProfile.value)
        assertEquals(0L, fixture.coordinator.contextKey.value.credentialGeneration)
    }

    @Test
    fun signedOutTransitionCannotDispatchTheOutgoingOwnersPatWhileHistoryCleanupSuspends() = runBlocking {
        val ownerA = OwnerSession("transition-owner-a", signedInAtEpochMillis = 1L)
        val ownerB = OwnerSession("transition-owner-b", signedInAtEpochMillis = 2L)
        val identity = SwitchingIdentity(ownerA, ownerB)
        val store = FakeThinQCredentialStore()
        store.save(SEED_TOKEN, "PH")
        val localDelegate = FakeLocalHistory()
        val clearEntered = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        val local = object : LocalHistoryPort by localDelegate {
            override suspend fun clear(partition: HistoryPartition) {
                clearEntered.complete(Unit)
                allowClear.await()
                localDelegate.clear(partition)
            }
        }
        val port = ScriptedThinQPort()
        val coordinator = ThinQContextCoordinator(
            credentialStore = store,
            thinQ = port,
            verifier = testVerifier(),
            configuredDeviceId = TEST_DEVICE_ID,
            ownerUid = ownerA.uid,
            projectId = TEST_PROJECT_ID,
        )
        val account = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = TEST_PROJECT_ID,
            configuredDeviceId = TEST_DEVICE_ID,
            credentialStore = store,
            beforeOwnerTransition = { coordinator.onOwnerChanged(null, TEST_PROJECT_ID) },
            afterOwnerTransition = { coordinator.onOwnerChanged(it?.uid, TEST_PROJECT_ID) },
        )

        account.requestSignOut()
        val signOut = async { account.signOutConfirmed() }
        try {
            withTimeout(INVALIDATION_TIMEOUT_MS) { clearEntered.await() }
            assertNull(coordinator.contextKey.value.ownerUid, "invalidation precedes suspending owner cleanup")

            val entry = coordinator.onDeviceOpened()

            assertEquals(ThinQEntryResult.Unavailable, entry)
            assertEquals(0, port.listCalls, "a signed-out context cannot dispatch the still-stored outgoing PAT")
            assertEquals(0, port.profileCalls)
            assertEquals(0, port.liveCalls)
        } finally {
            allowClear.complete(Unit)
        }

        assertIs<OwnerSettingsActionResult.Success>(withTimeout(INVALIDATION_TIMEOUT_MS) { signOut.await() })
        assertNull(identity.currentSession)
        assertNull(store.readToken())
        assertNull(coordinator.deviceProfile.value)
    }

    @Test
    fun offlineDevicePublishesIdentityWithoutProfileAndSkipsTheLiveRead() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        fixture.port.onListDevices = { listOf(testDevice(online = false)) }

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Unavailable, result)
        val publication = assertNotNull(fixture.coordinator.deviceProfile.value)
        assertNull(publication.profile, "an offline device publishes identity without a verified profile")
        assertEquals(fixture.coordinator.contextKey.value, publication.contextKey)
        assertEquals(0, fixture.port.liveCalls, "no live read is issued without a verified profile")
        assertEquals(0L, fixture.coordinator.contextKey.value.credentialGeneration)
    }

    // --------------------------------- invalidation while a network step is held

    @Test
    fun invalidationCompletesWhileListDevicesIsSuspendedAndTheLateResultIsDropped() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        val listStep = NetworkStep()
        fixture.port.onListDevices = {
            listStep.hold()
            listOf(testDevice())
        }

        val entry = async { fixture.coordinator.onDeviceOpened() }
        listStep.awaitEntered()
        try {
            // Task 2.2: invalidation must complete while the list request is
            // suspended — no lifecycle/profile mutex is held across LG I/O.
            withTimeout(INVALIDATION_TIMEOUT_MS) {
                fixture.coordinator.onOwnerChanged("owner-b", TEST_PROJECT_ID)
            }
            assertEquals("owner-b", fixture.coordinator.contextKey.value.ownerUid)
            assertNull(fixture.coordinator.deviceProfile.value)
        } finally {
            listStep.release()
        }

        assertEquals(ThinQEntryResult.Superseded, entry.await())
        assertEquals(1, fixture.port.listCalls)
        assertEquals(
            0,
            fixture.port.profileCalls,
            "no follow-up LG request may run for the superseded context",
        )
        assertEquals(0, fixture.port.liveCalls)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
    }

    @Test
    fun invalidationCompletesWhileProfileReadIsSuspendedAndTheLateResultIsDropped() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        val profileStep = NetworkStep()
        fixture.port.onProfile = {
            profileStep.hold()
            testProfile()
        }

        val entry = async { fixture.coordinator.onDeviceOpened() }
        profileStep.awaitEntered()
        try {
            withTimeout(INVALIDATION_TIMEOUT_MS) {
                fixture.coordinator.onOwnerChanged("owner-b", TEST_PROJECT_ID)
            }
            assertNull(fixture.coordinator.deviceProfile.value, "nothing was published before the invalidation")
        } finally {
            profileStep.release()
        }

        assertEquals(ThinQEntryResult.Superseded, entry.await())
        assertEquals(1, fixture.port.profileCalls)
        assertEquals(0, fixture.port.liveCalls, "the live read never follows a superseded profile step")
        assertNull(fixture.coordinator.deviceProfile.value)
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
    }

    @Test
    fun invalidationCompletesWhileLiveReadIsSuspendedAndTheLateResultIsDropped() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        val liveStep = NetworkStep()
        fixture.port.onLiveState = {
            liveStep.hold()
            testLive()
        }

        val entry = async { fixture.coordinator.onDeviceOpened() }
        liveStep.awaitEntered()
        try {
            assertNotNull(
                fixture.coordinator.deviceProfile.value,
                "device/profile were published under the captured context before the live read",
            )
            // The live read holds only its own read mutex here; the boundary
            // and profile mutexes are free, so invalidation completes.
            withTimeout(INVALIDATION_TIMEOUT_MS) {
                fixture.coordinator.removeCredential()
            }
            assertNull(fixture.coordinator.deviceProfile.value, "presentation is cleared before the read returns")
            assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        } finally {
            liveStep.release()
        }

        assertEquals(ThinQEntryResult.Superseded, entry.await())
        assertEquals(1, fixture.port.liveCalls)
        assertTrue(
            fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "the late old-context reading is discarded instead of becoming current",
        )
        assertNull(fixture.coordinator.deviceProfile.value)
    }

    @Test
    fun concurrentSetupCleanupOwnerChangeAndEntryWorkDoNotDeadlock() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        val listStep = NetworkStep()
        fixture.port.onListDevices = {
            listStep.hold()
            listOf(testDevice())
        }
        val entry = async { fixture.coordinator.onDeviceOpened() }
        listStep.awaitEntered()
        try {
            withTimeout(CONCURRENCY_TIMEOUT_MS) {
                awaitAll(
                    async { fixture.coordinator.setupCredential("synthetic-concurrent-pat-5678", "PH") },
                    async { fixture.coordinator.removeCredential() },
                    async { fixture.coordinator.onOwnerChanged("owner-b", TEST_PROJECT_ID) },
                    async { fixture.coordinator.clearCachedDeviceProfile() },
                    async { fixture.coordinator.refreshLiveState() },
                    async {
                        fixture.coordinator.commandController.submit(
                            AcCommand.SetPower(requestedAtEpochMillis = 1_700_000_000_100L, powerOn = false),
                        )
                    },
                )
            }
        } finally {
            listStep.release()
        }
        // Every operation finished (no deadlock) and the suspended entry is
        // released; its superseded result is dropped rather than published.
        assertEquals(ThinQEntryResult.Superseded, entry.await())
        assertNull(fixture.coordinator.deviceProfile.value)
    }

    // ------------------------------------------------------- failure semantics

    @Test
    fun entryListFailureFailsWithoutInvalidatingTheUnchangedContext() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        fixture.port.onListDevices = { throw ThinQClientException.Network }
        val keyBefore = fixture.coordinator.contextKey.value

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Failed, result)
        assertEquals(keyBefore, fixture.coordinator.contextKey.value, "a same-context transient failure never invalidates")
        assertNull(fixture.coordinator.deviceProfile.value, "nothing unverified is published")
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead)
        assertEquals(1, fixture.port.listCalls)
        assertEquals(0, fixture.port.profileCalls, "no profile request follows a failed list request")
        assertEquals(0, fixture.port.liveCalls)
    }

    @Test
    fun sameContextLiveFailureRetainsLabeledStaleStateWithCommandsDisabled() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        val oldReading = assertIs<ThinQLiveRefreshState.Current>(
            fixture.coordinator.liveStateController.state.value,
        ).state
        val keyBefore = fixture.coordinator.contextKey.value
        val publicationBefore = fixture.coordinator.deviceProfile.value
        fixture.port.onLiveState = { throw ThinQClientException.Network }

        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Failed, result)
        assertEquals(keyBefore, fixture.coordinator.contextKey.value, "a same-context failure never invalidates")
        val stale = assertIs<ThinQLiveRefreshState.Stale>(fixture.coordinator.liveStateController.state.value)
        assertEquals(oldReading, stale.lastKnownState, "the prior reading is retained and labeled old, not republished as current")
        assertFalse(stale.canIssueCommands, "commands stay disabled after the failed read")
        assertEquals(
            publicationBefore,
            fixture.coordinator.deviceProfile.value,
            "the same-context verified profile survives the failed live read",
        )
        assertEquals(2, fixture.port.liveCalls)
    }

    @Test
    fun failedNewContextReadPresentsUnavailableRatherThanThePreReplacementReading() = runBlocking {
        val fixture = Fixture()
        fixture.seedCredential()
        assertEquals(ThinQEntryResult.Success, fixture.coordinator.onDeviceOpened())
        assertTrue(fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.Current)

        val replacement = fixture.coordinator.setupCredential("synthetic-replacement-pat-5678", "PH")
        assertIs<ThinQCredentialSetupResult.Success>(replacement)
        val newKey = fixture.coordinator.contextKey.value
        assertTrue(
            fixture.coordinator.liveStateController.state.value is ThinQLiveRefreshState.NotRead,
            "the replacement context starts unread",
        )

        fixture.port.onLiveState = { throw ThinQClientException.Network }
        val result = fixture.coordinator.onDeviceOpened()

        assertEquals(ThinQEntryResult.Failed, result)
        assertEquals(newKey, fixture.coordinator.contextKey.value, "a failed read never invalidates again")
        // Unavailable (not Stale) proves no pre-replacement reading was retained.
        val live = assertIs<ThinQLiveRefreshState.Unavailable>(fixture.coordinator.liveStateController.state.value)
        assertFalse(live.canIssueCommands, "an unavailable state never enables commands")
        assertEquals(
            newKey,
            fixture.coordinator.deviceProfile.value?.contextKey,
            "the verified profile was republished under the new generation",
        )
    }

    private companion object {
        /** Invalidation must return promptly even while an LG step is suspended. */
        const val INVALIDATION_TIMEOUT_MS = 5_000L

        /** Bounded wait so a real lock-order regression fails instead of hanging. */
        const val CONCURRENCY_TIMEOUT_MS = 10_000L
    }
}
