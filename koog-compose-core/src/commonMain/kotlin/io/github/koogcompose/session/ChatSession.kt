package io.github.koogcompose.session

import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.PermissionCheckResult
import io.github.koogcompose.security.PermissionManager
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
public enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

/**
 * Distinguishes between a tool call request and its result.
 */
public enum class ToolMessageKind {
    CALL,
    RESULT
}

/**
 * The current state of a [ChatMessage].
 */
public enum class MessageState {
    SENDING,
    STREAMING,
    COMPLETE,
    ERROR
}

/**
 * Represents an attachment (image, file, or audio) in the conversation.
 * Contains both data metadata for the LLM and display metadata for the UI.
 */
public sealed class Attachment {
    public abstract val uri: String
    public abstract val displayName: String

    public data class Image(
        override val uri: String,
        override val displayName: String = "Image",
    ) : Attachment()

    public data class Document(
        override val uri: String,
        override val displayName: String,
        val mimeType: String,
        val sizeBytes: Long? = null,
    ) : Attachment()

    public data class Audio(
        override val uri: String,
        override val displayName: String = "Voice message",
        val durationMs: Long? = null,
    ) : Attachment()
}

/**
 * A message within a [ChatSession].
 */
public data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val state: MessageState = MessageState.COMPLETE,
    val attachments: List<Attachment> = emptyList(),
    val toolCallsUsed: List<String> = emptyList(),
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolKind: ToolMessageKind? = null,
    val timestampMs: Long,
)

/**
 * Chunks of data received during streaming from an [AIProvider].
 */
public sealed class AIResponseChunk {
    public data class TextDelta(val text: String) : AIResponseChunk()
    public data class TextComplete(val text: String) : AIResponseChunk()
    public data class ReasoningDelta(val text: String) : AIResponseChunk()
    public data class ToolCallRequest(
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject,
    ) : AIResponseChunk()

    public object End : AIResponseChunk()
    public data class Error(val message: String) : AIResponseChunk()
}

/**
 * The observable state of a [ChatSession].
 */
public data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val isRateLimited: Boolean = false,
    val activePhaseName: String? = null,
)

/**
 * Manages a single chat conversation and the tool/phase loop around it.
 */
