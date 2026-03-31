package io.github.koogcompose.phase

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.toKoogTool

/**
 * Builds a [KoogToolRegistry] from a [Phase] — its own tools plus transition tools.
 *
 * This is the per-subgraph tool set passed to `subgraph(tools = ...)` in
 * [PhaseStrategyBuilder]. Not the same as the agent-level global registry.
 */
internal fun Phase.buildKoogToolRegistry(): KoogToolRegistry =
    KoogToolRegistry {
        toolRegistry.all.forEach { tool(it.toKoogTool()) }
        transitions.forEach { tool(it.toTool().toKoogTool()) }
    }

/**
 * A named, reusable agent strategy that wraps a pre-built [AIAgentGraphStrategy].
 *
 * Use [KoogRoutine] when you want to package a complete multi-phase flow as a
 * named unit that can be instantiated multiple times with different contexts.
 *
 * ```kotlin
 * val onboardingRoutine = KoogRoutine("onboarding", myGraphStrategy)
 * val agent = onboardingRoutine.toAgent(context, executor)
 * ```
 */
class KoogRoutine(
    val id: String,
    private val graphStrategy: AIAgentGraphStrategy<String, String>
) {
    /**
     * Creates a [AIAgent] from this routine using [context] for configuration.
     *
     * The returned agent uses the global tool registry from [context] and
     * the global system prompt as the initial instruction.
     */
    fun toAgent(
        context: KoogComposeContext,
        promptExecutor: PromptExecutor
    ): AIAgent<String, String> {
        val provider = context.createProvider() as? KoogAIProvider
            ?: error("koog-compose: KoogRoutine '$id' requires a KoogAIProvider.")

        val agentConfig = AIAgentConfig(
            prompt = prompt("koog-compose-routine-$id") {
                system(context.resolveEffectiveInstructions())
            },
            model = provider.resolveModelForConfig(),
            maxAgentIterations = context.config.structureFixingRetries.coerceAtLeast(3),
            missingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
            responseProcessor = null,
            serializer = KotlinxSerializer()
        )

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
