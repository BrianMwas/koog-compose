package io.github.koogcompose.session

import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolRegistry


/**
 * History compression strategy.
 * Maps directly to Koog's built-in HistoryCompressionStrategy types.
 */
sealed class HistoryCompression {

    /**
     * Summarises the entire history into one TLDR message.
     * Best for general use — maintains full context awareness
     * while drastically reducing token count.
     */
    object WholeHistory : HistoryCompression()

    /**
     * Keeps only the last [n] messages and discards everything older.
     * Best when only recent context matters.
     * Most aggressive — lowest token usage.
     */
    data class FromLastN(val n: Int) : HistoryCompression()

    /**
     * Splits history into chunks of [chunkSize] and compresses each independently.
     * Best when you need both recent detail and older progress summaries.
     */
    data class Chunked(val chunkSize: Int) : HistoryCompression()

    /**
     * Searches history for specific concepts and extracts only those facts.
     * Most powerful — the AI retrieves exactly what it needs to know.
     *
     * ```kotlin
     * RetrieveFactsFromHistory(
     *     Concept("user_preferences", "spending habits and budget goals"),
     *     Concept("account_state", "current balance and recent transactions"),
     *     Concept("issue_resolved", "was the user's question answered", FactType.SINGLE)
     * )
     * ```
     */
    data class RetrieveFactsFromHistory(
        val concepts: List<Concept>
    ) : HistoryCompression() {
        constructor(vararg concepts: Concept) : this(concepts.toList())
    }
}



/**
 * A concept to extract from history in [HistoryCompression.RetrieveFactsFromHistory].
 *
 * @param keyword Short identifier for this concept.
 * @param description What the AI should search for — be specific.
 * @param factType Whether to extract one fact or multiple.
 */
data class Concept(
    val keyword: String,
    val description: String,
    val factType: FactType = FactType.MULTIPLE
)

enum class FactType { SINGLE, MULTIPLE }


/**
 * When to trigger history compression.
 */
sealed class CompressionTrigger {
    /** Compress after the history reaches [messageCount] messages. */
    data class AfterMessages(val messageCount: Int) : CompressionTrigger()

    /** Compress between logical workflow phases (subgraph boundaries). */
    object BetweenPhases : CompressionTrigger()

    /** Compress both on message count and between phases. */
    data class Both(val messageCount: Int) : CompressionTrigger()
}

/**
 * Configuration for history compression.
 */
data class HistoryCompressionConfig(
    val strategy: HistoryCompression,
    val trigger: CompressionTrigger = CompressionTrigger.AfterMessages(40),
    val preserveMemory: Boolean = true
)

/**
 * Retry policy for failed AI requests.
 */
data class RetryPolicy(
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
data class LLMParamsConfig(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String> = emptyList()
)


/**
 * KoogConfig — runtime configuration for a [KoogComposeContext].
 *
 * Every option has a sensible default so you only configure what you need.
 *
 * Full example:
 * ```kotlin
 * koogCompose {
 *     config {
 *         // Rate limiting
 *         rateLimitPerMinute = 10
 *
 *         // History compression — critical for long-running agents
 *         historyCompression {
 *             strategy = HistoryCompression.RetrieveFactsFromHistory(
 *                 Concept("spending_patterns", "User's recurring expenses and habits"),
 *                 Concept("financial_goals", "User's savings targets and budget rules"),
 *                 Concept("issue_resolved", "Was the user's question answered", FactType.SINGLE)
 *             )
 *             trigger = CompressionTrigger.AfterMessages(40)
 *             preserveMemory = true
 *         }
 *
 *         // Retry logic
 *         retry {
 *             maxAttempts = 3
 *             initialDelayMs = 500
 *             useStructureFixingParser = true
 *         }
 *
 *         // LLM parameters
 *         llmParams {
 *             temperature = 0.7
 *             maxTokens = 4096
 *         }
 *
 *         // Security
 *         auditLoggingEnabled = true
 *         requireConfirmationForSensitive = true
 *     }
 * }
 * ```
 */
data class KoogConfig(
    val streamingEnabled: Boolean = true,
    val rateLimitPerMinute: Int = 0,
    val auditLoggingEnabled: Boolean = true,
    val requireConfirmationForSensitive: Boolean = true,
    val historyCompression: HistoryCompressionConfig? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val llmParams: LLMParamsConfig? = null,
    val responseCache: Boolean = false,
    // Structured output fixing — maps to Koog's StructureFixingParser
    val structureFixingRetries: Int = 3,
) {
    class Builder {
        var streamingEnabled: Boolean = true
        var rateLimitPerMinute: Int = 0
        var auditLoggingEnabled: Boolean = true
        var requireConfirmationForSensitive: Boolean = true
        var responseCache: Boolean = false
        private var historyCompression: HistoryCompressionConfig? = null
        private var retryPolicy: RetryPolicy = RetryPolicy()
        private var llmParams: LLMParamsConfig? = null

        var structureFixingRetries: Int = 3

        fun historyCompression(block: HistoryCompressionConfigBuilder.() -> Unit) {
            historyCompression = HistoryCompressionConfigBuilder().apply(block).build()
        }

        fun retry(block: RetryPolicyBuilder.() -> Unit) {
            retryPolicy = RetryPolicyBuilder().apply(block).build()
        }

        fun llmParams(block: LLMParamsConfigBuilder.() -> Unit) {
            llmParams = LLMParamsConfigBuilder().apply(block).build()
        }

        fun build() = KoogConfig(
            streamingEnabled = streamingEnabled,
            rateLimitPerMinute = rateLimitPerMinute,
            auditLoggingEnabled = auditLoggingEnabled,
            requireConfirmationForSensitive = requireConfirmationForSensitive,
            historyCompression = historyCompression,
            retryPolicy = retryPolicy,
            llmParams = llmParams,
            structureFixingRetries = structureFixingRetries,
            responseCache = responseCache
        )
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): KoogConfig =
            Builder().apply(block).build()
    }
}

