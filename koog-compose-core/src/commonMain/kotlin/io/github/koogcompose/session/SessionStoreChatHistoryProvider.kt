package io.github.koogcompose.session

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock

/**
 * Bridges koog-compose's [SessionStore] to Koog's [ChatHistoryProvider] interface.
 *
 * Both [load] and [store] receive a [conversationId] from Koog which may differ
 * from [sessionId] during agent swaps (continueHistory=false). We intentionally
 * use the constructor-captured [sessionId] as the store key so that the provider
 * and the store always agree on the record they read and write.
 * [SessionRunner] creates a new provider instance per history scope, so the
 * captured [sessionId] is always correct for that scope.
 */
internal class SessionStoreChatHistoryProvider(
    private val store: SessionStore,
    private val sessionId: String,
) : ChatHistoryProvider {

    override suspend fun load(conversationId: String): List<Message> {
        val session = store.load(sessionId) ?: return emptyList()
        return session.messageHistory.mapNotNull { it.toKoogMessage() }
    }

    override suspend fun store(
        conversationId: String,
        messages: List<Message>,
    ) {
        val existing = store.load(sessionId)
        val sessionMessages = messages.flatMap { it.toSessionMessage() }
        val now = currentTimeMs()

        store.save(
            sessionId,
            AgentSession(
                sessionId              = sessionId,
                currentPhaseName       = existing?.currentPhaseName ?: "",
                messageHistory         = sessionMessages,
                serializedState        = existing?.serializedState,
                serializedStateVersion = existing?.serializedStateVersion ?: 0,
                contextVars            = existing?.contextVars ?: emptyMap(),
                toolCallCounts         = existing?.toolCallCounts ?: emptyMap(),
                createdAt              = existing?.createdAt ?: now,
                updatedAt              = now,
            )
        )
    }

    /**
     * Wipes the message history for this session while preserving the rest of the
     * [AgentSession] record. Called by [SessionRunner] when activating a specialist
     * agent with continueHistory = false.
     */
    internal suspend fun clearHistory() {
        val existing = store.load(sessionId) ?: return
        store.save(
            sessionId,
            existing.copy(
                messageHistory = emptyList(),
                updatedAt      = currentTimeMs(),
            )
        )
    }
}

// ── Message conversion ────────────────────────────────────────────────────────

private fun SessionMessage.toKoogMessage(): Message? {
    val reqMeta  = RequestMetaInfo.create(KoogClock.System)
    val respMeta = ResponseMetaInfo.create(KoogClock.System)

    return when (role) {
        "user"        -> Message.User(content = content, metaInfo = reqMeta)
        "assistant"   -> Message.Assistant(content = content, metaInfo = respMeta)
        "system"      -> Message.System(content = content, metaInfo = reqMeta)
        // koog 1.0.0: tool calls/results are MessagePart.Tool.* parts, not standalone
        // messages. A tool call rides in an assistant message; a tool result in a user message.
        "tool_call"   -> toolName?.let { name ->
            Message.Assistant(
                MessagePart.Tool.Call(id = toolCallId ?: "", tool = name, args = content),
                metaInfo = respMeta,
            )
        }
        "tool_result" -> toolName?.let { name ->
            Message.User(
                MessagePart.Tool.Result(id = toolCallId ?: "", tool = name, output = content),
                metaInfo = reqMeta,
            )
        }
        else -> null
    }
}

/**
 * koog 1.0.0 messages carry a list of [MessagePart]s, so a single [Message] can
 * decompose into several flat [SessionMessage] rows (e.g. an assistant message
 * with text plus a tool call). Tool calls/results are persisted as their own rows.
 */
private fun Message.toSessionMessage(): List<SessionMessage> = when (this) {
    is Message.User -> parts.mapNotNull { part ->
        when (part) {
            is MessagePart.Text -> SessionMessage(role = "user", content = part.text)
            is MessagePart.Tool.Result -> SessionMessage(
                role       = "tool_result",
                content    = part.output,
                toolName   = part.tool,
                toolCallId = part.id,
            )
            else -> null
        }
    }
    is Message.System -> listOf(SessionMessage(role = "system", content = textContent()))
    is Message.Assistant -> parts.mapNotNull { part ->
        when (part) {
            is MessagePart.Text -> SessionMessage(role = "assistant", content = part.text)
            is MessagePart.Tool.Call -> SessionMessage(
                role       = "tool_call",
                content    = part.args,
                toolName   = part.tool,
                toolCallId = part.id,
            )
            else -> null
        }
    }
    else -> emptyList()
}