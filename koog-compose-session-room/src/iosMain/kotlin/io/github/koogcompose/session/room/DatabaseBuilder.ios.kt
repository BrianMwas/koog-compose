package io.github.koogcompose.session.room

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSHomeDirectory

public fun getDatabaseBuilder(): RoomDatabase.Builder<KoogDatabase> {
    val dbFilePath = NSHomeDirectory() + "/koog_compose.db"
    return Room.databaseBuilder<KoogDatabase>(
        name = dbFilePath,
        factory = { KoogDatabase_Impl() }
    )
}
