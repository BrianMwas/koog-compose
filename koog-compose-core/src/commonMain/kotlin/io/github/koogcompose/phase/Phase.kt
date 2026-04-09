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
public data class Phase(
    val name: String,
    val instructions: String,
    val resolvedInstructions: String = instructions,
    val toolRegistry: ToolRegistry,
    val transitions: List<PhaseTransition> = emptyList(),
    val isInitial: Boolean = false,


    val outputStructure: PhaseOutput<*>? = null,
)

/**
 * Defines a transition from the current phase to another.
 * Exposed to the LLM as a tool call so it decides when to transition.
 */
public data class PhaseTransition(
    val targetPhase: String,
    val conditionDescription: String,
    val toolName: String = "transition_to_$targetPhase"
)

public fun PhaseTransition.toTool(): SecureTool = object : SecureTool {
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
public class PhaseRegistry private constructor(
    private val phases: Map<String, Phase>
) {
    public fun resolve(name: String): Phase? = phases[name]
    public val all: List<Phase> get() = phases.values.toList()

    /**
     * The initial phase — the first phase the agent enters.
     *
     * Priority:
     * 1. Phase explicitly marked `initial = true`
     * 2. First phase registered (fallback)
     */
    public val initialPhase: Phase?
        get() = phases.values.firstOrNull { it.isInitial }
            ?: phases.values.firstOrNull()

    /**
     * Returns a new [PhaseRegistry] with all [ToolName] references in phase
     * instructions resolved against [globalRegistry].
     *
     * Called once by [PhaseAwareAgent] at build time — not on every turn.
     */
    public fun resolveToolRefs(globalRegistry: ToolRegistry): PhaseRegistry {
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

    public class Builder {
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
        public fun phase(
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

        public fun build(): PhaseRegistry = PhaseRegistry(phases.toMap())
    }

    public companion object {
        public val Empty: PhaseRegistry = PhaseRegistry(emptyMap())
    }
}

// ── PhaseBuilder ───────────────────────────────────────────────────────────────

public class PhaseBuilder(
    private val name: String,
    private val isInitial: Boolean = false,
) {
    private var instructions: String = ""
    private val toolRegistryBuilder = ToolRegistry.Builder()
    private val transitions = mutableListOf<PhaseTransition>()
    public var outputStructure: PhaseOutput<*>? = null   // NEW

    public fun instructions(block: () -> String) { instructions = block() }
    public fun tools(block: ToolRegistry.Builder.() -> Unit) { toolRegistryBuilder.apply(block) }
    public fun tool(tool: SecureTool) { toolRegistryBuilder.register(tool) }
    public fun onCondition(on: String, targetPhase: String) {
        transitions.add(PhaseTransition(targetPhase, on))
    }

    /**
     * NEW — declares that this phase must return a value of type [O].
     *
     * Annotate your data class with @LLMDescription on each field —
     * the schema is generated automatically from those annotations.
     *
     * @param retries  parse-retry attempts before surfacing an error
     * @param examples injected into the prompt regardless of provider
     *                 type, fixing Koog's native-schema priming bug
     */
    public inline fun <reified O> typedOutput(
        retries: Int = 3,
        examples: List<O> = emptyList(),
        descriptionOverrides: Map<String, String> = emptyMap(),
        excludedProperties: Set<String> = emptySet(),
    ) {
        outputStructure = phaseOutput<O>(
            retries = retries,
            examples = examples,
            descriptionOverrides = descriptionOverrides,
            excludedProperties = excludedProperties,
        )
    }

    public fun build(): Phase = Phase(
        name                 = name,
        instructions         = instructions,
        resolvedInstructions = instructions,
        toolRegistry         = toolRegistryBuilder.build(),
        transitions          = transitions.toList(),
        isInitial            = isInitial,
        outputStructure      = outputStructure,
    )
}