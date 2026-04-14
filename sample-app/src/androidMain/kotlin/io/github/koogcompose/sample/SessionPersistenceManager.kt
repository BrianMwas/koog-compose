package io.github.koogcompose.sample

import android.content.Context
import androidx.room.Room
import io.github.koogcompose.session.room.RoomSessionStore
import io.github.koogcompose.session.room.KoogSessionDao
import io.github.koogcompose.session.room.KoogDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Manages session persistence for the teaching app.
 * Handles saving and loading teaching sessions across app restarts.
 */
class SessionPersistenceManager(context: Context) {

    private val database: KoogDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass   = KoogDatabase::class.java,
        name    = "teaching_sessions.db",
    ).build()

    private val koogDao: KoogSessionDao = database.koogSessionDao()

    val sessionStore = RoomSessionStore(
        dao             = koogDao,
        stateSerializer = serializer<TeachingState>(),
        stateMigration  = null,
    )

    val dao: KoogSessionDao get() = koogDao

    suspend fun getSessions()                      = koogDao.observeAllSessions()
    suspend fun deleteSession(sessionId: String)   = koogDao.deleteSession(sessionId)
    suspend fun getSession(sessionId: String)      = koogDao.getSession(sessionId)
}