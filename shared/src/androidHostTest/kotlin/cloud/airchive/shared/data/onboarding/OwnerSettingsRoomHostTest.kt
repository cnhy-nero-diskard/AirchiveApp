package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-backed Room KMP integration for [OwnerSettingsController.signOutConfirmed]:
 * the confirmed sign-out must wipe **exactly** the active owner's
 * (project, UID, device) partition and the PAT — and nothing else.
 *
 * The controller is wired to common code only, but the [LocalHistoryPort] behind
 * it is the durable [RoomLocalHistoryPort] on a real SQLite file via
 * [openAirchiveDatabase] (the bundled driver, same code path the app runs).
 * Two partitions for different UIDs on the same configured device are seeded
 * with [Observation]s + [SyncMetadata], and owner A's partition additionally
 * carries daily totals, covered ranges, and collector health. After
 * `refresh()` → `requestSignOut()` → `signOutConfirmed()`:
 *
 *  1. A's partition has no observations, totals, coverage, health, or sync
 *     metadata; the credential store is empty; the identity is signed out.
 *  2. B's partition on the same file retains **exactly** its prior observation
 *     and sync metadata — the exact-partition cleanup never touches another
 *     UID's rows, even sharing the device.
 *  3. The cleared/retained split survives a close-and-reopen of the database
 *     file, proving the cleanup is on-disk and durable, not just in memory.
 *  4. No Firestore, LG, or other external service is touched: the credential
 *     verifier is never invoked (`lastRequest` stays `null`), and no sign-in is
 *     ever started. Everything is Room + in-process fakes with an
 *     `.example.invalid` synthetic PAT.
 */
class OwnerSettingsRoomHostTest {

    private val projectId = "demo-firebase-project"
    private val deviceId = "demo-ac-id"
    private val nowEpochMillis = 1_750_000_000_000L

    private val ownerASession = OwnerSession(
        uid = "owner-uid-001",
        displayName = "Owner A",
        email = "owner-a@example.invalid",
        signedInAtEpochMillis = nowEpochMillis,
    )

    /** A different UID on the **same** configured device; its cache must survive A's sign-out. */
    private val ownerBPartition = HistoryPartition(projectId, "owner-uid-002", deviceId)

    /** Synthetic fixture PAT, `.example.invalid`-branded (never a real credential). */
    private val syntheticToken = "thinq-fixture.example.invalid/owner-a-1a2b"

