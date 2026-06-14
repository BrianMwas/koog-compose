package io.github.koogcompose.provider

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.toKoogToolDescriptor
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
    fun generationDefaults_anthropic_usesConfiguredValues() {
        val defaults = ProviderConfig.Anthropic(
            apiKey = "key",
            maxTokens = 2048,
            temperature = 0.5,
        ).generationDefaults()

        assertEquals(0.5, defaults.temperature)
        assertEquals(2048, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_openAI_usesConfiguredValues() {
        val defaults = ProviderConfig.OpenAI(
            apiKey = "key",
            maxTokens = 1024,
            temperature = 0.2,
        ).generationDefaults()

        assertEquals(0.2, defaults.temperature)
        assertEquals(1024, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_ollama_usesConfiguredValues() {
        val defaults = ProviderConfig.Ollama(
            model = "llama3.2",
            maxTokens = 512,
            temperature = 0.9,
        ).generationDefaults()

        assertEquals(0.9, defaults.temperature)
        assertEquals(512, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_liteRtLm_suppressesTemperatureButKeepsMaxTokens() {
        val defaults = ProviderConfig.LiteRtLm(
            maxTokens = 1024,
            temperature = 0.7,
        ).generationDefaults()

        assertEquals(null, defaults.temperature)
        assertEquals(1024, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_onDevice_isAlwaysNull() {
        val defaults = ProviderConfig.OnDevice(modelPath = "/path").generationDefaults()

        assertEquals(null, defaults.temperature)
        assertEquals(null, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_router_delegatesToFirstProvider() {
        val defaults = ProviderConfig.Router(
            providers = listOf(
                ProviderConfig.OpenAI(apiKey = "key", maxTokens = 256, temperature = 0.3),
                ProviderConfig.Anthropic(apiKey = "key2", maxTokens = 4096, temperature = 0.9),
            )
        ).generationDefaults()

        assertEquals(0.3, defaults.temperature)
        assertEquals(256, defaults.maxTokens)
    }

    @Test
    fun generationDefaults_emptyRouter_isAlwaysNull() {
        val defaults = ProviderConfig.Router(providers = emptyList()).generationDefaults()

        assertEquals(null, defaults.temperature)
        assertEquals(null, defaults.maxTokens)
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
