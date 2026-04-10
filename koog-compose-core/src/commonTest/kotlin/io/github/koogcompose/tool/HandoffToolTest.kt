package io.github.koogcompose.tool

import io.github.koogcompose.session.KoogAgentDefinition
import io.github.koogcompose.phase.PhaseRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffToolTest {

    private val testAgent = KoogAgentDefinition(
        name = "focus",
        provider = null,
        instructions = "Focus specialist",
        phaseRegistry = PhaseRegistry.Empty,
        toolRegistry = ToolRegistry.Empty,
    )

    // ── handoff() shorthand ────────────────────────────────────────────────────

    @Test
    fun `handoff creates tool with correct target agent name`() {
        val tool = handoff(testAgent, description = { "User wants to focus" })
        assertEquals("handoff_to_focus", tool.name)
    }

    @Test
    fun `handoff sets description from lambda`() {
        val tool = handoff(testAgent, description = { "User asks about pomodoro or focus" })
        assertEquals("User asks about pomodoro or focus", tool.description)
    }

    @Test
    fun `handoff sets SAFE permission level`() {
        val tool = handoff(testAgent, description = { "Focus handoff" })
        assertEquals(PermissionLevel.SAFE, tool.permissionLevel)
    }

    @Test
    fun `handoff has null parameters schema`() {
        val tool = handoff(testAgent, description = { "Focus handoff" })
        assertNull(tool.parametersSchema)
    }

    // ── handoffToolName ────────────────────────────────────────────────────────

    @Test
    fun `handoffToolName produces deterministic name`() {
        assertEquals("handoff_to_weather", handoffToolName("weather"))
        assertEquals("handoff_to_focus", handoffToolName("focus"))
    }

    // ── HandoffBuilder ─────────────────────────────────────────────────────────

    @Test
    fun `HandoffBuilder default description`() {
        val tool = HandoffBuilder("agent1").build()
        assertEquals("Hand off to agent1", tool.description)
    }

    @Test
    fun `HandoffBuilder custom description`() {
        val tool = HandoffBuilder("agent1").apply {
            description = "Custom description"
        }.build()
        assertEquals("Custom description", tool.description)
    }

    @Test
    fun `HandoffBuilder continueHistory defaults to true`() {
        val tool = HandoffBuilder("agent1").build()
        assertTrue(tool.continueHistory)
    }

    @Test
    fun `HandoffBuilder can set continueHistory to false`() {
        val tool = HandoffBuilder("agent1").apply {
            continueHistory = false
        }.build()
        assertFalse(tool.continueHistory)
    }

    // ── execute fallback ───────────────────────────────────────────────────────

    @Test
    fun `execute returns success when called outside session`() = kotlinx.coroutines.test.runTest {
        val tool = HandoffBuilder("focus").build()
        val result = tool.execute(buildJsonObject { put("irrelevant", true) })
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("focus"))
    }
}
