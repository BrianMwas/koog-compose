package io.github.koogcompose.session

import kotlinx.serialization.Serializable

/**
 * Pluggable persistence layer for [PhaseSession].
 */
public interface SessionStore {
    public suspend fun load(sessionId: String): AgentSession?
    public suspend fun save(sessionId: String, session: AgentSession)
    public suspend fun delete(sessionId: String)
    public suspend fun exists(sessionId: String): Boolean
}

/**
 * The full state required to resume an agent conversation.
 */
@Serializable
public data class AgentSession(
    val sessionId: String,
    val currentPhaseName: String,
    val messageHistory: List<SessionMessage>,
    /** JSON representation of the app state [S]. */
    val serializedState: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A single message in the LLM history.
 */
@Serializable
public data class SessionMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)
