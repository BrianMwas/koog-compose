package io.github.koogcompose.session

import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.layout.LayoutEngineConfig
import io.github.koogcompose.layout.LayoutPolicy
import io.github.koogcompose.layout.LayoutPolicyChain
import io.github.koogcompose.layout.SlotRegistry
import io.github.koogcompose.layout.ComponentRegistry
import io.github.koogcompose.layout.WorkflowContext
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.NoOpEventSink
import io.github.koogcompose.phase.Phase
import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.phase.toTool
import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.provider.ProviderRuntimeRegistry
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.security.Guardrails
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolRegistry


/**
 * History compression strategy.
 * Maps directly to Koog's built-in HistoryCompressionStrategy types.
 */
public sealed class HistoryCompression {

    /**
     * Summarizes the entire history into one TLDR message.
     * Best for general use — maintains full context awareness
     * while drastically reducing token count.
     */
    public object WholeHistory : HistoryCompression()

    /**
     * Keeps only the last [n] messages and discards everything older.
     * Best when only recent context matters.
     * Most aggressive — lowest token usage.
     */
    public data class FromLastN(val n: Int) : HistoryCompression()

    /**
     * Splits history into chunks of [chunkSize] and compresses each independently.
     * Best when you need both recent detail and older progress summaries.
     */
    public data class Chunked(val chunkSize: Int) : HistoryCompression()

    /**
     * Searches history for specific concepts and extracts only those facts.
     * Most powerful — the AI retrieves exactly what it needs to know.
     */
    public data class RetrieveFactsFromHistory(
        val concepts: List<Concept>
    ) : HistoryCompression() {
        public constructor(vararg concepts: Concept) : this(concepts.toList())
    }
}



/**
 * A concept to extract from history in [HistoryCompression.RetrieveFactsFromHistory].
 *
 * @param keyword Short identifier for this concept.
 * @param description What the AI should search for — be specific.
 * @param factType Whether to extract one fact or multiple.
 */
public data class Concept(
    val keyword: String,
    val description: String,
    val factType: FactType = FactType.MULTIPLE
)

public enum class FactType { SINGLE, MULTIPLE }


/**
 * When to trigger history compression.
 */
public sealed class CompressionTrigger {
    /** Compress after the history reaches [messageCount] messages. */
    public data class AfterMessages(val messageCount: Int) : CompressionTrigger()

    /** Compress between logical workflow phases (subgraph boundaries). */
    public object BetweenPhases : CompressionTrigger()

    /** Compress both on message count and between phases. */
    public data class Both(val messageCount: Int) : CompressionTrigger()
}

/**
 * Configuration for history compression.
 */
public data class HistoryCompressionConfig(
    val strategy: HistoryCompression,
    val trigger: CompressionTrigger = CompressionTrigger.AfterMessages(40),
    val preserveMemory: Boolean = true
)

/**
 * Retry policy for failed AI requests.
 */
public data class RetryPolicy(
    /** Number of retry attempts before surfacing an error. */
    val maxAttempts: Int = 3,
    /** Initial delay in ms. */
    val initialDelayMs: Long = 500L,
    /** Backoff multiplier applied on each retry. Default 2.0 = exponential backoff. */
    val backoffMultiplier: Double = 2.0,
    /** Whether to use a StructureFixingParser for structured output failures. */
    val useStructureFixingParser: Boolean = true,
    /** Number of structure-fixing retries (separate from network retries). */
    val structureFixingRetries: Int = 3
)


/**
 * LLM parameter overrides.
 * Leave null to use provider defaults.
 */
public data class LLMParamsConfig(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String> = emptyList()
)

public data class StuckDetectionConfig(
    val threshold: Int = 3,
    val fallbackMessage: String = "I'm having trouble with that. Please try again."
)

public class StuckDetectionConfigBuilder {
    public var threshold: Int = 3
    public var fallbackMessage: String = "I'm having trouble with that. Please try again."
    public fun build(): StuckDetectionConfig = StuckDetectionConfig(threshold, fallbackMessage)
}


