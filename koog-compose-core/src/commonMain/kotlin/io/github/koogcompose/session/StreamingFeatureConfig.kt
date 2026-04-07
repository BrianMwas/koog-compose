package io.github.koogcompose.session

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentFunctionalFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.AIAgentPlannerFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentFunctionalPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline
import ai.koog.prompt.streaming.StreamFrame
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
}

/**
 * Runtime instance of the streaming feature.
 */
internal class StreamingFeature(
    private val tokenSink: MutableSharedFlow<String>,
    private val coroutineScope: CoroutineScope,
) {
    /**
     * Dispatches a token to the sink.
     */
    internal suspend fun onToken(token: String) {
        tokenSink.emit(token)
    }

    companion object :
        AIAgentGraphFeature<StreamingFeatureConfig, StreamingFeature>,
        AIAgentFunctionalFeature<StreamingFeatureConfig, StreamingFeature>,
        AIAgentPlannerFeature<StreamingFeatureConfig, StreamingFeature> {

        override val key = AIAgentStorageKey<StreamingFeature>("koog-compose-streaming")

        override fun createInitialConfig(agentConfig: AIAgentConfig) = StreamingFeatureConfig()

        // ── Graph-based agents ──────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentGraphPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Functional agents ───────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentFunctionalPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Planner agents ──────────────────────────────────────────────────
        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentPlannerPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink, config.coroutineScope)
            installCommon(pipeline, feature)
            return feature
        }

        // ── Shared pipeline interceptors ────────────────────────────────────
        private fun installCommon(pipeline: AIAgentPipeline, feature: StreamingFeature) {

            pipeline.interceptLLMStreamingFrameReceived(this) { eventContext ->
                val frame = eventContext.streamFrame
                if (frame is StreamFrame.TextDelta) {
                    // Use the scope passed in config to avoid blocking the stream
                    feature.coroutineScope.launch {
                        runCatching { feature.onToken(frame.text) }
                    }
                }
            }
        }
    }
}