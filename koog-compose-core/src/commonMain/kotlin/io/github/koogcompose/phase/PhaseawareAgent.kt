package io.github.koogcompose.phase

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.provider.toKoogTool
import io.github.koogcompose.session.KoogComposeContext

/**
 * Creates a single [AIAgent] whose strategy is the multi-phase subgraph pipeline
 * built by [PhaseStrategyBuilder].
 *
 * One agent, one PromptExecutor session — all phases share the same LLM history.
 * Phase transitions update the system prompt in-session; the history is preserved.
 *
 * Usage:
 * ```kotlin
 * val agent = PhaseAwareAgent.create(context, promptExecutor)
 * val reply = agent.run("I want to send 500 KES to John")
 * ```
 */
object PhaseAwareAgent {

    fun create(
        context: KoogComposeContext,
        promptExecutor: PromptExecutor,
        strategyName: String = "koog-compose-phases"
    ): AIAgent<String, String> {

        val provider = context.createProvider() as? KoogAIProvider
            ?: error("koog-compose: PhaseAwareAgent requires a KoogAIProvider.")

        val llmModel = provider.resolveModelForConfig()

        // Resolve [ToolName] refs in all phase instructions against the global registry
        // before building the strategy, so subgraphs get the expanded instructions.
        val resolvedContext = context.copy(
            phaseRegistry = context.phaseRegistry.resolveToolRefs(context.toolRegistry)
        )

        val strategy = PhaseStrategyBuilder.build(resolvedContext, strategyName)

        // Register all tools at the agent level so Koog can resolve tool calls
        // that appear in shared history even when the current phase doesn't list them.
        val globalKoogRegistry = KoogToolRegistry {
            // Global tools
            context.toolRegistry.all.forEach { tool(it.toKoogTool()) }
            // Phase-scoped tools + transition tools
            context.phaseRegistry.all.forEach { phase ->
                phase.toolRegistry.all.forEach { tool(it.toKoogTool()) }
                phase.transitions.forEach { tool(it.toTool().toKoogTool()) }
            }
        }

        val agentConfig = AIAgentConfig(
            prompt = prompt("koog-compose-session") {
                system(resolvedContext.resolveEffectiveInstructions())
            },
            model = llmModel
        )

        return AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = globalKoogRegistry
        )
    }
}