public open class ChatSession(
    public open val initialContext: KoogComposeContext<*>,
    private val provider: AIProvider,
    private val scope: CoroutineScope,
    private val userId: String? = null,
    private val idGenerator: () -> String = { randomId() },
) {
    private val startingContext: KoogComposeContext<*> =
        initialContext.activePhaseName
            ?.let(initialContext::withPhase)
            ?: initialContext.phaseRegistry.initialPhase
                ?.name
                ?.let(initialContext::withPhase)
            ?: initialContext

    private val _currentContext = MutableStateFlow(startingContext)
    public val currentContext: StateFlow<KoogComposeContext<*>> = _currentContext.asStateFlow()
    public val context: KoogComposeContext<*>
        get() = _currentContext.value

    private val _state = MutableStateFlow(
        ChatSessionState(activePhaseName = startingContext.activePhaseName)
    )
    public open val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _chunks = MutableSharedFlow<AIResponseChunk>(extraBufferCapacity = 256)
    public val chunks: SharedFlow<AIResponseChunk> = _chunks.asSharedFlow()

    private val _events = MutableSharedFlow<KoogEvent>(extraBufferCapacity = 128)
    public open val events: SharedFlow<KoogEvent> = _events.asSharedFlow()

    public val auditLogger: AuditLogger = AuditLogger()
    public open val permissionManager: PermissionManager = PermissionManager(
        auditLogger = auditLogger,
        requireConfirmationForSensitive = startingContext.config.requireConfirmationForSensitive,
        userId = userId,
    )

    private var activeJob: Job? = null
    private val rateLimiter = RateLimiter(startingContext.config.rateLimitPerMinute)

    public open fun send(text: String, attachments: List<Attachment> = emptyList()): Unit {
        if (text.isBlank() && attachments.isEmpty()) {
            return
        }

        cancel()
        activeJob = scope.launch {
            if (!rateLimiter.tryAcquire()) {
                _state.update { current ->
                    current.copy(isRateLimited = true)
                }
                emitEvent(
                    KoogEvent.RateLimited(
                        timestampMs = currentTimeMs(),
                        phaseName = _currentContext.value.activePhaseName,
                    )
                )
                return@launch
            }

            _state.update { current ->
                current.copy(isRateLimited = false)
            }
            processTurn(text, attachments)
        }
    }

    public open fun cancel(): Unit {
        activeJob?.cancel()
        activeJob = null
        permissionManager.clearPending()
        _state.update { current ->
            current.copy(isStreaming = false, streamingContent = "")
        }
    }

    public open fun regenerate(): Unit {
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { message -> message.role == MessageRole.USER }
        if (lastUserIndex == -1) {
            return
        }

        val lastUser = messages[lastUserIndex]
        _state.update { current ->
            current.copy(
                messages = messages.take(lastUserIndex),
                isStreaming = false,
                streamingContent = "",
                error = null,
                activePhaseName = _currentContext.value.activePhaseName,
            )
        }
        send(lastUser.content, lastUser.attachments)
    }

    public open fun clearHistory(): Unit {
        cancel()
        _state.update {
            ChatSessionState(activePhaseName = _currentContext.value.activePhaseName)
        }
    }

    public open suspend fun confirmPendingToolExecution(): ToolResult =
        permissionManager.onUserConfirmed()

    public open suspend fun denyPendingToolExecution(): ToolResult =
        permissionManager.onUserDenied()

    public open fun withContext(additionalContext: String): ChatSession =
        ChatSession(
            initialContext = _currentContext.value.withSessionContext(additionalContext),
            provider = provider,
            scope = scope,
            userId = userId,
            idGenerator = idGenerator,
        ).also { session ->
            session._state.value = _state.value
        }

    public open fun close(): Unit {
        activeJob?.cancel()
        activeJob = null
        permissionManager.clearPending()
    }

    private suspend fun processTurn(text: String, attachments: List<Attachment>): Unit {
        val userMessage = ChatMessage(
            id = idGenerator(),
            role = MessageRole.USER,
            content = text,
            state = MessageState.COMPLETE,
            attachments = attachments,
            timestampMs = currentTimeMs(),
        )
        val assistantMessageId = idGenerator()
        val assistantMessage = ChatMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            state = MessageState.STREAMING,
            timestampMs = currentTimeMs(),
        )
        val turnId = assistantMessageId

        _state.update { current ->
            current.copy(
                messages = current.messages + userMessage + assistantMessage,
                isStreaming = true,
                streamingContent = "",
                error = null,
                activePhaseName = _currentContext.value.activePhaseName,
            )
        }

        emitEvent(
            KoogEvent.TurnStarted(
                timestampMs = currentTimeMs(),
                turnId = turnId,
                phaseName = _currentContext.value.activePhaseName,
                userMessageId = userMessage.id,
                text = text,
                attachmentCount = attachments.size,
            )
        )

        val assistantContent = StringBuilder()
        val toolsUsed = mutableListOf<String>()
        var passIndex = 0

        try {
            while (true) {
                passIndex += 1
                val passBuffer = StringBuilder()
                val requestedTools = mutableListOf<AIResponseChunk.ToolCallRequest>()
                val phaseName = _currentContext.value.activePhaseName

                emitEvent(
                    KoogEvent.ProviderPassStarted(
                        timestampMs = currentTimeMs(),
                        turnId = turnId,
                        phaseName = phaseName,
                        passIndex = passIndex,
                        availableTools = _currentContext.value.resolveEffectiveTools().map(SecureTool::name),
                    )
                )

                try {
                    provider.stream(
                        context = _currentContext.value,
                        history = _state.value.messages.filterNot { message -> message.id == assistantMessageId },
                        systemPrompt = _currentContext.value.resolveEffectiveInstructions(),
                        attachments = emptyList(),
                    ).collect { chunk ->
                        _chunks.tryEmit(chunk)
                        emitEvent(
                            KoogEvent.ProviderChunkReceived(
                                timestampMs = currentTimeMs(),
                                turnId = turnId,
                                phaseName = _currentContext.value.activePhaseName,
                                passIndex = passIndex,
                                chunk = chunk,
                            )
                        )

                        when (chunk) {
                            is AIResponseChunk.TextDelta -> {
                                passBuffer.append(chunk.text)
                                updateStreamingContent(
                                    messageId = assistantMessageId,
                                    content = assistantContent.toString() + passBuffer,
                                )
                            }

                            is AIResponseChunk.TextComplete -> {
                                passBuffer.clear()
                                passBuffer.append(chunk.text)
                                updateStreamingContent(
                                    messageId = assistantMessageId,
                                    content = assistantContent.toString() + passBuffer,
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

                    emitEvent(
                        KoogEvent.ProviderPassCompleted(
                            timestampMs = currentTimeMs(),
                            turnId = turnId,
                            phaseName = _currentContext.value.activePhaseName,
                            passIndex = passIndex,
                        )
                    )
                } catch (error: Exception) {
                    emitEvent(
                        KoogEvent.ProviderPassFailed(
                            timestampMs = currentTimeMs(),
                            turnId = turnId,
                            phaseName = _currentContext.value.activePhaseName,
                            passIndex = passIndex,
                            message = error.message ?: "Provider stream failed",
                        )
                    )
                    throw error
                }

                assistantContent.append(passBuffer)

                if (requestedTools.isEmpty()) {
                    break
                }

                for (request in requestedTools) {
                    val outcome = executeToolCall(turnId, request)
                    if (outcome.result is ToolResult.Success) {
                        toolsUsed += request.toolName
                    }
                    if (outcome.phaseChanged) {
                        break
                    }
                }
            }

            finalizeMessage(
                messageId = assistantMessageId,
                finalContent = assistantContent.toString(),
                toolsUsed = toolsUsed,
            )
            emitEvent(
                KoogEvent.TurnCompleted(
                    timestampMs = currentTimeMs(),
                    turnId = turnId,
                    phaseName = _currentContext.value.activePhaseName,
                    assistantMessageId = assistantMessageId,
                    toolNames = toolsUsed.toList(),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            markMessageError(
                messageId = assistantMessageId,
                error = error.message ?: "Stream interrupted",
            )
            emitEvent(
                KoogEvent.TurnFailed(
                    timestampMs = currentTimeMs(),
                    turnId = turnId,
                    phaseName = _currentContext.value.activePhaseName,
                    message = error.message ?: "Stream interrupted",
                )
            )
        }
    }

    private suspend fun executeToolCall(
        turnId: String,
        request: AIResponseChunk.ToolCallRequest,
    ): ToolExecutionOutcome {
        emitEvent(
            KoogEvent.ToolCallRequested(
                timestampMs = currentTimeMs(),
                turnId = turnId,
                phaseName = _currentContext.value.activePhaseName,
                toolCallId = request.toolCallId,
                toolName = request.toolName,
                args = request.args,
            )
        )

        appendToolMessage(
            ChatMessage(
                id = idGenerator(),
                role = MessageRole.TOOL,
                content = request.args.toString(),
                toolName = request.toolName,
                toolCallId = request.toolCallId,
                toolKind = ToolMessageKind.CALL,
                timestampMs = currentTimeMs(),
            )
        )

        if (request.toolName.startsWith("transition_to_")) {
            return handlePhaseTransition(turnId, request)
        }

        val tool = _currentContext.value.resolveEffectiveTools().find { candidate ->
            candidate.name == request.toolName
        }

        val result = when {
            tool == null -> {
                auditLogger.logFailed(
                    request.toolName,
                    request.args.toString(),
                    "Tool not registered",
                    userId,
                )
                ToolResult.Failure("Tool not registered: ${request.toolName}")
            }

            else -> when (val check = permissionManager.check(tool, request.args)) {
                PermissionCheckResult.Granted -> executeToolAndAudit(tool, request.args)

                is PermissionCheckResult.RequiresConfirmation -> {
                    emitEvent(
                        KoogEvent.ToolConfirmationRequested(
                            timestampMs = currentTimeMs(),
                            turnId = turnId,
                            phaseName = _currentContext.value.activePhaseName,
                            toolCallId = request.toolCallId,
                            toolName = request.toolName,
                            permissionLevel = check.permissionLevel,
                            confirmationMessage = check.confirmationMessage,
                        )
                    )
                    permissionManager.requestConfirmation(tool, request.args) {
                        tool.execute(request.args)
                    }
                }

                is PermissionCheckResult.Denied -> {
                    auditLogger.logDenied(
                        request.toolName,
                        request.args.toString(),
                        check.reason,
                        userId,
                    )
                    ToolResult.Denied(check.reason)
                }
            }
        }

        appendToolResultMessage(request, result)
        emitEvent(
            KoogEvent.ToolExecutionCompleted(
                timestampMs = currentTimeMs(),
                turnId = turnId,
                phaseName = _currentContext.value.activePhaseName,
                toolCallId = request.toolCallId,
                toolName = request.toolName,
                result = result,
            )
        )
        return ToolExecutionOutcome(result = result, phaseChanged = false)
    }

    private suspend fun handlePhaseTransition(
        turnId: String,
        request: AIResponseChunk.ToolCallRequest,
    ): ToolExecutionOutcome {
        val targetPhase = request.toolName.removePrefix("transition_to_")
        val previousPhase = _currentContext.value.activePhaseName
        val resolvedPhase = _currentContext.value.phaseRegistry.resolve(targetPhase)
            ?: return ToolExecutionOutcome(
                result = ToolResult.Failure("Unknown phase: $targetPhase"),
                phaseChanged = false,
            ).also { outcome ->
                appendToolResultMessage(request, outcome.result)
                emitEvent(
                    KoogEvent.ToolExecutionCompleted(
                        timestampMs = currentTimeMs(),
                        turnId = turnId,
                        phaseName = previousPhase,
                        toolCallId = request.toolCallId,
                        toolName = request.toolName,
                        result = outcome.result,
                        isPhaseTransition = true,
                    )
                )
            }

        _currentContext.update { current ->
            current.withPhase(resolvedPhase.name)
        }
        _state.update { current ->
            current.copy(activePhaseName = resolvedPhase.name)
        }

        val result = ToolResult.Success("Transitioned to phase '${resolvedPhase.name}'")
        auditLogger.logApproved(request.toolName, request.args.toString(), userId)
        appendToolResultMessage(request, result)
        emitEvent(
            KoogEvent.PhaseTransitioned(
                timestampMs = currentTimeMs(),
                turnId = turnId,
                phaseName = resolvedPhase.name,
                toolCallId = request.toolCallId,
                fromPhaseName = previousPhase,
                toPhaseName = resolvedPhase.name,
            )
        )
        emitEvent(
            KoogEvent.ToolExecutionCompleted(
                timestampMs = currentTimeMs(),
                turnId = turnId,
                phaseName = resolvedPhase.name,
                toolCallId = request.toolCallId,
                toolName = request.toolName,
                result = result,
                isPhaseTransition = true,
            )
        )
        return ToolExecutionOutcome(result = result, phaseChanged = true)
    }

    private suspend fun executeToolAndAudit(tool: SecureTool, args: JsonObject): ToolResult {
        val result = tool.execute(args)
        when (result) {
            is ToolResult.Success -> auditLogger.logApproved(tool.name, args.toString(), userId)
            is ToolResult.Failure -> auditLogger.logFailed(tool.name, args.toString(), result.message, userId)
            is ToolResult.Denied -> auditLogger.logDenied(tool.name, args.toString(), result.reason, userId)
            is ToolResult.Structured<*> -> auditLogger.logApproved(tool.name, args.toString(), userId)
        }
        return result
    }

    private fun appendToolMessage(message: ChatMessage): Unit {
        _state.update { current ->
            current.copy(messages = current.messages + message)
        }
    }

    private fun appendToolResultMessage(
        request: AIResponseChunk.ToolCallRequest,
        result: ToolResult,
    ): Unit {
        appendToolMessage(
            ChatMessage(
                id = idGenerator(),
                role = MessageRole.TOOL,
                content = toolResultPayload(result),
                toolName = request.toolName,
                toolCallId = request.toolCallId,
                toolKind = ToolMessageKind.RESULT,
                timestampMs = currentTimeMs(),
            )
        )
    }

    private fun updateStreamingContent(messageId: String, content: String): Unit {
        _state.update { current ->
            current.copy(
                streamingContent = content,
                messages = current.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(content = content)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun finalizeMessage(
        messageId: String,
        finalContent: String,
        toolsUsed: List<String>,
    ): Unit {
        _state.update { current ->
            current.copy(
                isStreaming = false,
                streamingContent = "",
                messages = current.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(
                            content = finalContent,
                            state = MessageState.COMPLETE,
                            toolCallsUsed = toolsUsed,
                        )
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun markMessageError(messageId: String, error: String): Unit {
        _state.update { current ->
            current.copy(
                isStreaming = false,
                streamingContent = "",
                error = error,
                messages = current.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(state = MessageState.ERROR)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private suspend fun emitEvent(event: KoogEvent): Unit {
        _events.emit(event)
        _currentContext.value.eventHandlers.dispatch(event)
    }
}

private class RateLimiter(private val maxPerMinute: Int) {
    private val calls = ArrayDeque<Long>()

    fun tryAcquire(): Boolean {
        if (maxPerMinute == 0) {
            return true
        }

        val now = currentTimeMs()
        val windowStart = now - 60_000L
        while (calls.isNotEmpty() && calls.first() < windowStart) {
            calls.removeFirst()
        }
        if (calls.size >= maxPerMinute) {
            return false
        }
        calls.addLast(now)
        return true
    }
}

private class ProviderStreamException(message: String) : IllegalStateException(message)

private data class ToolExecutionOutcome(
    val result: ToolResult,
    val phaseChanged: Boolean = false,
)

private val toolPayloadJson = Json { encodeDefaults = true }

private fun toolResultPayload(result: ToolResult): String = when (result) {
    is ToolResult.Success -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "success")
            put("output", result.output)
        },
    )

    is ToolResult.Denied -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "denied")
            put("reason", result.reason)
        },
    )

    is ToolResult.Failure -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "error")
            put("message", result.message)
        },
    )

    is ToolResult.Structured<*> -> toolPayloadJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "structured")
            put("data", result.toJson())
        },
    )
}

internal expect fun currentTimeMs(): Long
internal expect fun randomId(): String
