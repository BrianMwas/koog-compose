package io.github.koogcompose.session

import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.PermissionCheckResult
import io.github.koogcompose.security.PermissionManager
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ── Message model ─────────────────────────────────────────────────────────────

enum class MessageRole { USER, ASSISTANT, SYSTEM }

enum class MessageState { SENDING, STREAMING, COMPLETE, ERROR }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val state: MessageState = MessageState.COMPLETE,
    val attachments: List<Attachment> = emptyList(),
    val toolCallsUsed: List<String> = emptyList(),
    val timestampMs: Long
)

sealed class Attachment {
    data class Image(val uri: String) : Attachment()
    data class Document(val uri: String, val mimeType: String) : Attachment()
    data class Audio(val uri: String) : Attachment()
}

// ── Streaming response chunks ─────────────────────────────────────────────────

sealed class AIResponseChunk {
    data class TextDelta(val text: String) : AIResponseChunk()
    data class TextComplete(val text: String) : AIResponseChunk()
    data class ReasoningDelta(val text: String) : AIResponseChunk()
    data class ToolCallRequest(val toolName: String, val args: JsonObject) : AIResponseChunk()
    object End : AIResponseChunk()
    data class Error(val message: String) : AIResponseChunk()
}

// ── Session state ─────────────────────────────────────────────────────────────

data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val isRateLimited: Boolean = false,
)

// ── Provider interface ────────────────────────────────────────────────────────

interface AIProvider {
    fun stream(
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment> = emptyList()
    ): Flow<AIResponseChunk>
}

