package io.github.koogcompose.provider.ondevice

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import io.github.koogcompose.provider.ondevice.android.LiteRtLmToolOrchestrator.ToolCallRequest
import io.github.koogcompose.provider.ondevice.android.LiteRtLmToolOrchestrator
import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android actual for OnDeviceProvider.
 *
 * Backed by LiteRT-LM and Gemma 4 (E2B or E4B .litertlm models).
 *
 * Tool calling design: automaticToolCalling is set to false. Tool dispatch
 * is handled by LiteRtLmToolOrchestrator which parses Gemma 4 tool call
 * format, routes through koog SecureTool pipeline (validation + guardrails),
 * and feeds results back into the conversation.
 */
public actual class OnDeviceProvider public actual constructor(
    private val modelPath: String,
    private val tools: List<SecureTool>,
    private val maxToolRounds: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val toolRegistry: Map<String, SecureTool> =
        tools.associateBy { it.name }

    private val engine: Engine by lazy {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val config = EngineConfig(modelPath = modelPath)
        Engine(config).also { it.initialize() }
    }

    public actual fun isAvailable(): Boolean =
        try {
            File(modelPath).exists().also { exists ->
                if (exists) engine
            }
        } catch (e: Exception) {
            false
        }

    public actual fun supportsToolCalls(): Boolean = true

    public actual suspend fun execute(prompt: OnDevicePrompt): String =
        withContext(Dispatchers.IO) {
            engine.createConversation(buildConversationConfig(prompt)).use { conversation ->
                val orchestrator = LiteRtLmToolOrchestrator(
                    conversation = conversation,
                    toolRegistry = toolRegistry,
                    maxRounds = maxToolRounds,
                )
                orchestrator.runLoop(prompt.user)
            }
        }

    public actual fun executeStreaming(prompt: OnDevicePrompt): Flow<String> = callbackFlow {
        val conversation = engine.createConversation(buildConversationConfig(prompt))

        try {
            var currentMessage = prompt.user
            var round = 0

            while (round < maxToolRounds) {
                val parser = StreamingToolCallParser(
                    onText = { chunk -> trySend(chunk) }
                )

                val request = try {
                    conversation.sendMessageAsync(currentMessage).collect { message ->
                        val completedRequest = parser.consume(message.toString())
                        if (completedRequest != null) {
                            throw StreamingToolCallCaptured(completedRequest)
                        }
                    }
                    parser.flush()
                    null
                } catch (captured: StreamingToolCallCaptured) {
                    captured.request
                }

                if (request == null) {
                    break
                }

                val result = dispatchToolForStream(request)
                currentMessage = "User: <tool_result>$result</tool_result>"
                round++
            }
        } finally {
            conversation.close()
            close()
        }

        awaitClose { conversation.close() }
    }

    public actual fun close() {
        runCatching { engine.close() }
    }

    private fun buildConversationConfig(prompt: OnDevicePrompt): ConversationConfig {
        return ConversationConfig(
            systemInstruction = prompt.system?.let { Contents.of(it) },
            automaticToolCalling = false,
        )
    }

    private fun parseToolCallForStream(rawJson: String): ToolCallRequest? =
        try {
            json.decodeFromString<ToolCallRequest>(rawJson)
        } catch (e: Exception) {
            null
        }

    private suspend fun dispatchToolForStream(request: ToolCallRequest): String {
        val tool = toolRegistry[request.name]
            ?: return json.encodeToString(mapOf("error" to "Tool '${request.name}' not registered"))
        return try {
            val result = tool.execute(request.arguments)
            val output = when (result) {
                is io.github.koogcompose.tool.ToolResult.Success -> result.output
                is io.github.koogcompose.tool.ToolResult.Failure -> result.message
                is io.github.koogcompose.tool.ToolResult.Denied -> result.reason
                is io.github.koogcompose.tool.ToolResult.Structured<*> -> result.toJson()
            }
            json.encodeToString(mapOf("result" to output))
        } catch (e: Exception) {
            json.encodeToString(mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private inner class StreamingToolCallParser(
        private val onText: (String) -> Unit,
    ) {
        private val startTag = "<function_call>"
        private val endTag = "</function_call>"
        private val carry = StringBuilder()
        private val toolJson = StringBuilder()
        private var inToolCall = false

        fun consume(chunk: String): ToolCallRequest? {
            carry.append(chunk)

            while (true) {
                if (!inToolCall) {
                    val startIndex = carry.indexOf(startTag)
                    if (startIndex >= 0) {
                        emitText(carry.substring(0, startIndex))
                        carry.delete(0, startIndex + startTag.length)
                        inToolCall = true
                        continue
                    }

                    val safeTextLength = carry.length - (startTag.length - 1)
                    if (safeTextLength > 0) {
                        emitText(carry.substring(0, safeTextLength))
                        carry.delete(0, safeTextLength)
                    }
                    return null
                }

                val endIndex = carry.indexOf(endTag)
                if (endIndex >= 0) {
                    toolJson.append(carry.substring(0, endIndex))
                    carry.delete(0, endIndex + endTag.length)
                    val rawJson = toolJson.toString().trim()
                    toolJson.clear()
                    inToolCall = false
                    return parseToolCallForStream(rawJson)
                }

                val safeToolLength = carry.length - (endTag.length - 1)
                if (safeToolLength > 0) {
                    toolJson.append(carry.substring(0, safeToolLength))
                    carry.delete(0, safeToolLength)
                }
                return null
            }
        }

        fun flush() {
            if (!inToolCall && carry.isNotEmpty()) {
                emitText(carry.toString())
                carry.clear()
            }
        }

        private fun emitText(text: String) {
            if (text.isEmpty()) return
            text.chunked(32).forEach(onText)
        }
    }

    private class StreamingToolCallCaptured(
        val request: ToolCallRequest,
    ) : RuntimeException()
}
