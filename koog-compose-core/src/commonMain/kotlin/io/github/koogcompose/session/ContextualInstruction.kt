package io.github.koogcompose.session

/**
 * A conditional instruction layer appended to the agent's system prompt at
 * runtime, inspired by Scion's contextual agent instructions pattern.
 *
 * Scion automatically appends `agents-git.md` or `agents-hub.md` based on
 * the execution environment. We translate this as named instruction layers
 * evaluated against the current runtime state in
 * [KoogComposeContext.resolveEffectiveInstructions].
 *
 * Three condition types only — per the "Action over pondering" philosophy,
 * we don't build a rule engine:
 *
 * - [ContextCondition.Always] — appended on every turn.
 * - [ContextCondition.WhenPhase] — appended only when the active phase matches.
 * - [ContextCondition.WhenBlocked] — appended when stuck detection fires.
 *   Use this to inject "try a different approach" guidance automatically.
 *
 * ## DSL
 * ```kotlin
 * koogCompose {
 *     provider { anthropic(apiKey = "...") }
 *     contextual {
 *         always {
 *             "You are running inside a mobile app. Keep responses under 3 sentences."
 *         }
 *         whenPhase("payment") {
 *             "The user is completing a payment. Be precise and never guess amounts."
 *         }
 *         whenBlocked {
 *             "You appear to be repeating yourself. Try a completely different approach."
 *         }
 *     }
 *     phases { ... }
 * }
 * ```
 *
 * Contextual instructions are appended after phase instructions, separated by
 * a blank line, so they read as supplemental guidance rather than overrides.
 */
public data class ContextualInstruction(
    val content: String,
    val condition: ContextCondition,
)

public sealed class ContextCondition {
    /** Appended on every turn regardless of phase or activity. */
    public object Always : ContextCondition()

    /** Appended only when [KoogComposeContext.activePhaseName] matches [phaseName]. */
    public data class WhenPhase(val phaseName: String) : ContextCondition()

    /**
     * Appended when the agent is in [AgentActivity.Blocked] state.
     * Evaluated by [PhaseSession] and [SessionRunner] when stuck detection fires,
     * by re-resolving instructions before the fallback message is emitted.
     *
     * Useful for injecting "try a different approach" guidance automatically
     * rather than just surfacing a generic fallback message.
     */
    public object WhenBlocked : ContextCondition()
}

// ── DSL builder ───────────────────────────────────────────────────────────────

public class ContextualInstructionsBuilder {
    private val instructions = mutableListOf<ContextualInstruction>()

    /** Append [block] on every turn. */
    public fun always(block: () -> String) {
        instructions += ContextualInstruction(
            content   = block(),
            condition = ContextCondition.Always,
        )
    }

    /** Append [block] only when the active phase is [phaseName]. */
    public fun whenPhase(phaseName: String, block: () -> String) {
        instructions += ContextualInstruction(
            content   = block(),
            condition = ContextCondition.WhenPhase(phaseName),
        )
    }

    /**
     * Append [block] when stuck detection fires.
     * The content is injected into the system prompt on the turn where the
     * agent is blocked, giving the LLM a hint before the fallback message
     * is surfaced to the user.
     */
    public fun whenBlocked(block: () -> String) {
        instructions += ContextualInstruction(
            content   = block(),
            condition = ContextCondition.WhenBlocked,
        )
    }

    public fun build(): List<ContextualInstruction> = instructions.toList()
}