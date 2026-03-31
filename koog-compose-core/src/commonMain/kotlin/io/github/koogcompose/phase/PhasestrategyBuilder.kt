package io.github.koogcompose.phase


import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.ToolRegistry as KoogToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.provider.KoogAIProvider
import io.github.koogcompose.provider.toKoogTool
import io.github.koogcompose.session.CompressionTrigger
import io.github.koogcompose.session.HistoryCompression
import io.github.koogcompose.session.KoogComposeContext

/**
 * Builds a single [AIAgentGraphStrategy] from all phases registered in [KoogComposeContext].
 *
 * Each Phase becomes a subgraph. Subgraphs share the same LLM session history (via the
 * agent's single PromptExecutor), so all context is preserved across phase transitions.
 *
 * Transition flow:
 *   Phase A subgraph → detects transition tool call → signals PhaseRouter → Phase B subgraph
 *
 * The PhaseRouter node sits between subgraphs and reads the transition signal to decide
 * which subgraph to enter next.
 */
internal object PhaseStrategyBuilder {

    fun build(
        context: KoogComposeContext,
        strategyName: String = "koog-compose-phase-strategy"
    ): AIAgentGraphStrategy<String, String> {

        val phases = context.phaseRegistry.all
        require(phases.isNotEmpty()) {
            "koog-compose: phases { } block is empty. Define at least one phase."
        }

        val globalKoogTools = buildGlobalKoogRegistry(context)

        return strategy<String, String>(strategyName) {

            // ── Shared mutable routing state ───────────────────────────────
            // Holds the name of the phase we should enter next.
            // Written by the transition-detection node, read by the router node.
            var pendingPhase: String? = context.activePhaseName

            // ── Build one subgraph per Phase ───────────────────────────────
            val phaseSubgraphs = phases.associate { phase ->
                val phaseTools = buildPhaseKoogTools(phase, context)

                val subgraph = subgraph<String, String>(
                    name = phase.name,
                    tools = phaseTools
                ) {
                    val nodeCallLLM by nodeLLMRequest("${phase.name}_llm")
                    val nodeExecuteTool by nodeExecuteTool("${phase.name}_exec")
                    val nodeSendToolResult by nodeLLMSendToolResult("${phase.name}_result")

                    // Optionally insert history compression inside the subgraph
                    val compressionConfig = context.config.historyCompression
                    val useCompression = compressionConfig != null &&
                            compressionConfig.trigger is CompressionTrigger.BetweenPhases ||
                            (compressionConfig?.trigger is CompressionTrigger.Both)

                    if (useCompression && compressionConfig != null) {
                        val nodeCompress by nodeLLMCompressHistory<String>(
                            name = "${phase.name}_compress",
                            strategy = compressionConfig.strategy.toKoogStrategy()
                        )

                        edge(nodeStart forwardTo nodeCallLLM)

                        // Finish when LLM responds with text (not a tool call)
                        edge(
                            (nodeCallLLM forwardTo nodeFinish)
                                .onAssistantMessage { msg ->
                                    val isTransition = phase.transitions.any { t ->
                                        msg.content.contains(t.toolName)
                                    }
                                    if (isTransition) {
                                        pendingPhase = phase.transitions
                                            .firstOrNull { msg.content.contains(it.toolName) }
                                            ?.targetPhase
                                    }
                                    true
                                }
                        )

                        edge(
                            (nodeCallLLM forwardTo nodeExecuteTool).onToolCall { call ->
                                val transition = phase.transitions.find { it.toolName == call.tool }
                                if (transition != null) {
                                    pendingPhase = transition.targetPhase
                                }
                                true
                            }
                        )

                        edge(nodeExecuteTool forwardTo nodeCompress)
                        edge(nodeCompress forwardTo nodeCallLLM)

                        edge(
                            (nodeSendToolResult forwardTo nodeFinish)
                                .onAssistantMessage { true }
                        )
                        edge(
                            (nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { call ->
                                val transition = phase.transitions.find { it.toolName == call.tool }
                                if (transition != null) pendingPhase = transition.targetPhase
                                true
                            }
                        )

                    } else {
                        // Standard subgraph wiring without intra-phase compression
                        edge(nodeStart forwardTo nodeCallLLM)

                        edge(
                            (nodeCallLLM forwardTo nodeFinish)
                                .onAssistantMessage { true }
                        )

                        edge(
                            (nodeCallLLM forwardTo nodeExecuteTool).onToolCall { call ->
                                // Intercept transition tool calls before executing
                                val transition = phase.transitions.find { it.toolName == call.tool }
                                if (transition != null) {
                                    pendingPhase = transition.targetPhase
                                }
                                true
                            }
                        )

                        edge(nodeExecuteTool forwardTo nodeSendToolResult)

                        edge(
                            (nodeSendToolResult forwardTo nodeFinish)
                                .onAssistantMessage { true }
                        )

                        edge(
                            (nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { call ->
                                val transition = phase.transitions.find { it.toolName == call.tool }
                                if (transition != null) pendingPhase = transition.targetPhase
                                true
                            }
                        )
                    }
                }

                phase.name to subgraph
            }

            // ── Router node: decides which subgraph to call next ───────────
            // Sits between subgraph exits and re-entry. Acts as a pure dispatch node.
            val router by node<String, String> { input ->
                // Update the LLM system prompt with the new phase's instructions
                val targetPhaseName = pendingPhase
                val targetPhase = targetPhaseName?.let { context.phaseRegistry.resolve(it) }

                if (targetPhase != null) {
                    llm.writeSession {
                        updateSystemPrompt(targetPhase.instructions)
                    }
                }

                input // pass through to the next subgraph
            }

            // ── Optional between-phase history compression ─────────────────
            val compressionConfig = context.config.historyCompression
            val compressBetweenPhases = compressionConfig != null &&
                    (compressionConfig.trigger is CompressionTrigger.BetweenPhases ||
                            compressionConfig.trigger is CompressionTrigger.Both)

            // ── Wire the top-level graph ───────────────────────────────────
            // Start → initial phase subgraph
            val initialPhaseName = context.activePhaseName
                ?: phases.first().name
            val initialSubgraph = phaseSubgraphs[initialPhaseName]
                ?: error("Initial phase '$initialPhaseName' not found in registry")

            if (compressBetweenPhases && compressionConfig != null) {
                val nodeCompressBetween by nodeLLMCompressHistory<String>(
                    name = "compress_between_phases",
                    strategy = compressionConfig.strategy.toKoogStrategy()
                )

                nodeStart then initialSubgraph then nodeCompressBetween then router

                // Router dispatches to whichever subgraph pendingPhase points to.
                // We wire router → every subgraph and gate on pendingPhase.
                phaseSubgraphs.forEach { (phaseName, subgraph) ->
                    if (phaseName != initialPhaseName) {
                        edge(
                            (router forwardTo subgraph).onCondition { pendingPhase == phaseName }
                        )
                        edge(
                            (subgraph forwardTo nodeCompressBetween).onCondition {
                                pendingPhase != null && pendingPhase != phaseName
                            }
                        )
                        edge(
                            (subgraph forwardTo nodeFinish).onCondition { pendingPhase == null }
                        )
                    }
                }
            } else {
                nodeStart then initialSubgraph then router

                phaseSubgraphs.forEach { (phaseName, subgraph) ->
                    if (phaseName != initialPhaseName) {
                        edge(
                            (router forwardTo subgraph).onCondition { pendingPhase == phaseName }
                        )
                        edge(
                            (subgraph forwardTo router).onCondition {
                                pendingPhase != null && pendingPhase != phaseName
                            }
                        )
                        edge(
                            (subgraph forwardTo nodeFinish).onCondition { pendingPhase == null }
                        )
                    }
                }

                // If the initial subgraph finishes with no pending transition, we're done
                edge(
                    (initialSubgraph forwardTo nodeFinish).onCondition { pendingPhase == null }
                )
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a Koog ToolRegistry from a Phase's own tools + its transition tools.
     * This is what gets passed to subgraph(tools = ...).
     */
    private fun buildPhaseKoogTools(
        phase: Phase,
        context: KoogComposeContext
    ): List<ai.koog.agents.core.tools.Tool<*, *>> {
        val phaseTools = phase.toolRegistry.all.map { it.toKoogTool() }
        val transitionTools = phase.transitions.map { it.toTool().toKoogTool() }
        return phaseTools + transitionTools
    }

    /**
     * Builds the global ToolRegistry from context (used for agent-level registration).
     * Subgraphs can use subsets of this.
     */
    private fun buildGlobalKoogRegistry(context: KoogComposeContext): KoogToolRegistry {
        return KoogToolRegistry {
            context.toolRegistry.all.forEach { tool(it.toKoogTool()) }
        }
    }
}