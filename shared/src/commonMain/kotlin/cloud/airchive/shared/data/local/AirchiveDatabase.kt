package cloud.airchive.shared.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * Versioned Room KMP database for the owner-local history projection.
 *
 * Owns the normalized [ObservationEntity]/[DailyTotalEntity]/
 * [CoveredRangeEntity]/[SyncEntity]/[CollectorHealthEntity] tables, all keyed by
 * the exact (Firebase project, owner UID, device ID) partition. The current
 * schema version is explicit ([VERSION]); every version jump has an explicit,
 * non-destructive migration in [MIGRATIONS] (no destructive fallback is ever
 * configured). Schema JSON exports to `shared/schemas` via the Room Gradle
 * plugin.
 *
 * The `@ConstructedBy(::AirchiveDatabaseConstructor)` linkage makes the Room
 * compiler generate the platform `actual` constructor (see the `expect` object
 * below) and resolve it from the platform [airchiveDatabaseBuilder] /
 * [openAirchiveDatabase] helpers. Only normalized values enter these tables;
 * Firestore `raw`/`state` payloads and credentials never do.
 */
@Database(
    entities = [
        ObservationEntity::class,
        DailyTotalEntity::class,
        CoveredRangeEntity::class,
        SyncEntity::class,
        CollectorHealthEntity::class,
    ],
    // Must match VERSION below; kept as a literal so KSP2 resolves it without
    // depending on an in-module const reference.
    version = 2,
    exportSchema = true,
)
@ConstructedBy(AirchiveDatabaseConstructor::class)
abstract class AirchiveDatabase : RoomDatabase() {

    abstract fun localHistoryDao(): LocalHistoryDao

    companion object {
        /** Default file name for the app's on-device history database. */
        const val DATABASE_NAME: String = "airchive.db"

        /**
         * Explicit current schema version. Bump with every schema change, add a
         * matching entry to [MIGRATIONS], and keep the `@Database(version=…)`
         * literal in sync above; never delete or downgrade rows.
         */
        const val VERSION: Int = 2

        /**
         * Migration 1 → 2: the v1 `sync_metadata` table tracked only the
         * `persistedAt` watermark; v2 adds the `reconciledAt` watermark column
         * so incremental reconciliation state survives upgrades. The change is
         * intentionally narrow: `ALTER TABLE ... ADD COLUMN` preserves every
         * existing row in all partitioned tables.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sync_metadata " +
                        "ADD COLUMN reconciled_at_watermark_epoch_millis INTEGER",
                )
            }
        }

        /** Every explicit forward migration, in start→end order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}

/**
 * Links [AirchiveDatabase] to its generated platform implementation. The Room
 * compiler generates the `actual` object per platform target; callers construct
 * the database through [openAirchiveDatabase] instead of invoking this directly.
 */
@Suppress("KotlinNoActualForExpect")
expect object AirchiveDatabaseConstructor : RoomDatabaseConstructor<AirchiveDatabase> {
    override fun initialize(): AirchiveDatabase
}

/**
 * Platform-specific `RoomDatabase.Builder` factory. The only platform-specific
 * piece of the Room setup (file-system/context differences). Callers then apply
 * shared configuration via [openAirchiveDatabase], which uses the bundled SQLite
 * driver so every platform executes the same SQLite build.
 */
expect fun airchiveDatabaseBuilder(name: String): RoomDatabase.Builder<AirchiveDatabase>

/**
 * Opens (or migrates) the on-device history database at [name], configuring the
 * bundled SQLite driver and every explicit [migrations]. Pass
 * [AirchiveDatabase.MIGRATIONS] so previous-version files upgrade in place; the
 * database never falls back to a destructive migration.
 */
fun openAirchiveDatabase(
    name: String,
    migrations: List<Migration> = AirchiveDatabase.MIGRATIONS.asList(),
): AirchiveDatabase =
    airchiveDatabaseBuilder(name)
        .setDriver(BundledSQLiteDriver())
        .apply { migrations.forEach { addMigrations(it) } }
        .build()