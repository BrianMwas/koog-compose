package io.github.koogcompose.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.security.GuardedTool
import io.github.koogcompose.security.GuardrailEnforcer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a [SecureTool] to Koog's [Tool] type so it can be registered
 * in a Koog [ai.koog.agents.core.tools.ToolRegistry].
 *
 * Uses the tool's [SecureTool.parametersSchema] (JSON Schema) to build
 * rich parameter descriptors with proper types (String, Integer, Boolean,
 * Enum, Array, Object). Falls back to [SecureTool.describeParameters] if
 * no schema is provided — all typed as String for backward compatibility.
 */
@OptIn(InternalKoogSerializationApi::class)
internal fun SecureTool.toKoogTool(): Tool<JsonObject, String> {
    val descriptor = toKoogToolDescriptor()
    return object : Tool<JsonObject, String>(
        argsType = KSerializerTypeToken(JsonObject.serializer()),
        resultType = KSerializerTypeToken(String.serializer()),
        descriptor = descriptor
    ) {
        override suspend fun execute(args: JsonObject): String {
            return when (val result = this@toKoogTool.execute(args)) {
                is ToolResult.Success -> result.output
                is ToolResult.Denied -> "Denied: ${result.reason}"
                is ToolResult.Failure -> "Error: ${result.message}"
                is ToolResult.Structured<*> -> result.toJson()
            }
        }
    }
}

/**
 * Wraps this tool with [GuardedTool] and converts it to a Koog [Tool].
 *
 * All guardrails (validation, rate limits, allowlists, permission
 * confirmations) and observability ([AgentEvent.ToolCalled],
 * [AgentEvent.GuardrailDenied]) run through the shared [enforcer]
 * and [eventSink] when the LLM invokes the returned tool.
 *
 * When [enforcer] is null, behaves identically to [toKoogTool] — used
 * by code paths that don't have a session-scoped enforcer (e.g.
 * free-standing [KoogRoutine]).
 */
internal fun SecureTool.toGuardedKoogTool(
    enforcer: GuardrailEnforcer?,
    sessionId: String,
    eventSink: EventSink,
    userId: String? = null,
): Tool<JsonObject, String> {
    if (enforcer == null) return toKoogTool()
    return GuardedTool(
        delegate = this,
        enforcer = enforcer,
        userId = userId,
        sessionId = sessionId,
        eventSink = eventSink,
    ).toKoogTool()
}

@OptIn(InternalKoogSerializationApi::class)
internal fun SecureTool.toKoogToolDescriptor(): ToolDescriptor {
    val schema = parametersSchema
    if (schema != null) {
        val rootProperties = schema.rootProperties()
        val requiredNames = schema.rootRequiredNames()
        val descriptors = rootProperties.map { (propertyName, propertySchema) ->
            propertyName.toToolParameterDescriptor(propertySchema)
        }
        return ToolDescriptor(
            name = name,
            description = description,
            requiredParameters = descriptors.filter { it.name in requiredNames },
            optionalParameters = descriptors.filter { it.name !in requiredNames }
        )
    }

    // Fallback: use describeParameters() — all typed as String.
    val rawParams = describeParameters()
    val fallbackDescriptors = rawParams.map {
        ToolParameterDescriptor(
            name = it.name,
            description = it.description,
            type = ToolParameterType.String
        )
    }
    val requiredNames = rawParams.filter { it.required }.map { it.name }.toSet()
    return ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = fallbackDescriptors.filter { it.name in requiredNames },
        optionalParameters = fallbackDescriptors.filter { it.name !in requiredNames }
    )
}

private fun JsonObject.rootProperties(): JsonObject {
    if (get("type")?.jsonPrimitive?.contentOrNull == "object") {
        return get("properties")?.jsonObject ?: buildJsonObject { }
    }
    return this
}

private fun JsonObject.rootRequiredNames(): Set<String> {
    if (get("type")?.jsonPrimitive?.contentOrNull == "object") {
        return get("required")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            ?: emptySet()
    }
    return entries
        .filter { (_, schema) ->
            schema.jsonObject["required"]?.jsonPrimitive?.booleanOrNull ?: true
        }
        .map { it.key }
        .toSet()
}

private fun String.toToolParameterDescriptor(schema: JsonElement): ToolParameterDescriptor {
    val descriptorSchema = schema.jsonObject
    return ToolParameterDescriptor(
        name = this,
        description = descriptorSchema["description"]?.jsonPrimitive?.contentOrNull ?: this,
        type = descriptorSchema.toToolParameterType(this)
    )
}

private fun JsonObject.toToolParameterType(fallbackName: String): ToolParameterType {
    val enumEntries = get("enum")
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toTypedArray()
    if (!enumEntries.isNullOrEmpty()) {
        return ToolParameterType.Enum(enumEntries)
    }

    return when (get("type")?.jsonPrimitive?.contentOrNull?.lowercase()) {
        "string" -> ToolParameterType.String
        "integer", "int" -> ToolParameterType.Integer
        "number", "float", "double" -> ToolParameterType.Float
        "boolean", "bool" -> ToolParameterType.Boolean
        "array" -> ToolParameterType.List(
            get("items")
                ?.jsonObject
                ?.toToolParameterType("${fallbackName}_item")
                ?: ToolParameterType.String
        )

        "object" -> ToolParameterType.Object(
            properties = get("properties")
                ?.jsonObject
                ?.map { (name, schema) -> name.toToolParameterDescriptor(schema) }
                ?: emptyList(),
            requiredProperties = get("required")
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            additionalProperties = get("additionalProperties")?.let { additional ->
                when (additional) {
                    is JsonPrimitive -> additional.booleanOrNull
                    else -> true
                }
            },
            additionalPropertiesType = get("additionalProperties")
                ?.let { additional ->
                    if (additional is JsonObject) {
                        additional.toToolParameterType("${fallbackName}_additional")
                    } else {
                        null
                    }
                }
        )

        "enum" -> ToolParameterType.Enum(emptyArray())
        "null" -> ToolParameterType.Null
        else -> when {
            containsKey("properties") -> ToolParameterType.Object(
                properties = get("properties")
                    ?.jsonObject
                    ?.map { (name, schema) -> name.toToolParameterDescriptor(schema) }
                    ?: emptyList(),
                requiredProperties = get("required")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
            )

            else -> ToolParameterType.String
        }
    }
}
