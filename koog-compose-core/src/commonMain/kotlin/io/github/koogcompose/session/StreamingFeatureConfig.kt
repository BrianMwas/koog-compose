package io.github.koogcompose.session


import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.MutableSharedFlow

internal class StreamingFeatureConfig : FeatureConfig() {
    var tokenSink: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 64)
}

internal class StreamingFeature(
    private val tokenSink: MutableSharedFlow<String>
) {
    /**
     * Dispatches a token to the sink.
     */
    internal suspend fun onToken(token: String) {
        tokenSink.emit(token)
    }


    companion object : AIAgentGraphFeature<StreamingFeatureConfig, StreamingFeature> {

        override val key = AIAgentStorageKey<StreamingFeature>("koog-compose-streaming")

        override fun createInitialConfig(agentConfig: AIAgentConfig) =
            StreamingFeatureConfig()

        override fun install(
            config: StreamingFeatureConfig,
            pipeline: AIAgentGraphPipeline
        ): StreamingFeature {
            val feature = StreamingFeature(config.tokenSink)

            pipeline.interceptLLMStreamingFrameReceived(this) { eventContext ->
                val frame = eventContext.streamFrame
                if (frame is StreamFrame.TextDelta) {
                    feature.onToken(frame.text)
                }
            }

            return feature
        }
    }
}