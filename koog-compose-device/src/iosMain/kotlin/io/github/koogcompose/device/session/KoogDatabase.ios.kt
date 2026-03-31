package io.github.koogcompose.device.session

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation for obtaining a Room database builder for [KoogDatabase].
 */
public fun getKoogDatabaseBuilder(): RoomDatabase.Builder<KoogDatabase> {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val path = documentDirectory?.path + "/koog_compose.db"
    return Room.databaseBuilder<KoogDatabase>(
        name = path,
        factory = { KoogDatabase_Impl() }
    )
}
