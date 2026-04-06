package io.github.koogcompose.phase

import ai.koog.agents.core.agent.entity.AIAgentSubgraph
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import io.github.koogcompose.session.KoogComposeContext

/**
 * Builds the Koog graph strategy that mediates between phase definitions and the agent runtime.
 *
 * This remains the graph middleman for koog-compose. The current branch keeps the phase routing
 * and tool loop intact while avoiding platform-specific atomics and incomplete prompt-session APIs.
 */
internal object PhaseStrategyBuilder {

    internal fun build(
        context: KoogComposeContext<*>,
        strategyName: String = "koog-compose-phase-strategy"
    ): AIAgentGraphStrategy<String, String> {
        val phases = context.phaseRegistry.all
        require(phases.isNotEmpty()) {
            "koog-compose: phases { } block is empty. Define at least one phase."
        }

        return strategy<String, String>(strategyName) {
            var pendingPhaseName: String? =
                context.activePhaseName ?: context.phaseRegistry.initialPhase?.name

            val phaseSubgraphs = linkedMapOf<String, AIAgentSubgraph<String, String>>()

            phases.forEach { phase ->
                val phaseTools = phase.buildKoogTools()

                val phaseSubgraph by subgraph<String, String>(
                    name = phase.name,
                    tools = phaseTools
                ) {
                    val nodeCallLLM by nodeLLMRequest("${phase.name}_llm")
                    val nodeExecuteTool by nodeExecuteTool("${phase.name}_exec")
                    val nodeSendToolResult by nodeLLMSendToolResult("${phase.name}_result")

                    fun captureTransition(toolName: String): Boolean {
                        val transition = phase.transitions.firstOrNull { candidate ->
                            candidate.toolName == toolName
                        }
                        if (transition != null) {
                            pendingPhaseName = transition.targetPhase
                        }
                        return true
                    }

                    edge(nodeStart forwardTo nodeCallLLM)
                    edge(
                        (nodeCallLLM forwardTo nodeFinish)
                            .onAssistantMessage { true }
                    )
                    edge(
                        (nodeCallLLM forwardTo nodeExecuteTool)
                            .onToolCall { call -> captureTransition(call.tool) }
                    )
                    edge(nodeExecuteTool forwardTo nodeSendToolResult)
                    edge(
                        (nodeSendToolResult forwardTo nodeFinish)
                            .onAssistantMessage { true }
                    )
                    edge(
                        (nodeSendToolResult forwardTo nodeExecuteTool)
                            .onToolCall { call -> captureTransition(call.tool) }
                    )
                }

                phaseSubgraphs[phase.name] = phaseSubgraph
            }

            val router by node<String, String>("phase_router") { input ->
                input
            }

            val initialPhaseName = pendingPhaseName ?: phases.first().name
            val initialSubgraph = phaseSubgraphs[initialPhaseName]
                ?: error("koog-compose: Initial phase '$initialPhaseName' not found in registry.")

            edge(nodeStart forwardTo initialSubgraph)
            edge(
                (initialSubgraph forwardTo router)
                    .onCondition {
                        pendingPhaseName != null && pendingPhaseName != initialPhaseName
                    }
            )
            edge(
                (initialSubgraph forwardTo nodeFinish)
                    .onCondition {
                        pendingPhaseName == null || pendingPhaseName == initialPhaseName
                    }
            )

            phaseSubgraphs.forEach { (phaseName, subgraph) ->
                if (phaseName != initialPhaseName) {
                    edge(
                        (router forwardTo subgraph)
                            .onCondition { pendingPhaseName == phaseName }
                    )
                    edge(
                        (subgraph forwardTo router)
                            .onCondition {
                                pendingPhaseName != null && pendingPhaseName != phaseName
                            }
                    )
                    edge(
                        (subgraph forwardTo nodeFinish)
                            .onCondition {
                                pendingPhaseName == null || pendingPhaseName == phaseName
                            }
                    )
                }
            }
        }
    }
}
