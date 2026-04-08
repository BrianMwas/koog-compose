package io.github.koogcompose.phase

import ai.koog.agents.core.agent.entity.AIAgentSubgraph
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.memory.feature.history.RetrieveFactsFromHistory
import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.prompt.message.Message
import io.github.koogcompose.session.CompressionTrigger
import io.github.koogcompose.session.HistoryCompression
import io.github.koogcompose.session.HistoryCompressionConfig
import io.github.koogcompose.session.KoogComposeContext

/**
 * Builds the Koog graph strategy that mediates between phase definitions and the agent runtime.
 *
 * History compression is now wired into the phase subgraph when
 * [KoogComposeContext.config.historyCompression] is configured.
 * The compression node sits between tool execution and the next LLM
 * request, triggered by message count or between phases per the config.
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

        val compressionConfig = context.config.historyCompression
        val koogCompressionStrategy = compressionConfig?.strategy?.toKoogStrategy()

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

                    // Only create the compression node when compression is configured
                    // AND the trigger includes message-count compression.
                    // Resolve delegate → actual node immediately after creation
                    val nodeCompress by nodeLLMCompressHistory<ReceivedToolResult>(
                        name = "${phase.name}_compress",
                        strategy = koogCompressionStrategy ?: HistoryCompressionStrategy.NoCompression
                    )

                    fun captureTransition(toolCall: Message.Tool.Call): Boolean {
                        val transition = phase.transitions.firstOrNull { it.toolName == toolCall.tool }
                        if (transition != null) pendingPhaseName = transition.targetPhase
                        return true
                    }

                    fun shouldCompress(): Boolean {
                        if (compressionConfig == null || koogCompressionStrategy == null) return false
                        return compressionConfig.shouldCompressAfterTool()
                    }

                    edge(nodeStart forwardTo nodeCallLLM)
                    
                    // LLM responded with assistant message (no tool calls) → finish
                    edge(
                        (nodeCallLLM forwardTo nodeFinish)
                            .onAssistantMessage { true }
                    )
                    
                    // LLM wants to call a tool → execute it
                    edge(
                        (nodeCallLLM forwardTo nodeExecuteTool)
                            .onToolCall { call -> captureTransition(call) }
                    )

                    edge(
                        (nodeExecuteTool forwardTo nodeCompress)
                            .onCondition { _ -> shouldCompress() }
                    )
                    edge(nodeCompress forwardTo nodeSendToolResult)
                    edge(
                        (nodeExecuteTool forwardTo nodeSendToolResult)
                            .onCondition { _ -> !shouldCompress() }
                    )

                    // After sending tool result, LLM may respond with text or call more tools
                    edge(
                        (nodeSendToolResult forwardTo nodeFinish)
                            .onAssistantMessage { true }
                    )
                    edge(
                        (nodeSendToolResult forwardTo nodeExecuteTool)
                            .onToolCall { call -> captureTransition(call) }
                    )
                }

                phaseSubgraphs[phase.name] = phaseSubgraph
            }

            val router by node<String, String>("phase_router") { input -> input }

            val initialPhaseName = pendingPhaseName ?: phases.first().name
            val initialSubgraph = phaseSubgraphs[initialPhaseName]
                ?: error("koog-compose: Initial phase '$initialPhaseName' not found in registry.")

            edge(nodeStart forwardTo initialSubgraph)
            edge(
                (initialSubgraph forwardTo router)
                    .onCondition { pendingPhaseName != null && pendingPhaseName != initialPhaseName }
            )
            edge(
                (initialSubgraph forwardTo nodeFinish)
                    .onCondition { pendingPhaseName == null || pendingPhaseName == initialPhaseName }
            )

            phaseSubgraphs.forEach { (phaseName, subgraph) ->
                if (phaseName != initialPhaseName) {
                    edge(
                        (router forwardTo subgraph)
                            .onCondition { pendingPhaseName == phaseName }
                    )
                    edge(
                        (subgraph forwardTo router)
                            .onCondition { pendingPhaseName != null && pendingPhaseName != phaseName }
                    )
                    edge(
                        (subgraph forwardTo nodeFinish)
                            .onCondition { pendingPhaseName == null || pendingPhaseName == phaseName }
                    )
                }
            }
        }
    }
}

// ── Compression mapping ────────────────────────────────────────────────────────

/**
 * Maps koog-compose's [HistoryCompression] sealed class to Koog's
 * [HistoryCompressionStrategy] types.
 */
private fun HistoryCompression.toKoogStrategy(): HistoryCompressionStrategy = when (this) {
    is HistoryCompression.WholeHistory ->
        HistoryCompressionStrategy.WholeHistory

    is HistoryCompression.FromLastN ->
        HistoryCompressionStrategy.FromLastNMessages(n)

    is HistoryCompression.Chunked ->
        HistoryCompressionStrategy.Chunked(chunkSize)

    is HistoryCompression.RetrieveFactsFromHistory ->
        RetrieveFactsFromHistory(
            concepts.map { concept ->
                Concept(
                    keyword = concept.keyword,
                    description = concept.description,
                    factType = concept.factType.toKoogFactType()
                )
            }
        )

}

private fun io.github.koogcompose.session.FactType.toKoogFactType(): FactType = when (this) {
    io.github.koogcompose.session.FactType.SINGLE -> FactType.SINGLE
    io.github.koogcompose.session.FactType.MULTIPLE -> FactType.MULTIPLE
}

/**
 * Returns true when the compression trigger includes after-tool compression.
 * Between-phase-only compression is handled by the session runner, not the graph.
 */
private fun HistoryCompressionConfig.shouldCompressAfterTool(): Boolean = when (trigger) {
    is CompressionTrigger.AfterMessages -> true
    is CompressionTrigger.BetweenPhases -> false
    is CompressionTrigger.Both -> true
}
