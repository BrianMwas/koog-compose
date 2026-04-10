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

    /**
     * When non-empty, this phase is a container of sequential sub-steps.
     * [PhaseStrategyBuilder] chains them as nested subgraphs in order.
     * The parent phase's [transitions] only fire after ALL subphases complete.
     *
     * Subphase names are namespaced as `parentName__subphaseName`
     * (double underscore) to avoid collisions in the flat phaseSubgraphs map.
     */
    val subphases: List<Phase> = emptyList(),

    /**
     * Each inner list is a group of branches that run in parallel.
     * Multiple [parallel] blocks in one phase → multiple groups, run sequentially.
     * Within a group, all branches fan out simultaneously and join when all finish.
     *
     * Branch names are namespaced as `parentName__parallel__branchName`.
     */
    val parallelGroups: List<List<Phase>> = emptyList(),
) {
    /** True when this phase delegates execution to sequential subphases. */
    val hasSubphases: Boolean get() = subphases.isNotEmpty()
    /** True when this phase has parallel branches. */
    val hasParallel: Boolean get() = parallelGroups.isNotEmpty()
}

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
                ),
                subphases = phase.subphases.map { sub ->
                    sub.copy(
                        resolvedInstructions = ToolRefResolver.resolve(
                            sub.instructions,
                            globalRegistry
                        )
                    )
                },
                parallelGroups = phase.parallelGroups.map { group ->
                    group.map { branch ->
                        branch.copy(
                            resolvedInstructions = ToolRefResolver.resolve(
                                branch.instructions,
                                globalRegistry
                            )
                        )
                    }
                }
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

// ── Nesting level guard ──────────────────────────────────────────────────────

/** Defines what nesting is allowed in a PhaseBuilder context. */
internal enum class PhaseBuilderContext {
    /** Top-level phase — allows subphase, parallel, and transitions. */
    TopLevel,
    /** Subphase of a phase — no further nesting allowed. */
    Subphase,
    /** Branch of a parallel group — no nesting allowed. */
    ParallelBranch,
}

public class PhaseBuilder internal constructor(
    private val name: String,
    private val isInitial: Boolean = false,
    private val context: PhaseBuilderContext = PhaseBuilderContext.TopLevel,
) {
    private var instructions: String = ""
    private val toolRegistryBuilder = ToolRegistry.Builder()
    private val transitions = mutableListOf<PhaseTransition>()
    public var outputStructure: PhaseOutput<*>? = null
    private val subphaseBuilders = mutableListOf<PhaseBuilder>()
    private val parallelGroups = mutableListOf<List<PhaseBuilder>>()

    public fun instructions(block: () -> String) { instructions = block() }
    public fun tools(block: ToolRegistry.Builder.() -> Unit) { toolRegistryBuilder.apply(block) }
    public fun tool(tool: SecureTool) { toolRegistryBuilder.register(tool) }
    public fun onCondition(on: String, targetPhase: String) {
        require(context == PhaseBuilderContext.TopLevel) {
            "koog-compose: onCondition() is only allowed on top-level phases, not on '$name' (context: $context)."
        }
        transitions.add(PhaseTransition(targetPhase, on))
    }

    /**

     * Declares that this phase must return a value of type [O].
     *
     * Annotate your data class with @LLMDescription on each field —
     * the schema is generated automatically from those annotations.
     *
     * @param retries  parse-retry attempts before surfacing an error
     * @param version  schema version for evolving output types
     * @param examples injected into the prompt regardless of provider
     *                 type, fixing Koog's native-schema priming bug
     * @param validate optional validation lambda — return [ValidationResult.Invalid]
     *                 to trigger a retry with the error fed back to the LLM
     */
    public inline fun <reified O> typedOutput(
        retries: Int = 3,
        version: Int = 1,
        examples: List<O> = emptyList(),
        descriptionOverrides: Map<String, String> = emptyMap(),
        excludedProperties: Set<String> = emptySet(),
        noinline validate: (O) -> ValidationResult = { ValidationResult.Valid },
    ) {
        outputStructure = phaseOutput<O>(
            retries = retries,
            version = version,
            examples = examples,
            descriptionOverrides = descriptionOverrides,
            excludedProperties = excludedProperties,
            validate = validate,
        )
    }

    /**
     * Declares a sequential sub-step inside this phase.
     *
     * Subphases run in declaration order. Each has its own tool scope —
     * a tool registered in subphase 2 cannot be called in subphase 1.
     * The parent phase's [onCondition] transitions only fire after ALL
     * subphases complete.
     *
     * ```kotlin
     * phase("checkout") {
     *     subphase("validate_cart") {
     *         tool(CheckInventoryTool())
     *         typedOutput<CartValidation>()
     *     }
     *     subphase("process_payment") {
     *         tool(ChargeCardTool()) // only reachable here
     *         typedOutput<PaymentResult>()
     *     }
     *     subphase("confirm_order") {
     *         tool(SendEmailTool())
     *     }
     *     onCondition("order complete", "post_purchase")
     * }
     * ```
     */
    public fun subphase(
        name: String,
        block: PhaseBuilder.() -> Unit
    ) {
        require(context == PhaseBuilderContext.TopLevel) {
            "koog-compose: subphase { } can only be declared on a top-level phase. " +
                "'$name' is nested inside another phase (context: $context)."
        }
        // Namespace as parentName__subphaseName to avoid collisions
        val qualifiedName = "${this.name}__$name"
        subphaseBuilders.add(PhaseBuilder(qualifiedName, context = PhaseBuilderContext.Subphase).apply(block))
    }

    /**
     * Declares subgraphs that run simultaneously within this phase.
     *
     * All branches in the [parallel] block fan out at the same time.
     * The phase only continues once every branch has finished.
     * Each branch has its own isolated tool scope.
     *
     * Multiple [parallel] blocks in one phase → multiple groups, run sequentially.
     *
     * ```kotlin
     * phase("gather_context", initial = true) {
     *     parallel {
     *         branch("location") {
     *             tool(GeocoderTool())
     *             typedOutput<LocationContext>()
     *         }
     *         branch("device") {
     *             tool(LocaleTool())
     *             typedOutput<DeviceContext>()
     *         }
     *         branch("permissions") {
     *             tool(PermissionCheckTool())
     *             typedOutput<PermissionContext>()
     *         }
     *     }
     *     onCondition("context ready", "main")
     * }
     * ```
     */
    public fun parallel(block: ParallelBuilder.() -> Unit) {
        require(context == PhaseBuilderContext.TopLevel) {
            "koog-compose: parallel { } can only be declared on a top-level phase, not on '$name' (context: $context)."
        }
        val builder = ParallelBuilder(this.name).apply(block)
        parallelGroups.add(builder.branches())
    }

    /**
     * Applies a [PhaseTemplate] to this phase.
     *
     * Tools, instructions, and typed output from the template are merged in.
     * Anything declared after `include()` overrides template values (later
     * calls overwrite earlier ones).
     */
    public fun include(template: PhaseTemplate) {
        template.apply(this)
    }

    /**
     * Adds a [SubphaseTemplate] as a named subphase of this phase.
     *
     * Equivalent to calling `subphase(template.name) { template.apply(this) }`.
     */
    public fun include(template: SubphaseTemplate) {
        require(context == PhaseBuilderContext.TopLevel) {
            "koog-compose: include(SubphaseTemplate) can only be called on a top-level phase, not on '$name' (context: $context)."
        }
        subphase(template.name) { template.apply(this) }
    }

    public fun build(): Phase = Phase(
        name                 = name,
        instructions         = instructions,
        resolvedInstructions = instructions,
        toolRegistry         = toolRegistryBuilder.build(),
        transitions          = transitions.toList(),
        isInitial            = isInitial,
        outputStructure      = outputStructure,
        subphases            = subphaseBuilders.map { it.build() },
        parallelGroups       = parallelGroups.map { group -> group.map { it.build() } },
    )
}