    @Test
    fun confirmedSignOutClearsExactlyTheActiveUidPartitionOnDisk() = runBlocking {
        withTempDb { dbFile ->
            // ---- Session 1: open the real file and seed both partitions. ----
            val database = openAirchiveDatabase(dbFile.absolutePath)
            val port = RoomLocalHistoryPort(database)
            val ownerAPartition = HistoryPartition(projectId, ownerASession.uid, deviceId)
            try {
                val observationA1 = observation(sampleId = "owner-a-1", start = 1_000L, end = 1_300L)
                val observationA2 = observation(sampleId = "owner-a-2", start = 1_300L, end = 1_600L)
                val observationB = observation(sampleId = "owner-b-1", start = 1_000L, end = 1_300L)
                val syncA = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = 9_000L,
                    lastErrorEpochMillis = 8_800L,
                    lastError = "OFFLINE",
                    persistedAtWatermarkEpochMillis = 8_500L,
                    reconciledAtWatermarkEpochMillis = 9_100L,
                )
                val syncB = SyncMetadata(
                    lastSuccessfulSyncEpochMillis = 7_000L,
                    lastErrorEpochMillis = 6_500L,
                    lastError = "REMOTE_FAILURE",
                    persistedAtWatermarkEpochMillis = 6_000L,
                    reconciledAtWatermarkEpochMillis = 6_900L,
                )

                port.upsertObservations(ownerAPartition, listOf(observationA1, observationA2))
                port.upsertDailyTotals(
                    ownerAPartition,
                    listOf(
                        DailyTotal(
                            totalId = "daily:2026-01-15",
                            dayStartEpochMillis = 1_000L,
                            energyValue = 12.34,
                            recordedUnit = EnergyUnit.KWH,
                            source = DailyTotalSource.LG_FINALIZED,
                            finalized = true,
                            localDate = "2026-01-15",
                            totalText = "12.34",
                            totalNumber = 12.34,
                            unitText = "kWh",
                            deviceId = deviceId,
                            fetchedAtEpochMillis = 2_000L,
                        ),
                    ),
                )
                port.upsertCoveredRange(ownerAPartition, CoveredRange(1_000L, 1_600L))
                port.upsertCollectorHealth(
                    ownerAPartition,
                    CollectorHealth(
                        status = CollectorStatus.HEALTHY,
                        lastPollEpochMillis = 8_900L,
                        lastSuccessEpochMillis = 9_000L,
                        consecutiveFailures = 0,
                        collectorVersion = "1.4.2",
                    ),
                )
                port.recordSync(ownerAPartition, syncA)
                port.upsertObservations(ownerBPartition, listOf(observationB))
                port.recordSync(ownerBPartition, syncB)

                // ---- Settings surface for owner A, all in-process fakes. ----
                val identity = ScriptedOnboardingOwnerIdentity(initialSession = ownerASession)
                val store = FakeThinQCredentialStore()
                store.save(syntheticToken, "PH")
                val verifier = FakeThinQCredentialVerifier(deviceId)

                val controller = OwnerSettingsController(
                    identity = identity,
                    credentialStore = store,
                    credentialVerifier = verifier,
                    local = port,
                    firebaseProjectId = projectId,
                    configuredDeviceId = deviceId,
                )
                controller.refresh()

                // Precondition: signed in as A with the masked PAT and the full
                // cached projection visible.
                val before = controller.state.value
                assertEquals(ownerASession, before.session)
                assertEquals(2, before.history!!.observations.size)
                assertEquals(1, before.history.dailyTotals.size)
                assertEquals(CollectorStatus.HEALTHY, before.history.collectorHealth?.status)

                // ---- Confirmed sign-out from the Settings flow. ----
                controller.requestSignOut()
                val result = controller.signOutConfirmed()
                val success = assertIs<OwnerSettingsActionResult.Success>(result)
                assertNull(success.session, "successful sign-out ends with no active session")

                // A's exact partition is wiped: no rows, totals, coverage,
                // health, or sync metadata.
                val clearedA = port.observe(ownerAPartition).first()
                assertTrue(clearedA.observations.isEmpty(), "A's observations must be cleared")
                assertTrue(clearedA.dailyTotals.isEmpty(), "A's daily totals must be cleared")
                assertTrue(clearedA.coveredRanges.isEmpty(), "A's covered ranges must be cleared")
                assertNull(clearedA.collectorHealth, "A's collector health must be cleared")
                assertEquals(SyncMetadata(), clearedA.sync, "A's sync metadata must be cleared")

                // B's partition on the SAME file/devices retains exactly its
                // prior observation and sync metadata.
                val retainedB = port.observe(ownerBPartition).first()
                assertEquals(listOf(observationB), retainedB.observations)
                assertEquals(syncB, retainedB.sync)

                // The PAT is removed and the identity port signed out exactly once.
                assertNull(store.readToken())
                assertFalse(store.observe().first().present)
                assertEquals(1, identity.signOutCalls)
                assertNull(identity.currentSession)

                // No external service was contacted: the LG verifier was never
                // invoked and no (Google) sign-in was started.
                assertNull(verifier.lastRequest, "sign-out must not verify a PAT against LG/Firestore")
                assertEquals(0, identity.signInCalls)

                // The published state is signed out and clear.
                val after = controller.state.value
                assertNull(after.session)
                assertNull(after.credential)
                assertNull(after.history)
                assertFalse(after.toString().contains(syntheticToken))
            } finally {
                database.close()
            }

            // ---- Session 2: reopen the same file — the split is on disk. ----
            val reopened = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val reopenedPort = RoomLocalHistoryPort(reopened)
                val reopenedA = reopenedPort.observe(
                    HistoryPartition(projectId, ownerASession.uid, deviceId),
                ).first()
                assertTrue(reopenedA.observations.isEmpty())
                assertTrue(reopenedA.dailyTotals.isEmpty())
                assertTrue(reopenedA.coveredRanges.isEmpty())
                assertNull(reopenedA.collectorHealth)
                assertEquals(SyncMetadata(), reopenedA.sync)

                val reopenedB = reopenedPort.observe(ownerBPartition).first()
                assertEquals(1, reopenedB.observations.size, "B's observation survives the reopen")
                assertEquals("owner-b-1", reopenedB.observations.single().sampleId)
                assertEquals(7_000L, reopenedB.sync?.lastSuccessfulSyncEpochMillis)
                assertEquals(6_900L, reopenedB.sync?.reconciledAtWatermarkEpochMillis)
            } finally {
                reopened.close()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun observation(sampleId: String, start: Long, end: Long) = Observation(
        sampleId = sampleId,
        deviceId = deviceId,
        intervalStartEpochMillis = start,
        intervalEndEpochMillis = end,
        energyValue = 2.1,
        recordedUnit = EnergyUnit.KWH,
        intervalStatus = "NORMAL",
        quality = ObservationQuality.NORMAL,
        qualityFlags = emptySet(),
        sourceFailure = null,
        persistedAtEpochMillis = end,
        reconciledAtEpochMillis = null,
        scheduledAtEpochMillis = start,
        observedAtEpochMillis = end,
        localDate = "2026-01-15",
        timezone = "Asia/Manila",
        completeness = 3,
        unitText = "kWh",
        unitSource = cloud.airchive.shared.contract.telemetry.EnergyUnitSource.DEVICE,
        unitSourceText = "device",
    )

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-owner-settings-room-test").toFile()
        val dbFile = File(dir, "history.db")
        try {
            runBlocking { block(dbFile) }
        } finally {
            dbFile.delete()
            File(dir, "history.db-wal").delete()
            File(dir, "history.db-shm").delete()
            dir.delete()
        }
    }
}

/**
 * Deterministic, test-local [OwnerIdentityPort] signed in as owner A. It records
 * how many times sign-in/sign-out were invoked and never touches the network —
 * proving the settings controller drives only the in-process identity while
 * clearing the Room partition. (`signIn` is never needed by this test and just
 * cancels if it is ever reached, keeping the identity state unchanged.)
 */
private class ScriptedOnboardingOwnerIdentity(
    initialSession: OwnerSession? = null,
) : OwnerIdentityPort {

    private val session = MutableStateFlow<OwnerSession?>(initialSession)

    var signInCalls: Int = 0
        private set

    var signOutCalls: Int = 0
        private set

    override val currentSession: OwnerSession? get() = session.value

    override fun observeSession(): Flow<OwnerSession?> = session.asStateFlow()

    override suspend fun signIn(): SignInResult {
        signInCalls += 1
        return SignInResult.Cancelled
    }

    override suspend fun signOut() {
        signOutCalls += 1
        session.value = null
    }
}