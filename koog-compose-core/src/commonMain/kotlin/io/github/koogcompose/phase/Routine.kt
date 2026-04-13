package io.github.koogcompose.phase

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.github.koogcompose.phase.Phase
import io.github.koogcompose.phase.toTool
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.toKoogTool

/**
 * Builds the concrete Koog tools for a [Phase] — its own tools plus transition tools.
 * Used by [PhaseStrategyBuilder] to scope each phase subgraph.
 */
internal fun Phase.buildKoogTools(): List<Tool<*, *>> =
    buildList {
        toolRegistry.all.forEach { add(it.toKoogTool()) }
        transitions.forEach { transition -> add(transition.toTool().toKoogTool()) }
    }

/**
 * A named, reusable agent strategy that wraps a pre-built [AIAgentGraphStrategy].
 *
 * Use [KoogRoutine] when you want to package a complete multi-phase flow as a
 * named unit that can be instantiated multiple times with different contexts —
 * e.g. an "onboarding" routine reused across multiple user sessions.
 *
 * ```kotlin
 * val onboardingRoutine = KoogRoutine("onboarding", myGraphStrategy)
 * val agent = onboardingRoutine.toAgent(context, executor)
 * ```
 */
public class KoogRoutine(
    public val id: String,
    private val graphStrategy: AIAgentGraphStrategy<String, String>
) {
    /**
     * Creates an [AIAgent] from this routine using [context] for configuration.
     *
     * Uses the initial phase's instructions as the system prompt if phases
     * are registered, otherwise falls back to the global effective instructions.
     *
     * Tool registration mirrors [PhaseAwareAgent]: global tools + all phase tools
     * + all transition tools are registered so the LLM can call them regardless
     * of the current phase scope.
     */
    public fun toAgent(
        context: KoogComposeContext<*>,
        promptExecutor: PromptExecutor
    ): AIAgent<String, String> {
        val provider = context.provider as? KoogAIProvider<*>
            ?: error("koog-compose: KoogRoutine '$id' requires a KoogAIProvider.")

        // Use initial phase instructions if phases exist,
        // otherwise fall back to global instructions.
        val systemPrompt = context.phaseRegistry.initialPhase?.resolvedInstructions
            ?: context.resolveEffectiveInstructions()

        val agentConfig = AIAgentConfig(
            prompt = prompt("koog-compose-routine-$id") {
                system(systemPrompt)
            },
            model = provider.resolveModelForConfig(),
            maxAgentIterations = context.config.maxAgentIterations,
            missingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(
                ToolCallDescriber.JSON
            ),
            serializer = KotlinxSerializer()
        )

        // Only session-global tools live on the agent-level registry.
        // Phase-local and transition tools are scoped on the graph subgraphs.
        val toolRegistry = KoogToolRegistry {
            context.toolRegistry.all.forEach { tool(it.toKoogTool()) }
        }

        return AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = graphStrategy,
            toolRegistry = toolRegistry
        )
    }
}
