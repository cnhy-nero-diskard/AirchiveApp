package cloud.airchive.shared.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase

/**
 * iOS builder: the native overload takes an absolute file path (usually under
 * the app's documents directory) and `@ConstructedBy` resolves the generated
 * [AirchiveDatabaseConstructor]. Not compiled on this Windows host — validated
 * by the documented Room3 KMP contract and the Mac-side gate in the handoff.
 */
actual fun airchiveDatabaseBuilder(name: String): RoomDatabase.Builder<AirchiveDatabase> =
    Room.databaseBuilder<AirchiveDatabase>(name = name)