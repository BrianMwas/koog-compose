package io.github.koogcompose.session

import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.PermissionCheckResult
import io.github.koogcompose.security.PermissionManager
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Represents the role of a message in the conversation.
 */
  enum class MessageRole { 
    /** Message from the user. */
    USER, 
    /** Message from the assistant (AI). */
    ASSISTANT, 
    /** System instructions or context. */
    SYSTEM, 
    /** A message representing a tool call or its result. */
    TOOL 
}

/**
 * Distinguishes between a tool call request and its result.
 */
  enum class ToolMessageKind { 
    /** The AI is requesting to call a tool. */
    CALL, 
    /** The result of a tool execution. */
    RESULT 
}

/**
 * The current state of a [ChatMessage].
 */
  enum class MessageState { 
    /** Message is currently being sent. */
    SENDING, 
    /** Content is being streamed from the provider. */
    STREAMING, 
    /** Message is fully received and processed. */
    COMPLETE, 
    /** An error occurred while processing the message. */
    ERROR 
}

/**
 * A message within a [ChatSession].
 * 
 * @property id Unique identifier for the message.
 * @property role The [MessageRole] of the sender.
 * @property content The text content of the message.
 * @property state The current [MessageState].
 * @property attachments Any attachments associated with the message.
 * @property toolCallsUsed Names of tools called during the generation of this message.
 * @property toolName The name of the tool (if [role] is [MessageRole.TOOL]).
 * @property toolCallId The ID of the tool call (if [role] is [MessageRole.TOOL]).
 * @property toolKind The [ToolMessageKind] (if [role] is [MessageRole.TOOL]).
 * @property timestampMs Epoch timestamp in milliseconds.
 */
  data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val state: MessageState = MessageState.COMPLETE,
    val attachments: List<Attachment> = emptyList(),
    val toolCallsUsed: List<String> = emptyList(),
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolKind: ToolMessageKind? = null,
    val timestampMs: Long
)

/*
 * UI-specific models for attachments, used within the Compose layer.
 * These provide extra metadata like display names and sizes for the UI.
 */
sealed class ChatAttachment {
    abstract val uri: String
    abstract val displayName: String

    data class Image(
        override val uri: String,
        override val displayName: String = "Image",
    ) : ChatAttachment()

    data class File(
        override val uri: String,
        override val displayName: String,
        val mimeType: String,
        val sizeBytes: Long? = null,
    ) : ChatAttachment()

    data class Audio(
        override val uri: String,
        override val displayName: String = "Voice message",
        val durationMs: Long? = null,
    ) : ChatAttachment()
}

/** Maps UI [ChatAttachment] to core [CoreAttachment]. */
fun ChatAttachment.toCore(): CoreAttachment = when (this) {
    is ChatAttachment.Image -> CoreAttachment.Image(uri)
    is ChatAttachment.File -> CoreAttachment.Document(uri, mimeType)
    is ChatAttachment.Audio -> CoreAttachment.Audio(uri)
}

/** Maps core [CoreAttachment] to UI [ChatAttachment]. */
fun CoreAttachment.toUI(): ChatAttachment = when (this) {
    is CoreAttachment.Image -> ChatAttachment.Image(uri)
    is CoreAttachment.Document -> ChatAttachment.File(uri, uri.substringAfterLast('/'), mimeType)
    is CoreAttachment.Audio -> ChatAttachment.Audio(uri)
}


/**
 * Chunks of data received during streaming from an [AIProvider].
 */
  sealed class AIResponseChunk {
    /** A fragment of generated text. */
      data class TextDelta(val text: String) : AIResponseChunk()
    /** Final complete text. */
      data class TextComplete(val text: String) : AIResponseChunk()
    /** A fragment of internal reasoning/thought. */
      data class ReasoningDelta(val text: String) : AIResponseChunk()
    /** A request to call a tool. */
      data class ToolCallRequest(
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject
    ) : AIResponseChunk()

    /** Signal that the stream has ended. */
      object End : AIResponseChunk()
    /** An error occurred during streaming. */
      data class Error(val message: String) : AIResponseChunk()
}

/**
 * The state of a [ChatSession].
 */
  data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val isRateLimited: Boolean = false,
    val activePhaseName: String? = null,
)



