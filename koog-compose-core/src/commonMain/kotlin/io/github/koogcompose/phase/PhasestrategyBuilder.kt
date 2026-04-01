package io.github.koogcompose.phase

import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import io.github.koogcompose.session.CompressionTrigger
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.toKoogTool
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.atomics.AtomicReference

internal object PhaseStrategyBuilder {

    fun build(
        context: KoogComposeContext,
        strategyName: String = "koog-compose-phase-strategy"
    ): AIAgentGraphStrategy<String, String> {

        val phases = context.phaseRegistry.all
        require(phases.isNotEmpty()) {
            "koog-compose: phases { } block is empty. Define at least one phase."
        }

        return strategy<String, String>(strategyName) {

            // ── Thread-safe routing state ──────────────────────────────────
            // AtomicReference prevents race conditions when subgraph closures
            // read/write pendingPhase concurrently across coroutines.
            val pendingPhase = AtomicReference<String?>(context.activePhaseName)

            // ── Build one subgraph per Phase ───────────────────────────────
            val phaseSubgraphs = phases.associate { phase ->
                val phaseTools = phase.buildKoogToolRegistry()

                val subgraph = subgraph<String, String>(
                    name = phase.name,
                    tools = phaseTools
                ) {
                    val nodeCallLLM by nodeLLMRequest("${phase.name}_llm")
                    val nodeExecuteTool by nodeExecuteTool("${phase.name}_exec")
                    val nodeSendToolResult by nodeLLMSendToolResult("${phase.name}_result")

                    val compressionConfig = context.config.historyCompression
                    val compressInPhase = compressionConfig != null &&
                            (compressionConfig.trigger is CompressionTrigger.WithinPhase ||
                                    compressionConfig.trigger is CompressionTrigger.Both)

                    // Helper: intercept tool calls and capture transition intent
                    fun captureTransition(toolName: String): Boolean {
                        val transition = phase.transitions.find { it.toolName == toolName }
                        if (transition != null) {
                            pendingPhase.set(transition.targetPhase)
                        }
                        return true
                    }

                    if (compressInPhase && compressionConfig != null) {
                        val nodeCompress by nodeLLMCompressHistory<String>(
                            name = "${phase.name}_compress",
                            strategy = compressionConfig.strategy.toKoogStrategy()
                        )

                        edge(nodeStart forwardTo nodeCallLLM)

                        edge(
                            (nodeCallLLM forwardTo nodeFinish)
                                .onAssistantMessage { true }
                        )
                        edge(
                            (nodeCallLLM forwardTo nodeExecuteTool)
                                .onToolCall { call -> captureTransition(call.tool) }
                        )

                        edge(nodeExecuteTool forwardTo nodeCompress)
                        edge(nodeCompress forwardTo nodeCallLLM)

                        edge(
                            (nodeSendToolResult forwardTo nodeFinish)
                                .onAssistantMessage { true }
                        )
                        edge(
                            (nodeSendToolResult forwardTo nodeExecuteTool)
                                .onToolCall { call -> captureTransition(call.tool) }
                        )

                    } else {
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
                }

                phase.name to subgraph
            }

            // ── Router node ────────────────────────────────────────────────
            // Updates the system prompt to the incoming phase's instructions,
            // then passes input through to the selected subgraph.
            val router by node<String, String> { input ->
                val targetPhaseName = pendingPhase.get()
                val targetPhase = targetPhaseName?.let { context.phaseRegistry.resolve(it) }

                if (targetPhase != null) {
                    llm.writeSession {
                        updateSystemPrompt(targetPhase.resolvedInstructions)
                    }
                }

                input
            }

            // ── Between-phase compression ──────────────────────────────────
            val compressionConfig = context.config.historyCompression
            val compressBetweenPhases = compressionConfig != null &&
                    (compressionConfig.trigger is CompressionTrigger.BetweenPhases ||
                            compressionConfig.trigger is CompressionTrigger.Both)

            val initialPhaseName = context.activePhaseName ?: phases.first().name
            val initialSubgraph = phaseSubgraphs[initialPhaseName]
                ?: error("koog-compose: Initial phase '$initialPhaseName' not found in registry.")

            if (compressBetweenPhases && compressionConfig != null) {
                val nodeCompressBetween by nodeLLMCompressHistory<String>(
                    name = "compress_between_phases",
                    strategy = compressionConfig.strategy.toKoogStrategy()
                )

                edge(nodeStart forwardTo initialSubgraph)
                edge(initialSubgraph forwardTo nodeCompressBetween)
                edge(nodeCompressBetween forwardTo router)

                phaseSubgraphs.forEach { (phaseName, subgraph) ->
                    if (phaseName != initialPhaseName) {
                        edge(
                            (router forwardTo subgraph)
                                .onCondition { pendingPhase.get() == phaseName }
                        )
                        edge(
                            (subgraph forwardTo nodeCompressBetween)
                                .onCondition { pendingPhase.get() != null }
                        )
                        edge(
                            (subgraph forwardTo nodeFinish)
                                .onCondition { pendingPhase.get() == null }
                        )
                    }
                }

                // Initial subgraph also needs a finish path
                edge(
                    (initialSubgraph forwardTo nodeFinish)
                        .onCondition { pendingPhase.get() == null }
                )

            } else {
                edge(nodeStart forwardTo initialSubgraph)

                // Initial subgraph: go to router if transition pending, finish if done
                edge(
                    (initialSubgraph forwardTo router)
                        .onCondition { pendingPhase.get() != null }
                )
                edge(
                    (initialSubgraph forwardTo nodeFinish)
                        .onCondition { pendingPhase.get() == null }
                )

                phaseSubgraphs.forEach { (phaseName, subgraph) ->
                    if (phaseName != initialPhaseName) {
                        edge(
                            (router forwardTo subgraph)
                                .onCondition { pendingPhase.get() == phaseName }
                        )
                        edge(
                            (subgraph forwardTo router)
                                .onCondition { pendingPhase.get() != null && pendingPhase.get() != phaseName }
                        )
                        edge(
                            (subgraph forwardTo nodeFinish)
                                .onCondition { pendingPhase.get() == null }
                        )
                    }
                }
            }
        }
    }
}