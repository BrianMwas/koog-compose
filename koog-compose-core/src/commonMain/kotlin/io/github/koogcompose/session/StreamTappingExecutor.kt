package io.github.koogcompose.session

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorAPI
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onEach


internal class StreamTappingExecutor(
    private val delegate: PromptExecutor,
    private val tokenSink: MutableSharedFlow<String>
) : PromptExecutorAPI by delegate {

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)
        .onEach { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> tokenSink.emit(frame.text)
                else -> Unit // ignore tool calls, reasoning, end frames
            }
        }

    override fun close() = delegate.close()
}