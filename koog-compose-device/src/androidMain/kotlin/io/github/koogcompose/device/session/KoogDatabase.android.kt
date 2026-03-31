package io.github.koogcompose.device.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Provides the Android implementation of the Room database builder.
 */
public fun getKoogDatabaseBuilder(ctx: Context): RoomDatabase.Builder<KoogDatabase> {
    val appContext = ctx.applicationContext
    val dbFile = appContext.getDatabasePath("koog_compose.db")
    return Room.databaseBuilder<KoogDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}
