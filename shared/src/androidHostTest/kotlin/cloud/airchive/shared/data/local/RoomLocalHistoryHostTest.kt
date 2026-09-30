package cloud.airchive.shared.data.local

import cloud.airchive.shared.contract.history.CoveredRange
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.SyncMetadata
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.SourceReadFailure
import cloud.airchive.shared.data.telemetry.parseObservationJson
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File-backed Room KMP tests for the durable [LocalHistoryPort] adapter.
 *
 * These run host-side on the JVM (`androidHostTest`) against real SQLite files
 * via the bundled driver: a database is written, closed, reopened, and verified
 * — proving that reopen survives, partitions stay isolated across sessions, the
 * explicit v1→v2 migration keeps prior rows, and the stored projection carries
 * no raw Firestore payload.
 */
class RoomLocalHistoryHostTest {

    private val project = "demo-firebase-project"
    private val ownerA = HistoryPartition(project, "owner-uid-a", "device-8f3a")
    private val ownerB = HistoryPartition(project, "owner-uid-b", "device-8f3a")

    @Test
    fun reopenPreservesProjectionAndKeepsPartitionsIsolated() = runBlocking {
        withTempDb { dbFile ->
            val first = openAirchiveDatabase(dbFile.absolutePath)
            val firstPort = RoomLocalHistoryPort(first)

            val observationA1 = observation(sampleId = "sample-a-1", start = 1_000L, end = 1_300L)
            val observationA2 = observation(sampleId = "sample-a-2", start = 1_300L, end = 1_600L)
            val observationB = observation(sampleId = "sample-b-1", start = 1_000L, end = 1_300L)

            firstPort.upsertObservations(ownerA, listOf(observationA1, observationA2))
            firstPort.upsertObservations(ownerB, listOf(observationB))

            val totalA = DailyTotal(
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
                deviceId = "device-8f3a",
                fetchedAtEpochMillis = 2_000L,
            )
            firstPort.upsertDailyTotals(ownerA, listOf(totalA))

            // Two overlapping selections must merge into one durable range.
            firstPort.upsertCoveredRange(ownerA, CoveredRange(1_000L, 1_300L))
            firstPort.upsertCoveredRange(ownerA, CoveredRange(1_200L, 1_600L))

            firstPort.recordSync(
                ownerA,
                SyncMetadata(
                    lastSuccessfulSyncEpochMillis = 9_000L,
                    persistedAtWatermarkEpochMillis = 8_500L,
                    reconciledAtWatermarkEpochMillis = 9_100L,
                ),
            )
            firstPort.upsertCollectorHealth(
                ownerA,
                CollectorHealth(
                    status = CollectorStatus.HEALTHY,
                    lastPollEpochMillis = 8_900L,
                    lastSuccessEpochMillis = 9_000L,
                    consecutiveFailures = 0,
                    collectorVersion = "1.4.2",
                ),
            )
            first.close()

            // --- reopen the same file ---
            val reopened = openAirchiveDatabase(dbFile.absolutePath)
            val reopenedPort = RoomLocalHistoryPort(reopened)
            try {
                val snapshot = reopenedPort.observe(ownerA).first()
                assertEquals(listOf(observationA1, observationA2), snapshot.observations)
                assertEquals(listOf(totalA), snapshot.dailyTotals)
                assertEquals(listOf(CoveredRange(1_000L, 1_600L)), snapshot.coveredRanges)
                assertEquals(9_000L, snapshot.sync?.lastSuccessfulSyncEpochMillis)
                assertEquals(9_100L, snapshot.sync?.reconciledAtWatermarkEpochMillis)
                assertEquals(CollectorStatus.HEALTHY, snapshot.collectorHealth?.status)
                assertEquals("1.4.2", snapshot.collectorHealth?.collectorVersion)

                // Windowed observations respect the overlap semantics.
                assertEquals(
                    listOf(observationA2),
                    reopenedPort.observe(ownerA, fromEpochMillis = 1_300L, toEpochMillis = 1_700L).first().observations,
                )

                // Another owner never sees this owner's rows, even after reopen.
                val other = reopenedPort.observe(ownerB).first()
                assertEquals(listOf(observationB), other.observations)
                assertEquals(emptyList(), other.dailyTotals)
                assertEquals(emptyList(), other.coveredRanges)
                assertNull(other.sync?.lastSuccessfulSyncEpochMillis)

                // Merged updates still work against the reopened store.
                val replaced = observation(sampleId = "sample-a-1", start = 1_000L, end = 1_300L).copy(
                    energyValue = 0.99,
                    recordedUnit = EnergyUnit.WH,
                    reconciledAtEpochMillis = 9_500L,
                )
                reopenedPort.upsertObservations(ownerA, listOf(replaced))
                val afterUpdate = reopenedPort.observe(ownerA).first()
                assertEquals(listOf(replaced, observationA2), afterUpdate.observations)

                // Clear wipes every projection and sync marker for one partition only.
                reopenedPort.clear(ownerB)
                val cleared = reopenedPort.observe(ownerB).first()
                assertEquals(emptyList(), cleared.observations)
                assertEquals(emptyList(), cleared.dailyTotals)
                assertEquals(emptyList(), cleared.coveredRanges)
                assertNull(cleared.sync?.persistedAtWatermarkEpochMillis)
                assertNull(cleared.collectorHealth)
                // Partition A is untouched by clearing B.
                assertEquals(2, reopenedPort.observe(ownerA).first().observations.size)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun migrationFromPriorSchemaRetainsRowsAndAddsWatermarkColumn() = runBlocking {
        withTempDb { dbFile ->
            // --- build a v1 (prior-schema) database by hand with the bundled
            // driver, mirroring the exported v2 schema EXCEPT the column that
            // v2's explicit migration adds. ---
            createV1Database(dbFile)

            // --- open with the current schema: Room must run MIGRATION_1_2 and
            // keep every row. ---
            val migrated = openAirchiveDatabase(dbFile.absolutePath)
            try {
                val port = RoomLocalHistoryPort(migrated)
                val snapshot = port.observe(ownerA).first()

                assertEquals(1, snapshot.observations.size)
                val obs = snapshot.observations.single()
                assertEquals("sample-migrate-1", obs.sampleId)
                // Null energy and legacy missing unit survived as-is.
                assertNull(obs.energyValue)
                assertNull(obs.recordedUnit)
                assertEquals("2.10", obs.intervalValue)
                assertEquals(ObservationQuality.MISSING_INTERVAL, obs.quality)
                assertEquals(setOf(QualityFlag.MISSING_VALUE), obs.qualityFlags)
                assertEquals(
                    SourceReadFailure(ok = false, failureClass = "RATE_LIMITED", errorCode = "1310", errorName = "EXCEEDED_API_CALLS"),
                    obs.energySourceFailure,
                )

                assertEquals(1, snapshot.dailyTotals.size)
                assertEquals("daily:2026-01-15", snapshot.dailyTotals.single().totalId)
                assertEquals(12.34, snapshot.dailyTotals.single().energyValue)

                assertEquals(listOf(CoveredRange(1_000L, 1_300L)), snapshot.coveredRanges)

                val sync = snapshot.sync
                assertEquals(999L, sync?.lastSuccessfulSyncEpochMillis)
                assertEquals(950L, sync?.persistedAtWatermarkEpochMillis)
                // The added v2 column is present and null for migrated rows.
                assertNull(sync?.reconciledAtWatermarkEpochMillis)

                assertEquals(CollectorStatus.DEGRADED, snapshot.collectorHealth?.status)
                assertEquals(2, snapshot.collectorHealth?.consecutiveFailures)
            } finally {
                migrated.close()
            }
        }
    }

    @Test
    fun normalizedProjectionStoresNoRawPayloadColumnOrData() = runBlocking {
        withTempDb { dbFile ->
            val database = openAirchiveDatabase(dbFile.absolutePath)
            val port = RoomLocalHistoryPort(database)
            try {
                // A document that DOES carry opaque raw/state payload blocks.
                val parsed = parseObservationJson(
                    json = RAW_PAYLOAD_DOCUMENT,
                    documentId = "doc-raw",
                )
                port.upsertObservations(ownerA, listOf(parsed))
                database.close()

                // Reading back yields exactly the normalized projection.
                val reopened = openAirchiveDatabase(dbFile.absolutePath)
                try {
                    val stored = RoomLocalHistoryPort(reopened).observe(ownerA).first().observations.single()
                    assertEquals(parsed, stored)
                } finally {
                    reopened.close()
                }

                // The physical schema contains no storage type that could hold an
                // opaque payload, and no raw-payload container columns.
                val columnSpecs = readColumnSpecs(dbFile, "observations")
                columnSpecs.forEach { (name, type) -> assertTrue(type != "BLOB", "unexpected BLOB column '$name'") }
                val names = columnSpecs.map { it.first }.toSet()
                val forbidden = names.filter { it in RAW_PAYLOAD_COLUMN_NAMES }
                assertEquals(emptyList(), forbidden)
            } finally {
                database.close()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun observation(sampleId: String, start: Long, end: Long) = Observation(
        sampleId = sampleId,
        deviceId = ownerA.deviceId,
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

    /** Writes the v1 schema plus a full set of projections, then sets user_version to 1. */
    private suspend fun createV1Database(dbFile: File) {
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            connection.execSQL(V1_OBSERVATIONS)
            connection.execSQL(V1_OBSERVATIONS_INDEX_START)
            connection.execSQL(V1_OBSERVATIONS_INDEX_END)
            connection.execSQL(V1_DAILY_TOTALS)
            connection.execSQL(V1_COVERED_RANGES)
            connection.execSQL(V1_SYNC_METADATA)
            connection.execSQL(V1_COLLECTOR_HEALTH)

            connection.execSQL(
                "INSERT INTO `observations` (" +
                    "`firebase_project_id`,`owner_uid`,`device_id`,`sample_id`," +
                    "`interval_start_epoch_millis`,`interval_end_epoch_millis`,`energy_value`,`recorded_unit`," +
                    "`interval_status`,`quality`,`quality_flags`,`flag_texts`,`source_failure`," +
                    "`persisted_at_epoch_millis`,`reconciled_at_epoch_millis`,`scheduled_at_epoch_millis`," +
                    "`observed_at_epoch_millis`,`local_date`,`timezone`,`completeness`,`metadata_version`," +
                    "`collector_version`,`energy_property`,`raw_daily_total`,`raw_daily_total_number`," +
                    "`interval_value`,`interval_value_number`,`interval_seconds`,`final_previous_day_total`," +
                    "`final_previous_day_total_number`,`unit_text`,`unit_source`,`unit_source_text`," +
                    "`energy_source_failure_ok`,`energy_source_failure_class`,`energy_source_failure_error_code`," +
                    "`energy_source_failure_error_name`,`state_source_failure_ok`,`state_source_failure_class`," +
                    "`state_source_failure_error_code`,`state_source_failure_error_name`,`model_device_id`" +
                    ") VALUES (" +
                    "'demo-firebase-project','owner-uid-a','device-8f3a','sample-migrate-1'," +
                    "1000,1300,NULL,NULL," +
                    "'ENERGY_UNAVAILABLE','MISSING_INTERVAL','MISSING_VALUE','','energy:EXCEEDED_API_CALLS'," +
                    "1400,NULL,1000," +
                    "1300,'2026-01-15','Asia/Manila',2,'2'," +
                    "'1.4.2','WA_ENERGY','1234.56',1234.56," +
                    "'2.10',2.1,300.0,'900.00'," +
                    "900.0,NULL,NULL,NULL," +
                    "0,'RATE_LIMITED','1310'," +
                    "'EXCEEDED_API_CALLS',1,NULL,NULL," +
                    "NULL,'device-8f3a'" +
                    ")",
            )
            connection.execSQL(
                "INSERT INTO `daily_totals` (" +
                    "`firebase_project_id`,`owner_uid`,`device_id`,`total_id`,`day_start_epoch_millis`," +
                    "`energy_value`,`recorded_unit`,`source`,`finalized`,`local_date`,`total_text`,`total_number`," +
                    "`unit_text`,`fetched_at_epoch_millis`,`model_device_id`" +
                    ") VALUES (" +
                    "'demo-firebase-project','owner-uid-a','device-8f3a','daily:2026-01-15',1000," +
                    "12.34,NULL,'LG_FINALIZED',1,'2026-01-15','12.34',12.34," +
                    "NULL,2000,'device-8f3a'" +
                    ")",
            )
            connection.execSQL(
                "INSERT INTO `covered_ranges` (" +
                    "`firebase_project_id`,`owner_uid`,`device_id`,`from_epoch_millis`,`to_epoch_millis`" +
                    ") VALUES ('demo-firebase-project','owner-uid-a','device-8f3a',1000,1300)",
            )
            connection.execSQL(
                "INSERT INTO `sync_metadata` (" +
                    "`firebase_project_id`,`owner_uid`,`device_id`,`last_successful_sync_epoch_millis`," +
                    "`last_error_epoch_millis`,`last_error`,`persisted_at_watermark_epoch_millis`" +
                    ") VALUES ('demo-firebase-project','owner-uid-a','device-8f3a',999,NULL,NULL,950)",
            )
            connection.execSQL(
                "INSERT INTO `collector_health` (" +
                    "`firebase_project_id`,`owner_uid`,`device_id`,`status`,`last_poll_epoch_millis`," +
                    "`last_success_epoch_millis`,`last_failure_epoch_millis`,`last_failure_reason`,`last_sample_id`," +
                    "`last_sample_path`,`last_error_class`,`consecutive_failures`,`consecutive_rate_limits`," +
                    "`rate_limited_until_epoch_millis`,`collector_version`" +
                    ") VALUES (" +
                    "'demo-firebase-project','owner-uid-a','device-8f3a','DEGRADED',1000," +
                    "900,1000,'transient','sample-last'," +
                    "'devices/d/t/telemetry/sample-last','TRANSIENT',2,1," +
                    "2000,'1.4.2'" +
                    ")",
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    private fun readColumnSpecs(dbFile: File, table: String): List<Pair<String, String>> {
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            val specs = mutableListOf<Pair<String, String>>()
            // `PRAGMA table_info` rows: cid, name, type, notnull, dflt_value, pk.
            connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
                while (statement.step()) {
                    specs += statement.getText(1) to statement.getText(2)
                }
            }
            return specs
        } finally {
            connection.close()
        }
    }

    private fun withTempDb(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("airchive-room-test").toFile()
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

    private companion object {
        val RAW_PAYLOAD_DOCUMENT: String = """
            {
              "sampleId": "sample-raw-1",
              "deviceId": "device-8f3a",
              "scheduledAt": "2026-01-15T00:00:00+08:00",
              "observedAt": "2026-01-15T00:05:00+08:00",
              "persistedAt": "2026-01-15T00:05:02.5+08:00",
              "localDate": "2026-01-15",
              "timezone": "Asia/Manila",
              "completeness": 2,
              "metadataVersion": "2",
              "collectorVersion": "1.4.2",
              "energy": {
                "unit": "kWh",
                "unitSource": "device",
                "rawDailyTotal": "1234.56", "rawDailyTotalNumber": 1234.56,
                "intervalValue": "2.10", "intervalValueNumber": 2.1,
                "intervalSeconds": 300
              },
              "quality": {"intervalStatus": "NORMAL", "flags": []},
              "source": {"energy": {"ok": true}, "state": {"ok": true}},
              "state": {"power": "on", "temperature": 25.0},
              "raw": {"energy": {"foo": 1}, "state": {"bar": 2}}
            }
        """.trimIndent()

        /** Column names that would betray an opaque payload container. */
        val RAW_PAYLOAD_COLUMN_NAMES = setOf("raw", "raw_map", "state", "state_map", "payload", "payload_json")

        // -- v1 schema SQL, mirroring shared/schemas/.../2.json verbatim except
        // that `sync_metadata` lacks the column added by MIGRATION_1_2. --

        const val V1_OBSERVATIONS: String =
            "CREATE TABLE IF NOT EXISTS `observations` (" +
                "`firebase_project_id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
                "`sample_id` TEXT NOT NULL, `interval_start_epoch_millis` INTEGER NOT NULL, " +
                "`interval_end_epoch_millis` INTEGER NOT NULL, `energy_value` REAL, `recorded_unit` TEXT, " +
                "`interval_status` TEXT, `quality` TEXT NOT NULL, `quality_flags` TEXT NOT NULL, " +
                "`flag_texts` TEXT NOT NULL, `source_failure` TEXT, `persisted_at_epoch_millis` INTEGER, " +
                "`reconciled_at_epoch_millis` INTEGER, `scheduled_at_epoch_millis` INTEGER, " +
                "`observed_at_epoch_millis` INTEGER, `local_date` TEXT, `timezone` TEXT, `completeness` INTEGER, " +
                "`metadata_version` TEXT, `collector_version` TEXT, `energy_property` TEXT, " +
                "`raw_daily_total` TEXT, `raw_daily_total_number` REAL, `interval_value` TEXT, " +
                "`interval_value_number` REAL, `interval_seconds` REAL, `final_previous_day_total` TEXT, " +
                "`final_previous_day_total_number` REAL, `unit_text` TEXT, `unit_source` TEXT, " +
                "`unit_source_text` TEXT, `energy_source_failure_ok` INTEGER, " +
                "`energy_source_failure_class` TEXT, `energy_source_failure_error_code` TEXT, " +
                "`energy_source_failure_error_name` TEXT, `state_source_failure_ok` INTEGER, " +
                "`state_source_failure_class` TEXT, `state_source_failure_error_code` TEXT, " +
                "`state_source_failure_error_name` TEXT, `model_device_id` TEXT, " +
                "PRIMARY KEY(`firebase_project_id`, `owner_uid`, `device_id`, `sample_id`))"

        const val V1_OBSERVATIONS_INDEX_START: String =
            "CREATE INDEX IF NOT EXISTS " +
                "`index_observations_firebase_project_id_owner_uid_device_id_interval_start_epoch_millis` " +
                "ON `observations` (`firebase_project_id`, `owner_uid`, `device_id`, `interval_start_epoch_millis`)"

        const val V1_OBSERVATIONS_INDEX_END: String =
            "CREATE INDEX IF NOT EXISTS " +
                "`index_observations_firebase_project_id_owner_uid_device_id_interval_end_epoch_millis` " +
                "ON `observations` (`firebase_project_id`, `owner_uid`, `device_id`, `interval_end_epoch_millis`)"

        const val V1_DAILY_TOTALS: String =
            "CREATE TABLE IF NOT EXISTS `daily_totals` (" +
                "`firebase_project_id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
                "`total_id` TEXT NOT NULL, `day_start_epoch_millis` INTEGER NOT NULL, `energy_value` REAL NOT NULL, " +
                "`recorded_unit` TEXT, `source` TEXT NOT NULL, `finalized` INTEGER NOT NULL, `local_date` TEXT, " +
                "`total_text` TEXT, `total_number` REAL, `unit_text` TEXT, `fetched_at_epoch_millis` INTEGER, " +
                "`model_device_id` TEXT, " +
                "PRIMARY KEY(`firebase_project_id`, `owner_uid`, `device_id`, `total_id`))"

        const val V1_COVERED_RANGES: String =
            "CREATE TABLE IF NOT EXISTS `covered_ranges` (" +
                "`firebase_project_id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
                "`from_epoch_millis` INTEGER NOT NULL, `to_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`firebase_project_id`, `owner_uid`, `device_id`, `from_epoch_millis`))"

        const val V1_SYNC_METADATA: String =
            "CREATE TABLE IF NOT EXISTS `sync_metadata` (" +
                "`firebase_project_id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
                "`last_successful_sync_epoch_millis` INTEGER, `last_error_epoch_millis` INTEGER, " +
                "`last_error` TEXT, `persisted_at_watermark_epoch_millis` INTEGER, " +
                "PRIMARY KEY(`firebase_project_id`, `owner_uid`, `device_id`))"

        const val V1_COLLECTOR_HEALTH: String =
            "CREATE TABLE IF NOT EXISTS `collector_health` (" +
                "`firebase_project_id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `device_id` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `last_poll_epoch_millis` INTEGER, `last_success_epoch_millis` INTEGER, " +
                "`last_failure_epoch_millis` INTEGER, `last_failure_reason` TEXT, `last_sample_id` TEXT, " +
                "`last_sample_path` TEXT, `last_error_class` TEXT, `consecutive_failures` INTEGER, " +
                "`consecutive_rate_limits` INTEGER, `rate_limited_until_epoch_millis` INTEGER, " +
                "`collector_version` TEXT, " +
                "PRIMARY KEY(`firebase_project_id`, `owner_uid`, `device_id`))"
    }
}
