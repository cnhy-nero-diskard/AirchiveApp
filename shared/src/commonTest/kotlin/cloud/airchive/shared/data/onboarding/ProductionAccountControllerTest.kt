package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ordering and safety tests for the confirmed production account actions: every
 * cache clear precedes any identity change, a clear failure aborts sign-out,
 * and another owner's rows are never touched.
 */
class ProductionAccountControllerTest {

    private val project = "airchive-test-project"
    private val device = "configured-ac-1"
    private val owner =
        OwnerSession(uid = "owner-uid-1", displayName = "Owner One", email = "owner1@example.com", signedInAtEpochMillis = 1_750_000_000_000L)

    private fun partitionFor(session: OwnerSession): HistoryPartition =
        HistoryPartition(project, session.uid, device)

    @Test
    fun confirmedSignOutClearsPartitionBeforeEndingSession() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        assertIs<SignInResult.Success>(identity.signIn())
        val local = RecordingLocalHistory()
        val credentialStore = FakeThinQCredentialStore().also { it.save("fixture-token", "PH") }
        val controller = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = credentialStore,
        )

        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        assertIs<OwnerSettingsActionResult.Success>(result)
        assertNull(result.session)
        assertNull(identity.currentSession)
        assertEquals(listOf(partitionFor(owner)), local.clearedPartitions)
        assertNull(credentialStore.readToken())
    }

    @Test
    fun confirmedOwnerTransitionInvalidatesThinQBeforeClearingHistoryCredentialOrIdentity() = runBlocking {
        val events = mutableListOf<String>()
        val delegateIdentity = FakeOwnerIdentity(fixedSession = owner)
        delegateIdentity.signIn()
        val identity = object : OwnerIdentityPort by delegateIdentity {
            override suspend fun signOut() {
                events += "identity-sign-out"
                delegateIdentity.signOut()
            }
        }
        val delegateLocal = FakeLocalHistory()
        val local = object : LocalHistoryPort by delegateLocal {
            override suspend fun clear(partition: HistoryPartition) {
                events += "history-clear"
                delegateLocal.clear(partition)
            }
        }
        val delegateCredentialStore = FakeThinQCredentialStore().also { it.save("fixture-token", "PH") }
        val credentialStore = object : ThinQCredentialStore by delegateCredentialStore {
            override suspend fun clear() {
                events += "credential-clear"
                delegateCredentialStore.clear()
            }
        }
        val controller = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = credentialStore,
            beforeOwnerTransition = {
                events += "context-invalidate"
                assertNotNull(identity.currentSession, "the old owner is still active at invalidation")
                assertEquals("fixture-token", credentialStore.readToken(), "the PAT is still present at invalidation")
            },
            afterOwnerTransition = { session ->
                events += "context-rebind:${session?.uid ?: "signed-out"}"
            },
        )

        controller.requestSignOut()
        assertIs<OwnerSettingsActionResult.Success>(controller.signOutConfirmed())

        assertEquals(
            listOf(
                "context-invalidate",
                "history-clear",
                "credential-clear",
                "identity-sign-out",
                "context-rebind:signed-out",
            ),
            events,
            "old ThinQ presentation is invalidated before any owner data/session transition",
        )
    }

    @Test
    fun signOutRequiresExplicitConfirmation(): Unit = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val controller = ProductionAccountController(identity, RecordingLocalHistory(), project, device)

        assertFailsWith<IllegalStateException> {
            controller.signOutConfirmed()
        }
        assertNotNull(identity.currentSession)
    }

    @Test
    fun confirmationCannotClearAnAccountThatReplacedTheRequestingOwner() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val local = RecordingLocalHistory()
        val controller = ProductionAccountController(identity, local, project, device)
        controller.requestClearCache()
        identity.signOut()
        assertFailsWith<IllegalStateException> { controller.clearCacheConfirmed() }
        assertTrue(local.clearedPartitions.isEmpty())
    }

    @Test
    fun refreshClearsConfirmationAndFeedbackWhenTheOwnerChangesExternally() = runBlocking {
        val otherOwner = OwnerSession(
            uid = "owner-uid-2",
            displayName = "Owner Two",
            email = "owner2@example.invalid",
            signedInAtEpochMillis = owner.signedInAtEpochMillis + 1L,
        )
        val identity = SwitchingIdentity(owner, otherOwner)
        val controller = ProductionAccountController(identity, RecordingLocalHistory(), project, device)
        controller.requestClearCache()
        assertEquals(ProductionAccountConfirmStep.CLEAR_CACHE, controller.state.value.confirmStep)

        identity.signOut()
        identity.signIn()
        controller.refresh()

        assertEquals(otherOwner, controller.state.value.session)
        assertEquals(
            ProductionAccountConfirmStep.NONE,
            controller.state.value.confirmStep,
            "a confirmation opened by the previous owner cannot survive an external account switch",
        )
        assertNull(controller.state.value.lastActionResult, "prior-owner action feedback is cleared too")
        assertTrue(identity.currentSession == otherOwner)
    }

    @Test
    fun refreshClearsCompletedPriorOwnerActionFeedbackAfterAnExternalSwitch() = runBlocking {
        val otherOwner = OwnerSession(
            uid = "owner-uid-3",
            displayName = "Owner Three",
            email = "owner3@example.invalid",
            signedInAtEpochMillis = owner.signedInAtEpochMillis + 2L,
        )
        val identity = SwitchingIdentity(owner, otherOwner)
        val controller = ProductionAccountController(identity, RecordingLocalHistory(), project, device)
        controller.requestClearCache()
        assertIs<OwnerSettingsActionResult.Success>(controller.clearCacheConfirmed())
        assertNotNull(controller.state.value.lastActionResult)

        identity.signOut()
        identity.signIn()
        controller.refresh()

        assertEquals(otherOwner, controller.state.value.session)
        assertNull(
            controller.state.value.lastActionResult,
            "completed feedback from an unrelated prior owner is removed when the owner partition changes",
        )
    }

    @Test
    fun refreshClearsAConfirmationRequestedWithoutAnOwner() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        val controller = ProductionAccountController(identity, RecordingLocalHistory(), project, device)
        controller.requestSignOut()
        assertEquals(ProductionAccountConfirmStep.SIGN_OUT, controller.state.value.confirmStep)

        controller.refresh()

        assertEquals(ProductionAccountConfirmStep.NONE, controller.state.value.confirmStep,
            "a destructive confirmation without an owner is never shown to a later account")
        assertNull(controller.state.value.session)
    }

    @Test
    fun clearCacheFailureAbortsSignOutAndKeepsSession(): Unit = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val credentialStore = FakeThinQCredentialStore().also { it.save("fixture-token", "PH") }
        val controller = ProductionAccountController(
            identity = identity,
            local = ThrowingLocalHistory(),
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = credentialStore,
        )

        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        assertIs<OwnerSettingsActionResult.Failure>(result)
        assertEquals(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED, result.reason)
        assertNotNull(identity.currentSession)
        assertEquals("fixture-token", credentialStore.readToken())
    }

    @Test
    fun confirmedClearCacheKeepsSessionAndClearsOnlyThatOwner() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val local = RecordingLocalHistory()
        val credentialStore = FakeThinQCredentialStore().also { it.save("fixture-token", "PH") }
        val controller = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = credentialStore,
        )

        controller.requestClearCache()
        val result = controller.clearCacheConfirmed()

        assertIs<OwnerSettingsActionResult.Success>(result)
        assertEquals(owner, result.session)
        assertNotNull(identity.currentSession)
        assertEquals(listOf(partitionFor(owner)), local.clearedPartitions)
        assertEquals("fixture-token", credentialStore.readToken())
    }

    @Test
    fun confirmedAccountChangeClearsPriorPartitionThenStartsSignIn() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val local = RecordingLocalHistory()
        val credentialStore = FakeThinQCredentialStore().also { it.save("fixture-token", "PH") }
        val controller = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = credentialStore,
        )

        controller.requestChangeAccount()
        val result = controller.changeAccountConfirmed()

        assertIs<OwnerSettingsActionResult.Success>(result)
        assertEquals(owner, result.session)
        assertEquals(listOf(partitionFor(owner)), local.clearedPartitions)
        assertNull(credentialStore.readToken())
    }

    @Test
    fun credentialClearFailureAbortsIdentityChangeWithTypedFailure() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val local = RecordingLocalHistory()
        val controller = ProductionAccountController(
            identity = identity,
            local = local,
            firebaseProjectId = project,
            configuredDeviceId = device,
            credentialStore = ThrowingCredentialStore(),
        )

        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        assertEquals(
            OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED,
            assertIs<OwnerSettingsActionResult.Failure>(result).reason,
        )
        assertNotNull(identity.currentSession)
        // Cache deletion happened first; sign-out did not.
        assertEquals(listOf(partitionFor(owner)), local.clearedPartitions)
    }

    @Test
    fun cancelledAccountChangeClearsPriorDataAndStaysSignedOut() = runBlocking {
        val identity = CancellingIdentity(owner)
        val local = RecordingLocalHistory()
        val controller = ProductionAccountController(identity, local, project, device)

        controller.requestChangeAccount()
        val result = controller.changeAccountConfirmed()

        assertIs<OwnerSettingsActionResult.AccountChangeCancelled>(result)
        assertEquals(listOf(partitionFor(owner)), local.clearedPartitions)
        assertNull(identity.currentSession)
    }

    @Test
    fun stateNeverPublishesAnotherOwnersHistoryWhileSignedOut() = runBlocking {
        val identity = FakeOwnerIdentity(fixedSession = owner)
        identity.signIn()
        val local = RecordingLocalHistory()
        val controller = ProductionAccountController(identity, local, project, device)
        controller.refresh()
        assertNotNull(controller.state.value.history)

        controller.requestSignOut()
        controller.signOutConfirmed()
        controller.refresh()
        assertNull(controller.state.value.session)
        assertNull(controller.state.value.history)
    }

    /** Records every partition clear so ordering is assertable. */
    private class RecordingLocalHistory(
        private val delegate: FakeLocalHistory = FakeLocalHistory(),
    ) : LocalHistoryPort by delegate {
        val clearedPartitions = mutableListOf<HistoryPartition>()
        override suspend fun clear(partition: HistoryPartition) {
            clearedPartitions += partition
            delegate.clear(partition)
        }
    }

    private class ThrowingLocalHistory : LocalHistoryPort by FakeLocalHistory() {
        override suspend fun clear(partition: HistoryPartition) {
            error("simulated clear failure")
        }
    }

    private class ThrowingCredentialStore : ThinQCredentialStore by FakeThinQCredentialStore() {
        override suspend fun clear() {
            error("fixture clear failure")
        }
    }

    /** Identity whose account-change sign-in dialog is dismissed. */
    private class CancellingIdentity(private val sessionValue: OwnerSession?) : OwnerIdentityPort {
        private var current: OwnerSession? = sessionValue
        override val currentSession: OwnerSession? get() = current

        override fun observeSession(): Flow<OwnerSession?> = flowOf(current)

        override suspend fun signIn(): SignInResult = SignInResult.Cancelled

        override suspend fun signOut() {
            current = null
        }
    }

    private class SwitchingIdentity(
        initial: OwnerSession,
        private val next: OwnerSession,
    ) : OwnerIdentityPort {
        private val session = MutableStateFlow<OwnerSession?>(initial)
        override val currentSession: OwnerSession? get() = session.value
        override fun observeSession(): Flow<OwnerSession?> = session.asStateFlow()
        override suspend fun signIn(): SignInResult {
            session.value = next
            return SignInResult.Success(next)
        }
        override suspend fun signOut() { session.value = null }
    }
}