/**
 * KoogConfig — runtime configuration for a [KoogComposeContext].
 */
public data class KoogConfig(
    val streamingEnabled: Boolean = true,
    val rateLimitPerMinute: Int = 0,
    val auditLoggingEnabled: Boolean = true,
    val requireConfirmationForSensitive: Boolean = true,
    val historyCompression: HistoryCompressionConfig? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val llmParams: LLMParamsConfig? = null,
    val responseCache: Boolean = false,
    val structureFixingRetries: Int = 3,
    val maxAgentIterations: Int = 15,
    val guardrails: Guardrails = Guardrails.Default,
    val stuckDetection: StuckDetectionConfig? = null,
    val eventSink: EventSink = NoOpEventSink,
) {


    public class Builder {
        public var streamingEnabled: Boolean = true
        public var rateLimitPerMinute: Int = 0
        public var auditLoggingEnabled: Boolean = true
        public var requireConfirmationForSensitive: Boolean = true
        public var responseCache: Boolean = false
        public var structureFixingRetries: Int = 3
        private var stuckDetection: StuckDetectionConfig? = null
        public var maxAgentIterations: Int = 10
        public var eventSink: EventSink = NoOpEventSink

        private var historyCompression: HistoryCompressionConfig? = null
        private var retryPolicy: RetryPolicy = RetryPolicy()
        private var llmParams: LLMParamsConfig? = null
        private var guardrails: Guardrails = Guardrails.Default

        public fun historyCompression(block: HistoryCompressionConfigBuilder.() -> Unit): Unit {
            historyCompression = HistoryCompressionConfigBuilder().apply(block).build()
        }

        public fun retry(block: RetryPolicyBuilder.() -> Unit): Unit {
            retryPolicy = RetryPolicyBuilder().apply(block).build()
        }

        public fun llmParams(block: LLMParamsConfigBuilder.() -> Unit): Unit {
            llmParams = LLMParamsConfigBuilder().apply(block).build()
        }

        public fun guardrails(block: Guardrails.Builder.() -> Unit): Unit {
            guardrails = Guardrails.Builder().apply(block).build()
        }



        public fun stuckDetection(block: StuckDetectionConfigBuilder.() -> Unit) {
            stuckDetection = StuckDetectionConfigBuilder().apply(block).build()
        }


        public fun build(): KoogConfig = KoogConfig(
            streamingEnabled = streamingEnabled,
            rateLimitPerMinute = rateLimitPerMinute,
            auditLoggingEnabled = auditLoggingEnabled,
            requireConfirmationForSensitive = requireConfirmationForSensitive,
            historyCompression = historyCompression,
            retryPolicy = retryPolicy,
            llmParams = llmParams,
            structureFixingRetries = structureFixingRetries,
            responseCache = responseCache,
            maxAgentIterations = maxAgentIterations,
            guardrails = guardrails,
            stuckDetection = stuckDetection,
            eventSink = eventSink,
        )
    }

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): KoogConfig =
            Builder().apply(block).build()
    }
}

public class HistoryCompressionConfigBuilder {
    public var strategy: HistoryCompression = HistoryCompression.WholeHistory
    public var trigger: CompressionTrigger = CompressionTrigger.AfterMessages(40)
    public var preserveMemory: Boolean = true

    public fun build(): HistoryCompressionConfig = HistoryCompressionConfig(strategy, trigger, preserveMemory)
}

public class RetryPolicyBuilder {
    public var maxAttempts: Int = 3
    public var initialDelayMs: Long = 500L
    public var backoffMultiplier: Double = 2.0
    public var useStructureFixingParser: Boolean = true
    public var structureFixingRetries: Int = 3

    public fun build(): RetryPolicy = RetryPolicy(maxAttempts, initialDelayMs, backoffMultiplier, useStructureFixingParser, structureFixingRetries)
}

public class LLMParamsConfigBuilder {
    public var temperature: Double? = null
    public var maxTokens: Int? = null
    public var topP: Double? = null
    public var topK: Int? = null
    public var stopSequences: List<String> = emptyList()

