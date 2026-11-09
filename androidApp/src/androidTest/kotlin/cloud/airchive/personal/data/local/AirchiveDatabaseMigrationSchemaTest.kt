package cloud.airchive.personal.data.local

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.shared.data.local.AirchiveDatabase
import cloud.airchive.shared.data.local.openAirchiveDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Verifies each exported Room schema upgrades to current without losing rows. */
class AirchiveDatabaseMigrationSchemaTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseFile = instrumentation.targetContext.getDatabasePath(TEST_DATABASE_NAME)

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation,
        databaseFile,
        BundledSQLiteDriver(),
        AirchiveDatabase::class,
        { openAirchiveDatabase(databaseFile.absolutePath) },
        emptyList(),
    )

    @Test
    fun everyExportedSchemaMigratesToCurrentVersionWithoutLosingRows() = runBlocking {
        val schemaAssets = instrumentation.context.assets
        val schemaFiles = schemaAssets.list(SCHEMA_ASSET_DIRECTORY).orEmpty()
            .filter { it.endsWith(".json") }
            .sortedBy { it.removeSuffix(".json").toInt() }
        assertFalse("No exported Room schemas found in $SCHEMA_ASSET_DIRECTORY", schemaFiles.isEmpty())
        assertEquals(
            AirchiveDatabase.VERSION,
            schemaFiles.last().removeSuffix(".json").toInt(),
        )

        schemaFiles.forEach { schemaFile ->
            val sourceVersion = schemaFile.removeSuffix(".json").toInt()
            val tables = fixtureTables(
                JSONObject(
                    schemaAssets.open("$SCHEMA_ASSET_DIRECTORY/$schemaFile")
                        .bufferedReader().use { it.readText() },
                ),
            )

            val source = migrationHelper.createDatabase(sourceVersion)
            val sourceCounts: Map<String, Long>
            try {
                tables.forEach { insertFixtureRow(source, it) }
                sourceCounts = rowCounts(source, tables.map { it.name })
                assertEquals(tables.associate { it.name to 1L }, sourceCounts)
            } finally {
                source.close()
            }

            val migrated = migrationHelper.runMigrationsAndValidate(
                AirchiveDatabase.VERSION,
                AirchiveDatabase.MIGRATIONS.toList(),
            )
            try {
                assertEquals(
                    "Rows changed migrating schema $sourceVersion",
                    sourceCounts,
                    rowCounts(migrated, tables.map { it.name }),
                )
            } finally {
                migrated.close()
            }
        }
    }

    private fun fixtureTables(schema: JSONObject): List<FixtureTable> {
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entityIndex ->
            val entity = entities.getJSONObject(entityIndex)
            val primaryKey = entity.optJSONObject("primaryKey")?.optJSONArray("columnNames")
            val primaryKeyColumns = buildSet {
                if (primaryKey != null) {
                    for (index in 0 until primaryKey.length()) add(primaryKey.getString(index))
                }
            }
            val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).mapNotNull { fieldIndex ->
                val field = fields.getJSONObject(fieldIndex)
                val name = field.getString("columnName")
                if (!field.optBoolean("notNull") && name !in primaryKeyColumns) return@mapNotNull null
                FixtureColumn(name, field.getString("affinity"))
            }
            FixtureTable(entity.getString("tableName"), columns)
        }
    }

    private fun insertFixtureRow(connection: SQLiteConnection, table: FixtureTable) {
        val columns = table.columns.joinToString(", ") { quoteIdentifier(it.name) }
        val values = table.columns.joinToString(", ") { column ->
            when (column.affinity.uppercase()) {
                "TEXT" -> "'schema-migration-fixture'"
                "INTEGER" -> "1"
                "REAL" -> "1.0"
                "BLOB" -> "X''"
                else -> error("Unknown SQLite affinity '${column.affinity}' in ${table.name}.${column.name}")
            }
        }
        connection.execSQL("INSERT INTO ${quoteIdentifier(table.name)} ($columns) VALUES ($values)")
    }

    private fun rowCounts(connection: SQLiteConnection, tables: List<String>): Map<String, Long> =
        tables.associateWith { table ->
            connection.prepare("SELECT COUNT(*) FROM ${quoteIdentifier(table)}").use { statement ->
                check(statement.step()) { "COUNT(*) returned no row for $table" }
                statement.getLong(0)
            }
        }

    private fun quoteIdentifier(value: String): String = "`" + value.replace("`", "``") + "`"

    private data class FixtureTable(val name: String, val columns: List<FixtureColumn>)

    private data class FixtureColumn(val name: String, val affinity: String)

    private companion object {
        const val TEST_DATABASE_NAME = "migration-schema-test.db"
        const val SCHEMA_ASSET_DIRECTORY = "cloud.airchive.shared.data.local.AirchiveDatabase"
    }
}
