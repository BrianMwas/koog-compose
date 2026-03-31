package io.github.koogcompose.session.room

import io.github.koogcompose.session.AgentSession
import io.github.koogcompose.session.SessionMessage
import io.github.koogcompose.session.SessionStore

public class RoomSessionStore(
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
        val now = currentTimeMillis() // ✅ KMP-safe, no System.currentTimeMillis()

        dao.upsertSession(
            SessionEntity(
                sessionId = sessionId,
                currentPhaseName = session.currentPhaseName,
                createdAt = session.createdAt,
                updatedAt = now
            )
        )

        dao.replaceMessages(
            sessionId = sessionId,
            messages = session.messageHistory.mapIndexed { index, msg ->
                msg.toMessageEntity(sessionId, sequenceNumber = index)
            }
        )

        dao.deleteContextVars(sessionId)
        dao.upsertContextVars(
            session.contextVars.map { (key, value) ->
                ContextVarEntity(sessionId = sessionId, key = key, value = value)
            }
        )
    }

    override suspend fun delete(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    override suspend fun exists(sessionId: String): Boolean =
        dao.sessionExists(sessionId) > 0

    // ── Mapping helpers ───────────────────────────────────────────────────────

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
