package io.github.koogcompose.ui.state

import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.PermissionManager
import io.github.koogcompose.session.AgentActivity
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.ChatSessionState
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogSessionHandle
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.session.MessageState
import io.github.koogcompose.session.isRunning
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Adapts a [KoogSessionHandle] into the [ChatSession] interface that [ChatState] requires.
 *
 * Maps [AgentActivity] to [ChatSessionState] so the UI reflects the full
 * three-layer state model without any changes to [ChatState] or the UI components.
 *
 * Activity → ChatSessionState mapping:
 * - [AgentActivity.Reasoning]       → isStreaming=true, streamingContent="" (shimmer, no text yet)
 * - [AgentActivity.Thinking]        → isStreaming=true, streamingContent=partial response
 * - [AgentActivity.Executing]       → isStreaming=true, streamingContent=tool name
 * - [AgentActivity.WaitingForInput] → isStreaming=false (agent paused, waiting for user)
 * - [AgentActivity.Blocked]         → isStreaming=false, error=fallback message
 * - [AgentActivity.Completed]       → isStreaming=false
 * - [AgentActivity.Failed]          → isStreaming=false, error=error message
 * - [AgentActivity.Idle]            → isStreaming=false
 */
internal class HandleBackedChatSession(
    private val handle: KoogSessionHandle,
    private val scope: CoroutineScope,
    override val initialContext: KoogComposeContext<*>,
    private val provider: AIProvider = initialContext.createProvider(),
) : ChatSession(
    initialContext = initialContext,
    provider       = provider,
    scope          = scope,
) {
    private fun currentTimeMs(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var streamingMessageId: String? = null

    private val _state = MutableStateFlow(ChatSessionState())
    override val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KoogEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<KoogEvent> = _events.asSharedFlow()

    override val permissionManager: PermissionManager = PermissionManager(
        auditLogger                     = AuditLogger(),
        requireConfirmationForSensitive = false,
        userId                          = null,
    )

    init {
        // ── Token streaming → update the in-flight assistant message ──────────
        scope.launch {
            handle.responseStream.collect { token ->
                val msgId = streamingMessageId ?: return@collect
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == msgId) msg.copy(content = msg.content + token)
                        else msg
                    }
                }
                syncState()
            }
        }

        // ── Activity transitions → drive ChatSessionState ─────────────────────
        scope.launch {
            handle.activity.collect { activity ->
                when (activity) {
                    is AgentActivity.Thinking, AgentActivity.Reasoning -> {
                        // Ensure a streaming message placeholder exists.
                        if (streamingMessageId == null) {
                            val id = "a-${currentTimeMs()}"
                            streamingMessageId = id
                            _messages.update { list ->
                                list + ChatMessage(
                                    id          = id,
                                    role        = MessageRole.ASSISTANT,
                                    content     = "",
                                    state       = MessageState.STREAMING,
                                    timestampMs = currentTimeMs(),
                                )
                            }
                        }
                        syncState()
                        _events.tryEmit(
                            KoogEvent.TurnStarted(
                                timestampMs     = currentTimeMs(),
                                turnId          = streamingMessageId ?: "",
                                phaseName       = null,
                                userMessageId   = "",
                                text            = "",
                                attachmentCount = 0,
                            )
                        )
                    }

                    is AgentActivity.Executing -> {
                        // Tool in flight — keep the streaming placeholder alive,
                        // update streamingContent with the tool name so the UI
                        // can show "Running: toolName" if desired.
                        syncState()
                    }

                    is AgentActivity.WaitingForInput -> {
                        // Agent paused — stop the streaming indicator.
                        syncState()
                    }

                    is AgentActivity.Completed -> {
                        val msgId = streamingMessageId
                        streamingMessageId = null
                        if (msgId != null) {
                            _messages.update { list ->
                                list.map { msg ->
                                    if (msg.id == msgId) msg.copy(state = MessageState.COMPLETE)
                                    else msg
                                }
                            }
                        }
                        syncState()
                        _events.tryEmit(
                            KoogEvent.TurnCompleted(
                                timestampMs        = currentTimeMs(),
                                turnId             = msgId ?: "",
                                phaseName          = null,
                                assistantMessageId = msgId ?: "",
                                toolNames          = emptyList(),
                            )
                        )
                    }

                    is AgentActivity.Failed -> {
                        val msgId = streamingMessageId
                        streamingMessageId = null
                        if (msgId != null) {
                            _messages.update { list ->
                                list.map { msg ->
                                    if (msg.id == msgId) msg.copy(state = MessageState.ERROR)
                                    else msg
                                }
                            }
                        }
                        syncState()
                        _events.tryEmit(
                            KoogEvent.TurnFailed(
                                timestampMs = currentTimeMs(),
                                turnId      = msgId ?: "",
                                phaseName   = null,
                                message     = activity.error.message ?: "Unknown error",
                            )
                        )
                    }

                    is AgentActivity.Blocked -> {
                        streamingMessageId = null
                        syncState()
                    }

                    AgentActivity.Idle -> {
                        syncState()
                    }
                }
            }
        }
    }

    // ── ChatSession overrides ─────────────────────────────────────────────────

    override fun send(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        _messages.update { list ->
            list + ChatMessage(
                id          = "u-${currentTimeMs()}",
                role        = MessageRole.USER,
                content     = text,
                state       = MessageState.COMPLETE,
                attachments = attachments,
                timestampMs = currentTimeMs(),
            )
        }
        syncState()
        handle.send(text)
    }

    override fun cancel() {
        streamingMessageId = null
        syncState()
    }

    override fun regenerate() { /* not supported on handle runtime */ }

    override fun clearHistory() {
        _messages.value = emptyList()
        streamingMessageId = null
        syncState()
        handle.reset()
    }

    override fun withContext(additionalContext: String): ChatSession = this

    override fun close() { /* scope lifecycle owned by rememberCoroutineScope() */ }

    override suspend fun confirmPendingToolExecution(): ToolResult =
        permissionManager.onUserConfirmed()

    override suspend fun denyPendingToolExecution(): ToolResult =
        permissionManager.onUserDenied()

    // ── State sync ────────────────────────────────────────────────────────────

    private fun syncState() {
        val activity = handle.activity.value
        val detail   = handle.activityDetail.value

        _state.value = ChatSessionState(
            messages         = _messages.value,
            isStreaming      = activity.isRunning,
            streamingContent = when (activity) {
                is AgentActivity.Thinking        -> detail
                is AgentActivity.Executing       -> detail
                is AgentActivity.Reasoning       -> ""  // shimmer only, no text
                else                             -> ""
            },
            error            = when (activity) {
                is AgentActivity.Failed  -> activity.error.message
                is AgentActivity.Blocked -> detail
                else                     -> null
            },
        )
    }
}