/**
 * Manages a single chat conversation.
 * 
 * This class orchestrates the interaction between the user, the [AIProvider], 
 * the [ToolRegistry], and the [PermissionManager].
 */
  class ChatSession internal constructor(
      val initialContext: KoogComposeContext,
    private val provider: AIProvider,
    private val scope: CoroutineScope,
    private val userId: String? = null,
    private val idGenerator: () -> String = { randomId() }
) {
    private val _currentContext = MutableStateFlow(initialContext)
    /** The active context for this session, which may change (e.g., during phase transitions). */
      val currentContext: StateFlow<KoogComposeContext> = _currentContext.asStateFlow()

    private val _state = MutableStateFlow(
        ChatSessionState(activePhaseName = initialContext.activePhaseName)
    )
    /** The current UI state of the chat. */
      val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _chunks = MutableSharedFlow<AIResponseChunk>(extraBufferCapacity = 256)
    /** Emits raw response chunks as they arrive from the provider. */
      val chunks: SharedFlow<AIResponseChunk> = _chunks.asSharedFlow()

    /** Logger for auditing tool executions. */
      val auditLogger: AuditLogger = AuditLogger()
    /** Manager for tool execution permissions. */
      val permissionManager: PermissionManager = PermissionManager(
        auditLogger = auditLogger,
        requireConfirmationForSensitive = initialContext.config.requireConfirmationForSensitive,
        userId = userId
    )

    private var activeJob: Job? = null
    private val rateLimiter = RateLimiter(initialContext.config.rateLimitPerMinute)

    /** Sends a message to the AI. */
      fun send(text: String, attachments: List<Attachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        cancel()
        activeJob = scope.launch {
            if (!rateLimiter.tryAcquire()) {
                _state.update { it.copy(isRateLimited = true) }
                return@launch
            }
            _state.update { it.copy(isRateLimited = false) }
            processTurn(text, attachments)
        }
    }

    /** Cancels the current AI generation or tool execution. */
      fun cancel() {
        activeJob?.cancel()
        activeJob = null
        permissionManager.clearPending()
        _state.update { it.copy(isStreaming = false, streamingContent = "") }
    }

    /** Regenerates the last assistant message. */
      fun regenerate() {
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex == -1) return

        val lastUser = messages[lastUserIndex]
        _state.update {
            it.copy(
                messages = messages.take(lastUserIndex),
                isStreaming = false,
                streamingContent = "",
                error = null,
                activePhaseName = _currentContext.value.activePhaseName
            )
        }
        send(lastUser.content, lastUser.attachments)
    }

    /** Clears the conversation history. */
      fun clearHistory() {
        cancel()
        _state.update { ChatSessionState(activePhaseName = _currentContext.value.activePhaseName) }
    }

    /** Confirms a pending tool execution. */
      suspend fun confirmPendingToolExecution(): ToolResult =
        permissionManager.onUserConfirmed()

    /** Denies a pending tool execution. */
      suspend fun denyPendingToolExecution(): ToolResult =
        permissionManager.onUserDenied()

    /** Returns a new session with additional context. */
      fun withContext(additionalContext: String): ChatSession {
        return ChatSession(
            initialContext = _currentContext.value.withSessionContext(additionalContext),
            provider = provider,
            scope = scope,
            userId = userId,
            idGenerator = idGenerator
        ).also { it._state.value = _state.value }
    }

    private suspend fun processTurn(text: String, attachments: List<Attachment>) {
        val userMessage = ChatMessage(
            id = idGenerator(),
            role = MessageRole.USER,
            content = text,
            state = MessageState.COMPLETE,
            attachments = attachments,
            timestampMs = currentTimeMs()
        )
        val assistantMessageId = idGenerator()
        val assistantMessage = ChatMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            state = MessageState.STREAMING,
            timestampMs = currentTimeMs()
        )

        _state.update {
            it.copy(
                messages = it.messages + userMessage + assistantMessage,
                isStreaming = true,
                streamingContent = "",
                error = null
            )
        }

        val assistantContent = StringBuilder()
        val toolsUsed = mutableListOf<String>()

        try {
            while (true) {
                val passBuffer = StringBuilder()
                val requestedTools = mutableListOf<AIResponseChunk.ToolCallRequest>()

                provider.stream(
                    context = _currentContext.value,
                    history = _state.value.messages.filterNot { it.id == assistantMessageId },
                    systemPrompt = _currentContext.value.resolveEffectiveInstructions(),
                    attachments = emptyList()
                ).collect { chunk ->
                    _chunks.tryEmit(chunk)
                    when (chunk) {
                        is AIResponseChunk.TextDelta -> {
                            passBuffer.append(chunk.text)
                            updateStreamingContent(
                                assistantMessageId,
                                assistantContent.toString() + passBuffer
                            )
                        }

                        is AIResponseChunk.TextComplete -> {
                            passBuffer.clear()
                            passBuffer.append(chunk.text)
                            updateStreamingContent(
                                assistantMessageId,
                                assistantContent.toString() + passBuffer
                            )
                        }

                        is AIResponseChunk.ToolCallRequest -> {
                            requestedTools += chunk
                        }

                        is AIResponseChunk.Error -> {
                            throw ProviderStreamException(chunk.message)
                        }

                        is AIResponseChunk.ReasoningDelta,
                        AIResponseChunk.End -> Unit
                    }
                }

                assistantContent.append(passBuffer)

                if (requestedTools.isEmpty()) break

                var phaseChanged = false
                for (request in requestedTools) {
                    val outcome = executeToolCall(request)
                    if (outcome.result is ToolResult.Success) {
                        toolsUsed += request.toolName
                    }
                    if (outcome.phaseChanged) {
                        phaseChanged = true
                        break
                    }
                }
                
                if (phaseChanged) continue else break
            }

            finalizeMessage(assistantMessageId, assistantContent.toString(), toolsUsed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            markMessageError(assistantMessageId, error.message ?: "Stream interrupted")
        }
    }

    private suspend fun executeToolCall(request: AIResponseChunk.ToolCallRequest): ToolExecutionOutcome {
        // Intercept phase transitions
        if (request.toolName.startsWith("transition_to_")) {
            val targetPhase = request.toolName.removePrefix("transition_to_")
            if (_currentContext.value.phaseRegistry.resolve(targetPhase) != null) {
                _currentContext.update { it.withPhase(targetPhase) }
                _state.update { it.copy(activePhaseName = targetPhase) }
                return ToolExecutionOutcome(ToolResult.Success("Transitioned to phase: $targetPhase"), true)
            }
        }

        appendToolMessage(
            ChatMessage(
                id = idGenerator(),
                role = MessageRole.TOOL,
                content = request.args.toString(),
                toolName = request.toolName,
                toolCallId = request.toolCallId,
                toolKind = ToolMessageKind.CALL,
                timestampMs = currentTimeMs()
            )
        )

        // Merge effective tools (Phase-specific + Global)
        val tool = _currentContext.value.resolveEffectiveTools().find { it.name == request.toolName }
        
        val result = when {
            tool == null -> {
                auditLogger.logFailed(
                    request.toolName,
                    request.args.toString(),
                    "Tool not registered",
                    userId
                )
                ToolResult.Failure("Tool not registered: ${request.toolName}")
            }

            else -> when (val check = permissionManager.check(tool, request.args)) {
                PermissionCheckResult.Granted ->
                    executeToolAndAudit(tool, request.args)

                is PermissionCheckResult.RequiresConfirmation ->
                    permissionManager.requestConfirmation(tool, request.args) {
                        tool.execute(request.args)
                    }

                is PermissionCheckResult.Denied -> {
                    auditLogger.logDenied(
                        request.toolName,
                        request.args.toString(),
                        check.reason,
                        userId
                    )
                    ToolResult.Denied(check.reason)
                }
            }
        }

        appendToolMessage(
            ChatMessage(
                id = idGenerator(),
                role = MessageRole.TOOL,
                content = toolResultPayload(result),
                toolName = request.toolName,
                toolCallId = request.toolCallId,
                toolKind = ToolMessageKind.RESULT,
                timestampMs = currentTimeMs()
            )
        )

        return ToolExecutionOutcome(result, false)
    }

    private suspend fun executeToolAndAudit(tool: SecureTool, args: JsonObject): ToolResult {
        val result = tool.execute(args)
        when (result) {
            is ToolResult.Success -> auditLogger.logApproved(tool.name, args.toString(), userId)
            is ToolResult.Failure -> auditLogger.logFailed(tool.name, args.toString(), result.message, userId)
            is ToolResult.Denied -> auditLogger.logDenied(tool.name, args.toString(), result.reason, userId)
        }
        return result
    }

    private fun appendToolMessage(message: ChatMessage) {
        _state.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    private fun updateStreamingContent(messageId: String, content: String) {
        _state.update { state ->
            state.copy(
                streamingContent = content,
                messages = state.messages.map { message ->
                    if (message.id == messageId) message.copy(content = content) else message
                }
            )
        }
    }

    private fun finalizeMessage(messageId: String, finalContent: String, toolsUsed: List<String>) {
        _state.update { state ->
            state.copy(
                isStreaming = false,
                streamingContent = "",
                messages = state.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(
                            content = finalContent,
                            state = MessageState.COMPLETE,
                            toolCallsUsed = toolsUsed
                        )
                    } else {
                        message
                    }
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
                messages = state.messages.map { message ->
                    if (message.id == messageId) message.copy(state = MessageState.ERROR) else message
                }
            )
        }
    }

    /** Closes the session and releases resources. */
      fun close() {
        activeJob?.cancel()
        activeJob = null
        permissionManager.clearPending()
    }
}

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

private class ProviderStreamException(message: String) : IllegalStateException(message)

private data class ToolExecutionOutcome(
    val result: ToolResult,
    val phaseChanged: Boolean = false
)

private val toolPayloadJson = Json { encodeDefaults = true }

private fun toolResultPayload(result: ToolResult): String = when (result) {
    is ToolResult.Success -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "success")
            put("output", result.output)
        }
    )

    is ToolResult.Denied -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "denied")
            put("reason", result.reason)
        }
    )

    is ToolResult.Failure -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "error")
            put("message", result.message)
        }
    )
}

internal expect fun currentTimeMs(): Long
internal expect fun randomId(): String
