package io.github.koogcompose.device.session



import io.github.koogcompose.session.AgentSession
import io.github.koogcompose.session.SessionMessage
import io.github.koogcompose.session.SessionStore

/**
 * Room-backed [SessionStore] for koog-compose-device.
 *
 * Persists full LLM message history to a local Room database. Suitable for:
 *  - Apps that need conversation history to survive app restarts and device reboots
 *  - Offline-first assistants (math tutors, budgeting apps, etc.)
 *  - "Continue where you left off" UX
 *
 * Sessions are split across three tables:
 *  - `koog_sessions`      — metadata (phase, timestamps)
 *  - `koog_messages`      — full LLM history, one row per message
 *  - `koog_context_vars`  — app-level key-value context
 *
 * Foreign keys with CASCADE delete ensure no orphaned messages when a session
 * is deleted.
 *
 * Setup:
 * ```kotlin
 * // In Application.onCreate() or your DI module
 * val db = KoogSessionDatabase.create(context)
 *
 * val session = PhaseSession(
 *     context = koogContext,
 *     executor = executor,
 *     sessionId = "user_${userId}",
 *     store = RoomSessionStore(db.sessionDao())
 * )
 * ```
 *
 * To merge into an existing AppDatabase instead of creating a standalone DB,
 * see [KoogSessionDatabase] docs.
 */
class RoomSessionStore(
    private val dao: KoogSessionDao
) : SessionStore {

    override suspend fun load(sessionId: String): AgentSession? {
        val sessionEntity = dao.getSession(sessionId) ?: return null
        val messageEntities = dao.getMessages(sessionId)
        val contextVarEntities = dao.getContextVars(sessionId)

        return AgentSession(
            sessionId = sessionId,
            currentPhaseName = sessionEntity.currentPhaseName,
            messageHistory = messageEntities.map { it.toSessionMessage() },
            contextVars = contextVarEntities.associate { it.key to it.value },
            createdAt = sessionEntity.createdAt,
            updatedAt = sessionEntity.updatedAt
        )
    }

    override suspend fun save(sessionId: String, session: AgentSession) {
        val now = System.currentTimeMillis()

        // Upsert session metadata
        dao.upsertSession(
            SessionEntity(
                sessionId = sessionId,
                currentPhaseName = session.currentPhaseName,
                createdAt = session.createdAt,
                updatedAt = now
            )
        )

        // Replace all messages (simpler than diffing; history is append-only in practice)
        dao.replaceMessages(
            sessionId = sessionId,
            messages = session.messageHistory.mapIndexed { index, msg ->
                msg.toMessageEntity(sessionId, sequenceNumber = index)
            }
        )

        // Replace context vars
        dao.deleteContextVars(sessionId)
        dao.upsertContextVars(
            session.contextVars.map { (key, value) ->
                ContextVarEntity(sessionId = sessionId, key = key, value = value)
            }
        )
    }

    override suspend fun delete(sessionId: String) {
        // CASCADE deletes messages and context vars automatically
        dao.deleteSession(sessionId)
    }

    override suspend fun exists(sessionId: String): Boolean =
        dao.sessionExists(sessionId) > 0

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private fun MessageEntity.toSessionMessage() = SessionMessage(
        role = role,
        content = content,
        toolName = toolName,
        toolCallId = toolCallId
    )

    private fun SessionMessage.toMessageEntity(
        sessionId: String,
        sequenceNumber: Int
    ) = MessageEntity(
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        role = role,
        content = content,
        toolName = toolName,
        toolCallId = toolCallId
    )
}