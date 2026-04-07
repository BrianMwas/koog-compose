package io.github.koogcompose.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified runtime interface for both single-agent ([PhaseSession]) and
 * multi-agent ([SessionRunner]) flows.
 *
 * `rememberChatState` accepts any [KoogSessionHandle] — the Compose layer
 * never needs to know whether it is talking to one agent or many.
 *
 * ```kotlin
 * // Single-agent
 * val handle: KoogSessionHandle = PhaseSession(context, executor, "session-id", scope = viewModelScope)
 *
 * // Multi-agent
 * val handle: KoogSessionHandle = SessionRunner(koogSession, executor, "session-id", scope = viewModelScope)
 *
 * // Both work identically in Compose
 * val chatState = rememberChatState(handle)
 * ```
 */
public interface KoogSessionHandle {

    /** Stable conversation identifier. */
    public val sessionId: String

    /** True while the agent is processing a turn. */
    public val isRunning: StateFlow<Boolean>

    /** Non-null when the last turn failed after all retry attempts. Clears on the next [send]. */
    public val error: StateFlow<Throwable?>

    /**
     * Token-by-token stream of the agent's current response.
     * Emits nothing between turns. Collect in a streaming UI component.
     */
    public val responseStream: Flow<String>

    /**
     * Sends a user message and starts a new agent turn.
     * Returns immediately — observe [isRunning] and [responseStream] for progress.
     */
    public fun send(userMessage: String)

    /**
     * Clears persisted session state and resets the runtime to its initial configuration.
     * The next [send] starts a fresh conversation.
     */
    public fun reset()
}