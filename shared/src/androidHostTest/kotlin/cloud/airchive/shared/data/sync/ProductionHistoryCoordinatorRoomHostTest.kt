package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInResult
import cloud.airchive.shared.data.local.AirchiveDatabase
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * File-backed Room integration for the production history graph.
 *
 * Proves the [ProductionHistoryCoordinator] persists the exact partition into
 * real Room files: a restart with the same owner keeps the cached rows and
 * watermarks, a different owner's partition is fully isolated, and clearing one
 * owner never touches another's rows — the durable cache behavior the production
 * Android app relies on for offline/restart rendering.
 */
class ProductionHistoryCoordinatorRoomHostTest {

    private val nowEpochMillis = 1_750_000_000_000L
    private val project = "airchive-test-project"
    private val device = "configured-ac-1"
    private val ownerA = OwnerSession(uid = "owner-a", signedInAtEpochMillis = nowEpochMillis)
    private val ownerB = OwnerSession(uid = "owner-b", signedInAtEpochMillis = nowEpochMillis)

    private fun partitionFor(session: OwnerSession): HistoryPartition =
        HistoryPartition(project, session.uid, device)

    @Test
    fun bootstrapPersistsIntoSameDatabaseAndSurvivesReopenForSameOwner() {
        withTempDb { dbFile ->
            // First run: bootstrap writes the full 24h window into the Room file.
            val database1 = openAirchiveDatabase(
                name = dbFile.absolutePath,
                migrations = AirchiveDatabase.MIGRATIONS.asList(),
            )
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val local1 = RoomLocalHistoryPort(database1)
                val coordinator1 = ProductionHistoryCoordinator(
                    identity = StaticOwnerIdentity(ownerA),
                    telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
                    local = local1,
                    firebaseProjectId = project,
                    configuredDeviceId = device,
                    scope = scope1,
                )
                val outcome = coordinator1.onForeground(nowEpochMillis)
                assertIs<ProductionRefreshOutcome.Bootstrapped>(outcome)

                val snapshot = local1.observe(partitionFor(ownerA)).first()
                assertEquals(288, snapshot.observations.size)
                assertEquals(30, snapshot.dailyTotals.size)
                assertTrue(coordinator1.hasInitialWindowCoverage(snapshot.coveredRanges))
                assertEquals(nowEpochMillis, snapshot.sync?.lastSuccessfulSyncEpochMillis)
            } finally {
                scope1.cancel()
                database1.close()
            }

            // Reopen the same file: the durable cache is still there for the
            // same owner, and a resume past the ten-minute gate continues
            // incrementally from the persisted watermarks.
            val database2 = openAirchiveDatabase(
                name = dbFile.absolutePath,
                migrations = AirchiveDatabase.MIGRATIONS.asList(),
            )
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val local2 = RoomLocalHistoryPort(database2)
                val coordinator2 = ProductionHistoryCoordinator(
                    identity = StaticOwnerIdentity(ownerA),
                    telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
                    local = local2,
                    firebaseProjectId = project,
                    configuredDeviceId = device,
                    scope = scope2,
                )
                val snapshot = local2.observe(partitionFor(ownerA)).first()
                assertEquals(288, snapshot.observations.size)
                assertTrue(coordinator2.hasInitialWindowCoverage(snapshot.coveredRanges))

                val resume = coordinator2.onForeground(nowEpochMillis + 11 * 60_000L)
                assertIs<ProductionRefreshOutcome.Refreshed>(resume)
                assertEquals(288, local2.observe(partitionFor(ownerA)).first().observations.size)
            } finally {
                scope2.cancel()
                database2.close()
            }
        }
    }

    @Test
    fun differentOwnerPartitionsStayIsolatedAndClearOnlyTouchesCurrentOwner() {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(
                name = dbFile.absolutePath,
                migrations = AirchiveDatabase.MIGRATIONS.asList(),
            )
            val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val local = RoomLocalHistoryPort(database)
                val coordinatorA = ProductionHistoryCoordinator(
                    identity = StaticOwnerIdentity(ownerA),
                    telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
                    local = local,
                    firebaseProjectId = project,
                    configuredDeviceId = device,
                    scope = scopeA,
                )
                val coordinatorB = ProductionHistoryCoordinator(
                    identity = StaticOwnerIdentity(ownerB),
                    telemetry = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis),
                    local = local,
                    firebaseProjectId = project,
                    configuredDeviceId = device,
                    scope = scopeB,
                )

                assertIs<ProductionRefreshOutcome.Bootstrapped>(coordinatorA.onForeground(nowEpochMillis))
                assertIs<ProductionRefreshOutcome.Bootstrapped>(coordinatorB.onForeground(nowEpochMillis))

                val snapshotA = local.observe(partitionFor(ownerA)).first()
                val snapshotB = local.observe(partitionFor(ownerB)).first()
                assertEquals(288, snapshotA.observations.size)
                assertEquals(288, snapshotB.observations.size)
                assertEquals(partitionFor(ownerA), snapshotA.partition)
                assertEquals(partitionFor(ownerB), snapshotB.partition)

                // Clearing only the current owner leaves the other owner intact.
                assertTrue(coordinatorA.clearCurrentOwnerPartition())
                assertEquals(0, local.observe(partitionFor(ownerA)).first().observations.size)
                assertEquals(288, local.observe(partitionFor(ownerB)).first().observations.size)
            } finally {
                scopeA.cancel()
                scopeB.cancel()
                database.close()
            }
        }
    }

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-production-room-test").toFile()
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

    /** Identity that reports a fixed signed-in session (matches restored Firebase state). */
    private class StaticOwnerIdentity(private val sessionValue: OwnerSession) : OwnerIdentityPort {
        override val currentSession: OwnerSession? get() = sessionValue
        override fun observeSession(): Flow<OwnerSession?> = flowOf(sessionValue)
        override suspend fun signIn(): SignInResult = SignInResult.Success(sessionValue)
        override suspend fun signOut() {}
    }
}