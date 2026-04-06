package io.github.koogcompose.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.security.PendingConfirmation
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.ChatSessionState
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


/**
 * Stable state holder exposed to Compose UI.
 *
 * Wraps [ChatSession] and exposes everything the UI needs.
 * Input state (text, attachments) is owned here as mutableStateOf
 * since it only matters to the input bar.
 * Everything from the session (messages, streaming, confirmations)
 * is collected from StateFlow.
 */
@Stable
public class ChatState internal constructor(
    internal val session: ChatSession,
    private val scope: CoroutineScope
) {
    // ── Input state ───────────────────────────────────────────────────────────

    public var inputText: String by mutableStateOf("")
        private set

    public var attachments: List<Attachment> by mutableStateOf(emptyList())
        private set

    // ── Session state (collected in composables via collectAsState) ───────────

    public val sessionStateFlow: StateFlow<ChatSessionState>
        get() = session.state
    public val eventFlow: SharedFlow<KoogEvent>
        get() = session.events
    public val pendingConfirmationFlow: StateFlow<PendingConfirmation?>
        get() = session.permissionManager.pendingConfirmation


    // ── Convenience accessors (use inside @Composable with collectAsState) ────

    public val context: KoogComposeContext<*>
        get() = session.context

    // ── Input actions ─────────────────────────────────────────────────────────

    public fun onInputChanged(text: String): Unit {
        inputText = text
    }

    public fun addAttachment(attachment: Attachment): Unit {
        attachments = attachments + attachment
    }

    public fun removeAttachment(attachment: Attachment): Unit {
        attachments = attachments - attachment
    }

    // ── Send actions ──────────────────────────────────────────────────────────

    /**
     * Send the current [inputText] and [attachments].
     * Clears both after sending.
     */
    public fun send(): Unit {
        val text = inputText.trim()
        val current = attachments
        if (text.isBlank() && current.isEmpty()) return
        inputText = ""
        attachments = emptyList()
        session.send(text, current)
    }

    /**
     * Send a message programmatically — used by quick-reply chips,
     * tool response cards, proactive agents, etc.
     */
    public fun sendMessage(text: String, attachments: List<Attachment> = emptyList()): Unit {
        session.send(text, attachments)
    }

    public fun cancel(): Unit = session.cancel()
    public fun regenerate(): Unit = session.regenerate()
    public fun clearHistory(): Unit = session.clearHistory()

    // ── Permission actions ────────────────────────────────────────────────────

    public fun confirmToolExecution(): Unit {
        scope.launch { session.confirmPendingToolExecution() }
    }

    public fun denyToolExecution(): Unit {
        scope.launch { session.denyPendingToolExecution() }
    }

    // ── Context ───────────────────────────────────────────────────────────────

    /**
     * Returns a new [ChatState] with additional session-level context
     * injected into the prompt stack. Conversation history is preserved.
     */
    public fun withContext(additionalContext: String): ChatState = ChatState(
        session = session.withContext(additionalContext),
        scope = scope
    )

    internal fun close() = session.close()
}
