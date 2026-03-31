package io.github.koogcompose.session.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

public fun getDatabaseBuilder(ctx: Context): RoomDatabase.Builder<KoogDatabase> {
    val appContext = ctx.applicationContext
    val dbFile = appContext.getDatabasePath("koog_compose.db")
    return Room.databaseBuilder<KoogDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}
