package io.github.koogcompose.session

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentFunctionalFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.AIAgentPlannerFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentFunctionalPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline
import ai.koog.prompt.streaming.StreamFrame
import io.github.koogcompose.layout.LayoutDirectiveProcessor
import io.github.koogcompose.workflow.EmitLayoutDirectiveTool
import io.github.koogcompose.workflow.StreamingDirectiveAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Configuration for the streaming feature.
 */
internal class StreamingFeatureConfig : FeatureConfig() {
    var tokenSink: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 64)
    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    /** When set, streamed emit_layout_directive tool-call args drive live UI previews. */
    var layoutProcessor: LayoutDirectiveProcessor? = null
}

/**
 * Runtime instance of the streaming feature.
 */
internal class StreamingFeature(
    private val tokenSink: MutableSharedFlow<String>,
    private val coroutineScope: CoroutineScope,
    private val layoutProcessor: LayoutDirectiveProcessor? = null,
) {
    private val directiveAssembler = StreamingDirectiveAssembler()
    private val emitToolIndices = mutableSetOf<Int>()

    /**
     * Dispatches a token to the sink.
     */
    internal suspend fun onToken(token: String) {
        tokenSink.emit(token)
    }

    /**
     * Feeds a streamed tool-call argument [delta] for the call at [index]. When it belongs to
     * [EmitLayoutDirectiveTool.TOOL_NAME], a best-effort preview directive is pushed to the
     * layout processor so the UI animates in as the JSON streams (the authoritative directive
     * still commits when the tool call completes; they reconcile idempotently).
     */
    internal fun onToolCallDelta(index: Int, name: String?, delta: String?) {
        val processor = layoutProcessor ?: return
        if (name != null) {
            directiveAssembler.startCall(index)
            if (name == EmitLayoutDirectiveTool.TOOL_NAME) emitToolIndices += index else emitToolIndices -= index
        }
        if (index !in emitToolIndices) return
        val preview = directiveAssembler.consume(index, delta) ?: return
        processor.send(preview)
    }

    companion object :
        AIAgentGraphFeature<StreamingFeatureConfig, StreamingFeature>,
        AIAgentFunctionalFeature<StreamingFeatureConfig, StreamingFeature>,
        AIAgentPlannerFeature<StreamingFeatureConfig, StreamingFeature> {

        override val key = createStorageKey<StreamingFeature>("koog-compose-streaming")

        override fun createInitialConfig(agentConfig: AIAgentConfig) = StreamingFeatureConfig()

        // ── Graph-based agents ──────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentGraphPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope, config.layoutProcessor)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Functional agents ───────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentFunctionalPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope, config.layoutProcessor)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Planner agents ──────────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentPlannerPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope, config.layoutProcessor)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Shared pipeline interceptors ────────────────────────────────────
        private fun installCommon(pipeline: AIAgentPipeline, feature: StreamingFeature) {

            pipeline.interceptLLMStreamingFrameReceived(this) { eventContext ->
                when (val frame = eventContext.streamFrame) {
                    is StreamFrame.TextDelta ->
                        // Use the scope passed in config to avoid blocking the stream
                        feature.coroutineScope.launch {
                            runCatching { feature.onToken(frame.text) }
                        }
                    is StreamFrame.ToolCallDelta ->
                        // Live UI preview while the directive JSON streams (best-effort, non-blocking).
                        runCatching { feature.onToolCallDelta(frame.index ?: 0, frame.name, frame.content) }
                    else -> Unit
                }
            }
        }
    }
}