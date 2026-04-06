package io.github.koogcompose.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Adapts a [SecureTool] to Koog's [Tool] type so it can be registered
 * in a Koog [ai.koog.agents.core.tools.ToolRegistry].
 *
 * This is the single bridge between koog-compose's tool model and Koog internals.
 */
internal fun SecureTool.toKoogTool(): Tool<JsonObject, String> =
    SecureToolAdapter(this)

@OptIn(InternalKoogSerializationApi::class)
private class SecureToolAdapter(
    private val secureTool: SecureTool
) : Tool<JsonObject, String>(
    argsType = KSerializerTypeToken(JsonObject.serializer()),
    resultType = KSerializerTypeToken(String.serializer()),
    descriptor = ToolDescriptor(
        name = secureTool.name,
        description = secureTool.description,
        requiredParameters = secureTool.describeParameters()
            .filter { it.required }
            .map { it.toKoogDescriptor() },
        optionalParameters = secureTool.describeParameters()
            .filter { !it.required }
            .map { it.toKoogDescriptor() }
    )
) {
    override suspend fun execute(args: JsonObject): String {
        return when (val result = secureTool.execute(args)) {
            is ToolResult.Success -> result.output
            is ToolResult.Denied -> "Denied: ${result.reason}"
            is ToolResult.Failure -> "Error: ${result.message}"
        }
    }
}

private fun ParameterDescriptor.toKoogDescriptor(): ToolParameterDescriptor =
    ToolParameterDescriptor(
        name = name,
        description = description,
        type = ai.koog.agents.core.tools.ToolParameterType.String
    )