    public fun build(): LLMParamsConfig = LLMParamsConfig(temperature, maxTokens, topP, topK, stopSequences)
}



/**
 * The central runtime object for koog-compose.
 *
 * Carries everything the agent needs for a turn:
 * - [providerConfig]           → which LLM to call
 * - [promptStack]              → global system prompt layers
 * - [toolRegistry]             → tools available at the session level
 * - [phaseRegistry]            → phase definitions and transitions
 * - [activePhaseName]          → which phase is currently active
 * - [eventHandlers]            → session-level event observers
 * - [stateStore]               → typed shared app state (null if stateless)
 * - [config]                   → runtime config (retries, stuck detection, etc.)
 * - [contextualInstructions]   → environment-scoped instruction layers (new)
 *
 * ## Contextual instructions
 * Inspired by Scion's automatic instruction injection (agents-git.md,
 * agents-hub.md), contextual instructions are conditional layers appended
 * to the resolved system prompt in [resolveEffectiveInstructions].
 *
 * They are evaluated lazily on each call — [WhenPhase] checks the current
 * [activePhaseName], [WhenBlocked] is passed in explicitly by [PhaseSession]
 * when stuck detection fires, [Always] always appends.
 *
 * Declared via the `contextual { }` DSL block:
 * ```kotlin
 * koogCompose {
 *     contextual {
 *         always { "You are running inside a mobile app. Keep responses concise." }
 *         whenPhase("payment") { "The user is completing a payment. Be precise." }
 *         whenBlocked { "Try a completely different approach." }
 *     }
 * }
 * ```
 */
