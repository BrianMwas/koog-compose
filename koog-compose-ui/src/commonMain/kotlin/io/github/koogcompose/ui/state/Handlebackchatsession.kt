package io.github.koogcompose.ui.state

import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.PermissionManager
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.ChatSessionState
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogSessionHandle
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.session.MessageState
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

/**
 * Adapts a [KoogSessionHandle] into the [ChatSession] interface that [ChatState] requires.
 *
 * [ChatState] is constructed with a [ChatSession] + [CoroutineScope]. Rather than
 * changing [ChatState]'s constructor or adding a second code path through it, we
 * implement the minimal [ChatSession] surface that [ChatState] and the UI components
 * actually use:
 *
 *  - [state]              → [ChatSessionState] built from accumulated messages
 *  - [events]             → forwarded from [handle.responseStream] lifecycle observations
 *  - [permissionManager]  → stub (handle runtime manages its own tool permissions)
 *  - [send]               → optimistically appends user message, delegates to [handle.send]
 *  - [cancel]             → no-op (handle has no cancel; call [reset] to abort)
 *  - [regenerate]         → not supported on handle runtime
 *  - [clearHistory]       → clears local message list and calls [handle.reset]
 *  - [close]              → no-op (scope lifecycle is owned by the caller)
 *
 * Message accumulation strategy:
 *  1. User messages are appended immediately in [send] (optimistic).
 *  2. A streaming assistant placeholder is created and updated token-by-token
 *     via [handle.responseStream].
 *  3. When [handle.isRunning] transitions false→false after a running→true→false
 *     cycle, the streaming placeholder is finalised into a complete message.
 */
internal class HandleBackedChatSession(
    private val handle: KoogSessionHandle,
    scope: CoroutineScope,
    override val initialContext: KoogComposeContext<*>,
    provider: AIProvider = initialContext.createProvider(),
) : ChatSession(
    initialContext = initialContext,
    provider       = provider,
    scope          = scope,
) {
    // We override every property and method that [ChatState] uses, so the super
    // class constructor runs but its internal loops are never started.
    // This is safe because [ChatSession] starts its coroutines lazily on [send].

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    // The id of the assistant bubble currently being streamed into.
    // Null when no turn is in flight.
    private var streamingMessageId: String? = null

    private val _state = MutableStateFlow(ChatSessionState())
    override val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KoogEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<KoogEvent> = _events.asSharedFlow()

    // Stub permission manager — the handle runtime enforces tool permissions internally.
    // ChatState.confirmToolExecution / denyToolExecution delegate to this, so it must
    // exist, but the actual confirmation dialog lifecycle is driven by KoogEvent.ToolConfirmationRequested
    // emitted into [events] and handled by ConfirmationObserver in the UI.
    override val permissionManager: PermissionManager = PermissionManager(
        auditLogger                    = AuditLogger(),
        requireConfirmationForSensitive = false,
        userId                         = null,
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
                syncState(isStreaming = true)
            }
        }

        // ── isRunning transitions → create / finalise the assistant bubble ────
        scope.launch {
            var wasRunning = false
            handle.isRunning.collect { running ->
                when {
                    running && !wasRunning -> {
                        // Turn started: create a blank streaming placeholder.
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
                        syncState(isStreaming = true)
                        _events.tryEmit(
                            KoogEvent.TurnStarted(
                                timestampMs    = currentTimeMs(),
                                turnId         = id,
                                phaseName      = null,
                                userMessageId  = "",
                                text           = "",
                                attachmentCount = 0,
                            )
                        )
                    }
                    !running && wasRunning -> {
                        // Turn complete: finalise the streaming message.
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
                        syncState(isStreaming = false)
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
                }
                wasRunning = running
            }
        }

        // ── Error propagation ─────────────────────────────────────────────────
        scope.launch {
            handle.error.collect { error ->
                if (error != null) {
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
                    _state.update { it.copy(error = error.message, isStreaming = false) }
                    _events.tryEmit(
                        KoogEvent.TurnFailed(
                            timestampMs = currentTimeMs(),
                            turnId      = msgId ?: "",
                            phaseName   = null,
                            message     = error.message ?: "Unknown error",
                        )
                    )
                }
            }
        }
    }

    // ── ChatSession overrides ─────────────────────────────────────────────────

    override fun send(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return

        // Optimistically append the user message before the round-trip.
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
        syncState(isStreaming = false)

        handle.send(text)
    }

    override fun cancel() {
        // PhaseSession / SessionRunner have no cancellation surface exposed via
        // KoogSessionHandle. Clearing the streaming state locally is the best we can do.
        streamingMessageId = null
        syncState(isStreaming = false)
    }

    override fun regenerate() {
        // Not supported on the handle runtime — handle has no concept of regeneration.
        // Silently ignored to avoid crashing if a UI component calls it.
    }

    override fun clearHistory() {
        _messages.value = emptyList()
        streamingMessageId = null
        syncState(isStreaming = false)
        handle.reset()
    }

    override fun withContext(additionalContext: String): ChatSession = this

    override fun close() {
        // Scope lifecycle is owned by the Composable via rememberCoroutineScope().
        // We don't cancel it here.
    }

    override suspend fun confirmPendingToolExecution(): ToolResult =
        permissionManager.onUserConfirmed()

    override suspend fun denyPendingToolExecution(): ToolResult =
        permissionManager.onUserDenied()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun syncState(isStreaming: Boolean) {
        _state.value = ChatSessionState(
            messages         = _messages.value,
            isStreaming      = isStreaming,
            streamingContent = if (isStreaming) _messages.value.lastOrNull()?.content ?: "" else "",
            error            = handle.error.value?.message,
        )
    }
}