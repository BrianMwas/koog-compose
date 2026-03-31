package io.github.koogcompose.device.session

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ───────────────────────────────────────────────────────────────────

/**
 * Stores the top-level session metadata.
 * Include this in your Room database if you want to use [RoomSessionStore].
 */
@Entity(tableName = "koog_sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val currentPhaseName: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Stores a single LLM message. One row per message.
 * Ordered by [sequenceNumber] to reconstruct history in the correct order.
 */
@Entity(
    tableName = "koog_messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE // deleting a session deletes its messages
    )],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequenceNumber: Int, // position in history — used for ordering
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)

/**
 * Stores app-level context variables (key-value pairs) for a session.
 */
@Entity(
    tableName = "koog_context_vars",
    primaryKeys = ["sessionId", "key"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ContextVarEntity(
    val sessionId: String,
    val key: String,
    val value: String
)

// ── DAO ────────────────────────────────────────────────────────────────────────

/**
 * Data Access Object for Koog sessions.
 * Define an abstract method returning this in your @Database class.
 */
@Dao
interface KoogSessionDao {

    // ── Session ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM koog_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Query("DELETE FROM koog_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM koog_sessions WHERE sessionId = :sessionId")
    suspend fun sessionExists(sessionId: String): Int

    // ── Messages ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    /**
     * Replaces all messages for a session in one transaction.
     * Used on save to avoid incremental tracking complexity.
     */
    @Transaction
    suspend fun replaceMessages(sessionId: String, messages: List<MessageEntity>) {
        deleteMessages(sessionId)
        insertMessages(messages)
    }

    @Query("SELECT * FROM koog_messages WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    suspend fun getMessages(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM koog_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    // ── Context vars ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextVar(contextVar: ContextVarEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextVars(contextVars: List<ContextVarEntity>)

    @Query("SELECT * FROM koog_context_vars WHERE sessionId = :sessionId")
    suspend fun getContextVars(sessionId: String): List<ContextVarEntity>

    @Query("DELETE FROM koog_context_vars WHERE sessionId = :sessionId")
    suspend fun deleteContextVars(sessionId: String)

    // ── Observe (bonus — for Compose UI) ─────────────────────────────────────

    /** Observe message count live — useful for "X messages in session" indicators. */
    @Query("SELECT COUNT(*) FROM koog_messages WHERE sessionId = :sessionId")
    fun observeMessageCount(sessionId: String): Flow<Int>

    /** List all sessions ordered by last updated. */
    @Query("SELECT * FROM koog_sessions ORDER BY updatedAt DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>
}
