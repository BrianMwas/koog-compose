package io.github.koogcompose.session

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import kotlin.time.Clock as KoogClock
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo

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
        val sessionMessages = messages.mapNotNull { it.toSessionMessage() }
        val now = currentTimeMs()

        store.save(
            sessionId,
            AgentSession(
                sessionId        = sessionId,
                currentPhaseName = existing?.currentPhaseName ?: "",
                messageHistory   = sessionMessages,
                serializedState  = existing?.serializedState,
                contextVars      = existing?.contextVars ?: emptyMap(),
                createdAt        = existing?.createdAt ?: now,
                updatedAt        = now,
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
        "tool_call"   -> toolName?.let { name ->
            Message.Tool.Call(
                id       = toolCallId ?: "",
                tool     = name,
                content  = content,
                metaInfo = respMeta,
            )
        }
        "tool_result" -> toolName?.let { name ->
            Message.Tool.Result(
                id       = toolCallId ?: "",
                tool     = name,
                content  = content,
                metaInfo = reqMeta,
            )
        }
        else -> null
    }
}

private fun Message.toSessionMessage(): SessionMessage? = when (this) {
    is Message.User -> SessionMessage(
        role    = "user",
        // Message.User.content in koog is a String, not List<ContentPart>.
        // extractText() handles whichever concrete type your Koog version uses.
        content = extractUserText(this),
    )
    is Message.Assistant -> SessionMessage(
        role    = "assistant",
        content = content,
    )
    is Message.System -> SessionMessage(
        role    = "system",
        content = content,
    )
    is Message.Tool.Call -> SessionMessage(
        role       = "tool_call",
        content    = content,
        toolName   = tool,
        toolCallId = id,
    )
    is Message.Tool.Result -> SessionMessage(
        role       = "tool_result",
        content    = content,
        toolName   = tool,
        toolCallId = id,
    )
    else -> null
}

/**
 * Extracts a plain string from [Message.User.content].
 *
 * Koog's [Message.User] content type changed across versions:
 * - Older versions: `content: String`
 * - Newer versions: `content: List<MessageContent>` or similar sealed type
 *
 * We call `toString()` as a safe universal fallback. If your Koog version
 * exposes a typed content list, replace the body with the appropriate accessor
 * (e.g. `content.filterIsInstance<ContentPart.Text>().joinToString { it.text }`).
 */
private fun extractUserText(message: Message.User): String =
    message.content.toString()