public data class KoogComposeContext<S>(
    val providerConfig: ProviderConfig,
    val promptStack: PromptStack,
    val toolRegistry: ToolRegistry,
    val phaseRegistry: PhaseRegistry = PhaseRegistry.Empty,
    val activePhaseName: String? = null,
    val eventHandlers: EventHandlers = EventHandlers.Empty,
    override val stateStore: KoogStateStore<S>?,
    val config: KoogConfig,
    val contextualInstructions: List<ContextualInstruction> = emptyList(),
    val layoutEngineConfig: LayoutEngineConfig? = null,
) : KoogDefinition<S> {
    public fun createProvider(): AIProvider =
        ProviderRuntimeRegistry.create(this) ?: KoogAIProvider(this)
    public val provider: AIProvider get() = createProvider()

    /**
     * Creates a [PromptExecutor] backed by this context's provider config.
     * Use this when constructing [PhaseSession] outside of the convenience DSL.
     */
    override fun createExecutor(): ai.koog.prompt.executor.model.PromptExecutor =
        io.github.koogcompose.provider.buildExecutor(providerConfig)

    public val activePhase: Phase? get() = activePhaseName?.let { phaseRegistry.resolve(it) }

    public fun withSessionContext(context: String): KoogComposeContext<S> = copy(
        promptStack = promptStack.withSessionContext(context)
    )

    public fun withTool(tool: SecureTool): KoogComposeContext<S> = copy(
        toolRegistry = toolRegistry.plus(tool)
    )

    public fun withPhase(name: String): KoogComposeContext<S> = copy(activePhaseName = name)

    /**
     * Resolves the full system prompt for the current turn.
     *
     * Resolution order:
     * 1. Global prompt stack (session-level context)
     * 2. Current phase name + phase instructions
     * 3. Contextual instruction layers whose conditions are met
     *
     * [isBlocked] should be true when stuck detection has fired so that
     * [ContextCondition.WhenBlocked] layers are included in the prompt.
     * [PhaseSession] and [SessionRunner] pass this when building the agent
     * on a blocked turn.
     */
    public fun resolveEffectiveInstructions(isBlocked: Boolean = false): String {
        val globalPrompt = promptStack.resolve().trim()
        val phase = activePhase ?: phaseRegistry.initialPhase

        val parts = buildList {
            if (globalPrompt.isNotBlank()) add(globalPrompt)

            if (phase != null) {
                add("CURRENT PHASE: ${phase.name}")
                if (phase.resolvedInstructions.isNotBlank()) {
                    add(phase.resolvedInstructions.trim())
                }
            }

            // Evaluate contextual instruction conditions against current runtime state.
            val matching = contextualInstructions.filter { instruction ->
                when (val cond = instruction.condition) {
                    is ContextCondition.Always    -> true
                    is ContextCondition.WhenPhase -> cond.phaseName == activePhaseName
                    is ContextCondition.WhenBlocked -> isBlocked
                }
            }
            matching.forEach { add(it.content.trim()) }
        }

        return parts.joinToString(separator = "\n\n")
    }

    public fun resolveEffectiveTools(): List<SecureTool> {
        val phase = activePhase ?: phaseRegistry.initialPhase
        val baseTools = buildList {
            addAll(toolRegistry.all)
            if (phase != null) {
                addAll(phase.toolRegistry.all)
            }
        }
        val transitionTools = phase?.transitions?.map { it.toTool() } ?: emptyList()
        return (baseTools + transitionTools).distinctBy(SecureTool::name)
    }

    public class Builder<S> {
        private var providerConfig: ProviderConfig? = null
        private var promptStack: PromptStack = PromptStack.Empty
        private var toolRegistry: ToolRegistry = ToolRegistry.Empty
        private var phaseRegistry: PhaseRegistry = PhaseRegistry.Empty
        private var activePhaseName: String? = null
        private var stateStore: KoogStateStore<S>? = null
        private var eventHandlers: EventHandlers = EventHandlers.Empty
        private var config: KoogConfig = KoogConfig()
        private var contextualInstructions: List<ContextualInstruction> = emptyList()
        private var layoutEngineConfig: LayoutEngineConfig? = null

        public fun provider(block: ProviderConfigBuilder.() -> Unit) {
            providerConfig = ProviderConfigBuilder().apply(block).build()
        }

        public fun prompt(block: PromptStack.Builder.() -> Unit) {
            promptStack = PromptStack(block)
        }

        public fun tools(block: ToolRegistry.Builder.() -> Unit) {
            toolRegistry = ToolRegistry(block)
        }

        public fun phases(block: PhaseRegistry.Builder.() -> Unit) {
            phaseRegistry = PhaseRegistry.Builder().apply(block).build()
            if (activePhaseName == null) {
                activePhaseName = phaseRegistry.all.firstOrNull()?.name
            }
        }

        public fun initialState(block: () -> S) {
            stateStore = KoogStateStore(block())
        }

        public fun initialPhase(name: String) {
            activePhaseName = name
        }

        public fun events(block: EventHandlers.Builder.() -> Unit) {
            eventHandlers = EventHandlers(block)
        }

        public fun config(block: KoogConfig.Builder.() -> Unit) {
            config = KoogConfig(block)
        }

        /**
         * Declares environment-scoped instruction layers.
         *
         * ```kotlin
         * contextual {
         *     always { "You are running inside a mobile app. Keep responses concise." }
         *     whenPhase("payment") { "The user is completing a payment. Be precise." }
         *     whenBlocked { "Try a completely different approach." }
         * }
         * ```
         */
        public fun contextual(block: ContextualInstructionsBuilder.() -> Unit) {
            contextualInstructions = ContextualInstructionsBuilder().apply(block).build()
        }

        /**
         * Enables the agent-driven layout engine for this session.
         *
         * ```kotlin
         * layout {
         *     workflowContext = WorkflowContext(...)
         *     slotRegistry    = SlotRegistry(listOf(...))
         *     componentRegistry = ComponentRegistry(listOf(...))
         *     policy { /* optional host/workflow policy tiers */ }
         * }
         * ```
         */
        public fun layout(block: LayoutEngineConfigBuilder.() -> Unit) {
            layoutEngineConfig = LayoutEngineConfigBuilder().apply(block).build()
        }

        public fun build(): KoogComposeContext<S> = KoogComposeContext(
            providerConfig          = providerConfig
                ?: error("koog-compose: provider { } block is required."),
            promptStack             = promptStack,
            toolRegistry            = toolRegistry,
            phaseRegistry           = phaseRegistry,
            activePhaseName         = activePhaseName,
            eventHandlers           = eventHandlers,
            stateStore              = stateStore,
            config                  = config,
            contextualInstructions  = contextualInstructions,
            layoutEngineConfig      = layoutEngineConfig,
        )
    }

    public companion object {
        public operator fun <S> invoke(block: Builder<S>.() -> Unit): KoogComposeContext<S> =
            Builder<S>().apply(block).build()
    }
}



// ── Shared interface ─────────────────────────────────────────────────────────

/**
 * Common supertype for both single-agent ([KoogComposeContext]) and
 * multi-agent ([KoogSession]) definitions.
 *
 * Callers never need to pattern-match — `.stateStore` and `.createExecutor()`
 * work regardless of which variant was built.
 */
public interface KoogDefinition<S> {
    /** Shared typed state store, if `initialState { }` was called. */
    public val stateStore: KoogStateStore<S>?
    /** Creates a [PromptExecutor] backed by this definition's provider config. */
    public fun createExecutor(): ai.koog.prompt.executor.model.PromptExecutor
}

// ── Unified DSL entry point ───────────────────────────────────────────────────

/**
 * Single entry point for building koog-compose agents.
 *
 * **Single-agent** (most common):
 * ```kotlin
 * val ctx = koogCompose<AppState> {
 *     provider { anthropic(apiKey = "...") { model = "claude-sonnet-4-5" } }
 *     initialState { AppState() }
 *     phases {
 *         phase("greeting", initial = true) {
 *             instructions { "Greet the user." }
 *         }
 *     }
 * }
 * ```
 *
 * **Multi-agent** (add `main {}` and `agents {}`):
 * ```kotlin
 * val session = koogCompose<Unit> {
 *     provider { anthropic(apiKey = "...") }
 *     main {
 *         instructions { "General assistant." }
 *         phases {
 *             phase("root", initial = true) {
 *                 handoff(focusAgent) { "User asks about focus." }
 *             }
 *         }
 *     }
 *     agents(focusAgent)
 * }
 * ```
 *
 * @return A [KoogDefinition] — works with [PhaseSession] (single-agent)
 *   or [SessionRunner] (multi-agent) without any branching.
 */
public fun <S : Any?> koogCompose(
    block: UnifiedAgentBuilder<S>.() -> Unit
): KoogDefinition<S> = UnifiedAgentBuilder<S>().apply(block).build()

/**
 * Unified builder that supports both single-agent and multi-agent DSLs.
 *
 * When `main {}` is called → multi-agent path (builds [KoogSession]).
 * When `main {}` is NOT called → single-agent path (builds [KoogComposeContext]).
 */
public class UnifiedAgentBuilder<S> {
    private var providerConfig: ProviderConfig? = null
    private var promptStack: PromptStack = PromptStack.Empty
    private var toolRegistry: ToolRegistry = ToolRegistry.Empty
    private var phaseRegistry: PhaseRegistry = PhaseRegistry.Empty
    private var activePhaseName: String? = null
    private var stateStore: KoogStateStore<S>? = null
    private var stateSerializer: kotlinx.serialization.KSerializer<S>? = null
    private var eventHandlers: EventHandlers = EventHandlers.Empty
    private var config: KoogConfig = KoogConfig()
    private var contextualInstructions: List<ContextualInstruction> = emptyList()
    private var store: SessionStore = InMemorySessionStore()
    private var sessionConfig: KoogSessionConfig = KoogSessionConfig()
    private var layoutEngineConfig: LayoutEngineConfig? = null

    // Multi-agent fields
    private var mainAgentDefinition: KoogAgentDefinition? = null
    private val specialists = mutableMapOf<String, KoogAgentDefinition>()

    // ── Shared DSL blocks ─────────────────────────────────────────────────

    public fun provider(block: ProviderConfigBuilder.() -> Unit) {
        providerConfig = ProviderConfigBuilder().apply(block).build()
    }

    public fun prompt(block: PromptStack.Builder.() -> Unit) {
        promptStack = PromptStack(block)
    }

    public fun tools(block: ToolRegistry.Builder.() -> Unit) {
        toolRegistry = ToolRegistry(block)
    }

    public fun phases(block: PhaseRegistry.Builder.() -> Unit) {
        phaseRegistry = PhaseRegistry.Builder().apply(block).build()
        if (activePhaseName == null) {
            activePhaseName = phaseRegistry.all.firstOrNull()?.name
        }
    }

    public fun initialState(block: () -> S) {
        stateStore = KoogStateStore(block())
    }

    public fun initialPhase(name: String) {
        activePhaseName = name
    }

    public fun events(block: EventHandlers.Builder.() -> Unit) {
        eventHandlers = EventHandlers(block)
    }

    public fun config(block: KoogConfig.Builder.() -> Unit) {
        config = KoogConfig(block)
    }

    public fun contextual(block: ContextualInstructionsBuilder.() -> Unit) {
        contextualInstructions = ContextualInstructionsBuilder().apply(block).build()
    }

    /** Enables the agent-driven layout engine. See [KoogComposeContext.Builder.layout]. */
    public fun layout(block: LayoutEngineConfigBuilder.() -> Unit) {
        layoutEngineConfig = LayoutEngineConfigBuilder().apply(block).build()
    }

    // ── Multi-agent DSL blocks ────────────────────────────────────────────

    /**
     * Defines the main agent. Presence of this block switches the builder
     * into multi-agent mode.
     */
    public fun main(block: KoogAgentDefinitionBuilder.() -> Unit) {
        mainAgentDefinition = KoogAgentDefinitionBuilder("main").apply(block).build()
    }

    /**
     * Registers specialist agents for multi-agent handoff.
     */
    public fun agents(vararg definitions: KoogAgentDefinition) {
        definitions.forEach { specialists[it.name] = it }
    }

    public fun store(block: () -> SessionStore) {
        store = block()
    }

    public fun sessionConfig(block: KoogSessionConfig.Builder.() -> Unit) {
        sessionConfig = KoogSessionConfig.Builder().apply(block).build()
    }

    // ── Build ─────────────────────────────────────────────────────────────

    public fun build(): KoogDefinition<S> {
        val provider = requireNotNull(providerConfig) {
            "koog-compose: provider { } block is required."
        }

        return if (mainAgentDefinition != null) {
            // Multi-agent path
            KoogSession(
                globalProvider = provider,
                mainAgent = mainAgentDefinition!!,
                agentRegistry = specialists.toMap(),
                stateStore = stateStore,
                stateSerializer = stateSerializer,
                store = store,
                config = sessionConfig,
                eventHandlers = eventHandlers,
            )
        } else {
            // Single-agent path
            KoogComposeContext(
                providerConfig = provider,
                promptStack = promptStack,
                toolRegistry = toolRegistry,
                phaseRegistry = phaseRegistry,
                activePhaseName = activePhaseName,
                eventHandlers = eventHandlers,
                stateStore = stateStore,
                config = config,
                contextualInstructions = contextualInstructions,
                layoutEngineConfig = layoutEngineConfig,
            )
        }
    }
}

/**
 * DSL builder for [LayoutEngineConfig].
 *
 * ```kotlin
 * layout {
 *     workflowContext   = WorkflowContext(...)
 *     slotRegistry      = SlotRegistry(listOf(...))
 *     componentRegistry = ComponentRegistry(listOf(...))
 * }
 * ```
 */
public class LayoutEngineConfigBuilder {
    public var workflowContext: WorkflowContext? = null
    public var slotRegistry: SlotRegistry? = null
    public var componentRegistry: ComponentRegistry? = null
    public var policy: LayoutPolicy = LayoutPolicyChain.Empty

    public fun build(): LayoutEngineConfig = LayoutEngineConfig(
        workflowContext   = workflowContext
            ?: error("koog-compose layout { }: workflowContext is required"),
        slotRegistry      = slotRegistry
            ?: error("koog-compose layout { }: slotRegistry is required"),
        componentRegistry = componentRegistry
            ?: error("koog-compose layout { }: componentRegistry is required"),
        policy            = policy,
    )
}
