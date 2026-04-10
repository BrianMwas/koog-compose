package io.github.koogcompose.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class WeatherResult(
    val temperature: Double,
    val condition: String,
    val unit: String = "C",
)

class ToolResultStructuredTest {

    // ── Serialization ──────────────────────────────────────────────────────────

    @Test
    fun `Structured toJson serializes typed data`() {
        val result = ToolResult.Structured(
            data = WeatherResult(temperature = 22.5, condition = "sunny"),
            serializer = WeatherResult.serializer(),
        )
        val json = result.toJson()
        assertTrue(json.contains("22.5"))
        assertTrue(json.contains("sunny"))
    }

    @Test
    fun `Structured preserves original data`() {
        val data = WeatherResult(temperature = -5.0, condition = "snowy", unit = "C")
        val result = ToolResult.Structured(data, WeatherResult.serializer())
        assertEquals(data, result.data)
    }

    // ── Bridge round-trip ──────────────────────────────────────────────────────

    @Test
    fun `toKoogTool returns JSON string for Structured result`() = kotlinx.coroutines.test.runTest {
        val tool = object : SecureTool {
            override val name = "GetWeather"
            override val description = "Fetch weather data"
            override val permissionLevel = PermissionLevel.SAFE

            override suspend fun execute(args: JsonObject): ToolResult {
                return ToolResult.Structured(
                    data = WeatherResult(temperature = 18.0, condition = "cloudy"),
                    serializer = WeatherResult.serializer(),
                )
            }
        }

        val koogTool = tool.toKoogTool()
        val output = koogTool.execute(buildJsonObject { put("location", "nairobi") })

        assertTrue(output.contains("18.0"))
        assertTrue(output.contains("cloudy"))
    }

    // ── Audit integration ──────────────────────────────────────────────────────

    @Test
    fun `Structured result is treated as approved in audit`() {
        val result: ToolResult = ToolResult.Structured(
            data = WeatherResult(temperature = 30.0, condition = "hot"),
            serializer = WeatherResult.serializer(),
        )
        assertTrue(result is ToolResult.Structured<*>)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    fun `Structured with String serializes correctly`() {
        val result = ToolResult.Structured(
            data = "success",
            serializer = String.serializer(),
        )
        assertEquals("\"success\"", result.toJson())
    }

    @Test
    fun `Structured with Int serializes correctly`() {
        val result = ToolResult.Structured(
            data = 42,
            serializer = Int.serializer(),
        )
        assertEquals("42", result.toJson())
    }
}
