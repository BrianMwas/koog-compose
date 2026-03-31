package io.github.koogcompose.session.room

import androidx.room.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [SessionEntity::class, MessageEntity::class, ContextVarEntity::class],
    version = 1
)
@ConstructedBy(KoogDatabaseConstructor::class)
public abstract class KoogDatabase : RoomDatabase() {
    public abstract fun koogSessionDao(): KoogSessionDao
}

// Room compiler generates the actual implementations
public expect object KoogDatabaseConstructor : RoomDatabaseConstructor<KoogDatabase>

public fun createKoogDatabase(
    builder: RoomDatabase.Builder<KoogDatabase>
): KoogDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
