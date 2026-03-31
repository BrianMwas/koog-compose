package io.github.koogcompose.session.room

import androidx.room.*

@Entity(tableName = "koog_sessions")
public data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val currentPhaseName: String,
    val createdAt: Long,
    val updatedAt: Long
)

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
