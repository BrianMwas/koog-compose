package io.github.koogcompose.phase

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.FilePersistenceStorageProvider
import ai.koog.agents.snapshot.providers.filters.AgentCheckpointPredicateFilter
import ai.koog.prompt.dsl.prompt
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.event.installKoogEventHandlers
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.session.CompressionTrigger
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.SessionStore
import io.github.koogcompose.session.SessionStoreChatHistoryProvider
import io.github.koogcompose.session.StreamingFeature
import io.github.koogcompose.tool.toKoogTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path

/**
 * Creates a single [AIAgent] whose strategy is the multi-phase subgraph pipeline
 * built by [PhaseStrategyBuilder].
 *
 * Four features are installed on every agent using Koog's install-feature pattern:
 *
 * 1. **[ChatMemory]** — owns conversation history. Loads history before each turn
 *    and saves it after via [SessionStoreChatHistoryProvider], which delegates to
 *    the caller's [SessionStore]. This replaces the manual `messageHistory` list
 *    that previously lived in [PhaseSession] and [SessionRunner].
 *
 * 2. **[StreamingFeature]** — intercepts [StreamFrame.TextDelta] events and emits
 *    tokens to [tokenSink] for real-time UI streaming. Now implements all three
 *    Koog feature interfaces (Graph, Functional, Planner).
 *
 * 3. **Koog [EventHandler]** via [KoogEventHandlerBridge] — maps Koog's native
 *    lifecycle callbacks (tool calls, agent completion, failures) to koog-compose's
 *    [KoogEvent] sealed hierarchy. Installed as a proper feature, not via direct
 *    handleEvents wiring. Previously [EventHandlers] was never called by any Koog hook.
 *
 * 4. **[Persistence]** — captures complete agent state (message history, current
 *    node, input data, timestamps) at each execution point. Supports rollback to
 *    previous checkpoints via Koog's snapshot API.
 *    **Disabled by default** — pass [persistenceStorage] to opt-in for crash recovery.
 *    All data stays on the user's device.
 *
 * Call `agent.run(userMessage, sessionId)` — the two-arg overload is required
 * for [ChatMemory] to scope history correctly per session.
 */
public object PhaseAwareAgent {

    public fun <S> create(
        context: KoogComposeContext<S>,
        promptExecutor: PromptExecutor,
        sessionId: String,
        store: SessionStore,
        strategyName: String = "koog-compose-phases",
        tokenSink: MutableSharedFlow<String>? = null,
        eventHandlers: EventHandlers = EventHandlers.Empty,
        currentTurnId: () -> String = { "0" },
        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        persistenceStorage: FilePersistenceStorageProvider<Path>? = null,
    ): AIAgent<String, String> {

        // Resolve [ToolName] refs in all phase instructions before building the strategy.
        val resolvedContext = context.copy(
            phaseRegistry = context.phaseRegistry.resolveToolRefs(context.toolRegistry)
        )

        val provider = resolvedContext.provider as? KoogAIProvider<*>
            ?: error(
                "koog-compose: PhaseAwareAgent requires a KoogAIProvider. " +
                        "Make sure you called provider { } in your koogCompose { } block."
            )

        val llmModel = provider.resolveModelForConfig()
        val strategy = PhaseStrategyBuilder.build(resolvedContext, strategyName)

        // Agent-level registry: all tools from all phases + transitions.
        val globalKoogRegistry = KoogToolRegistry {
            resolvedContext.toolRegistry.all.forEach { tool(it.toKoogTool()) }
            resolvedContext.phaseRegistry.all.forEach { phase ->
                phase.toolRegistry.all.forEach { tool(it.toKoogTool()) }
                phase.transitions.forEach { transition ->
                    tool(transition.toTool().toKoogTool())
                }
            }
        }

        val initialPhase = resolvedContext.phaseRegistry.initialPhase
            ?: error("koog-compose: No phases registered. Add at least one phase { } block.")

        val agentConfig = AIAgentConfig(
            prompt = prompt("koog-compose-session") {
                system(initialPhase.resolvedInstructions)
            },
            model = llmModel,
            maxAgentIterations = resolvedContext.config.maxAgentIterations,
            missingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(
                ToolCallDescriber.JSON
            ),
            serializer = KotlinxSerializer()
        )

        // Build the ChatHistoryProvider that delegates to our SessionStore.
        val historyProvider = SessionStoreChatHistoryProvider(
            store = store,
            sessionId = sessionId,
        )

        // Resolve ChatMemory window size from compression config if present.
        // AfterMessages(n) → window of n messages. Otherwise use a safe default.
        val compressionConfig = resolvedContext.config.historyCompression
        val chatMemoryWindowSize: Int? = when (val trigger = compressionConfig?.trigger) {
            is CompressionTrigger.AfterMessages -> trigger.messageCount
            is CompressionTrigger.Both -> trigger.messageCount
            else -> null
        }

        return AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = globalKoogRegistry,
            installFeatures = {

                // ── 1. ChatMemory — owns LLM history, scoped per sessionId ────────
                install(ChatMemory) {
                    chatHistoryProvider = historyProvider
                    if (chatMemoryWindowSize != null) {
                        windowSize(chatMemoryWindowSize)
                    }
                }

                // ── 2. Streaming — token-by-token emission to UI ─────────────────
                if (tokenSink != null) {
                    install(StreamingFeature) {
                        this.tokenSink = tokenSink
                        this.coroutineScope = coroutineScope
                    }
                }

                // ── 3. EventHandler — Koog lifecycle → KoogEvent dispatch ─────────
                // Installed via Koog's EventHandler feature, mapping callbacks to
                // koog-compose's [KoogEvent] sealed hierarchy.
                if (eventHandlers !== EventHandlers.Empty) {
                    install(EventHandler) {
                        installKoogEventHandlers(
                            eventHandlers = eventHandlers,
                            phaseName = {
                                resolvedContext.activePhaseName
                                    ?: resolvedContext.phaseRegistry.initialPhase?.name
                            },
                            turnIdProvider = currentTurnId,
                        )
                    }
                }

                // ── 4. Persistence — full agent state checkpoints (OPT-IN) ───────
                // Disabled by default. Pass persistenceStorage to enable crash recovery.
                // When enabled, captures message history, current node, input data,
                // and timestamps at each execution point.
                if (persistenceStorage != null) {
                    install(Persistence) {
                        storage = persistenceStorage
                        enableAutomaticPersistence = true
                    }
                }
            }
        )
    }

    /**
     * Enable file-based persistence for crash recovery.
     *
     * Pass a fully-constructed [FilePersistenceStorageProvider] to persist
     * agent checkpoints to disk. All data stays on the user's device.
     *
     * ```kotlin
     * PhaseAwareAgent.create(
     *     context = ctx,
     *     promptExecutor = executor,
     *     sessionId = "session-1",
     *     store = store,
     *     persistenceStorage = myFilePersistenceProvider,  // opt-in
     * )
     * ```
     */
    public fun withPersistence(
        provider: FilePersistenceStorageProvider<AgentCheckpointPredicateFilter>,
    ): PhaseAwareAgentCreateConfig =
        PhaseAwareAgentCreateConfig(persistenceStorage = provider)
}

/**
 * Configuration object for [PhaseAwareAgent.create].
 * Returned by [PhaseAwareAgent.withPersistence] for fluent chaining.
 */
public class PhaseAwareAgentCreateConfig internal constructor(
    internal val persistenceStorage: FilePersistenceStorageProvider<AgentCheckpointPredicateFilter>? = null
)