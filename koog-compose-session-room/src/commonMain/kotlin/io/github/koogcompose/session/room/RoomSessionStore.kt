package io.github.koogcompose.session.room

import io.github.koogcompose.session.AgentSession
import io.github.koogcompose.session.SessionMessage
import io.github.koogcompose.session.SessionStore
import io.github.koogcompose.session.StateMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

public class RoomSessionStore<S : Any>(
    private val dao: KoogSessionDao,
    private val stateSerializer: kotlinx.serialization.KSerializer<S>,
    private val stateMigration: StateMigration<S>? = null
) : SessionStore {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val effectiveMigration: StateMigration<S> =
        stateMigration ?: StateMigration.lenient(stateSerializer)

    override suspend fun load(sessionId: String): AgentSession? {
        val sessionEntity = dao.getSession(sessionId) ?: return null
        val messageEntities = dao.getMessages(sessionId)
        val contextVarEntities = dao.getContextVars(sessionId)

        // Migrate serializedState if it exists and a migration is configured
        val migratedState = sessionEntity.serializedState?.let { rawJson ->
            val storedVersion = sessionEntity.serializedStateVersion
            val currentVersion = effectiveMigration.schemaVersion

            if (storedVersion < currentVersion) {
                val element = Json.parseToJsonElement(rawJson)
                val jsonObject = element as? JsonObject
                if (jsonObject != null) {
                    val migrated = effectiveMigration.migrate(jsonObject, storedVersion)
                    Json.encodeToJsonElement(migrated).toString()
                } else {
                    rawJson // not a JSON object, leave as-is
                }
            } else {
                rawJson
            }
        }

        return AgentSession(
            sessionId = sessionId,
            currentPhaseName = sessionEntity.currentPhaseName,
            messageHistory = messageEntities.map { it.toSessionMessage() },
            serializedState = migratedState,
            serializedStateVersion = effectiveMigration.schemaVersion,
            contextVars = contextVarEntities.associate { it.key to it.value },
            toolCallCounts = sessionEntity.toolCallCountsJson
                ?.let { json.decodeFromString<Map<String, Int>>(it) }
                ?: emptyMap(),
            createdAt = sessionEntity.createdAt,
            updatedAt = sessionEntity.updatedAt
        )
    }

    override suspend fun save(sessionId: String, session: AgentSession) {
        val now = currentTimeMillis()

        dao.upsertSession(
            SessionEntity(
                sessionId = sessionId,
                currentPhaseName = session.currentPhaseName,
                serializedState = session.serializedState,
                serializedStateVersion = session.serializedStateVersion,
                toolCallCountsJson = if (session.toolCallCounts.isNotEmpty()) {
                    Json.encodeToString(session.toolCallCounts)
                } else null,
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
