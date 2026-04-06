package io.github.koogcompose.phase

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.StreamingFeature
import io.github.koogcompose.tool.toKoogTool
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Creates a single [AIAgent] whose strategy is the multi-phase subgraph pipeline
 * built by [PhaseStrategyBuilder].
 *
 * One agent, one PromptExecutor session — all phases share the same LLM history.
 * Phase transitions update the system prompt in-session via the router node;
 * the full message history is preserved across transitions.
 *
 * Usage:
 * ```kotlin
 * val agent = PhaseAwareAgent.create(context, promptExecutor)
 * val reply = agent.run("I want to send 500 KES to John")
 * ```
 */
public object PhaseAwareAgent {

    public fun <S> create(
        context: KoogComposeContext<S>,
        promptExecutor: PromptExecutor,
        strategyName: String = "koog-compose-phases",
        tokenSink: MutableSharedFlow<String>? = null
    ): AIAgent<String, String> {

        // Resolve [ToolName] refs in all phase instructions before building
        // the strategy, so subgraphs receive expanded instructions.
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
        // Koog needs this to resolve tool calls that appear in shared history
        // even when the current phase doesn't list them.
        val globalKoogRegistry = KoogToolRegistry {
            resolvedContext.toolRegistry.all.forEach { tool(it.toKoogTool()) }
            resolvedContext.phaseRegistry.all.forEach { phase ->
                phase.toolRegistry.all.forEach { tool(it.toKoogTool()) }
                phase.transitions.forEach { transition ->
                    tool(transition.toTool().toKoogTool())
                }
            }
        }

        // Use the initial phase's instructions as the opening system prompt.
        // The router node will update this as transitions occur.
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

        return AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = globalKoogRegistry,
            installFeatures = {
                if (tokenSink != null) {
                    install(StreamingFeature) {
                        this.tokenSink = tokenSink
                    }
                }
            }
        )
    }
}
