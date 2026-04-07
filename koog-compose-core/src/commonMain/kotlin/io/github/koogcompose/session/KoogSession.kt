package io.github.koogcompose.session

import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.tool.ToolRegistry
import kotlinx.serialization.KSerializer

/**
 * Multi-agent session definition.
 *
 * Declared once, typically as a top-level value. Passed to [SessionRunner]
 * (or `rememberChatState`) to create a live runtime.
 *
 * ```kotlin
 * val session = koogSession {
 *     provider {
 *         anthropic(apiKey = BuildConfig.ANTHROPIC_KEY) {
 *             model = "claude-3-5-sonnet"
 *         }
 *     }
 *     state { AppState() }
 *     main {
 *         phases {
 *             phase("root") {
 *                 instructions { "You are a productivity assistant." }
 *                 handoff(focusAgent)   { "User wants to focus or block apps" }
 *                 handoff(expenseAgent) { "User mentions spending or receipts" }
 *             }
 *         }
 *     }
 *     agents(focusAgent, expenseAgent)
 * }
 * ```
 *
 * @param S               Shared app state type. Use [Unit] for stateless sessions.
 * @param globalProvider  Session-level provider. Inherited by all agents without their own.
 * @param mainAgent       Root agent — always the entry point for user messages.
 * @param agentRegistry   Specialist agents, keyed by [KoogAgentDefinition.name].
 * @param stateStore      Shared observable state. Readable and writable by every agent's tools.
 * @param store           Persistence layer. Defaults to [InMemorySessionStore].
 * @param config          Runtime configuration (retries, guardrails, etc.).
 */
public data class KoogSession<S>(
    val globalProvider: ProviderConfig,
    val mainAgent: KoogAgentDefinition,
    val agentRegistry: Map<String, KoogAgentDefinition>,
    val stateStore: KoogStateStore<S>?,
    val stateSerializer: KSerializer<S>? = null,
    val store: SessionStore = InMemorySessionStore(),
    val config: KoogSessionConfig = KoogSessionConfig(),
) {
    /**
     * Resolves the [ProviderConfig] for [agent].
     *
     * Resolution order (highest → lowest priority):
     * 1. Agent-level provider (explicit override)
     * 2. Session-level global provider
     *
     * Phase-level provider override is handled inside [KoogComposeContext] during agent execution.
     */
    public fun resolveProviderFor(agent: KoogAgentDefinition): ProviderConfig =
        agent.provider ?: globalProvider

    /**
     * Looks up a registered specialist by name.
     * Returns null if the name is not in [agentRegistry].
     */
    public fun findAgent(name: String): KoogAgentDefinition? = agentRegistry[name]

    /**
     * Builds a [KoogComposeContext] for [agent], injecting the resolved provider
     * and shared state store. Called by [SessionRunner] when activating an agent.
     */
    public fun contextFor(agent: KoogAgentDefinition): KoogComposeContext<S> {
        val resolvedProvider = resolveProviderFor(agent)
        return KoogComposeContext.createInternal(
            providerConfig = resolvedProvider,
            promptStack = io.github.koogcompose.prompt.PromptStack.Empty,
            toolRegistry = agent.toolRegistry,
            phaseRegistry = agent.phaseRegistry,
            activePhaseName = agent.phaseRegistry.initialPhase?.name,
            stateStore = stateStore,
            stateSerializer = stateSerializer,
            config = config.toKoogConfig(),
        )
    }
}

// ── KoogSessionConfig ─────────────────────────────────────────────────────────

/**
 * Runtime configuration scoped to a [KoogSession].
 *
 * Mirrors [KoogConfig] but lives at the session level so it can be
 * applied uniformly to every agent the session activates.
 */
public data class KoogSessionConfig(
    val maxAgentIterations: Int = 15,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val guardrails: io.github.koogcompose.security.Guardrails =
        io.github.koogcompose.security.Guardrails.Default,
    val streamingEnabled: Boolean = true,
    val auditLoggingEnabled: Boolean = true,
) {
    public fun toKoogConfig(): KoogConfig = KoogConfig(
        streamingEnabled = streamingEnabled,
        rateLimitPerMinute = 0,
        auditLoggingEnabled = auditLoggingEnabled,
        requireConfirmationForSensitive = true,
        historyCompression = null,
        retryPolicy = retryPolicy,
        llmParams = null,
        responseCache = false,
        structureFixingRetries = 3,
        maxAgentIterations = maxAgentIterations,
        guardrails = guardrails,
    )

    public class Builder {
        public var maxAgentIterations: Int = 15
        public var streamingEnabled: Boolean = true
        public var auditLoggingEnabled: Boolean = true
        private var retryPolicy: RetryPolicy = RetryPolicy()
        private var guardrails: io.github.koogcompose.security.Guardrails =
            io.github.koogcompose.security.Guardrails.Default

        public fun retry(block: RetryPolicyBuilder.() -> Unit) {
            retryPolicy = RetryPolicyBuilder().apply(block).build()
        }

        public fun guardrails(block: io.github.koogcompose.security.Guardrails.Builder.() -> Unit) {
            guardrails = io.github.koogcompose.security.Guardrails.Builder().apply(block).build()
        }

        public fun build(): KoogSessionConfig = KoogSessionConfig(
            maxAgentIterations = maxAgentIterations,
            retryPolicy = retryPolicy,
            guardrails = guardrails,
            streamingEnabled = streamingEnabled,
            auditLoggingEnabled = auditLoggingEnabled,
        )
    }
}

