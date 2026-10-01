package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic tests for the common account/credential settings controller
 * (OpenSpec task 3.5), driven by in-memory fakes with scripted owner identities.
 * No Google account, Firebase, network, Room, or real LG PAT anywhere.
 *
 * Proves that removing a credential clears only the PAT and retains the owner's
 * rows; that confirmed sign-out clears the active UID's rows + PAT and calls
 * sign-out exactly once; that confirmed account change clears the prior UID's
 * rows + PAT before the new sign-in (and another account never sees the prior
 * owner's rows or loses its own cache); that a confirmed cache-only clear
 * removes only the active UID's partition while the session and PAT survive and
 * another UID's cache stays intact; that replacement persists only on
 * verifier success while the prior PAT survives every failed replacement and
 * cancellation; that published state and results always stay masked/secret-free;
 * that local-clear and store-clear failures block every identity switch and
 * never leak messages; and that destructive actions require their explicit
 * confirmation step.
 */
class OwnerSettingsControllerTest {

    private val nowEpochMillis = 1_750_000_000_000L

    private val projectId = "demo-firebase-project"
    private val deviceId = "demo-ac-id"

    private val firstSession = OwnerSession(
        uid = "owner-uid-001",
        displayName = "First Owner",
        email = "first@example.invalid",
        signedInAtEpochMillis = nowEpochMillis,
    )
    private val secondSession = OwnerSession(
        uid = "owner-uid-002",
        displayName = "Second Owner",
        email = "second@example.invalid",
        signedInAtEpochMillis = nowEpochMillis,
    )

    private val firstPartition = HistoryPartition(projectId, firstSession.uid, deviceId)
    private val secondPartition = HistoryPartition(projectId, secondSession.uid, deviceId)

    /** Synthetic fixture PAT, `.example.invalid`-branded (never a real credential). */
    private val priorToken = "thinq-fixture.example.invalid/prior-a1b2"

    /** Synthetic fixture replacement PAT for a verified-replacement attempt. */
    private val newToken = "thinq-fixture.example.invalid/new-2b4c"

    private fun controller(
        identity: OwnerIdentityPort,
        store: ThinQCredentialStore = FakeThinQCredentialStore(),
        verifier: ThinQCredentialVerifier = FakeThinQCredentialVerifier(deviceId),
        local: LocalHistoryPort = FakeLocalHistory(),
    ) = OwnerSettingsController(
        identity = identity,
        credentialStore = store,
        credentialVerifier = verifier,
        local = local,
        firebaseProjectId = projectId,
        configuredDeviceId = deviceId,
    )

    // -------------------------------------------------- remove credential

    @Test
    fun removeCredentialConfirmedClearsOnlyThePatAndRetainsRows() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1", "owner-a-2"))
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()

        // Precondition view: signed in, masked credential present, rows cached.
        val before = controller.state.value
        assertEquals(firstSession, before.session)
        assertEquals(priorToken.takeLast(4), before.credential!!.credential!!.maskedToken.takeLast(4))
        assertEquals(2, before.history!!.observations.size)

        controller.requestRemoveCredential()
        val result = controller.removeCredentialConfirmed()

        val success = assertIs<OwnerSettingsActionResult.Success>(result)
        assertEquals(firstSession, success.session, "removing the PAT does not affect the signed-in owner")

        // The PAT is gone...
        assertNull(store.readToken())
        assertFalse(store.observe().first().present)

        // ...while the owner's rows, totals, and sync metadata survive untouched.
        val retained = local.observe(firstPartition).first()
        assertEquals(listOf("owner-a-1", "owner-a-2"), retained.observations.map { it.sampleId })
        assertEquals(nowEpochMillis - 60_000L, retained.sync?.lastSuccessfulSyncEpochMillis)
        assertEquals("OFFLINE", retained.sync?.lastError)

        // The state mirrors all of it and never leaks the raw PAT.
        val after = controller.state.value
        assertEquals(firstSession, after.session)
        assertEquals(2, after.history!!.observations.size)
        assertFalse(after.credential!!.present)
        assertFalse(after.toString().contains(priorToken))
        assertEquals(OwnerSettingsConfirmStep.NONE, after.confirmStep)
    }

    // -------------------------------------------------------- confirmed sign-out

    @Test
    fun confirmedSignOutClearsActiveUidRowsAndPatThenSignsOut() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1"))
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()

        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        val success = assertIs<OwnerSettingsActionResult.Success>(result)
        assertNull(success.session, "successful sign-out ends with no active session")

        // The exact active-UID partition was cleared...
        val cleared = local.observe(firstPartition).first()
        assertTrue(cleared.observations.isEmpty())
        assertTrue(cleared.dailyTotals.isEmpty())
        assertTrue(cleared.coveredRanges.isEmpty())
        assertNull(cleared.sync?.lastSuccessfulSyncEpochMillis)

        // ...the PAT was removed, and the identity port signed out exactly once.
        assertNull(store.readToken())
        assertFalse(store.observe().first().present)
        assertEquals(1, identity.signOutCalls)
        assertNull(identity.currentSession)

        // The published state is signed out and clear.
        val after = controller.state.value
        assertNull(after.session)
        assertNull(after.credential)
        assertNull(after.history)
        assertEquals(OwnerSettingsConfirmStep.NONE, after.confirmStep)
    }

    @Test
    fun confirmedSignOutWhileSignedOutClearsThePatIdempotently() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = null)
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")

        val controller = controller(identity, store)
        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        val success = assertIs<OwnerSettingsActionResult.Success>(result)
        assertNull(success.session)
        assertNull(store.readToken(), "the PAT is removed even with no active session")
        assertEquals(1, identity.signOutCalls)
        assertNull(controller.state.value.session)
    }

    // ---------------------------------------------------- confirmed account change

    @Test
    fun changeAccountClearsPriorUidRowsAndPatBeforeTheNewSignIn() = runBlocking {
        val identity = ScriptedOwnerIdentity(
            initialSession = firstSession,
            nextSignInResult = SignInResult.Success(secondSession),
        )
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1", "owner-a-2"))
        seedHistory(local, secondPartition, listOf("owner-b-1"))
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()
        assertEquals(firstSession, controller.state.value.session)

        controller.requestChangeAccount()
        val result = controller.changeAccountConfirmed()

        val success = assertIs<OwnerSettingsActionResult.Success>(result)
        assertEquals(secondSession, success.session)

        // Cleanup happened BEFORE the new sign-in was started.
        assertEquals(1, identity.signInCalls, "the account-change cleanup must precede the new sign-in")

        // The prior owner's exact partition is empty...
        val priorRows = local.observe(firstPartition).first()
        assertTrue(priorRows.observations.isEmpty(), "the prior UID's cache must be cleared")

        // ...and the other account's cache was neither read nor cleared.
        val secondRows = local.observe(secondPartition).first()
        assertEquals(listOf("owner-b-1"), secondRows.observations.map { it.sampleId })

        // The PAT was removed before the new account had any session.
        assertNull(store.readToken())

        // The published state belongs to the new owner and never shows prior rows.
        val after = controller.state.value
        assertEquals(secondSession, after.session)
        assertEquals(listOf("owner-b-1"), after.history!!.observations.map { it.sampleId })
        assertFalse(after.credential!!.present)
        assertFalse(after.history.toString().contains("owner-a"), "another account must never see the prior UID's rows")
        assertEquals(OwnerSettingsConfirmStep.NONE, after.confirmStep)
    }

    @Test
    fun cancelledChangeAccountStaysSignedOutAndClearAfterCleanup() = runBlocking {
        val identity = ScriptedOwnerIdentity(
            initialSession = firstSession,
            nextSignInResult = SignInResult.Cancelled,
        )
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1"))
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()

        controller.requestChangeAccount()
        val result = controller.changeAccountConfirmed()

        assertIs<OwnerSettingsActionResult.AccountChangeCancelled>(result)

        // The cleanup is durable even though the new dialog was dismissed...
        assertEquals(1, identity.signInCalls)
        assertNull(store.readToken())
        assertTrue(local.observe(firstPartition).first().observations.isEmpty())

        // ...and the install ends signed out and clear.
        assertEquals(1, identity.signOutCalls, "the prior session is ended after the confirmed switch is abandoned")
        assertNull(identity.currentSession)
        val after = controller.state.value
        assertNull(after.session)
        assertNull(after.credential)
        assertNull(after.history)
        assertEquals(OwnerSettingsConfirmStep.NONE, after.confirmStep)
    }

    // --------------------------------------------- confirmed cache-only clear

    @Test
    fun confirmedClearCacheClearsOnlyTheActiveUidCacheAndPreservesSessionAndPat() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1", "owner-a-2"))
        seedHistory(local, secondPartition, listOf("owner-b-1"))
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()

        controller.requestClearCache()
        assertEquals(OwnerSettingsConfirmStep.CLEAR_CACHE, controller.state.value.confirmStep)
        val result = controller.clearCacheConfirmed()

        val success = assertIs<OwnerSettingsActionResult.Success>(result)
        assertEquals(firstSession, success.session, "clearing the cache keeps the signed-in owner")

        // Only the active UID's exact partition was cleared: rows, daily totals,
        // covered ranges, and every sync marker are gone from it.
        val cleared = local.observe(firstPartition).first()
        assertTrue(cleared.observations.isEmpty(), "the active UID's observations must be cleared")
        assertTrue(cleared.dailyTotals.isEmpty(), "the active UID's daily totals must be cleared")
        assertTrue(cleared.coveredRanges.isEmpty(), "the active UID's covered ranges must be cleared")
        assertNull(cleared.sync?.lastSuccessfulSyncEpochMillis, "the active UID's sync markers must be cleared")
        assertNull(cleared.sync?.lastError)

        // Another account's cache is neither read nor cleared.
        val secondRows = local.observe(secondPartition).first()
        assertEquals(listOf("owner-b-1"), secondRows.observations.map { it.sampleId })
        assertEquals(nowEpochMillis - 60_000L, secondRows.sync?.lastSuccessfulSyncEpochMillis)

        // The Google session and the ThinQ PAT (+ client ID) survive untouched.
        assertEquals(0, identity.signOutCalls, "a cache clear must never sign out")
        assertEquals(0, identity.signInCalls, "a cache clear must never start a new sign-in")
        assertEquals(priorToken, store.readToken(), "the PAT survives a cache clear")
        assertTrue(store.observe().first().present)
        assertEquals("fake-lg-client-id", store.observe().first().clientId)

        // The subsequent state re-snapshots the now-empty active cache while the
        // signed-in session and masked credential remain.
        val after = controller.state.value
        assertEquals(firstSession, after.session)
        val cache = requireNotNull(after.history)
        assertEquals(0, cache.observations.size)
        assertTrue(cache.dailyTotals.isEmpty())
        assertTrue(cache.coveredRanges.isEmpty())
        assertNull(cache.sync?.lastSuccessfulSyncEpochMillis)
        assertTrue(after.credential!!.present)
        assertFalse(after.toString().contains(priorToken), "the raw PAT must never leak")
        assertFalse(cache.toString().contains("owner-a"), "the cleared active partition is re-snapshotted empty")
        assertEquals(OwnerSettingsConfirmStep.NONE, after.confirmStep)
    }

    @Test
    fun clearCacheFailurePreservesSessionAndPatAndReturnsSafeCode() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val delegate = FakeLocalHistory()
        seedHistory(delegate, firstPartition, listOf("owner-a-1"))
        val local = FailOnClearLocalHistory(delegate, failClear = IllegalStateException("secret cache-clear boom"))
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()
        controller.requestClearCache()
        val result = controller.clearCacheConfirmed()

        val failure = assertIs<OwnerSettingsActionResult.Failure>(result)
        assertEquals(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED, failure.reason)

        // No identity switch, no PAT change, and the rows stay for a retry.
        assertEquals(0, identity.signOutCalls)
        assertEquals(firstSession, identity.currentSession)
        assertEquals(priorToken, store.readToken(), "the PAT is untouched when the cache could not be cleared")
        assertEquals(
            listOf("owner-a-1"),
            delegate.observe(firstPartition).first().observations.map { it.sampleId },
        )

        // The exception message never reaches the result or the state.
        assertFalse(result.toString().contains("secret cache-clear boom"))
        assertFalse(controller.state.value.toString().contains("secret cache-clear boom"))
        assertEquals(firstSession, controller.state.value.session)
        assertEquals(OwnerSettingsConfirmStep.NONE, controller.state.value.confirmStep)
    }

    @Test
    fun clearCacheNotInvokedBeforeItsConfirmationStep() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val local = RecordingLocalHistory(FakeLocalHistory())
        val controller = controller(identity, local = local)
        controller.refresh()

        // A direct call without any request fails the confirmation gate and
        // never touches the local store.
        assertFailsWith<IllegalStateException> { controller.clearCacheConfirmed() }
        assertEquals(0, local.clearCalls, "clear must not run before its confirmation step")

        // A non-matching pending step (e.g. sign-out) is also rejected.
        controller.requestSignOut()
        assertFailsWith<IllegalStateException> { controller.clearCacheConfirmed() }
        assertEquals(0, local.clearCalls)

        // Only the matching CLEAR_CACHE step lets the clear run.
        controller.dismissConfirmation()
        controller.requestClearCache()
        assertEquals(OwnerSettingsConfirmStep.CLEAR_CACHE, controller.state.value.confirmStep)
        assertIs<OwnerSettingsActionResult.Success>(controller.clearCacheConfirmed())
        assertEquals(1, local.clearCalls, "clear only runs once the explicit confirmation step matches")
    }

    @Test
    fun staleCacheClearConfirmationAfterSignOutDoesNotReportSuccessOrClearAnyPartition() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val local = RecordingLocalHistory(FakeLocalHistory())
        val controller = controller(identity, local = local)
        controller.refresh()
        controller.requestClearCache()

        // Simulate the platform session disappearing while the confirmation is
        // open. The stale dialog cannot become a successful no-op clear.
        identity.signOut()
        val result = controller.clearCacheConfirmed()

        val failure = assertIs<OwnerSettingsActionResult.Failure>(result)
        assertEquals(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED, failure.reason)
        assertEquals(0, local.clearCalls)
        assertNull(controller.state.value.session)
        assertEquals(OwnerSettingsConfirmStep.NONE, controller.state.value.confirmStep)
    }

    // --------------------------------------------- verified credential replacement

    @Test
    fun replacementSucceedsOnlyWhenTheVerifierPasses() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1"))
        store.save(priorToken, "PH")

        // Case 1: LG rejects the replacement token → the prior PAT survives.
        val rejectingVerifier = FakeThinQCredentialVerifier(deviceId).apply {
            result = ThinQVerificationResult.WrongAccount
        }
        val rejecting = controller(identity, store, rejectingVerifier, local)
        val wrong = assertIs<ThinQCredentialSetupResult.Failure.WrongAccount>(
            rejecting.replaceCredential(newToken, "PH"),
        )
        assertEquals(priorToken, store.readToken(), "a failed replacement must keep the previous PAT")
        assertEquals(priorToken.takeLast(4), wrong.credential.credential!!.maskedToken.takeLast(4))
        assertFalse(wrong.toString().contains(newToken))
        assertEquals(1, local.observe(firstPartition).first().observations.size)
        assertFalse(rejecting.state.value.toString().contains(newToken))

        // Case 2: the verifier crashes → RemoteFailure; the prior PAT still
        // survives and the exception text never leaks.
        val crashingVerifier = FakeThinQCredentialVerifier(deviceId).apply {
            failWith = IllegalStateException("secret verifier boom")
        }
        val crashing = controller(identity, store, crashingVerifier, local)
        val remote = assertIs<ThinQCredentialSetupResult.Failure.RemoteFailure>(
            crashing.replaceCredential(newToken, "PH"),
        )
        assertEquals(priorToken, store.readToken())
        assertEquals(priorToken.takeLast(4), remote.credential.credential!!.maskedToken.takeLast(4))
        assertFalse(remote.toString().contains("secret verifier boom"), "an exception message must never leak")

        // Case 3: verification passes → the new PAT is stored and only the mask
        // is published; the owner's history stays untouched.
        val verified = controller(identity, store, FakeThinQCredentialVerifier(deviceId), local)
        val success = assertIs<ThinQCredentialSetupResult.Success>(
            verified.replaceCredential(newToken, "ph"),
        )
        assertEquals(newToken, store.readToken())
        assertEquals(newToken.takeLast(4), success.credential.credential!!.maskedToken.takeLast(4))
        assertEquals("PH", success.credential.credential.accountCountry)
        assertFalse(success.toString().contains(newToken))
        assertEquals(1, local.observe(firstPartition).first().observations.size)

        val state = verified.state.value
        assertEquals(newToken.takeLast(4), state.credential!!.credential!!.maskedToken.takeLast(4))
        assertFalse(state.toString().contains(newToken))
    }

    @Test
    fun replacementCancellationPropagatesAndKeepsThePriorPat() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")
        val verifier = FakeThinQCredentialVerifier(deviceId).apply {
            failWith = CancellationException("cancelled mid-verification")
        }

        val controller = controller(identity, store, verifier)
        assertFailsWith<CancellationException> {
            controller.replaceCredential(newToken, "PH")
        }
        assertEquals(priorToken, store.readToken(), "a cancelled replacement never touches the stored token")
    }

    // ---------------------------------------------------- masked-only state

    @Test
    fun publishedStateNeverContainsThePlaintextPat() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1"))
        val raw = "lg-pat-top-secret-9f3a"
        store.save(raw, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()

        val state = controller.state.value
        val masked = assertNotNull(state.credential!!.credential).maskedToken
        assertFalse(masked.contains(raw), "the raw PAT must never appear in the masked token")
        assertTrue(masked.endsWith(raw.takeLast(4)), "only the last four characters are retained")
        assertFalse(state.toString().contains(raw), "no serialized state may leak the raw PAT")
        assertFalse(state.history.toString().contains(raw), "the local projection never stores the PAT")
    }

    // ------------------------------------------ slightly-failing clear paths

    @Test
    fun localClearFailureBlocksSignOutWithoutSwitchingIdentityOrLeaking() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val delegate = FakeLocalHistory()
        seedHistory(delegate, firstPartition, listOf("owner-a-1"))
        val local = FailOnClearLocalHistory(delegate, failClear = IllegalStateException("secret local-clear boom"))
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")

        val controller = controller(identity, store, local = local)
        controller.refresh()
        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        val failure = assertIs<OwnerSettingsActionResult.Failure>(result)
        assertEquals(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED, failure.reason)

        // No identity switch: never signed out, and the old data stays for a retry.
        assertEquals(0, identity.signOutCalls)
        assertEquals(firstSession, identity.currentSession)
        assertEquals(priorToken, store.readToken(), "the PAT is untouched when the rows could not be cleared")
        assertEquals(listOf("owner-a-1"), delegate.observe(firstPartition).first().observations.map { it.sampleId })

        // The exception message never reaches the result or the state.
        assertFalse(result.toString().contains("secret local-clear boom"))
        assertFalse(controller.state.value.toString().contains("secret local-clear boom"))
        assertEquals(firstSession, controller.state.value.session)
        assertEquals(OwnerSettingsConfirmStep.NONE, controller.state.value.confirmStep)
    }

    @Test
    fun credentialClearFailureAfterLocalClearBlocksSignOutButKeepsRowsCleared() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val delegateStore = FakeThinQCredentialStore()
        delegateStore.save(priorToken, "PH")
        val store = FailOnClearCredentialStore(delegateStore, failClear = IllegalStateException("secret store-clear boom"))
        val local = FakeLocalHistory()
        seedHistory(local, firstPartition, listOf("owner-a-1"))

        val controller = controller(identity, store, local = local)
        controller.refresh()
        controller.requestSignOut()
        val result = controller.signOutConfirmed()

        val failure = assertIs<OwnerSettingsActionResult.Failure>(result)
        assertEquals(OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED, failure.reason)

        // The rows are already cleared, but the sign-out was NOT performed and
        // the PAT still requires removal, so the owner can retry.
        assertTrue(local.observe(firstPartition).first().observations.isEmpty())
        assertEquals(0, identity.signOutCalls)
        assertEquals(firstSession, identity.currentSession)
        assertEquals(priorToken, delegateStore.readToken(), "the PAT survives the failed store clear")
        assertFalse(result.toString().contains("secret store-clear boom"))
        assertFalse(controller.state.value.toString().contains("secret store-clear boom"))
        assertEquals(OwnerSettingsConfirmStep.NONE, controller.state.value.confirmStep)
    }

    @Test
    fun clearFailuresDuringChangeAccountNeverStartTheNewSignIn() = runBlocking {
        val identity = ScriptedOwnerIdentity(
            initialSession = firstSession,
            nextSignInResult = SignInResult.Success(secondSession),
        )

        // Local-clear failure: the new sign-in must not start.
        val delegate = FakeLocalHistory()
        seedHistory(delegate, firstPartition, listOf("owner-a-1"))
        val local = FailOnClearLocalHistory(delegate, failClear = IllegalStateException("secret-clear-a"))
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")

        val first = controller(identity, store, local = local)
        first.refresh()
        first.requestChangeAccount()
        val localFailure = assertIs<OwnerSettingsActionResult.Failure>(first.changeAccountConfirmed())
        assertEquals(OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED, localFailure.reason)
        assertEquals(0, identity.signInCalls, "no new sign-in may start while cleanup is incomplete")
        assertEquals(firstSession, identity.currentSession, "the prior identity must not toggle")
        assertEquals(priorToken, store.readToken())
        assertFalse(localFailure.toString().contains("secret-clear-a"))

        // Store-clear failure: same guarantee, and no message leaks.
        val delegateStore = FakeThinQCredentialStore()
        delegateStore.save(priorToken, "PH")
        val failingStore = FailOnClearCredentialStore(delegateStore, failClear = IllegalStateException("secret-clear-b"))
        val localOk = FakeLocalHistory()
        seedHistory(localOk, firstPartition, listOf("owner-a-1"))

        val second = controller(identity, failingStore, local = localOk)
        second.refresh()
        second.requestChangeAccount()
        val storeFailure = assertIs<OwnerSettingsActionResult.Failure>(second.changeAccountConfirmed())
        assertEquals(OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED, storeFailure.reason)
        assertEquals(0, identity.signInCalls, "no new sign-in may start while the PAT survives")
        assertEquals(firstSession, identity.currentSession, "the prior identity must not toggle")
        assertEquals(priorToken, delegateStore.readToken())
        assertFalse(storeFailure.toString().contains("secret-clear-b"))
    }

    // ----------------------------------------------------- refresh + metadata

    @Test
    fun refreshRepublishesSessionCredentialCacheAndSyncMetadata() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()

        val controller = controller(identity, store, local = local)
        controller.refresh()

        // Signed in with a credential-status slot (absent until one is saved).
        assertEquals(firstSession, controller.state.value.session)
        assertNotNull(controller.state.value.credential)
        assertFalse(controller.state.value.credential!!.present)
        assertNotNull(controller.state.value.history)

        // A successful sync's last-success and an offline error's last-error
        // metadata are surfaced in the same settings snapshot.
        local.replace(
            firstPartition,
            FakeLocalHistory.syncedSnapshotFor(
                partition = firstPartition,
                observations = listOf(sample("owner-a-1")),
                sync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = nowEpochMillis - 60_000L,
                    lastErrorEpochMillis = nowEpochMillis - 30_000L,
                    lastError = "OFFLINE",
                ),
            ),
        )
        controller.refresh()
        val refreshed = controller.state.value
        assertEquals(listOf("owner-a-1"), refreshed.history!!.observations.map { it.sampleId })
        assertEquals(nowEpochMillis - 60_000L, refreshed.history.sync?.lastSuccessfulSyncEpochMillis)
        assertEquals("OFFLINE", refreshed.history.sync?.lastError)

        // A signed-out refresh hides every credential/cache detail.
        identity.signOut()
        controller.refresh()
        assertNull(controller.state.value.session)
        assertNull(controller.state.value.credential)
        assertNull(controller.state.value.history)
    }

    @Test
    fun refreshSurfacesProtectedKeyUnlockFailed() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val delegate = FakeThinQCredentialStore()
        delegate.save(priorToken, "PH")
        delegate.keyUnavailable = true
        val store = KeyLostCredentialStore(delegate)

        val controller = controller(identity, store)
        controller.refresh()

        val state = controller.state.value
        assertTrue(state.credential!!.unlockFailed, "the protected-key loss must surface as unlockFailed")
        assertFalse(state.toString().contains(priorToken))
    }

    // ------------------------------------------------- explicit confirmation

    @Test
    fun destructiveConfirmedActionsRequireTheirExplicitConfirmationStep() = runBlocking {
        val identity = ScriptedOwnerIdentity(initialSession = firstSession)
        val controller = controller(identity)

        // No confirmation requested yet: every destructive method refuses.
        assertFailsWith<IllegalStateException> { controller.removeCredentialConfirmed() }
        assertFailsWith<IllegalStateException> { controller.signOutConfirmed() }
        assertFailsWith<IllegalStateException> { controller.changeAccountConfirmed() }
        assertFailsWith<IllegalStateException> { controller.clearCacheConfirmed() }

        // A non-matching confirmation step is also rejected, and dismiss clears it.
        controller.requestSignOut()
        assertEquals(OwnerSettingsConfirmStep.SIGN_OUT, controller.state.value.confirmStep)
        assertFailsWith<IllegalStateException> { controller.removeCredentialConfirmed() }
        assertFailsWith<IllegalStateException> { controller.clearCacheConfirmed() }
        controller.dismissConfirmation()
        assertEquals(OwnerSettingsConfirmStep.NONE, controller.state.value.confirmStep)
        assertFailsWith<IllegalStateException> { controller.changeAccountConfirmed() }

        // Replacing a credential requires a signed-in session.
        val signedOut = controller(ScriptedOwnerIdentity(initialSession = null))
        assertFailsWith<IllegalStateException> { signedOut.replaceCredential(newToken, "PH") }
        Unit
    }

    // --------------------------------------------------------------- helpers

    private fun sample(id: String): Observation = Observation(
        sampleId = id,
        intervalStartEpochMillis = nowEpochMillis - 300_000,
        intervalEndEpochMillis = nowEpochMillis,
        energyValue = 2.5,
        recordedUnit = EnergyUnit.WH,
        persistedAtEpochMillis = nowEpochMillis,
        reconciledAtEpochMillis = nowEpochMillis,
    )

    private suspend fun seedHistory(
        local: FakeLocalHistory,
        partition: HistoryPartition,
        sampleIds: List<String>,
    ) {
        local.replace(
            partition,
            FakeLocalHistory.syncedSnapshotFor(
                partition = partition,
                observations = sampleIds.map(::sample),
                sync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = nowEpochMillis - 60_000L,
                    lastErrorEpochMillis = nowEpochMillis - 30_000L,
                    lastError = "OFFLINE",
                ),
            ),
        )
    }
}

