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
 * ## State model
 * The handle exposes a three-layer state model inspired by Scion:
 *
 *  1. **Lifecycle** — [isRunning]: Boolean. Derived from [activity]. True while
 *     the agent is actively processing. False when idle or in a sticky terminal state.
 *
 *  2. **Activity** — [activity]: [AgentActivity]. What the agent is doing:
 *     Idle, Reasoning, Thinking, Executing, WaitingForInput, Blocked, Completed, Failed.
 *     Sticky states (Blocked, Completed, Failed) persist until the next [send].
 *
 *  3. **Detail** — [activityDetail]: String. Freeform context for the current activity.
 *     Contents vary by activity:
 *     - Reasoning    → accumulated reasoning text
 *     - Thinking     → partial response text
 *     - Executing    → tool name
 *     - WaitingForInput → confirmation message
 *     - Blocked      → fallback message
 *     - Completed    → final response
 *     - Failed       → error message
 */
public interface KoogSessionHandle {

    /** Stable conversation identifier. */
    public val sessionId: String

    /**
     * Current cognitive activity of the agent.
     * Sticky states ([AgentActivity.Blocked], [AgentActivity.Completed],
     * [AgentActivity.Failed]) persist until the next [send].
     */
    public val activity: StateFlow<AgentActivity>

    /**
     * Freeform detail string for the current [activity].
     * See [KoogSessionHandle] kdoc for contents by activity type.
     */
    public val activityDetail: StateFlow<String>

    /**
     * True while the agent is actively processing a turn.
     * Derived from [activity] — convenience for existing code that checks isRunning.
     */
    public val isRunning: StateFlow<Boolean>

    /**
     * Non-null when the last turn failed after all retry attempts.
     * Prefer observing [activity] as [AgentActivity.Failed] instead — this is
     * kept for backward compatibility.
     */
    public val error: StateFlow<Throwable?>

    /**
     * Token-by-token stream of the agent's current response.
     * During [AgentActivity.Reasoning], emits reasoning tokens.
     * During [AgentActivity.Thinking], emits visible response tokens.
     * Emits nothing between turns.
     */
    public val responseStream: Flow<String>

    /**
     * Sends a user message and starts a new agent turn.
     * Clears any sticky activity state ([Blocked], [Completed], [Failed])
     * before starting the new turn.
     * Returns immediately — observe [activity] and [responseStream] for progress.
     */
    public fun send(userMessage: String)

    /**
     * Clears persisted session state and resets the runtime to its initial
     * configuration. The next [send] starts a fresh conversation.
     */
    public fun reset()

    /**
     * Tool call frequency for the current session.
     *
     * Maps each tool name to the number of times it has been called
     * in this session. Useful for analytics, usage quotas, and
     * detecting tool call loops.
     *
     * Updated after each [KoogEvent.ToolExecutionCompleted].
     */
    public val toolCallCounts: StateFlow<Map<String, Int>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()) // no-op default
}