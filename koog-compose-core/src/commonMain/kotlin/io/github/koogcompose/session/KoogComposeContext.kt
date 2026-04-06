package io.github.koogcompose.session

import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.phase.Phase
import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.phase.toTool
import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.security.Guardrails
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolRegistry
import kotlin.jvm.JvmName


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
    /** Initial delay in ms, doubles on each retry. */
    val initialDelayMs: Long = 500L,
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
    val guardrails: Guardrails = Guardrails.Default
) {


    public class Builder {
        public var streamingEnabled: Boolean = true
        public var rateLimitPerMinute: Int = 0
        public var auditLoggingEnabled: Boolean = true
        public var requireConfirmationForSensitive: Boolean = true
        public var responseCache: Boolean = false
        public var structureFixingRetries: Int = 3

        public var maxAgentIterations: Int = 10
        
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
            guardrails = guardrails
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
    public var useStructureFixingParser: Boolean = true
    public var structureFixingRetries: Int = 3

    public fun build(): RetryPolicy = RetryPolicy(maxAttempts, initialDelayMs, useStructureFixingParser, structureFixingRetries)
}

public class LLMParamsConfigBuilder {
    public var temperature: Double? = null
    public var maxTokens: Int? = null
    public var topP: Double? = null
    public var topK: Int? = null
    public var stopSequences: List<String> = emptyList()

    public fun build(): LLMParamsConfig = LLMParamsConfig(temperature, maxTokens, topP, topK, stopSequences)
}


// ── KoogComposeContext ────────────────────────────────────────────────────────

/**
 * The central runtime object for koog-compose.
 */
public data class KoogComposeContext<S>(
    val providerConfig: ProviderConfig,
    val promptStack: PromptStack,
    val toolRegistry: ToolRegistry,
    val phaseRegistry: PhaseRegistry = PhaseRegistry.Empty,
    val activePhaseName: String? = null,
    val eventHandlers: EventHandlers = EventHandlers.Empty,
    val stateStore: KoogStateStore<S>?,          // ← typed, not <*>
    val config: KoogConfig,
) {
    public fun createProvider(): AIProvider = KoogAIProvider(this)
    public val provider: AIProvider
        get() = createProvider()

    public val activePhase: Phase? get() = activePhaseName?.let { phaseRegistry.resolve(it) }

    public fun withSessionContext(context: String): KoogComposeContext<S> = copy(
        promptStack = promptStack.withSessionContext(context)
    )

    public fun withTool(tool: SecureTool): KoogComposeContext<S> = copy(
        toolRegistry = toolRegistry.plus(tool)
    )

    public fun withPhase(name: String): KoogComposeContext<S> = copy(activePhaseName = name)

    public fun resolveEffectiveInstructions(): String {
        val globalPrompt = promptStack.resolve().trim()
        val phase = activePhase ?: phaseRegistry.initialPhase
        if (phase == null) {
            return globalPrompt
        }

        return buildList {
            if (globalPrompt.isNotBlank()) {
                add(globalPrompt)
            }
            add("CURRENT PHASE: ${phase.name}")
            if (phase.resolvedInstructions.isNotBlank()) {
                add(phase.resolvedInstructions.trim())
            }
        }.joinToString(separator = "\n\n")
    }

    public fun resolveEffectiveTools(): List<SecureTool> {
        val phase = activePhase ?: phaseRegistry.initialPhase
        val baseTools = phase?.toolRegistry?.all ?: toolRegistry.all
        val transitionTools = phase?.transitions?.map { it.toTool() } ?: emptyList()
        return baseTools + transitionTools
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

        public fun provider(block: ProviderConfigBuilder.() -> Unit): Unit {
            providerConfig = ProviderConfigBuilder().apply(block).build()
        }

        public fun prompt(block: PromptStack.Builder.() -> Unit): Unit {
            promptStack = PromptStack(block)
        }

        public fun tools(block: ToolRegistry.Builder.() -> Unit): Unit {
            toolRegistry = ToolRegistry(block)
        }

        public fun phases(block: PhaseRegistry.Builder.() -> Unit): Unit {
            phaseRegistry = PhaseRegistry.Builder().apply(block).build()
            if (activePhaseName == null) {
                activePhaseName = phaseRegistry.all.firstOrNull()?.name
            }
        }

        public fun initialState(block: () -> S): Unit {
            stateStore = KoogStateStore(block())
        }

        public fun initialPhase(name: String): Unit {
            activePhaseName = name
        }

        public fun events(block: EventHandlers.Builder.() -> Unit): Unit {
            eventHandlers = EventHandlers(block)
        }

        public fun config(block: KoogConfig.Builder.() -> Unit): Unit {
            config = KoogConfig(block)
        }

        public fun build(): KoogComposeContext<S> = KoogComposeContext(
            providerConfig = providerConfig
                ?: error("koog-compose: provider { } block is required."),
            promptStack = promptStack,
            toolRegistry = toolRegistry,
            phaseRegistry = phaseRegistry,
            activePhaseName = activePhaseName,
            eventHandlers = eventHandlers,
            stateStore = stateStore,
            config = config
        )
    }

    public companion object {
        public operator fun <S> invoke(block: Builder<S>.() -> Unit): KoogComposeContext<S> =
            Builder<S>().apply(block).build()
    }
}

// Stateless sessions (no shared state needed)
@JvmName("koogComposeStateless")
public fun koogCompose(block: KoogComposeContext.Builder<Unit>.() -> Unit): KoogComposeContext<Unit> =
    KoogComposeContext(block)

// Stateful sessions — infer S from the initialState { } block
@JvmName("koogComposeStateful")
public fun <S> koogCompose(block: KoogComposeContext.Builder<S>.() -> Unit): KoogComposeContext<S> =
    KoogComposeContext(block)
