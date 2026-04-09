package io.github.koogcompose.session

import kotlinx.serialization.Serializable

/**
 * Pluggable persistence layer for [PhaseSession].
 *
 * A session stores everything needed to resume an agent conversation exactly
 * where it left off — full LLM message history, current phase, and any
 * app-level context variables written during the session.
 *
 * koog-compose currently ships two maintained implementations:
 *  - [InMemorySessionStore]  — default, no setup required, lost on process death
 *  - [RoomSessionStore] — for persistent on-device storage
 *
 * For server-side or multi-device sync, provide a custom [SessionStore]
 * implementation backed by your own service or database.
 *
 * Custom implementation:
 * ```kotlin
 * class MyEncryptedSessionStore : SessionStore {
 *     override suspend fun load(sessionId: String): AgentSession? { ... }
 *     override suspend fun save(sessionId: String, session: AgentSession) { ... }
 *     override suspend fun delete(sessionId: String) { ... }
 *     override suspend fun exists(sessionId: String): Boolean { ... }
 * }
 * ```
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
    /** App-level context variables (key-value pairs). */
    val contextVars: Map<String, String> = emptyMap(),
    /** Tool call frequency counts. Keyed by tool name, value is call count for this session. */
    val toolCallCounts: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A single message in the LLM history.
 *
 * Maps directly to Koog's internal message types so the full conversation
 * can be replayed into a new PromptExecutor session on resume.
 *
 * @param role "system" | "user" | "assistant" | "tool_result"
 * @param content The text content of the message.
 * @param toolName Non-null for tool_call and tool_result roles.
 * @param toolCallId Matches a tool_call to its tool_result.
 */
@Serializable
public data class SessionMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)
