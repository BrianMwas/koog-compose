package io.github.koogcompose.session.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
