package io.github.koogcompose.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.CoroutineScope
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
class ChatState internal constructor(
    internal val session: ChatSession,
    private val scope: CoroutineScope
) {
    // ── Input state ───────────────────────────────────────────────────────────

    var inputText by mutableStateOf("")
        private set

    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    // ── Session state (collected in composables via collectAsState) ───────────

    val sessionStateFlow get() = session.state
    val pendingConfirmationFlow get() = session.permissionManager.pendingConfirmation


    // ── Convenience accessors (use inside @Composable with collectAsState) ────

    val context: KoogComposeContext get() = session.context

    // ── Input actions ─────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        inputText = text
    }

    fun addAttachment(attachment: Attachment) {
        attachments = attachments + attachment
    }

    fun removeAttachment(attachment: Attachment) {
        attachments = attachments - attachment
    }

    // ── Send actions ──────────────────────────────────────────────────────────

    /**
     * Send the current [inputText] and [attachments].
     * Clears both after sending.
     */
    fun send() {
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
    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
        session.send(text, attachments)
    }

    fun cancel() = session.cancel()
    fun regenerate() = session.regenerate()
    fun clearHistory() = session.clearHistory()

    // ── Permission actions ────────────────────────────────────────────────────

    fun confirmToolExecution() {
        scope.launch { session.permissionManager.onUserConfirmed() }
    }

    fun denyToolExecution() {
        scope.launch { session.permissionManager.onUserDenied() }
    }

    // ── Context ───────────────────────────────────────────────────────────────

    /**
     * Returns a new [ChatState] with additional session-level context
     * injected into the prompt stack. Conversation history is preserved.
     */
    fun withContext(additionalContext: String): ChatState = ChatState(
        session = session.withContext(additionalContext),
        scope = scope
    )

    internal fun close() = session.close()
}