/**
 * Deterministic, scripted [OwnerIdentityPort] used instead of the commonMain
 * [cloud.airchive.shared.fakes.FakeOwnerIdentity] when a test needs a *second*
 * account session or an explicit cancellation/failure on sign-in. It records how
 * many times sign-in/sign-out were invoked and never touches the network.
 */
private class ScriptedOwnerIdentity(
    initialSession: OwnerSession? = null,
    nextSignInResult: SignInResult = SignInResult.Cancelled,
) : OwnerIdentityPort {

    private val session = MutableStateFlow<OwnerSession?>(initialSession)

    var nextSignInResult: SignInResult = nextSignInResult
        private set

    var signInCalls: Int = 0
        private set

    var signOutCalls: Int = 0
        private set

    override val currentSession: OwnerSession? get() = session.value

    override fun observeSession(): Flow<OwnerSession?> = session.asStateFlow()

    override suspend fun signIn(): SignInResult {
        signInCalls += 1
        val result = nextSignInResult
        (result as? SignInResult.Success)?.let { session.value = it.session }
        return result
    }

    override suspend fun signOut() {
        signOutCalls += 1
        session.value = null
    }
}

/**
 * Test-only [LocalHistoryPort] wrapper that injects a failure into [clear] while
 * delegating every other call to the in-memory [FakeLocalHistory] — used to
 * prove that a local-clear failure never switches identities.
 */
