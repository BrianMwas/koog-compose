package io.github.koogcompose.phase

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolRegistry
import io.github.koogcompose.tool.ToolRefResolver
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * A Phase is a scoped conversation mode with its own instructions and tool subset.
 *
 * Instructions support [ToolName] references — these are resolved against the
 * global ToolRegistry at agent build time, expanding to the tool's full schema.
 *
 * ```kotlin
 * phase("payment", initial = false) {
 *     instructions {
 *         """
 *         Help the user send money.
 *         Use [GetBalance] to verify funds are available.
 *         Use [SendMoney] to execute — but ONLY after confirmation.
 *         """.trimIndent()
 *     }
 * }
 * ```
 *
 * @param name Unique phase identifier. Used for transitions and logging.
 * @param instructions Raw instruction string (may contain [ToolName] refs).
 * @param resolvedInstructions Instructions with [ToolName] refs expanded to full schemas.
 *        Populated by [PhaseRegistry] after all tools are registered globally.
 * @param toolRegistry Tools scoped to this phase (the LLM can only call these).
 * @param transitions Possible exits from this phase, exposed as tool calls.
 * @param isInitial Whether this is the first phase the agent enters.
 */
data class Phase(
    val name: String,
    val instructions: String,
    val resolvedInstructions: String = instructions,
    val toolRegistry: ToolRegistry,
    val transitions: List<PhaseTransition> = emptyList(),
    val isInitial: Boolean = false
)

/**
 * Defines a transition from the current phase to another.
 * Exposed to the LLM as a tool call so it decides when to transition.
 */
data class PhaseTransition(
    val targetPhase: String,
    val conditionDescription: String,
    val toolName: String = "transition_to_$targetPhase"
)

fun PhaseTransition.toTool(): SecureTool = object : SecureTool {
    override val name: String = toolName
    override val description: String = "Transition to $targetPhase when $conditionDescription"
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE
    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success("Transitioning to $targetPhase...")
}

// ── PhaseRegistry ──────────────────────────────────────────────────────────────

/**
 * Holds all registered phases and resolves [ToolName] refs in instructions
 * once the global [ToolRegistry] is available.
 *
 * Resolution happens lazily in [resolveToolRefs] — called by [PhaseAwareAgent]
 * after the full context is assembled.
 */
class PhaseRegistry private constructor(
    private val phases: Map<String, Phase>
) {
    fun resolve(name: String): Phase? = phases[name]
    val all: List<Phase> get() = phases.values.toList()

    /**
     * The initial phase — the first phase the agent enters.
     *
     * Priority:
     * 1. Phase explicitly marked `initial = true`
     * 2. First phase registered (fallback)
     */
    val initialPhase: Phase?
        get() = phases.values.firstOrNull { it.isInitial }
            ?: phases.values.firstOrNull()

    /**
     * Returns a new [PhaseRegistry] with all [ToolName] references in phase
     * instructions resolved against [globalRegistry].
     *
     * Called once by [PhaseAwareAgent] at build time — not on every turn.
     */
    fun resolveToolRefs(globalRegistry: ToolRegistry): PhaseRegistry {
        val resolved = phases.mapValues { (_, phase) ->
            phase.copy(
                resolvedInstructions = ToolRefResolver.resolve(
                    phase.instructions,
                    globalRegistry
                )
            )
        }
        return PhaseRegistry(resolved)
    }

    class Builder {
        private val phases = mutableMapOf<String, Phase>()
        private var explicitInitial: String? = null

        /**
         * Registers a phase.
         *
         * @param name Unique phase identifier.
         * @param initial If true, this phase is entered first. Only one phase
         *   should set this to true. Defaults to false — the first registered
         *   phase is used as fallback if none is marked initial.
         * @param block Phase configuration DSL.
         */
        fun phase(
            name: String,
            initial: Boolean = false,
            block: PhaseBuilder.() -> Unit
        ) {
            val phase = PhaseBuilder(name, initial).apply(block).build()
            phases[name] = phase
            if (initial) {
                require(explicitInitial == null) {
                    "koog-compose: Only one phase can be marked initial = true. " +
                            "Found both '$explicitInitial' and '$name'."
                }
                explicitInitial = name
            }
        }

        fun build(): PhaseRegistry = PhaseRegistry(phases.toMap())
    }

    companion object {
        val Empty = PhaseRegistry(emptyMap())
    }
}

// ── PhaseBuilder ───────────────────────────────────────────────────────────────

class PhaseBuilder(
    private val name: String,
    private val isInitial: Boolean = false
) {
    // Raw instructions — [ToolName] refs resolved later against global registry
    private var instructions: String = ""
    private val toolRegistryBuilder = ToolRegistry.Builder()
    private val transitions = mutableListOf<PhaseTransition>()

    /**
     * Sets the phase instructions. Supports [ToolName] references.
     *
     * ```kotlin
     * instructions {
     *     """
     *     Help the user check their balance.
     *     Use [GetBalance] to fetch the current amount.
     *     Use [GetTransactions] to show recent activity.
     *     """.trimIndent()
     * }
     * ```
     *
     * [GetBalance] will be resolved to the tool's full schema when the agent
     * is built, provided GetBalance is registered in the global tools { } block.
     */
    fun instructions(block: () -> String) {
        instructions = block()
    }

    fun tools(block: ToolRegistry.Builder.() -> Unit) {
        toolRegistryBuilder.apply(block)
    }

    fun tool(tool: SecureTool) {
        toolRegistryBuilder.register(tool)
    }

    /**
     * Declares a condition under which the LLM should transition to [targetPhase].
     * Exposed to the LLM as a tool call — the LLM decides when the condition is met.
     *
     * @param on Natural language description of when to transition (seen by LLM).
     * @param targetPhase The name of the phase to move to.
     */
    fun onCondition(on: String, targetPhase: String) {
        transitions.add(PhaseTransition(targetPhase, on))
    }

    fun build() = Phase(
        name = name,
        instructions = instructions,
        resolvedInstructions = instructions, // resolved later by PhaseRegistry.resolveToolRefs()
        toolRegistry = toolRegistryBuilder.build(),
        transitions = transitions.toList(),
        isInitial = isInitial
    )
}