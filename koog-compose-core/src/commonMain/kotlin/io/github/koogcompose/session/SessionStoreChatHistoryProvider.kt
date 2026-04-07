package io.github.koogcompose.session

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.collections.emptyMap
import kotlin.time.Clock

/**
 * Bridges koog-compose's [SessionStore] to Koog's [ChatHistoryProvider] interface.
 *
 * [ChatMemory] calls [loadHistory] before each turn and [saveHistory] after.
 * We persist into our [SessionStore] so the same store serves both the
 * phase/agent state (currentPhaseName) and the full LLM message history —
 * one source of truth, one persistence point.
 *
 * Message serialization uses [SessionMessage] (role + content + optional tool fields)
 * which already covers all Koog message types we care about.
 */
internal class SessionStoreChatHistoryProvider(
    private val store: SessionStore,
    private val sessionId: String,
) : ChatHistoryProvider {

    //    override suspend fun loadHistory(sessionId: String): List<Message> {
//        val session = store.load(sessionId) ?: return emptyList()
//        return session.messageHistory.mapNotNull { it.toKoogMessage() }
//    }
//
//    override suspend fun saveHistory(sessionId: String, messages: List<Message>) {
//        val existing = store.load(sessionId)
//        val sessionMessages = messages.mapNotNull { it.toSessionMessage() }
//
//        store.save(
//            sessionId,
//            AgentSession(
//                sessionId = sessionId,
//                // Preserve currentPhaseName from existing session — ChatMemory only owns history
//                currentPhaseName = existing?.currentPhaseName ?: "",
//                messageHistory = sessionMessages,
//                contextVars = existing?.contextVars ?: emptyMap(),
//                createdAt = existing?.createdAt ?: currentTimeMs(),
//            )
//        )
//    }
    override suspend fun store(
        conversationId: String,
        messages: List<Message>
    ) {
        val existing = store.load(sessionId)
        val sessionMessages = messages.mapNotNull { it.toSessionMessage() }

        store.save(
            sessionId,
            AgentSession(
                sessionId = sessionId,
                // Preserve currentPhaseName from existing session — ChatMemory only owns history
                currentPhaseName = existing?.currentPhaseName ?: "",
                messageHistory = sessionMessages,
                contextVars = existing?.contextVars ?: emptyMap(),
                createdAt = existing?.createdAt ?: currentTimeMs(),
                serializedState = TODO(),
                updatedAt = TODO(),
            )
        )
    }

    override suspend fun load(conversationId: String): List<Message> {
        val session = store.load(conversationId) ?: return emptyList()
        return session.messageHistory.mapNotNull { it.toKoogMessage() }
    }
}

// ── Message conversion ─────────────────────────────────────────────────────────

/**
 * Maps a [SessionMessage] back to a Koog [Message].
 * Returns null for roles we don't recognize — these are silently dropped.
 */
private fun SessionMessage.toKoogMessage(): Message? {
    val meta = RequestMetaInfo.create(Clock.System)
    val responseMeta = ResponseMetaInfo.create(Clock.System)

    return when (role) {
        "user" -> Message.User(
            listOf(ContentPart.Text(content)),
            meta
        )
        "assistant" -> Message.Assistant(content, responseMeta)
        "system" -> Message.System(content, meta)
        "tool_call" -> toolName?.let { name ->
            Message.Tool.Call(
                id = toolCallId ?: "",
                tool = name,
                content = content,
                metaInfo = responseMeta
            )
        }
        "tool_result" -> toolName?.let { name ->
            Message.Tool.Result(
                id = toolCallId ?: "",
                tool = name,
                content = content,
                metaInfo = meta
            )
        }
        else -> null
    }
}

/**
 * Maps a Koog [Message] to a [SessionMessage] for persistence.
 * Returns null for message types we don't persist (e.g. internal meta messages).
 */
private fun Message.toSessionMessage(): SessionMessage? = when (this) {
    is Message.User -> SessionMessage(
        role = "user",
        content = content.filterIsInstance<ContentPart.Text>()
            .joinToString(" ") { it.text }
    )
    is Message.Assistant -> SessionMessage(role = "assistant", content = content)
    is Message.System -> SessionMessage(role = "system", content = content)
    is Message.Tool.Call -> SessionMessage(
        role = "tool_call",
        content = content,
        toolName = tool,
        toolCallId = id
    )
    is Message.Tool.Result -> SessionMessage(
        role = "tool_result",
        content = content,
        toolName = tool,
        toolCallId = id
    )
    else -> null
}