/// Chat Session
class ChatSession(
    val context: KoogComposeContext,
    private val provider: AIProvider,
    private val scope: CoroutineScope,
    private val userId: String? = null,
    private val idGenerator: () -> String = { randomId() }
) {
    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _chunks = MutableSharedFlow<AIResponseChunk>(extraBufferCapacity = 256)
    val chunks: SharedFlow<AIResponseChunk> = _chunks.asSharedFlow()

    val auditLogger = AuditLogger()
    val permissionManager = PermissionManager(
        auditLogger = auditLogger,
        requireConfirmationForSensitive = context.config.requireConfirmationForSensitive,
        userId = userId
    )

    private var activeJob: Job? = null
    private val rateLimiter = RateLimiter(context.config.rateLimitPerMinute)

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        activeJob?.cancel()
        activeJob = scope.launch {
            if (!rateLimiter.tryAcquire()) {
                _state.update { it.copy(isRateLimited = true) }
                return@launch
            }
            _state.update { it.copy(isRateLimited = false) }
            streamResponse(text, attachments)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.update { it.copy(isStreaming = false, streamingContent = "") }
    }

    fun regenerate() {
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER } ?: return
        val withoutLastAssistant = messages.dropLastWhile { it.role == MessageRole.ASSISTANT }
        _state.update { it.copy(messages = withoutLastAssistant) }
        send(lastUser.content, lastUser.attachments)
    }

    fun clearHistory() {
        cancel()
        _state.update { ChatSessionState() }
    }

    fun withContext(additionalContext: String): ChatSession {
        return ChatSession(
            context = context.withSessionContext(additionalContext),
            provider = provider,
            scope = scope,
            userId = userId,
            idGenerator = idGenerator
        ).also { it._state.value = _state.value }
    }

    private  suspend fun streamResponse(text: String, attachments: List<Attachment>) {
        val userMessage = ChatMessage(
            id = idGenerator(),
            role = MessageRole.USER,
            content = text,
            state = MessageState.COMPLETE,
            attachments = attachments,
            timestampMs = currentTimeMs()
        )
        val streamingMessageId = idGenerator()
        val streamingMessage = ChatMessage(
            id = streamingMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            state = MessageState.STREAMING,
            timestampMs = currentTimeMs()
        )

        _state.update {
            it.copy(
                messages = it.messages + userMessage + streamingMessage,
                isStreaming = true,
                streamingContent = "",
                error = null
            )
        }

        val accumulated = StringBuilder()
        val toolsUsed = mutableListOf<String>()

        try {
            provider.stream(
                history = _state.value.messages.dropLast(1),
                systemPrompt = context.promptStack.resolve(),
                attachments = attachments
            )
                .onCompletion { cause ->
                    if (cause == null) finalizeMessage(streamingMessageId, accumulated.toString(), toolsUsed)
                }
                .catch { error ->
                    markMessageError(streamingMessageId, error.message ?: "Unknown error")
                }
                .collect { chunk ->
                    when (chunk) {
                        is AIResponseChunk.TextDelta -> {
                            accumulated.append(chunk.text)
                            updateStreamingContent(streamingMessageId, accumulated.toString())
                        }
                        is AIResponseChunk.TextComplete -> {
                            accumulated.clear()
                            accumulated.append(chunk.text)
                            updateStreamingContent(streamingMessageId, chunk.text)
                        }
                        is AIResponseChunk.ToolCallRequest ->
                            handleToolCall(chunk.toolName, chunk.args, toolsUsed)
                        is AIResponseChunk.Error ->
                            markMessageError(streamingMessageId, chunk.message)
                        else -> Unit
                    }
                }
        } catch (e: Exception) {
            markMessageError(streamingMessageId, e.message ?: "Stream interrupted")
        }
    }

    private fun updateStreamingContent(messageId: String, content: String) {
        _state.update { state ->
            state.copy(
                streamingContent = content,
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(content = content) else msg
                }
            )
        }
    }

    private fun finalizeMessage(messageId: String, finalContent: String, toolsUsed: List<String>) {
        _state.update { state ->
            state.copy(
                isStreaming = false,
                streamingContent = "",
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(
                        content = finalContent,
                        state = MessageState.COMPLETE,
                        toolCallsUsed = toolsUsed
                    ) else msg
                }
            )
        }
    }

    private fun markMessageError(messageId: String, error: String) {
        _state.update { state ->
            state.copy(
                isStreaming = false,
                streamingContent = "",
                error = error,
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(state = MessageState.ERROR) else msg
                }
            )
        }
    }

    private suspend fun handleToolCall(
        toolName: String,
        args: JsonObject,
        toolsUsed: MutableList<String>
    ) {
        val tool = context.toolRegistry.resolve(toolName) ?: run {
            auditLogger.logFailed(toolName, args.toString(), "Tool not registered", userId)
            return
        }
        when (val check = permissionManager.check(tool, args)) {
            is PermissionCheckResult.Granted -> {
                val result = context.toolRegistry.execute(toolName, args)
                if (result is ToolResult.Success) {
                    toolsUsed.add(toolName)
                    auditLogger.logApproved(toolName, args.toString(), userId)
                } else if (result is ToolResult.Failure) {
                    auditLogger.logFailed(toolName, args.toString(), result.message, userId)
                }
            }
            is PermissionCheckResult.RequiresConfirmation -> {
                permissionManager.requestConfirmation(tool, args) {
                    val result = context.toolRegistry.execute(toolName, args)
                    if (result is ToolResult.Success) toolsUsed.add(toolName)
                    result
                }
            }
            is PermissionCheckResult.Denied ->
                auditLogger.logDenied(toolName, args.toString(), check.reason, userId)
        }
    }

    fun close() {
        activeJob?.cancel()
        scope.cancel()
    }
}

// ── Rate limiter ──────────────────────────────────────────────────────────────

private class RateLimiter(private val maxPerMinute: Int) {
    private val calls = ArrayDeque<Long>()

    fun tryAcquire(): Boolean {
        if (maxPerMinute == 0) return true
        val now = currentTimeMs()
        val windowStart = now - 60_000L
        while (calls.isNotEmpty() && calls.first() < windowStart) calls.removeFirst()
        if (calls.size >= maxPerMinute) return false
        calls.addLast(now)
        return true
    }
}

// ── Platform expect ───────────────────────────────────────────────────────────

internal expect fun currentTimeMs(): Long
internal expect fun randomId(): String