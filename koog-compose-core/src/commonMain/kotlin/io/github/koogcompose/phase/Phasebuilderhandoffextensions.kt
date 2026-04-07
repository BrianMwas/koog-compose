package io.github.koogcompose.phase

import io.github.koogcompose.session.KoogAgentDefinition
import io.github.koogcompose.tool.HandoffBuilder
import io.github.koogcompose.tool.HandoffTool
import io.github.koogcompose.tool.handoff as createHandoffTool

/**
 * Registers a [HandoffTool] in this phase's tool registry.
 *
 * The LLM reads [HandoffTool.description] to decide when to invoke the handoff.
 * [SessionRunner] intercepts the tool call and swaps the active agent.
 *
 * This is an extension on [PhaseBuilder] rather than a member so that
 * [PhaseBuilder] stays decoupled from the session layer.
 *
 * Shorthand — description only:
 * ```kotlin
 * phase("root") {
 *     instructions { "You are a productivity assistant." }
 *     handoff(focusAgent)   { "User wants to start a focus session or block apps" }
 *     handoff(expenseAgent) { "User mentions a receipt, spending, or budget" }
 * }
 * ```
 *
 * With options:
 * ```kotlin
 * phase("root") {
 *     handoff(expenseAgent) {
 *         description = "User photographed a receipt"
 *         continueHistory = false
 *         onHandoff { stateStore?.update { it.copy(pendingHandoff = "expense") } }
 *     }
 * }
 * ```
 */
public fun PhaseBuilder.handoff(
    target: KoogAgentDefinition,
    description: () -> String,
): Unit {
    tool(createHandoffTool(target, description))
}

public fun PhaseBuilder.handoff(
    target: KoogAgentDefinition,
    block: HandoffBuilder.() -> Unit,
): Unit {
    tool(createHandoffTool(target, block))
}
