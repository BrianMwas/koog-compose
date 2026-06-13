package io.github.koogcompose.provider.ondevice

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import io.github.koogcompose.phase.Phase
import io.github.koogcompose.phase.toTool
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class OnDevicePromptExecutor(
    private val context: KoogComposeContext<*>,
) : PromptExecutor() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val text = StringBuilder()
        var toolCallPart: MessagePart.Tool.Call? = null

        executeStreaming(prompt, model, tools).collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> text.append(frame.text)
                is StreamFrame.TextComplete -> text.clear().append(frame.text)
                is StreamFrame.ToolCallComplete -> {
                    // koog 1.0.0: a tool call is a MessagePart inside the assistant message.
                    toolCallPart = MessagePart.Tool.Call(
                        id = frame.id,
                        tool = frame.name,
                        args = frame.content,
                    )
                }
                else -> Unit
            }
        }

        val meta = ResponseMetaInfo.create(KoogClock.System)
        return toolCallPart?.let { Message.Assistant(it, meta) }
            ?: Message.Assistant(text.toString(), meta)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val config = context.providerConfig as? ProviderConfig.OnDevice
            ?: error("OnDevicePromptExecutor requires ProviderConfig.OnDevice")
        val provider = OnDeviceProvider(
            modelPath = config.modelPath,
            tools = context.toolsMatching(tools),
            maxToolRounds = config.maxToolRounds,
        )

        try {
            if (!provider.isAvailable()) {
                val fallback = fallbackExecutor(config)
                if (fallback != null) {
                    emitAll(fallback.executeStreaming(prompt, model, tools))
                    return@flow
                }
                error("On-device model is unavailable and no fallback provider is configured.")
            }

            val parser = FunctionCallParser(
                json = json,
                onText = { chunk -> emit(StreamFrame.TextDelta(chunk)) },
            )

            try {
                provider.executeStreamingRaw(prompt.toOnDevicePrompt()).collect { chunk ->
                    val request = parser.consume(chunk)
                    if (request != null) {
                        throw ToolCallCaptured(request)
                    }
                }
                parser.flush()
            } catch (captured: ToolCallCaptured) {
                emit(
                    StreamFrame.ToolCallComplete(
                        id = "tool-${KoogClock.System.now().toEpochMilliseconds()}",
                        name = captured.request.name,
                        content = captured.request.arguments.toString(),
                    )
                )
            }

            emit(StreamFrame.End())
        } finally {
            provider.close()
        }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close(): Unit = Unit

    private fun fallbackExecutor(config: ProviderConfig.OnDevice): PromptExecutor? =
        config.fallback?.let { fallback ->
            context.copy(providerConfig = fallback).createExecutor()
        }

    private fun Prompt.toOnDevicePrompt(): OnDevicePrompt {
        val systemPrompt = messages
            .filterIsInstance<Message.System>()
            .joinToString("\n\n") { it.textContent() }
            .ifBlank { null }

        val transcript = messages
            .filterNot { it is Message.System }
            .joinToString("\n\n") { message ->
                "${message.role.name}: ${message.textContent()}"
            }

        return OnDevicePrompt(
            system = systemPrompt,
            user = transcript,
        )
    }

    private fun KoogComposeContext<*>.toolsMatching(descriptors: List<ToolDescriptor>): List<SecureTool> {
        val names = descriptors.map(ToolDescriptor::name).toSet()
        if (names.isEmpty()) return emptyList()
        return allTools().filter { it.name in names }
    }

    private fun KoogComposeContext<*>.allTools(): List<SecureTool> {
        val phaseTools = phaseRegistry.all.flatMap { phase ->
            phase.allScopedTools()
        }
        return (toolRegistry.all + phaseTools).distinctBy(SecureTool::name)
    }

    private fun Phase.allScopedTools(): List<SecureTool> =
        toolRegistry.all +
            transitions.map { it.toTool() } +
            subphases.flatMap { it.allScopedTools() } +
            parallelGroups.flatten().flatMap { it.allScopedTools() }
}

@Serializable
private data class OnDeviceToolCallRequest(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

private class ToolCallCaptured(
    val request: OnDeviceToolCallRequest,
) : RuntimeException()

private class FunctionCallParser(
    private val json: Json,
    private val onText: suspend (String) -> Unit,
) {
    private val startTag = "<function_call>"
    private val endTag = "</function_call>"
    private val carry = StringBuilder()
    private val toolJson = StringBuilder()
    private var inToolCall = false

    suspend fun consume(chunk: String): OnDeviceToolCallRequest? {
        carry.append(chunk)

        while (true) {
            if (!inToolCall) {
                val startIndex = carry.indexOf(startTag)
                if (startIndex >= 0) {
                    emitText(carry.substring(0, startIndex))
                    carry.deleteRange(0, startIndex + startTag.length)
                    inToolCall = true
                    continue
                }

                val safeTextLength = carry.length - (startTag.length - 1)
                if (safeTextLength > 0) {
                    emitText(carry.substring(0, safeTextLength))
                    carry.deleteRange(0, safeTextLength)
                }
                return null
            }

            val endIndex = carry.indexOf(endTag)
            if (endIndex >= 0) {
                toolJson.append(carry.substring(0, endIndex))
                carry.deleteRange(0, endIndex + endTag.length)
                val rawJson = toolJson.toString().trim()
                toolJson.clear()
                inToolCall = false
                return parseToolCall(rawJson)
            }

            val safeToolLength = carry.length - (endTag.length - 1)
            if (safeToolLength > 0) {
                toolJson.append(carry.substring(0, safeToolLength))
                carry.deleteRange(0, safeToolLength)
            }
            return null
        }
    }

    suspend fun flush() {
        if (!inToolCall && carry.isNotEmpty()) {
            emitText(carry.toString())
            carry.clear()
        }
    }

    private fun parseToolCall(rawJson: String): OnDeviceToolCallRequest? =
        try {
            json.decodeFromString<OnDeviceToolCallRequest>(rawJson)
        } catch (_: Exception) {
            null
        }

    private suspend fun emitText(text: String) {
        if (text.isEmpty()) return
        text.chunked(32).forEach { onText(it) }
    }
}
