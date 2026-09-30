package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerAccessCoordinator
import cloud.airchive.shared.contract.owner.OwnerAccessState
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.data.sync.FirstLoadPhase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import kotlinx.coroutines.flow.first
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
 * Deterministic tests for the first-run onboarding controller (task 3.2),
 * driven by the in-memory fake providers with a fixed clock. No accounts,
 * network, or Firebase — and no real ThinQ PAT anywhere.
 *
 * Proves the four-step progression stays gated (access pending never advances),
 * the editable `PH` country default, pre-store token/country validation, masked-
 * only credential state, distinct KEY_UNAVAILABLE vs INVALID_INPUT save
 * failures, deterministic first-load progress mirroring with durable
 * history/totals/last-success, cache-preserving sync failures with stable safe
 * codes, retry convergence, and the access-denied → retry → authorized path.
 */
class FirstRunOnboardingControllerTest {

    private val nowEpochMillis = 1_750_000_000_000L

    private val deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID

    private val partition = HistoryPartition(
        firebaseProjectId = PROJECT_ID,
        ownerUid = FakeOwnerIdentity.DEFAULT_SESSION.uid,
        deviceId = deviceId,
    )

    private fun controller(
        identity: FakeOwnerIdentity = FakeOwnerIdentity(),
        telemetry: FailOnCallTelemetry = FailOnCallTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
        ),
        credentialStore: FakeThinQCredentialStore = FakeThinQCredentialStore(),
        local: FakeLocalHistory = FakeLocalHistory(),
        demoOnly: Boolean = false,
    ) = FirstRunOnboardingController(
        ownerAccess = OwnerAccessCoordinator(identity, telemetry),
        credentialStore = credentialStore,
        credentialVerifier = FakeThinQCredentialVerifier(deviceId),
        telemetry = telemetry,
        local = local,
        firebaseProjectId = PROJECT_ID,
        configuredDeviceId = deviceId,
        demoOnly = demoOnly,
    )

    // ------------------------------------------------- access pending gating

    @Test
    fun accessPendingNeverAdvancesPastGoogleAccessStep() = runBlocking {
        val identity = FakeOwnerIdentity()
        val telemetry = FailOnCallTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
            failDeviceIdentity = true,
            failure = TelemetryReadFailure.AccessDenied(),
        )
        val controller = controller(identity = identity, telemetry = telemetry)

        // No session yet: restore stays on Google with no sign-in activity.
        controller.restore()
        assertEquals(FirstRunOnboardingStep.GOOGLE_ACCESS, controller.state.value.step)
        assertIs<OwnerAccessState.SignedOut>(controller.state.value.ownerAccess)
        assertNull(controller.state.value.safeErrorCode)

        // Sign-in succeeds but the device read is denied: access pending, and
        // the flow still cannot advance past the Google step.
        controller.signIn()
        assertEquals(FirstRunOnboardingStep.GOOGLE_ACCESS, controller.state.value.step)
        val pending = assertIs<OwnerAccessState.AccessPending>(controller.state.value.ownerAccess)
        assertEquals("ACCESS_DENIED", pending.safeErrorCode)
        assertEquals("ACCESS_DENIED", controller.state.value.safeErrorCode)
        assertNull(
            controller.state.value.credential,
            "masked credential status remains hidden until Firestore access is authorized",
        )
        assertNull(controller.state.value.history)
        assertNull(controller.state.value.firstLoadProgress)
    }

    // ------------------------------------------------------- country default

    @Test
    fun countryDefaultsToPhAndRemainsEditableAfterNormalization() = runBlocking {
        assertEquals("PH", FirstRunOnboardingController.DEFAULT_COUNTRY)
        val controller = controller()
        controller.signIn()
        assertEquals(FirstRunOnboardingStep.THINQ_CREDENTIAL, controller.state.value.step)

        // Default preset saves cleanly.
        controller.saveThinQCredential("demo-token-1", FirstRunOnboardingController.DEFAULT_COUNTRY)
        assertEquals("PH", controller.state.value.credential!!.credential!!.accountCountry)

        // Editable: a lowercase two-letter country is normalized to uppercase.
        controller.saveThinQCredential("demo-token-2", "jp")
        assertEquals("JP", controller.state.value.credential!!.credential!!.accountCountry)
        assertNull(controller.state.value.credentialFailure)
    }

    // ------------------------------------------------ invalid input validation

    @Test
    fun invalidBlankTokenOrCountryIsRejectedBeforeTheStoreIsTouched() = runBlocking {
        val store = FakeThinQCredentialStore()
        val controller = controller(credentialStore = store)
        controller.signIn()

        controller.saveThinQCredential("", "PH")
        controller.saveThinQCredential("   ", "PH")
        controller.saveThinQCredential("demo-token", "")
        controller.saveThinQCredential("demo-token", "PHI")
        controller.saveThinQCredential("demo-token", "P1")
        controller.saveThinQCredential("demo-token", "philippines")
        controller.saveThinQCredential("demo-token-valid-elsewhere", "P")

        assertEquals(ThinQCredentialSaveFailure.INVALID_INPUT, controller.state.value.credentialFailure)
        assertEquals(false, controller.state.value.credential?.present)
        // Every invalid attempt failed before the store was called.
        assertNull(store.readToken())
        assertEquals(FirstRunOnboardingStep.THINQ_CREDENTIAL, controller.state.value.step)

        // A valid save then succeeds and clears the failure.
        controller.saveThinQCredential("demo-token", "PH")
        assertNull(controller.state.value.credentialFailure)
        assertEquals(true, controller.state.value.credential?.present)
        assertEquals("demo-token", store.readToken())
    }

    // ----------------------------------------------------- masked-only state

    @Test
    fun savedTokenIsRepresentedOnlyInMaskedFormInState() = runBlocking {
        val raw = "lg-pat-top-secret-9f3a"
        val controller = controller()
        controller.signIn()
        controller.saveThinQCredential(raw, "PH")

        val state = controller.state.value
        val masked = state.credential!!.credential!!.maskedToken
        assertFalse(masked.contains(raw), "the raw PAT must never appear in the masked token")
        assertTrue(masked.endsWith(raw.takeLast(4)), "only the last four characters are retained")
        // Even the full serialized onboarding state never leaks the raw token.
        assertFalse(state.toString().contains(raw), "no serialized state field may leak the raw PAT")
    }

    // -------------------------------------------- key loss vs invalid input

    @Test
    fun keyUnavailableAndInvalidInputAreDistinctAndFailedReplacementKeepsPriorToken() = runBlocking {
        val store = FakeThinQCredentialStore()
        val controller = controller(credentialStore = store)
        controller.signIn()

        // Invalid input (blank token) is INVALID_INPUT — distinct from key loss.
        controller.saveThinQCredential("", "PH")
        assertEquals(ThinQCredentialSaveFailure.INVALID_INPUT, controller.state.value.credentialFailure)

        // A valid save succeeds first.
        controller.saveThinQCredential("first-prefixed-token", "PH")
        assertNull(controller.state.value.credentialFailure)
        assertEquals("first-prefixed-token", store.readToken())

        // The platform then loses its protected key: KEY_UNAVAILABLE is its own
        // typed failure, nothing is replaced, and the old credential survives.
        // (With the key gone, the fake's readToken intentionally yields null —
        // "undecryptable" — so the proof is that the store's observable state
        // still carries the previous credential and the controller never
        // advanced its masked credential.)
        store.keyUnavailable = true
        controller.saveThinQCredential("replacement-token", "PH")
        assertEquals(ThinQCredentialSaveFailure.KEY_UNAVAILABLE, controller.state.value.credentialFailure)
        val retained = controller.state.value.credential!!.credential!!
        assertEquals("first-prefixed-token".takeLast(4), retained.maskedToken.takeLast(4))
        assertFalse(
            retained.maskedToken.contains("replacement"),
            "a failed replacement must not advance the masked credential state",
        )
        val storeStillHoldsOld = store.observe().first().credential!!
        assertEquals("first-prefixed-token".takeLast(4), storeStillHoldsOld.maskedToken.takeLast(4))
        assertFalse(storeStillHoldsOld.maskedToken.contains("replacement"))
    }

    // ------------------------------------------ initial sync happy path

    @Test
    fun initialSyncReportsDeterministicProgressAndStoresHistoryTotalsAndLastSuccess() = runBlocking {
        val local = FakeLocalHistory()
        val controller = controller(local = local)
        controller.signIn()
        controller.saveThinQCredential("demo-token-sync", "PH")
        assertEquals(FirstRunOnboardingStep.INITIAL_HISTORY, controller.state.value.step)

        controller.syncInitialHistory(nowEpochMillis)

        val state = controller.state.value
        assertEquals(FirstRunOnboardingStep.COMPLETE, state.step)
        assertNull(state.safeErrorCode)

        // Deterministic final progress: two pages (250 + 38), window ready.
        val progress = assertNotNull(state.firstLoadProgress)
        assertEquals(FirstLoadPhase.READY, progress.phase)
        assertEquals(2, progress.observationPagesFetched)
        assertEquals(288, progress.observationsSeen)
        assertEquals(30, progress.dailyTotalsFetched)
        assertTrue(progress.windowReady)

        // The state snapshot mirrors the durable local projection...
        val history = assertNotNull(state.history)
        assertEquals(288, history.observations.size)
        assertEquals(30, history.dailyTotals.size)
        assertEquals(nowEpochMillis, history.sync?.lastSuccessfulSyncEpochMillis)

        // ...and the store itself holds the identical rows and last-success.
        val stored = local.observe(partition).first()
        assertEquals(288, stored.observations.size)
        assertEquals(288, stored.observations.map { it.sampleId }.toSet().size)
        assertEquals(30, stored.dailyTotals.size)
        assertEquals(nowEpochMillis, stored.sync?.lastSuccessfulSyncEpochMillis)
        assertEquals(
            nowEpochMillis,
            stored.sync?.persistedAtWatermarkEpochMillis,
            "the first load establishes the durable mutation watermarks",
        )
    }

    // ------------------------------------ failure preserves cache + stable code

    @Test
    fun telemetryFailureLeavesNoPartialRowsPreservesPriorSuccessAndSetsStableError() = runBlocking {
        val local = FakeLocalHistory()
        val telemetry = FailOnCallTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val controller = controller(telemetry = telemetry, local = local)
        controller.signIn()
        controller.saveThinQCredential("demo-token-sync", "PH")

        // A prior successful sync leaves durable success + watermarks behind.
        val previousSuccess = nowEpochMillis - 60_000L
        local.replace(
            partition,
            FakeLocalHistory.syncedSnapshotFor(
                partition = partition,
                observations = listOf(priorSample("prior-1")),
                dailyTotals = emptyList(),
                sync = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = previousSuccess,
                    persistedAtWatermarkEpochMillis = previousSuccess,
                    reconciledAtWatermarkEpochMillis = previousSuccess,
                ),
            ),
        )

        // The second observation page fails with an offline read (the first
        // page already mirrored its progress, none of it written yet).
        telemetry.failOnLatestCall = 2
        telemetry.failure = TelemetryReadFailure.Offline()
        controller.syncInitialHistory(nowEpochMillis)

        val state = controller.state.value
        assertEquals(FirstRunOnboardingStep.INITIAL_HISTORY, state.step)
        assertEquals("OFFLINE", state.safeErrorCode)

        // Intermediate page progress was mirrored before the failure.
        val progress = assertNotNull(state.firstLoadProgress)
        assertEquals(FirstLoadPhase.OBSERVATION_PAGE, progress.phase)
        assertEquals(1, progress.observationPagesFetched)
        assertEquals(250, progress.observationsSeen)
        assertTrue(progress.hasMoreObservationPages)

        // No partial first-load rows: the buffered page was never written and
        // the prior rows survive untouched.
        val history = assertNotNull(state.history)
        assertEquals(listOf("prior-1"), history.observations.map { it.sampleId })
        assertEquals(0, history.dailyTotals.size)

        // Prior success and watermarks are preserved; only the error fields
        // advanced, with the stable safe code — never an exception message.
        val sync = assertNotNull(history.sync)
        assertEquals(previousSuccess, sync.lastSuccessfulSyncEpochMillis)
        assertEquals(previousSuccess, sync.persistedAtWatermarkEpochMillis)
        assertEquals(previousSuccess, sync.reconciledAtWatermarkEpochMillis)
        assertEquals(nowEpochMillis, sync.lastErrorEpochMillis)
        assertEquals("OFFLINE", sync.lastError)

        // The store agrees with the state snapshot.
        val stored = local.observe(partition).first()
        assertEquals(listOf("prior-1"), stored.observations.map { it.sampleId })
        assertEquals("OFFLINE", stored.sync?.lastError)
    }

    // ----------------------------------------------- retry converges

    @Test
    fun failedInitialSyncRetryConvergesAndClearsTheError() = runBlocking {
        val telemetry = FailOnCallTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val controller = controller(telemetry = telemetry)
        controller.signIn()
        controller.saveThinQCredential("demo-token-sync", "PH")

        telemetry.failOnLatestCall = 1
        telemetry.failure = TelemetryReadFailure.RemoteFailure()
        controller.syncInitialHistory(nowEpochMillis)
        assertEquals(FirstRunOnboardingStep.INITIAL_HISTORY, controller.state.value.step)
        assertEquals("REMOTE_FAILURE", controller.state.value.safeErrorCode)
        assertTrue(
            controller.state.value.history!!.observations.isEmpty(),
            "a failed first page must not leave a partial initial window",
        )

        // The remote recovers; the retry converges, clears the error, completes.
        telemetry.failOnLatestCall = null
        telemetry.failure = null
        controller.syncInitialHistory(nowEpochMillis)
        val state = controller.state.value
        assertEquals(FirstRunOnboardingStep.COMPLETE, state.step)
        assertNull(state.safeErrorCode)
        val history = assertNotNull(state.history)
        assertEquals(288, history.observations.size)
        assertEquals(nowEpochMillis, history.sync?.lastSuccessfulSyncEpochMillis)
        assertNull(history.sync?.lastError, "a successful retry clears the prior recorded error")
    }

    // --------------------------------- access denied → retry → authorized

    @Test
    fun accessDeniedThenRetryProgressesToAuthorizedAndThinQStep() = runBlocking {
        val identity = FakeOwnerIdentity()
        val telemetry = FailOnCallTelemetry(
            FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
            failDeviceIdentity = true,
            failure = TelemetryReadFailure.AccessDenied(),
        )
        val controller = controller(identity = identity, telemetry = telemetry)

        controller.signIn()
        assertIs<OwnerAccessState.AccessPending>(controller.state.value.ownerAccess)
        assertEquals(FirstRunOnboardingStep.GOOGLE_ACCESS, controller.state.value.step)

        // The operator enrolls the exact UID; a retry now authorizes and the
        // flow advances to the credential step.
        telemetry.failDeviceIdentity = false
        telemetry.failure = null
        controller.retryAccess()
        val authorized = assertIs<OwnerAccessState.Authorized>(controller.state.value.ownerAccess)
        assertEquals(FakeOwnerIdentity.DEFAULT_SESSION.uid, authorized.session.uid)
        assertEquals(FakeAirchiveTelemetry.DEMO_DEVICE_ID, authorized.device.deviceId)
        assertEquals(FirstRunOnboardingStep.THINQ_CREDENTIAL, controller.state.value.step)
        assertNull(controller.state.value.safeErrorCode)
    }

    // ------------------------------------------------ resume completed flow

    @Test
    fun restoreResumesACompletedPartitionStraightToComplete() = runBlocking {
        val identity = FakeOwnerIdentity()
        val telemetry = FailOnCallTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()

        val first = controller(identity = identity, telemetry = telemetry, credentialStore = store, local = local)
        first.signIn()
        first.saveThinQCredential("demo-token-sync", "PH")
        first.syncInitialHistory(nowEpochMillis)
        assertEquals(FirstRunOnboardingStep.COMPLETE, first.state.value.step)

        // Re-entering onboarding over the same providers skips every step done:
        // session + read access first, then credential + cache decide COMPLETE.
        val resumed = controller(identity = identity, telemetry = telemetry, credentialStore = store, local = local)
        resumed.restore()
        assertEquals(FirstRunOnboardingStep.COMPLETE, resumed.state.value.step)
        assertIs<OwnerAccessState.Authorized>(resumed.state.value.ownerAccess)
        assertNull(resumed.state.value.safeErrorCode)
    }

    @Test
    fun incrementalLastSuccessAloneDoesNotSkipInitialHistorySetup() = runBlocking {
        val identity = FakeOwnerIdentity()
        val telemetry = FailOnCallTelemetry(FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis))
        val store = FakeThinQCredentialStore()
        val local = FakeLocalHistory()
        val partition = HistoryPartition(PROJECT_ID, FakeOwnerIdentity.DEFAULT_SESSION.uid, deviceId)
        identity.signIn()
        store.save("demo-token", "PH")
        // An app-open incremental refresh can be recent while the separate
        // initial 24-hour/30-total bootstrap has not happened yet.
        local.replace(
            partition,
            FakeLocalHistory.syncedSnapshotFor(
                partition,
                sync = SyncMetadata(lastSuccessfulSyncEpochMillis = nowEpochMillis),
            ),
        )

        val resumed = controller(identity, telemetry, store, local)
        resumed.restore()

        assertEquals(FirstRunOnboardingStep.INITIAL_HISTORY, resumed.state.value.step)
        assertTrue(resumed.state.value.history!!.coveredRanges.isEmpty())
    }

    // ------------------------------------------------------ preconditions

    @Test
    fun syncAndSaveRequireAuthorizedOwnerAccess(): Unit = runBlocking {
        val controller = controller()
        controller.restore() // stays signed out
        assertFailsWith<IllegalStateException> {
            controller.saveThinQCredential("demo-token", "PH")
        }
        assertFailsWith<IllegalStateException> {
            controller.syncInitialHistory(nowEpochMillis)
        }

        // Authorized but no saved credential: history sync still refuses.
        val authorizedButCredentialless = controller()
        authorizedButCredentialless.signIn()
        assertEquals(FirstRunOnboardingStep.THINQ_CREDENTIAL, authorizedButCredentialless.state.value.step)
        assertFailsWith<IllegalStateException> {
            authorizedButCredentialless.syncInitialHistory(nowEpochMillis)
        }
    }

    // -------------------------------------------------------- demo warning

    @Test
    fun demoOnlyStateCarriesTheMemoryOnlyWarningAndStillAcceptsFixtures() = runBlocking {
        val controller = controller(demoOnly = true)
        controller.signIn()
        controller.saveThinQCredential("fixture-token", "PH")

        val state = controller.state.value
        assertTrue(state.demoOnly)
        assertEquals(FirstRunOnboardingController.DEMO_MEMORY_ONLY_WARNING, state.demoWarning)
        assertNull(state.credentialFailure)
        assertEquals(true, state.credential?.present)

        // The non-demo build carries no warning.
        assertNull(controller().state.value.demoWarning)
    }

    // ------------------------------------------------------------- helpers

    private fun priorSample(id: String): Observation = Observation(
        sampleId = id,
        intervalStartEpochMillis = nowEpochMillis - 2 * 24 * 60 * 60 * 1000L,
        intervalEndEpochMillis = nowEpochMillis - 2 * 24 * 60 * 60 * 1000L + 300_000,
        energyValue = 3.5,
        recordedUnit = EnergyUnit.WH,
        persistedAtEpochMillis = nowEpochMillis - 2 * 24 * 60 * 60 * 1000L,
        reconciledAtEpochMillis = nowEpochMillis - 2 * 24 * 60 * 60 * 1000L,
    )

    private companion object {
        const val PROJECT_ID = "demo-firebase-project"
    }
}

/**
 * Delegating telemetry double with two deterministic failure switches:
 * [failDeviceIdentity] makes only the access-check read fail (used to simulate
 * an unenrolled owner), and [failOnLatestCall] throws on the Nth
 * `latestObservations` call (used to inject mid-sync page failures while still
 * mirroring the earlier pages' progress).
 */
private class FailOnCallTelemetry(
    private val delegate: AirchiveTelemetryPort,
    var failDeviceIdentity: Boolean = false,
    var failOnLatestCall: Int? = null,
    var failure: Throwable? = null,
) : AirchiveTelemetryPort by delegate {

    private var latestCalls = 0

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        latestCalls += 1
        if (failOnLatestCall == latestCalls) {
            throw failure ?: IllegalStateException("injected latestObservations failure")
        }
        return delegate.latestObservations(sinceEpochMillis, pageSize, cursor)
    }

    override suspend fun deviceIdentity(): DeviceIdentity {
        if (failDeviceIdentity) throw failure ?: TelemetryReadFailure.AccessDenied()
        return delegate.deviceIdentity()
    }
}
