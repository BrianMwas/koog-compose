package io.github.koogcompose.provider

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class KoogProviderHelpersTest {
    @Test
    fun normalizeOpenAIBaseUrl_stripsTrailingV1() {
        assertEquals(
            "https://api.openai.com",
            normalizeOpenAIBaseUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com",
            normalizeOpenAIBaseUrl("https://api.openai.com/v1/")
        )
    }

    @Test
    fun resolveModel_usesFallbackCapabilities_forUnknownOpenAIModel() {
        val model = resolveModel(
            ProviderConfig.OpenAI(
                apiKey = "test-key",
                model = "gpt-custom-demo"
            )
        )

        assertEquals("gpt-custom-demo", model.id)
        assertTrue(model.capabilities.orEmpty().isNotEmpty())
        assertTrue(model.capabilities.orEmpty().map { it.id }.contains("tools"))
    }

    @Test
    fun secureToolDescriptor_supportsArraysEnumsAndObjects() {
        val descriptor = StructuredTool().toKoogToolDescriptor()

        assertEquals(2, descriptor.requiredParameters.size)
        assertEquals(1, descriptor.optionalParameters.size)

        val tags = descriptor.requiredParameters.first { it.name == "tags" }
        assertEquals("ARRAY", tags.type.name)

        val options = descriptor.requiredParameters.first { it.name == "options" }
        assertEquals("OBJECT", options.type.name)

        val mode = descriptor.optionalParameters.first { it.name == "mode" }
        assertEquals("ENUM", mode.type.name)
    }
}

private class StructuredTool : SecureTool {
    override val name: String = "structured_tool"
    override val description: String = "Structured tool"
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tags") {
                put("type", "array")
                put("description", "Tags to send")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
            putJsonObject("options") {
                put("type", "object")
                put("description", "Nested options")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                    }
                }
                put("required", buildJsonArray {
                    add(JsonPrimitive("limit"))
                })
            }
            putJsonObject("mode") {
                put("description", "Execution mode")
                put("required", false)
                put("enum", buildJsonArray {
                    add(JsonPrimitive("fast"))
                    add(JsonPrimitive("safe"))
                })
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("tags"))
            add(JsonPrimitive("options"))
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success(args.toString())
}
