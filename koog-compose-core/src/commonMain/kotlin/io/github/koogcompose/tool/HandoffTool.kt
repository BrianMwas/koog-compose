package io.github.koogcompose.tool


import io.github.koogcompose.session.KoogAgentDefinition
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * A [SecureTool] the LLM calls to hand off control to a specialist agent.
 *
 * Registered in the main agent's phase by [HandoffBuilder]. When the LLM
 * calls this tool, [SessionRunner] intercepts the result and swaps the
 * active agent rather than continuing the current turn.
 *
 * The tool name is deterministic: `handoff_to_<agentName>`. This lets
 * [SessionRunner] identify handoff calls in the tool result stream without
 * storing extra metadata alongside the history.
 *
 * @param targetAgentName  Name of the [KoogAgentDefinition] to activate.
 * @param description      Natural language condition the LLM reads to decide when to call this.
 * @param continueHistory  If true (default), the incoming agent sees the full conversation history.
 *                         If false, it starts with a clean context window.
 * @param onHandoff        Optional lambda invoked just before the swap — use to mutate shared state.
 */
public class HandoffTool(
    public val targetAgentName: String,
    public val description: String,
    public val continueHistory: Boolean = true,
    public val onHandoff: (HandoffContext.() -> Unit)? = null,
) : SecureTool {

    override val name: String = handoffToolName(targetAgentName)

    override val description: String
        get() = this.description

    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override val parametersSchema: JsonObject? = null

    override suspend fun execute(args: JsonObject): ToolResult =
    // Execution is intercepted by SessionRunner before reaching here.
        // This fallback fires only if the tool is called outside a session context.
        ToolResult.Success("Handing off to $targetAgentName...")
}

/**
 * Context passed to the [HandoffTool.onHandoff] lambda.
 * Gives the callback access to the session's shared state store.
 *
 * ```kotlin
 * handoff(expenseAgent) {
 *     description = "User photographed a receipt"
 *     onHandoff { stateStore.update { it.copy(pendingHandoff = "expense") } }
 * }
 * ```
 */
public class HandoffContext(
    public val stateStore: KoogStateStore<*>?
)

/** Canonical tool name for a handoff targeting [agentName]. */
public fun handoffToolName(agentName: String): String = "handoff_to_$agentName"

// ── DSL ───────────────────────────────────────────────────────────────────────

/**
 * Declares a handoff from the current phase to [target].
 *
 * The [block] sets the LLM-visible description and optional behaviour:
 *
 * ```kotlin
 * phase("root") {
 *     instructions { "You are a productivity assistant." }
 *
 *     handoff(focusAgent)   { "User wants to start a focus session or block apps" }
 *     handoff(expenseAgent) { "User mentions a receipt, spending, or budget" }
 * }
 * ```
 *
 * With options:
 * ```kotlin
 * handoff(expenseAgent) {
 *     description = "User photographed a receipt"
 *     continueHistory = false          // specialist starts with a clean context window
 *     onHandoff { stateStore.update { it.copy(lastReceiptUri = extractUri()) } }
 * }
 * ```
 */
public fun handoff(
    target: KoogAgentDefinition,
    block: HandoffBuilder.() -> Unit
): HandoffTool = HandoffBuilder(target.name).apply(block).build()

/**
 * Shorthand for a handoff whose only configuration is its description string.
 *
 * ```kotlin
 * handoff(focusAgent) { "User wants to start a focus session" }
 * ```
 */
public fun handoff(
    target: KoogAgentDefinition,
    description: () -> String
): HandoffTool = HandoffTool(
    targetAgentName = target.name,
    description = description(),
)

// ── HandoffBuilder ────────────────────────────────────────────────────────────

public class HandoffBuilder(private val targetAgentName: String) {

    public var description: String = "Hand off to $targetAgentName"
    public var continueHistory: Boolean = true
    private var onHandoff: (HandoffContext.() -> Unit)? = null

    public fun onHandoff(block: HandoffContext.() -> Unit) {
        onHandoff = block
    }

    public fun build(): HandoffTool = HandoffTool(
        targetAgentName = targetAgentName,
        description = description,
        continueHistory = continueHistory,
        onHandoff = onHandoff,
    )
}