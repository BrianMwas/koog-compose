package io.github.koogcompose.session

import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.tool.ToolRegistry

/**
 * A named, self-contained agent definition.
 *
 * Produced by both [koogAgent] and [koogCompose]. Both entry points return the
 * same type — [koogCompose] is sugar for `koogAgent("default") { }`.
 *
 * Agent definitions are stateless templates. The [SessionRunner] instantiates
 * a live [KoogComposeContext] from them at runtime by resolving provider
 * inheritance against the session-level [ProviderConfig].
 *
 * ```kotlin
 * val focusAgent = koogAgent("focus") {
 *     // No provider — inherits from session global
 *     instructions { "You manage focus sessions." }
 *     phases {
 *         phase("active") {
 *             tool(BlockAppsTool())
 *         }
 *     }
 * }
 * ```
 *
 * @param name       Unique identifier. Used by [handoff] to route to this agent.
 * @param provider   Optional per-agent provider override. Null means inherit from session.
 * @param instructions  Top-level system instructions (prepended to phase instructions).
 * @param phaseRegistry Phases registered for this agent.
 * @param toolRegistry  Tools available globally to this agent (outside any phase scope).
 */
public data class KoogAgentDefinition(
    val name: String,
    val provider: ProviderConfig?,
    val instructions: String,
    val phaseRegistry: PhaseRegistry,
    val toolRegistry: ToolRegistry,
)

/**
 * DSL entry point for a standalone, named agent definition.
 *
 * Define agents as top-level values — one per file for larger projects.
 * Register them in a [koogSession] via `agents(focusAgent, expenseAgent)`.
 *
 * ```kotlin
 * val focusAgent = koogAgent("focus") {
 *     provider { anthropic(apiKey = BuildConfig.KEY) { model = "claude-3-5-sonnet" } }
 *     instructions { "You manage focus sessions and content blocking." }
 *     phases {
 *         phase("active") {
 *             tool(BlockAppsTool())
 *             tool(FocusTimerTool())
 *             onCondition("focus task complete", targetPhase = "done")
 *         }
 *         phase("done") {
 *             instructions { "Summarise what was set up and hand back." }
 *         }
 *     }
 * }
 * ```
 */
public fun koogAgent(
    name: String,
    block: KoogAgentDefinitionBuilder.() -> Unit
): KoogAgentDefinition = KoogAgentDefinitionBuilder(name).apply(block).build()

// ── Builder ───────────────────────────────────────────────────────────────────

public class KoogAgentDefinitionBuilder(private val name: String) {

    private var provider: ProviderConfig? = null
    private var instructions: String = ""
    private var phaseRegistry: io.github.koogcompose.phase.PhaseRegistry =
        io.github.koogcompose.phase.PhaseRegistry.Empty
    private var toolRegistry: ToolRegistry = ToolRegistry.Empty

    /**
     * Optional provider override for this agent.
     * Omit to inherit from the session-level provider.
     *
     * ```kotlin
     * provider {
     *     openAI(apiKey = BuildConfig.OPENAI_KEY) { model = "gpt-4o" }
     * }
     * ```
     */
    public fun provider(block: ProviderConfigBuilder.() -> Unit) {
        provider = ProviderConfigBuilder().apply(block).build()
    }

    /**
     * Top-level system instructions for this agent.
     * Prepended to any phase-level instructions at runtime.
     */
    public fun instructions(block: () -> String) {
        instructions = block()
    }

    public fun phases(block: PhaseRegistry.Builder.() -> Unit) {
        phaseRegistry = PhaseRegistry.Builder().apply(block).build()
    }

    public fun tools(block: ToolRegistry.Builder.() -> Unit) {
        toolRegistry = ToolRegistry(block)
    }

    public fun tool(tool: io.github.koogcompose.tool.SecureTool) {
        toolRegistry = toolRegistry.plus(tool)
    }

    public fun build(): KoogAgentDefinition = KoogAgentDefinition(
        name = name,
        provider = provider,
        instructions = instructions,
        phaseRegistry = phaseRegistry,
        toolRegistry = toolRegistry,
    )
}