package io.github.koogcompose.phase

import ai.koog.agents.core.agent.entity.AIAgentSubgraph
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestMultiple
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.nodeLLMSendMultipleToolResults
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.agents.memory.feature.history.RetrieveFactsFromHistory
import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import io.github.koogcompose.session.ContextCondition
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.security.GuardrailEnforcer
import io.github.koogcompose.session.CompressionTrigger
import io.github.koogcompose.session.HistoryCompression
import io.github.koogcompose.session.HistoryCompressionConfig
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.toGuardedKoogTool
import kotlin.time.Clock

internal const val PHASE_SYSTEM_MARKER: String = "[koog-compose phase instructions]"

/**
 * Builds the Koog graph strategy that mediates between phase definitions and the agent runtime.
 *
 * When a phase has [Phase.hasSubphases], its subphases are chained as sequential
 * nested subgraphs. The parent phase's [Phase.transitions] only fire after ALL
 * subphases complete.
 *
 * When a phase has [Phase.hasParallel], all branch tools are collected into a
 * single subgraph where tool calls execute in parallel via
 * [nodeExecuteMultipleTools] with `parallelTools = true`.
 *
 * History compression is wired into the phase subgraph when
 * [KoogComposeContext.config.historyCompression] is configured.
 */
internal object PhaseStrategyBuilder {
    internal fun build(
        context: KoogComposeContext<*>,
        strategyName: String = "koog-compose-phase-strategy",
        enforcer: GuardrailEnforcer?,
        sessionId: String,
        eventSink: EventSink,
        onPhaseTransition: (fromPhaseName: String, toPhaseName: String, toolCallId: String?) -> Unit = { _, _, _ -> },
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
                val phaseSubgraph = when {
                    phase.hasParallel -> buildParallelSubgraph(
                        context,
                        phase,
                        compressionConfig,
                        koogCompressionStrategy,
                        enforcer,
                        sessionId,
                        eventSink,
                        onPhaseTransition,
                        getPending = { pendingPhaseName },
                        setPending = { pendingPhaseName = it }
                    )
                    phase.hasSubphases -> buildMultiStepSubgraph(
                        context,
                        phase,
                        compressionConfig,
                        koogCompressionStrategy,
                        enforcer,
                        sessionId,
                        eventSink,
                        onPhaseTransition,
                        getPending = { pendingPhaseName },
                        setPending = { pendingPhaseName = it }
                    )
                    else -> buildFlatSubgraph(
                        context,
                        phase,
                        compressionConfig,
                        koogCompressionStrategy,
                        enforcer,
                        sessionId,
                        eventSink,
                        onPhaseTransition,
                        getPending = { pendingPhaseName },
                        setPending = { pendingPhaseName = it }
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

    // ── Parallel branch subgraph building ─────────────────────────────────────

    /**
     * Builds a subgraph for parallel branches. All branch tools are collected
     * into a single subgraph where tool calls execute in parallel via
     * [nodeExecuteMultipleTools] with `parallelTools = true`.
     *
     * Multiple [parallel] blocks run sequentially (group 1 → group 2 → ...).
     * Branch results flow through state (KoogStateStore) — design branch tools
     * to write their output to `stateStore.update { }` directly.
     */
    private fun StrategyBuilder.buildParallelSubgraph(
        context: KoogComposeContext<*>,
        phase: Phase,
        compressionConfig: HistoryCompressionConfig?,
        koogCompressionStrategy: HistoryCompressionStrategy?,
        enforcer: GuardrailEnforcer?,
        sessionId: String,
        eventSink: EventSink,
        onPhaseTransition: (fromPhaseName: String, toPhaseName: String, toolCallId: String?) -> Unit,
        getPending: () -> String?,
        setPending: (String?) -> Unit,
    ): AIAgentSubgraph<String, String> {
        // Collect all tools from all branches across all parallel groups
        val allBranchTools = phase.parallelGroups.flatten()
            .flatMap { it.toolRegistry.all }
            .map { it.toGuardedKoogTool(enforcer, sessionId, eventSink) }

        val parent by subgraph<String, String>(name = phase.name, tools = allBranchTools) {
            var phaseInput = ""
            val nodeApplyPhasePrompt by phasePromptNode(context, phase) { input ->
                phaseInput = input
                if (getPending() == phase.name) setPending(null)
            }
            val nodeCallLLM by nodeLLMRequestMultiple("${phase.name}_llm_parallel")
            val nodeExecuteTools by nodeExecuteMultipleTools("${phase.name}_exec_parallel", parallelTools = true)
            val nodeSendToolResults by nodeLLMSendMultipleToolResults("${phase.name}_results")
            val nodeFinishTransition by node<List<ReceivedToolResult>, String>("${phase.name}_transition_finish") { toolResults ->
                llm.writeSession {
                    appendPrompt {
                        tool {
                            toolResults.forEach { result(it) }
                        }
                    }
                }
                phaseInput
            }

            val nodeCompress by nodeLLMCompressHistory<List<ReceivedToolResult>>(
                name = "${phase.name}_compress",
                strategy = koogCompressionStrategy ?: HistoryCompressionStrategy.NoCompression
            )

            fun captureTransitions(toolCalls: List<Message.Tool.Call>): Boolean {
                for (toolCall in toolCalls) {
                    for (branch in phase.parallelGroups.flatten()) {
                        val transition = branch.transitions.firstOrNull { it.toolName == toolCall.tool }
                        if (transition != null) {
                            setPending(transition.targetPhase)
                            onPhaseTransition(branch.name, transition.targetPhase, toolCall.id)
                        }
                    }
                }
                return true
            }

            fun shouldCompress(): Boolean {
                if (compressionConfig == null || koogCompressionStrategy == null) return false
                return compressionConfig.shouldCompressAfterTool()
            }

            fun hasPendingTransition(): Boolean =
                getPending() != null && getPending() != phase.name

            edge(nodeStart forwardTo nodeApplyPhasePrompt)
            edge(nodeApplyPhasePrompt forwardTo nodeCallLLM)

            // LLM responded with assistant message (no tool calls) → finish
            edge(
                (nodeCallLLM forwardTo nodeFinish)
                    .onAssistantMessage { true }
            )

            // LLM wants to call tools → execute all in parallel
            edge(
                (nodeCallLLM forwardTo nodeExecuteTools)
                    .onMultipleToolCalls { calls -> captureTransitions(calls) }
            )

            edge(
                (nodeExecuteTools forwardTo nodeFinishTransition)
                    .onCondition { _ -> hasPendingTransition() }
            )
            edge(nodeFinishTransition forwardTo nodeFinish)
            edge(
                (nodeExecuteTools forwardTo nodeCompress)
                    .onCondition { _ -> !hasPendingTransition() && shouldCompress() }
            )
            edge(nodeCompress forwardTo nodeSendToolResults)
            edge(
                (nodeExecuteTools forwardTo nodeSendToolResults)
                    .onCondition { _ -> !hasPendingTransition() && !shouldCompress() }
            )

            // After sending results, LLM may respond with text or call more tools
            edge(
                (nodeSendToolResults forwardTo nodeFinish)
                    .onAssistantMessage { true }
            )
            edge(
                (nodeSendToolResults forwardTo nodeExecuteTools)
                    .onMultipleToolCalls { calls -> captureTransitions(calls) }
            )
        }

        return parent
    }

    // ── Multi-step subphase chaining ──────────────────────────────────────────

    /**
     * Builds N sequential subgraphs for a parent phase with subphases,
     * chained: start → sub[0] → sub[1] → ... → sub[n-1] → finish.
     *
     * The parent phase's [Phase.transitions] are wired on each subphase
     * so the LLM can exit at any point, but in practice they only fire
     * after all subphases have run.
     */
    private fun StrategyBuilder.buildMultiStepSubgraph(
        context: KoogComposeContext<*>,
        phase: Phase,
        compressionConfig: HistoryCompressionConfig?,
        koogCompressionStrategy: HistoryCompressionStrategy?,
        enforcer: GuardrailEnforcer?,
        sessionId: String,
        eventSink: EventSink,
        onPhaseTransition: (fromPhaseName: String, toPhaseName: String, toolCallId: String?) -> Unit,
        getPending: () -> String?,
        setPending: (String?) -> Unit,
    ): AIAgentSubgraph<String, String> {
        // Build each subphase as its own flat subgraph
        val subgraphList = phase.subphases.map { sub ->
            buildFlatSubgraph(
                context,
                sub,
                compressionConfig,
                koogCompressionStrategy,
                enforcer,
                sessionId,
                eventSink,
                onPhaseTransition,
                getPending,
                setPending
            )
        }

        // Wrap them in a parent subgraph that chains them sequentially
        val parent by subgraph<String, String>(
            name = phase.name,
            tools = emptyList() // tools live on individual subphases
        ) {
            var prev: AIAgentSubgraph<String, String>? = null

            subgraphList.forEachIndexed { i, sub ->
                if (i == 0) {
                    edge(nodeStart forwardTo sub)
                } else {
                    edge(prev!! forwardTo sub)
                }
                prev = sub
            }

            // Last subphase → finish (triggers parent phase's onCondition)
            edge(
                (subgraphList.last() forwardTo nodeFinish)
                    .onCondition { true }
            )
        }

        return parent
    }

    // ── Flat (non-subphase) subgraph building ─────────────────────────────────

    /**
     * Builds a single flat subgraph for a phase with no subphases.
     * This is the original phase subgraph building logic, extracted here
     * so [buildMultiStepSubgraph] can reuse it for individual subphases.
     */
    private fun StrategyBuilder.buildFlatSubgraph(
        context: KoogComposeContext<*>,
        phase: Phase,
        compressionConfig: HistoryCompressionConfig?,
        koogCompressionStrategy: HistoryCompressionStrategy?,
        enforcer: GuardrailEnforcer?,
        sessionId: String,
        eventSink: EventSink,
        onPhaseTransition: (fromPhaseName: String, toPhaseName: String, toolCallId: String?) -> Unit,
        getPending: () -> String?,
        setPending: (String?) -> Unit,
    ): AIAgentSubgraph<String, String> {
        val phaseTools = phase.buildKoogTools(enforcer, sessionId, eventSink)

        val phaseSubgraph by subgraph<String, String>(
            name = phase.name,
            tools = phaseTools
        ) {
            var phaseInput = ""
            val nodeApplyPhasePrompt by phasePromptNode(context, phase) { input ->
                phaseInput = input
                if (getPending() == phase.name) setPending(null)
            }
            val nodeCallLLM by nodeLLMRequest("${phase.name}_llm")
            val nodeExecuteTool by nodeExecuteTool("${phase.name}_exec")
            val nodeSendToolResult by nodeLLMSendToolResult("${phase.name}_result")
            val nodeFinishTransition by node<ReceivedToolResult, String>("${phase.name}_transition_finish") { toolResult ->
                llm.writeSession {
                    appendPrompt {
                        tool {
                            result(toolResult)
                        }
                    }
                }
                phaseInput
            }

            // Only create the compression node when compression is configured
            // AND the trigger includes message-count compression.
            val nodeCompress by nodeLLMCompressHistory<ReceivedToolResult>(
                name = "${phase.name}_compress",
                strategy = koogCompressionStrategy ?: HistoryCompressionStrategy.NoCompression
            )

            fun captureTransition(toolCall: Message.Tool.Call): Boolean {
                val transition = phase.transitions.firstOrNull { it.toolName == toolCall.tool }
                if (transition != null) {
                    setPending(transition.targetPhase)
                    onPhaseTransition(phase.name, transition.targetPhase, toolCall.id)
                }
                return true
            }

            fun shouldCompress(): Boolean {
                if (compressionConfig == null || koogCompressionStrategy == null) return false
                return compressionConfig.shouldCompressAfterTool()
            }

            fun hasPendingTransition(): Boolean =
                getPending() != null && getPending() != phase.name

            edge(nodeStart forwardTo nodeApplyPhasePrompt)
            edge(nodeApplyPhasePrompt forwardTo nodeCallLLM)

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
                (nodeExecuteTool forwardTo nodeFinishTransition)
                    .onCondition { _ -> hasPendingTransition() }
            )
            edge(nodeFinishTransition forwardTo nodeFinish)
            edge(
                (nodeExecuteTool forwardTo nodeCompress)
                    .onCondition { _ -> !hasPendingTransition() && shouldCompress() }
            )
            edge(nodeCompress forwardTo nodeSendToolResult)
            edge(
                (nodeExecuteTool forwardTo nodeSendToolResult)
                    .onCondition { _ -> !hasPendingTransition() && !shouldCompress() }
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

        return phaseSubgraph
    }

    private fun phasePromptNode(
        context: KoogComposeContext<*>,
        phase: Phase,
        onInput: (String) -> Unit = {},
    ): AIAgentNodeDelegate<String, String> {
        val instructions = context.effectiveInstructionsFor(phase)
        return node("${phase.name}_phase_prompt") { input ->
            onInput(input)
            llm.writeSession {
                rewritePrompt { current ->
                    current.withMessages { messages ->
                        val withoutPreviousPhaseSystem = messages.filterNot { message ->
                            message is Message.System && message.content.startsWith(PHASE_SYSTEM_MARKER)
                        }
                        listOf(
                            Message.System(
                                "$PHASE_SYSTEM_MARKER\n$instructions",
                                RequestMetaInfo.create(Clock.System),
                            )
                        ) + withoutPreviousPhaseSystem
                    }
                }
            }
            input
        }
    }

    private fun KoogComposeContext<*>.effectiveInstructionsFor(phase: Phase): String {
        val globalPrompt = promptStack.resolve().trim()
        val parts = buildList {
            if (globalPrompt.isNotBlank()) add(globalPrompt)

            add("CURRENT PHASE: ${phase.name}")
            if (phase.resolvedInstructions.isNotBlank()) {
                add(phase.resolvedInstructions.trim())
            }

            contextualInstructions
                .filter { instruction ->
                    when (val condition = instruction.condition) {
                        is ContextCondition.Always -> true
                        is ContextCondition.WhenPhase -> condition.phaseName == phase.name
                        is ContextCondition.WhenBlocked -> false
                    }
                }
                .forEach { add(it.content.trim()) }
        }

        return parts.joinToString(separator = "\n\n")
    }
}

// ── StrategyBuilder alias for extension functions ──────────────────────────────

/**
 * Alias for the Koog strategy builder DSL receiver type.
 * Gives us a place to hang extension functions for subgraph building.
 */
internal typealias StrategyBuilder =
    ai.koog.agents.core.dsl.builder.AIAgentGraphStrategyBuilder<String, String>

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
