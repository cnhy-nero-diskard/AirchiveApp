package cloud.airchive.shared.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase

/**
 * Android builder: `name` is an absolute file path and `@ConstructedBy` resolves
 * the generated [AirchiveDatabaseConstructor] in place of reflection. This
 * context-free overload is also what the file-backed `androidHostTest` tests use
 * on the local JVM.
 */
actual fun airchiveDatabaseBuilder(name: String): RoomDatabase.Builder<AirchiveDatabase> =
    Room.databaseBuilder<AirchiveDatabase>(name = name)