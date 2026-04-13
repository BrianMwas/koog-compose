package io.github.koogcompose.provider.ondevice.android

import com.google.ai.edge.litertlm.Conversation
import io.github.koogcompose.provider.ondevice.android.LiteRtLmToolOrchestrator.ToolCallRequest
import io.github.koogcompose.tool.SecureTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Owns the agentic loop between a LiteRT-LM Conversation and koog-compose's
 * SecureTool pipeline.
 *
 * Why this exists: LiteRT-LM automatic tool calling bypasses koog validation,
 * guardrails, and event emission. By setting automaticToolCalling to false
 * and using this orchestrator, we keep koog as the single authoritative
 * dispatcher.
 *
 * Gemma 4 tool call format uses open/close tool call tag delimiters. Tool
 * results are fed back as a user turn containing a JSON result object.
 *
 * Loop contract each round:
 *   1. Collect a full response from the model.
 *   2. If a tool call tag is found, dispatch and inject result, repeat.
 *   3. If no tool call, return the response as the final answer.
 *   4. If maxRounds is reached, return the last partial response with a
 *      warning prefix so callers are aware the loop was capped.
 */
internal class LiteRtLmToolOrchestrator(
    private val conversation: Conversation,
    private val toolRegistry: Map<String, SecureTool>,
    private val maxRounds: Int = 5,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val toolCallRegex = Regex(
        """<function_call>(.*?)</function_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    suspend fun runLoop(initialUserMessage: String): String {
        var fullResponse = collectFullResponse(initialUserMessage)
        var round = 0

        while (round < maxRounds) {
            val toolCallMatch = toolCallRegex.find(fullResponse)
                ?: break

            val toolCallJson = toolCallMatch.groupValues[1].trim()
            val toolCallRequest = parseToolCall(toolCallJson)
                ?: break

            val toolResult = dispatchTool(toolCallRequest)
            fullResponse = collectFullResponse(formatToolResponse(toolResult))
            round++
        }

        if (round == maxRounds) {
            return LOOP_CAP_PREFIX + fullResponse
        }

        return fullResponse
    }

    internal suspend fun collectFullResponse(userMessage: String): String {
        val sb = StringBuilder()
        conversation.sendMessageAsync(userMessage).collect { message ->
            sb.append(message.toString())
        }
        return sb.toString()
    }

    internal data class ToolCallRequest(
        val name: String,
        val arguments: JsonObject = JsonObject(emptyMap()),
    )

    private fun parseToolCall(rawJson: String): ToolCallRequest? =
        try {
            json.decodeFromString<ToolCallRequest>(rawJson)
        } catch (e: Exception) {
            null
        }

    private suspend fun dispatchTool(request: ToolCallRequest): String {
        val tool = toolRegistry[request.name]
            ?: return errorResult("Tool '${request.name}' is not registered.")

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
            errorResult("Tool execution failed: ${e.message}")
        }
    }

    private fun formatToolResponse(result: String): String =
        "User: <tool_result>$result</tool_result>"

    private fun errorResult(message: String): String =
        json.encodeToString(mapOf("error" to message))

    companion object {
        const val LOOP_CAP_PREFIX = "[koog:loop_cap] "
    }
}
