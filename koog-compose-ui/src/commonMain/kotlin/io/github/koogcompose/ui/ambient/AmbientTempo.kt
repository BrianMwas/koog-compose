package io.github.koogcompose.ui.ambient

import io.github.koogcompose.session.AgentActivity

/**
 * Semantic colour role for the ambient gradient.
 *
 * Deliberately colour-free: this file stays free of Compose types so the mapping from
 * agent state to motion can be unit-tested without a UI toolkit. [AmbientGradient]
 * resolves each role against the active `MaterialTheme.colorScheme`.
 */
public enum class AmbientRole {
    /** Nothing in flight — the gradient is not drawn. */
    Dormant,

    /** Extended-thinking tokens are arriving, before any visible response. */
    Reasoning,

    /** Visible response tokens are streaming. */
    Speaking,

    /** A tool call is running. */
    Working,

    /** Blocked on the user confirming or denying a tool call. */
    Awaiting,

    /** Stuck detection fired. */
    Stalled,

    /** The turn finished cleanly. */
    Settled,

    /** The turn failed. */
    Error,
}

/**
 * Motion parameters for the ambient gradient, derived purely from [AgentActivity].
 *
 * @param role          which colour role to resolve against the theme.
 * @param cycleMillis   duration of one full sweep across the gradient. `0` means "do
 *                      not animate" — used for terminal states that should sit still.
 * @param intensity     `0f..1f` overall visibility. `0f` means the gradient is skipped
 *                      entirely rather than drawn transparent, so an idle agent costs
 *                      no frames.
 * @param pulse         whether a slow breathing alpha rides on top of the sweep.
 */
public data class AmbientTempo(
    public val role: AmbientRole,
    public val cycleMillis: Int,
    public val intensity: Float,
    public val pulse: Boolean,
) {
    init {
        require(cycleMillis >= 0) { "cycleMillis must not be negative, was $cycleMillis" }
        require(intensity in 0f..1f) { "intensity must be within 0f..1f, was $intensity" }
    }

    /** True when this tempo produces visible, moving output. */
    public val isAnimated: Boolean get() = intensity > 0f && cycleMillis > 0
}

/**
 * Maps the agent's current [AgentActivity] onto the gradient's motion.
 *
 * The intent is that the motion reads as the agent's tempo without the user having to
 * read any text: reasoning breathes slowly, streaming moves briskly, tool calls pulse,
 * and terminal states stop moving so a finished turn does not look like a running one.
 */
public fun ambientTempoFor(activity: AgentActivity): AmbientTempo = when (activity) {
    AgentActivity.Idle -> AmbientTempo(
        role = AmbientRole.Dormant,
        cycleMillis = 0,
        intensity = 0f,
        pulse = false,
    )

    AgentActivity.Reasoning -> AmbientTempo(
        role = AmbientRole.Reasoning,
        cycleMillis = 4_200,
        intensity = 0.45f,
        pulse = true,
    )

    AgentActivity.Thinking -> AmbientTempo(
        role = AmbientRole.Speaking,
        cycleMillis = 1_800,
        intensity = 0.85f,
        pulse = false,
    )

    is AgentActivity.Executing -> AmbientTempo(
        role = AmbientRole.Working,
        cycleMillis = 2_600,
        intensity = 0.7f,
        pulse = true,
    )

    AgentActivity.WaitingForInput -> AmbientTempo(
        role = AmbientRole.Awaiting,
        cycleMillis = 3_400,
        intensity = 0.6f,
        pulse = true,
    )

    // Sticky states all hold still. Stuck detection means the agent has given up on this
    // turn and is waiting for the user, so it must not keep sweeping as though working.
    AgentActivity.Blocked -> AmbientTempo(
        role = AmbientRole.Stalled,
        cycleMillis = 0,
        intensity = 0.3f,
        pulse = false,
    )

    // Likewise a completed or failed turn: animating on would read as work in flight.
    is AgentActivity.Completed -> AmbientTempo(
        role = AmbientRole.Settled,
        cycleMillis = 0,
        intensity = 0.2f,
        pulse = false,
    )

    is AgentActivity.Failed -> AmbientTempo(
        role = AmbientRole.Error,
        cycleMillis = 0,
        intensity = 0.4f,
        pulse = false,
    )
}
