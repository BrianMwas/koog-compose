package io.github.koogcompose.phase

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.toKoogTool


/**
 * Bridge between Koog-Compose Phases and Koog's core Graph AIAgent.
 * This allows a Phase to be executed as a Koog Graph node.
 */
internal fun Phase.toKoogAgent(
    context: KoogComposeContext,
    promptExecutor: PromptExecutor
): AIAgent<String, String> {
    val koogTools = ai.koog.agents.core.tools.ToolRegistry {
        toolRegistry.all.forEach { tool ->
            tool(tool.toKoogTool())
        }
        transitions.forEach { transition ->
            tool(transition.toTool().toKoogTool())
        }
    }

    return AIAgent(
        promptExecutor = promptExecutor,
        llmModel = context.createProvider().let { (it as? io.github.koogcompose.provider.KoogAIProvider)?.resolveModelForConfig() ?: error("Unsupported provider") },
        systemPrompt = instructions,
        toolRegistry = koogTools
    )
}

