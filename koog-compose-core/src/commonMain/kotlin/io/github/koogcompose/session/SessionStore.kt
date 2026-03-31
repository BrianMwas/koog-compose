package io.github.koogcompose.session


import kotlinx.serialization.Serializable

/**
 * Pluggable persistence layer for [PhaseSession].
 *
 * A session stores everything needed to resume an agent conversation exactly
 * where it left off — full LLM message history, current phase, and any
 * app-level context variables written during the session.
 *
 * koog-compose ships three implementations:
 *  - [InMemorySessionStore]  — default, no setup required, lost on process death
 *  - [RedisSessionStore]     — for server-side or multi-device sync (koog-compose-device)
 *  - [RoomSessionStore]      — for persistent on-device storage (koog-compose-device)
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
interface SessionStore {
    /**
     * Loads an existing session. Returns null if no session exists for [sessionId].
     */
    suspend fun load(sessionId: String): AgentSession?

    /**
     * Persists the session. Overwrites any existing session with the same [sessionId].
     */
    suspend fun save(sessionId: String, session: AgentSession)

    /**
     * Deletes a session. No-op if it doesn't exist.
     */
    suspend fun delete(sessionId: String)

    /**
     * Returns true if a session exists for [sessionId].
     */
    suspend fun exists(sessionId: String): Boolean
}

// ── AgentSession ───────────────────────────────────────────────────────────────

/**
 * The full state required to resume an agent conversation.
 *
 * @param sessionId Stable identifier for this session (e.g. userId, conversationId).
 * @param currentPhaseName The phase the agent was in when the session was last saved.
 * @param messageHistory The complete LLM message history — all turns, all phases.
 *   Each entry is a serialized [SessionMessage].
 * @param contextVars App-level key-value pairs written via [PhaseSession.setContext].
 *   Example: `mapOf("user_id" to "brian_123", "account_id" to "KE-4567")`
 * @param createdAt Unix timestamp (ms) when the session was first created.
 * @param updatedAt Unix timestamp (ms) of the last save.
 */
@Serializable
data class AgentSession(
    val sessionId: String,
    val currentPhaseName: String,
    val messageHistory: List<SessionMessage>,
    val contextVars: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
data class SessionMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)