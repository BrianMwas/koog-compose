package io.github.koogcompose.session

/**
 * The cognitive activity state of a running agent — the middle layer of the
 * three-dimensional state model inspired by Scion's Agent State Model.
 *
 * Three dimensions:
 *  1. **Lifecycle** — whether the agent process is alive. Derived via [isRunning].
 *  2. **Activity** — this sealed class. What the agent is doing right now.
 *  3. **Detail** — [KoogSessionHandle.activityDetail]. Freeform context:
 *     tool name, reasoning excerpt, error message, fallback message.
 *
 * ## Sticky states
 * [Blocked], [Completed], and [Failed] persist until the next
 * [KoogSessionHandle.send] call. This matches Scion's philosophy: terminal
 * states stay visible to the UI until the user explicitly continues.
 * They are cleared at the top of send(), not in reset() — so a completed
 * agent is resumable without wiping session history.
 *
 * ## Reasoning
 * [Reasoning] is separate from [Thinking] because the UI treatment differs:
 * - [Reasoning] → show a shimmer or collapsed "thinking..." block.
 *   activityDetail carries the accumulated reasoning text.
 * - [Thinking]  → stream tokens into the message bubble.
 *   activityDetail carries the partial response.
 *
 * Models that don't emit reasoning tokens skip [Reasoning] entirely:
 * Idle → Thinking.
 */
public sealed class AgentActivity {

    /** No turn in flight. Initial state and state after sticky states clear. */
    public object Idle : AgentActivity()

    /**
     * The model is emitting reasoning tokens before its visible response.
     * Reached only on models with extended thinking support (o1, Gemini Thinking,
     * Claude extended thinking). Transitions to [Thinking] when reasoning ends.
     * activityDetail carries the accumulated reasoning text.
     */
    public object Reasoning : AgentActivity()

    /**
     * The LLM is generating visible response tokens.
     * activityDetail carries the partial response text so far.
     */
    public object Thinking : AgentActivity()

    /**
     * A tool call is in flight.
     * activityDetail carries the tool name.
     */
    public data class Executing(val toolName: String) : AgentActivity()

    /**
     * The agent is blocked waiting for the user to confirm or deny a tool call.
     * Sticky — persists until the user confirms/denies via the confirmation handler.
     * activityDetail carries the confirmation message shown to the user.
     */
    public object WaitingForInput : AgentActivity()

    /**
     * Stuck detection fired — same phase+input pair repeated past threshold.
     * Sticky until the next send().
     * activityDetail carries the fallback message.
     */
    public object Blocked : AgentActivity()

    /**
     * The turn completed cleanly.
     * Sticky until the next send().
     * activityDetail carries the final assembled response.
     */
    public data class Completed(val response: String) : AgentActivity()

    /**
     * All retry attempts exhausted.
     * Sticky until the next send().
     * activityDetail carries the error message.
     */
    public data class Failed(val error: Throwable) : AgentActivity()
}

/** True while the agent is actively processing — not idle, not in a sticky terminal state. */
public val AgentActivity.isRunning: Boolean
    get() = this is AgentActivity.Thinking
            || this is AgentActivity.Reasoning
            || this is AgentActivity.Executing
            || this is AgentActivity.WaitingForInput

/** True for sticky states that persist until the next send(). */
public val AgentActivity.isSticky: Boolean
    get() = this is AgentActivity.Blocked
            || this is AgentActivity.Completed
            || this is AgentActivity.Failed