private class FailOnClearLocalHistory(
    private val delegate: FakeLocalHistory,
    var failClear: Throwable? = null,
) : LocalHistoryPort by delegate {

    override suspend fun clear(partition: HistoryPartition) {
        failClear?.let { throw it }
        delegate.clear(partition)
    }
}

/**
 * Test-only [LocalHistoryPort] wrapper that records every [clear] call while
 * delegating to the in-memory [FakeLocalHistory] — used to prove the cache-only
 * clear action never touches the store before its confirmation step.
 */
private class RecordingLocalHistory(
    private val delegate: FakeLocalHistory,
) : LocalHistoryPort by delegate {

    var clearCalls: Int = 0
        private set

    override suspend fun clear(partition: HistoryPartition) {
        clearCalls += 1
        delegate.clear(partition)
    }
}

/**
 * Test-only [ThinQCredentialStore] wrapper that injects a failure into [clear]
 * while delegating everything else to the in-memory fake — used to prove that a
 * PAT-clear failure blocks sign-out/account change without leaking the message.
 */
private class FailOnClearCredentialStore(
    private val delegate: FakeThinQCredentialStore,
    var failClear: Throwable? = null,
) : ThinQCredentialStore by delegate {

    override suspend fun clear() {
        failClear?.let { throw it }
        delegate.clear()
    }
}

/**
 * Test-only [ThinQCredentialStore] wrapper that mirrors the observable state
 * with [ThinQCredentialState.unlockFailed] set whenever the delegate's
 * platform-protected key is unavailable — proving the controller surfaces the
 * protected-key flag without ever exposing ciphertext.
 */
private class KeyLostCredentialStore(
    private val delegate: FakeThinQCredentialStore,
) : ThinQCredentialStore by delegate {

    override fun observe(): Flow<ThinQCredentialState> = delegate.observe().map { state ->
        if (delegate.keyUnavailable) state.copy(unlockFailed = true) else state
    }
}