// ── DSL entry point ───────────────────────────────────────────────────────────

/**
 * Builds a [KoogSession] using the multi-agent DSL.
 *
 * ```kotlin
 * val session = koogSession {
 *     provider { anthropic(apiKey = BuildConfig.KEY) }
 *     state { AppState() }
 *     main {
 *         phases {
 *             phase("root") {
 *                 instructions { "You are a productivity assistant." }
 *                 handoff(focusAgent)   { "User wants to focus" }
 *                 handoff(expenseAgent) { "User mentions spending" }
 *             }
 *         }
 *     }
 *     agents(focusAgent, expenseAgent)
 * }
 * ```
 */
public fun <S> koogSession(
    block: KoogSessionBuilder<S>.() -> Unit
): KoogSession<S> = KoogSessionBuilder<S>().apply(block).build()

/** Stateless convenience overload — infers [S] as [Unit]. */
public fun koogSession(
    block: KoogSessionBuilder<Unit>.() -> Unit
): KoogSession<Unit> = KoogSessionBuilder<Unit>().apply(block).build()

// ── KoogSessionBuilder ────────────────────────────────────────────────────────

public class KoogSessionBuilder<S> {
    private var globalProvider: ProviderConfig? = null
    private var mainAgentDefinition: KoogAgentDefinition? = null
    private val specialists = mutableMapOf<String, KoogAgentDefinition>()
    private var stateStore: KoogStateStore<S>? = null
    private var stateSerializer: KSerializer<S>? = null
    private var store: SessionStore = InMemorySessionStore()
    private var config: KoogSessionConfig = KoogSessionConfig()

    public fun provider(block: ProviderConfigBuilder.() -> Unit) {
        globalProvider = ProviderConfigBuilder().apply(block).build()
    }

    public fun state(serializer: KSerializer<S>, block: () -> S) {
        stateStore = KoogStateStore(block())
        stateSerializer = serializer
    }

    /**
     * Declares the root (main) agent.
     * Always the entry point for user messages. Defines handoff targets.
     *
     * The main agent implicitly inherits the session provider — you rarely
     * need a `provider { }` block inside `main { }`.
     *
     * ```kotlin
     * main {
     *     phases {
     *         phase("root") {
     *             instructions { "You are a productivity assistant." }
     *             handoff(focusAgent) { "User wants to focus" }
     *         }
     *     }
     * }
     * ```
     */
    public fun main(block: KoogAgentDefinitionBuilder.() -> Unit) {
        mainAgentDefinition = KoogAgentDefinitionBuilder("main").apply(block).build()
    }

    /**
     * Pluggable session persistence.
     *
     * ```kotlin
     * store { RoomSessionStore(db = AppDatabase.getInstance(context)) }
     * ```
     */
    public fun store(block: () -> SessionStore) {
        store = block()
    }

    /**
     * Session-level runtime configuration.
     *
     * ```kotlin
     * config {
     *     maxAgentIterations = 20
     *     retry { maxAttempts = 5 }
     * }
     * ```
     */
    public fun config(block: KoogSessionConfig.Builder.() -> Unit) {
        config = KoogSessionConfig.Builder().apply(block).build()
    }

    /**
     * Registers specialist agents available for handoff.
     *
     * ```kotlin
     * agents(focusAgent, expenseAgent, cameraAgent)
     * ```
     *
     * Or with the spread operator for grouped sets:
     * ```kotlin
     * agents(*productivityAgents, *wellbeingAgents)
     * ```
     */
    public fun agents(vararg definitions: KoogAgentDefinition) {
        definitions.forEach { specialists[it.name] = it }
    }

    public fun build(): KoogSession<S> {
        val provider = requireNotNull(globalProvider) {
            "koog-compose: provider { } block is required in koogSession { }."
        }
        val main = requireNotNull(mainAgentDefinition) {
            "koog-compose: main { } block is required in koogSession { }."
        }
        return KoogSession(
            globalProvider = provider,
            mainAgent = main,
            agentRegistry = specialists.toMap(),
            stateStore = stateStore,
            stateSerializer = stateSerializer,
            store = store,
            config = config,
        )
    }
}
