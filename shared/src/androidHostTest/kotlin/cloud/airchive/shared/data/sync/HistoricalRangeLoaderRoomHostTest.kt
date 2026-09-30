package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.fakes.FakeAirchiveTelemetry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File-backed Room integration for on-demand historical range coverage. */
class HistoricalRangeLoaderRoomHostTest {

    private val partition = HistoryPartition(
        firebaseProjectId = "demo-firebase-project",
        ownerUid = "owner-uid-room-test",
        deviceId = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    )

    @Test
    fun coveredRevisitAfterRoomReopenDoesNotRepeatTelemetryQuery() = runBlocking {
        withTempDb { dbFile ->
            val firstDatabase = openAirchiveDatabase(dbFile.absolutePath)
            val samples = fixtureSamples()
            try {
                val firstRemote = CountingRangeReader(samples)
                val first = HistoricalRangeLoader(
                    telemetry = firstRemote,
                    local = RoomLocalHistoryPort(firstDatabase),
                    partition = partition,
                ).loadHistoricalRange(fromEpochMillis = 1_000L, toEpochMillis = 3_600_000L, pageSize = 2)

                assertEquals(2, firstRemote.rangeQueries)
                assertEquals(samples, first.observations)
                assertEquals(3, first.observations.size)
                assertEquals(listOf(CoveredRange(1_000L, 3_600_000L)), first.newlyCoveredRanges)
                assertTrue(first.observations.any { it.energyValue == null })
            } finally {
                firstDatabase.close()
            }

            // Reopen the same file to prove that the coverage—not just an
            // in-memory session flag—prevents a repeated Firestore range query.
            val reopenedDatabase = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val secondRemote = CountingRangeReader(fixtureSamples())
                val second = HistoricalRangeLoader(
                    telemetry = secondRemote,
                    local = RoomLocalHistoryPort(reopenedDatabase),
                    partition = partition,
                ).loadHistoricalRange(fromEpochMillis = 1_000L, toEpochMillis = 3_600_000L)

                assertEquals(0, secondRemote.rangeQueries)
                assertTrue(second.fullyCovered)
                assertTrue(second.newlyCoveredRanges.isEmpty())
                assertEquals(samples, second.observations)
                assertEquals(
                    listOf(CoveredRange(1_000L, 3_600_000L)),
                    RoomLocalHistoryPort(reopenedDatabase).observe(partition).first().coveredRanges,
                )
                assertNull(second.observations.single { it.sampleId == "range-null" }.energyValue)
            } finally {
                reopenedDatabase.close()
            }
        }
    }

    private fun fixtureSamples(): List<Observation> = listOf(
        Observation(
            sampleId = "range-1",
            intervalStartEpochMillis = 1_000L,
            intervalEndEpochMillis = 301_000L,
            energyValue = 1.25,
            deviceId = partition.deviceId,
        ),
        Observation(
            sampleId = "range-null",
            intervalStartEpochMillis = 301_000L,
            intervalEndEpochMillis = 601_000L,
            energyValue = null,
            deviceId = partition.deviceId,
        ),
        Observation(
            sampleId = "range-3",
            intervalStartEpochMillis = 601_000L,
            intervalEndEpochMillis = 901_000L,
            energyValue = 2.75,
            deviceId = partition.deviceId,
        ),
    )

    private fun withTempDb(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("airchive-range-room-test").toFile()
        val databaseFile = File(directory, "history.db")
        try {
            runBlocking { block(databaseFile) }
        } finally {
            databaseFile.delete()
            File(directory, "history.db-wal").delete()
            File(directory, "history.db-shm").delete()
            directory.delete()
        }
    }
}

private class CountingRangeReader(
    samples: List<Observation>,
) : AirchiveTelemetryPort {

    private val delegate = FakeAirchiveTelemetry(
        nowEpochMillis = 1_750_000_000_000L,
        observations = samples,
    )

    var rangeQueries: Int = 0
        private set

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.latestObservations(sinceEpochMillis, pageSize, cursor)

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.changedSincePersistedAt(sinceEpochMillis, pageSize, cursor)

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = delegate.changedSinceReconciledAt(sinceEpochMillis, pageSize, cursor)

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        rangeQueries += 1
        return delegate.observations(fromEpochMillis, toEpochMillis, pageSize, cursor)
    }

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> =
        delegate.latestFinalizedTotals(limit)

    override suspend fun collectorHealth(): CollectorHealth = delegate.collectorHealth()

    override suspend fun deviceIdentity(): DeviceIdentity = delegate.deviceIdentity()
}
