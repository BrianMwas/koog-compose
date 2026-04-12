package io.github.koogcompose.session.room

import androidx.room.RoomDatabase
import io.github.koogcompose.session.SessionStore
import io.github.koogcompose.session.StateMigration
import kotlinx.serialization.serializer

/**
 * Entry point for the Room-backed [SessionStore] battery.
 *
 * Usage (Android):
 * ```kotlin
 * val store = KoogRoomSession.create<AppState>(
 *     builder = getDatabaseBuilder(context),
 *     stateSerializer = AppState.serializer()
 * )
 * ```
 *
 * Usage (iOS):
 * ```kotlin
 * val store = KoogRoomSession.create<AppState>(
 *     builder = getDatabaseBuilder(),
 *     stateSerializer = AppState.serializer()
 * )
 * ```
 *
 * @param S The app state type.
 * @param builder The platform-specific Room database builder.
 * @param stateSerializer The serializer for your app state class.
 * @param stateMigration Optional migration for evolving app state schemas.
 */
public object KoogRoomSession {

    public inline fun <reified S : Any> create(
        builder: RoomDatabase.Builder<KoogDatabase>,
        stateMigration: StateMigration<S>? = null
    ): SessionStore {
        val db = createKoogDatabase(builder)
        return RoomSessionStore(
            dao = db.koogSessionDao(),
            stateSerializer = serializer<S>(),
            stateMigration = stateMigration
        )
    }
}
