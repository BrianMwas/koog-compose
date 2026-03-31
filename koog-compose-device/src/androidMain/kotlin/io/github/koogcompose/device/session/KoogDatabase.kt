package io.github.koogcompose.device.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Android implementation for obtaining a Room database builder.
 */
internal fun getDatabaseBuilder(ctx: Context): RoomDatabase.Builder<KoogDatabase> {
    val appContext = ctx.applicationContext
    val dbFile = appContext.getDatabasePath("koog_compose.db")
    return Room.databaseBuilder<KoogDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}