class HistoryCompressionConfigBuilder {
    var strategy: HistoryCompression = HistoryCompression.WholeHistory
    var trigger: CompressionTrigger = CompressionTrigger.AfterMessages(40)
    var preserveMemory: Boolean = true

    fun build() = HistoryCompressionConfig(strategy, trigger, preserveMemory)
}

class RetryPolicyBuilder {
    var maxAttempts: Int = 3
    var initialDelayMs: Long = 500L
    var useStructureFixingParser: Boolean = true
    var structureFixingRetries: Int = 3

    fun build() = RetryPolicy(maxAttempts, initialDelayMs, useStructureFixingParser, structureFixingRetries)
}

class LLMParamsConfigBuilder {
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var topK: Int? = null
    var stopSequences: List<String> = emptyList()

    fun build() = LLMParamsConfig(temperature, maxTokens, topP, topK, stopSequences)
}


// ── KoogComposeContext ────────────────────────────────────────────────────────

/**
 * The central runtime object for koog-compose.
 * Built via the [koogCompose] DSL. Immutable.
 *
 * Full example:
 * ```kotlin
 * val context = koogCompose {
 *
 *     // Which LLM to use
 *     provider {
 *         anthropic(apiKey = BuildConfig.ANTHROPIC_KEY) {
 *             model = "claude-sonnet-4-5"
 *             temperature = 0.7
 *         }
 *     }
 *
 *     // What the AI knows and must follow
 *     prompt {
 *         enforce { "Never transfer funds without confirmation." }
 *         default { "You are a secure banking assistant." }
 *         session { "User balance: KES 12,500" }
 *     }
 *
 *     // What the AI can do
 *     tools {
 *         // Device tools from koog-compose-device
 *         register(GetLocationTool(locationEngine))
 *         register(SendNotificationTool(notificationEngine))
 *
 *         // Your own backend tools — just implement SecureTool
 *         register(GetTransactionsTool(retrofitApi))
 *         register(SendMoneyTool(retrofitApi))
 *     }
 *
 *     // Runtime behaviour
 *     config {
 *         rateLimitPerMinute = 10
 *         auditLoggingEnabled = true
 *         requireConfirmationForSensitive = true
 *         responseCache = true
 *
 *         historyCompression {
 *             strategy = HistoryCompression.RetrieveFactsFromHistory(
 *                 Concept("spending_patterns", "User's recurring expenses"),
 *                 Concept("issue_resolved", "Was the question answered", FactType.SINGLE)
 *             )
 *             trigger = CompressionTrigger.AfterMessages(40)
 *         }
 *
 *         llmParams {
 *             temperature = 0.7
 *             maxTokens = 4096
 *         }
 *     }
 * }
 * ```
 */
class KoogComposeContext private constructor(
    val providerConfig: ProviderConfig,
    val promptStack: PromptStack,
    val toolRegistry: ToolRegistry,
    val config: KoogConfig,
) {
    fun withSessionContext(context: String): KoogComposeContext = copy(
        promptStack = promptStack.withSessionContext(context)
    )

    fun withTool(tool: SecureTool): KoogComposeContext = copy(
        toolRegistry = toolRegistry.plus(tool)
    )

    private fun copy(
        providerConfig: ProviderConfig = this.providerConfig,
        promptStack: PromptStack = this.promptStack,
        toolRegistry: ToolRegistry = this.toolRegistry,
        config: KoogConfig = this.config,
    ) = KoogComposeContext(providerConfig, promptStack, toolRegistry, config)
    class Builder {
        private var providerConfig: ProviderConfig? = null
        private var promptStack: PromptStack = PromptStack.Empty
        private var toolRegistry: ToolRegistry = ToolRegistry.Empty
        private var config: KoogConfig = KoogConfig()

        fun provider(block: ProviderConfigBuilder.() -> Unit) {
            providerConfig = ProviderConfigBuilder().apply(block).build()
        }

        fun prompt(block: PromptStack.Builder.() -> Unit) {
            promptStack = PromptStack(block)
        }

        fun tools(block: ToolRegistry.Builder.() -> Unit) {
            toolRegistry = ToolRegistry(block)
        }

        fun config(block: KoogConfig.Builder.() -> Unit) {
            config = KoogConfig(block)
        }

        fun build(): KoogComposeContext = KoogComposeContext(
            providerConfig = providerConfig
                ?: error("koog-compose: provider { } block is required. Add anthropic(), openAI(), google(), or ollama() inside it."),
            promptStack = promptStack,
            toolRegistry = toolRegistry,
            config = config
        )
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): KoogComposeContext =
            Builder().apply(block).build()
    }
}

/**
 * Top-level DSL entry point.
 *
 * ```kotlin
 * val context = koogCompose {
 *     provider { anthropic(apiKey = "...") }
 *     prompt { default { "You are a helpful assistant." } }
 *     tools { register(MyTool()) }
 *     config { rateLimitPerMinute = 10 }
 * }
 * ```
 */
fun koogCompose(block: KoogComposeContext.Builder.() -> Unit): KoogComposeContext =
    KoogComposeContext(block)