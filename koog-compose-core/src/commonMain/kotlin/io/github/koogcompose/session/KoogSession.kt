package io.github.koogcompose.session

import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderConfigBuilder
import io.github.koogcompose.tool.ToolRegistry
import kotlinx.serialization.KSerializer

/**
 * Multi-agent session definition.
 *
 * Declared once, typically as a top-level or remembered value. Passed to
 * [multiAgentHandle] to create a live [SessionRunner] runtime.
 */
public data class KoogSession<S>(
    val globalProvider: ProviderConfig,
    val mainAgent: KoogAgentDefinition,
    val agentRegistry: Map<String, KoogAgentDefinition>,
    val stateStore: KoogStateStore<S>?,
    val stateSerializer: KSerializer<S>? = null,
    val store: SessionStore = InMemorySessionStore(),
    val config: KoogSessionConfig = KoogSessionConfig(),
    val eventHandlers: EventHandlers = EventHandlers.Empty,
) {
    public fun resolveProviderFor(agent: KoogAgentDefinition): ProviderConfig =
        agent.provider ?: globalProvider

    public fun findAgent(name: String): KoogAgentDefinition? = agentRegistry[name]

    /**
     * Builds a [KoogComposeContext] for [agent].
     *
     * [KoogComposeContext] is a data class — we use [copy] directly with the
     * agent's own registries and the session's shared state. No builder needed.
     *
     * Resolution:
     * - providerConfig → agent-level override if present, else session globalProvider
     * - toolRegistry   → agent's own tools
     * - phaseRegistry  → agent's own phases
     * - activePhaseName → agent's initial phase (first phase marked initial=true, else first)
     * - stateStore     → shared across all agents in the session (same instance)
     * - config         → session-level KoogConfig
     * - promptStack    → empty per agent (instructions live on phases)
     * - eventHandlers  → empty here; session-level handlers are merged in SessionRunner.buildAgent()
     */
    public fun contextFor(agent: KoogAgentDefinition): KoogComposeContext<S> =
        KoogComposeContext(
            providerConfig  = resolveProviderFor(agent),
            promptStack     = PromptStack.Empty,
            toolRegistry    = agent.toolRegistry,
            phaseRegistry   = agent.phaseRegistry,
            activePhaseName = agent.phaseRegistry.initialPhase?.name,
            eventHandlers   = EventHandlers.Empty,
            stateStore      = stateStore,
            config          = config.toKoogConfig(),
        )
}

// ── KoogSessionConfig ─────────────────────────────────────────────────────────

public data class KoogSessionConfig(
    val maxAgentIterations: Int = 15,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val guardrails: io.github.koogcompose.security.Guardrails =
        io.github.koogcompose.security.Guardrails.Default,
    val streamingEnabled: Boolean = true,
    val auditLoggingEnabled: Boolean = true,
) {
    public fun toKoogConfig(): KoogConfig = KoogConfig(
        streamingEnabled                = streamingEnabled,
        rateLimitPerMinute              = 0,
        auditLoggingEnabled             = auditLoggingEnabled,
        requireConfirmationForSensitive = true,
        historyCompression              = null,
        retryPolicy                     = retryPolicy,
        llmParams                       = null,
        responseCache                   = false,
        structureFixingRetries          = 3,
        maxAgentIterations              = maxAgentIterations,
        guardrails                      = guardrails,
        stuckDetection                  = null,
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
            maxAgentIterations  = maxAgentIterations,
            retryPolicy         = retryPolicy,
            guardrails          = guardrails,
            streamingEnabled    = streamingEnabled,
            auditLoggingEnabled = auditLoggingEnabled,
        )
    }
}

// ── DSL entry point ───────────────────────────────────────────────────────────

// Unified entry point — type is inferred from the builder context.
// S : Any? covers both stateless (Unit) and stateful (data class) usage.
public fun <S : Any?> koogSession(
    block: KoogSessionBuilder<S>.() -> Unit,
): KoogSession<S> = KoogSessionBuilder<S>().apply(block).build()

// ── KoogSessionBuilder ────────────────────────────────────────────────────────

public class KoogSessionBuilder<S> {
    private var globalProvider: ProviderConfig? = null
    private var mainAgentDefinition: KoogAgentDefinition? = null
    private val specialists = mutableMapOf<String, KoogAgentDefinition>()
    private var stateStore: KoogStateStore<S>? = null
    private var stateSerializer: KSerializer<S>? = null
    private var store: SessionStore = InMemorySessionStore()
    private var config: KoogSessionConfig = KoogSessionConfig()
    private var eventHandlers: EventHandlers = EventHandlers.Empty

    public fun provider(block: ProviderConfigBuilder.() -> Unit) {
        globalProvider = ProviderConfigBuilder().apply(block).build()
    }

    public fun state(serializer: KSerializer<S>, block: () -> S) {
        stateStore = KoogStateStore(block())
        stateSerializer = serializer
    }

    public fun events(block: EventHandlers.Builder.() -> Unit) {
        eventHandlers = EventHandlers(block)
    }

    public fun main(block: KoogAgentDefinitionBuilder.() -> Unit) {
        mainAgentDefinition = KoogAgentDefinitionBuilder("main").apply(block).build()
    }

    public fun store(block: () -> SessionStore) {
        store = block()
    }

    public fun config(block: KoogSessionConfig.Builder.() -> Unit) {
        config = KoogSessionConfig.Builder().apply(block).build()
    }

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
            globalProvider  = provider,
            mainAgent       = main,
            agentRegistry   = specialists.toMap(),
            stateStore      = stateStore,
            stateSerializer = stateSerializer,
            store           = store,
            config          = config,
            eventHandlers   = eventHandlers,
        )
    }
}