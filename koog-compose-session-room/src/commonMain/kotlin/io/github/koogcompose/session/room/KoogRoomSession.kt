package io.github.koogcompose.session.room

import androidx.room.RoomDatabase
import io.github.koogcompose.session.SessionStore

/**
 * Entry point for the Room-backed [SessionStore] battery.
 *
 * Usage (Android):
 * ```kotlin
 * val store = KoogRoomSession.create(getDatabaseBuilder(context))
 * ```
 *
 * Usage (iOS):
 * ```kotlin
 * val store = KoogRoomSession.create(getDatabaseBuilder())
 * ```
 */
public object KoogRoomSession {

    public fun create(
        builder: RoomDatabase.Builder<KoogDatabase>
    ): SessionStore {
        val db = createKoogDatabase(builder)
        return RoomSessionStore(db.koogSessionDao())
    }
}