// ── ParallelBuilder ────────────────────────────────────────────────────────────

/**
 * Builder for parallel branches within a phase.
 *
 * Used via [PhaseBuilder.parallel]:
 * ```kotlin
 * parallel {
 *     branch("location") { tool(GeocoderTool()) }
 *     branch("device") { tool(LocaleTool()) }
 * }
 * ```
 */
public class ParallelBuilder(private val parentName: String) {
    private val branchBuilders = mutableListOf<PhaseBuilder>()

    /**
     * Declares one branch in the parallel group.
     * Each branch has its own isolated tool scope and runs simultaneously
     * with all other branches in this group.
     */
    public fun branch(name: String, block: PhaseBuilder.() -> Unit) {
        // Namespace as parentName__parallel__branchName
        val qualifiedName = "${parentName}__parallel__$name"
        branchBuilders.add(PhaseBuilder(qualifiedName, context = PhaseBuilderContext.ParallelBranch).apply(block))
    }

    internal fun branches(): List<PhaseBuilder> = branchBuilders.toList()
}

// ── Reusable templates ─────────────────────────────────────────────────────────

/**
 * A reusable phase configuration.
 *
 * A template captures tools, instructions, and typed output for a common
 * pattern so it can be included in multiple phases without duplication.
 *
 * Create with [phaseTemplate], include with [PhaseBuilder.include].
 *
 * ```kotlin
 * val researchTemplate = phaseTemplate {
 *     instructions { "Search the web and summarise findings." }
 *     tool(WebSearchTool())
 *     typedOutput<ResearchSummary>()
 * }
 *
 * phases {
 *     phase("answer_question") {
 *         include(researchTemplate)          // pulls in tools + instructions
 *         onCondition("done", "respond")
 *     }
 *     phase("draft_email") {
 *         include(researchTemplate)          // same template, different phase
 *         tool(EmailDraftTool())             // add phase-specific tools after
 *         onCondition("draft ready", "review")
 *     }
 * }
 * ```
 */
public class PhaseTemplate internal constructor(
    internal val apply: PhaseBuilder.() -> Unit
)

/**
 * Creates a [PhaseTemplate] from a [PhaseBuilder] configuration block.
 * The block is applied lazily — evaluated each time [PhaseBuilder.include] is called.
 */
public fun phaseTemplate(block: PhaseBuilder.() -> Unit): PhaseTemplate =
    PhaseTemplate(block)

/**
 * A template for a subphase — used with [PhaseBuilder.include] to add
 * a named subphase from a reusable definition.
 *
 * ```kotlin
 * val research = subphaseTemplate("research") {
 *     instructions { "Search and summarise." }
 *     tool(WebSearchTool())
 *     typedOutput<ResearchSummary>()
 * }
 *
 * phase("answer") {
 *     include(research)   // equivalent to subphase("research") { ... }
 * }
 * ```
 */
public class SubphaseTemplate internal constructor(
    public val name: String,
    internal val apply: PhaseBuilder.() -> Unit
)

/** Creates a [SubphaseTemplate] with the given [name] and configuration [block]. */
public fun subphaseTemplate(
    name: String,
    block: PhaseBuilder.() -> Unit
): SubphaseTemplate = SubphaseTemplate(name, block)