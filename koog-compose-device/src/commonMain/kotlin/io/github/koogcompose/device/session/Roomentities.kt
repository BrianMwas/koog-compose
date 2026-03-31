package io.github.koogcompose.device.session

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ───────────────────────────────────────────────────────────────────

/**
 * Stores the top-level session metadata.
 */
@Entity(tableName = "koog_sessions")
public data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val currentPhaseName: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Stores a single LLM message.
 */
@Entity(
    tableName = "koog_messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
public data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequenceNumber: Int,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)

/**
 * Stores app-level context variables.
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
public data class ContextVarEntity(
    val sessionId: String,
    val key: String,
    val value: String
)

// ── DAO ────────────────────────────────────────────────────────────────────────

@Dao
public interface KoogSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM koog_sessions WHERE sessionId = :sessionId")
    public suspend fun getSession(sessionId: String): SessionEntity?

    @Query("DELETE FROM koog_sessions WHERE sessionId = :sessionId")
    public suspend fun deleteSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM koog_sessions WHERE sessionId = :sessionId")
    public suspend fun sessionExists(sessionId: String): Int

    @Insert
    public suspend fun insertMessages(messages: List<MessageEntity>)

    @Transaction
    public suspend fun replaceMessages(sessionId: String, messages: List<MessageEntity>) {
        deleteMessages(sessionId)
        insertMessages(messages)
    }

    @Query("SELECT * FROM koog_messages WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    public suspend fun getMessages(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM koog_messages WHERE sessionId = :sessionId")
    public suspend fun deleteMessages(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertContextVars(contextVars: List<ContextVarEntity>)

    @Query("SELECT * FROM koog_context_vars WHERE sessionId = :sessionId")
    public suspend fun getContextVars(sessionId: String): List<ContextVarEntity>

    @Query("DELETE FROM koog_context_vars WHERE sessionId = :sessionId")
    public suspend fun deleteContextVars(sessionId: String)

    @Query("SELECT COUNT(*) FROM koog_messages WHERE sessionId = :sessionId")
    public fun observeMessageCount(sessionId: String): Flow<Int>

    @Query("SELECT * FROM koog_sessions ORDER BY updatedAt DESC")
    public fun observeAllSessions(): Flow<List<SessionEntity>>
}
