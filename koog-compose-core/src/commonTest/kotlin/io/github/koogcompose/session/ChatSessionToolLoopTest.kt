package io.github.koogcompose.session

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionToolLoopTest {
    @Test
    fun send_executesToolAppendsResultAndResumesAssistantTurn() = runTest {
        val provider = RecordingAIProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "tool-call-1",
                        toolName = "demo_location",
                        args = buildJsonObject {
                            put("source", "device")
                        }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Location received."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = toolContext(DemoSensitiveTool()),
            provider = provider,
            scope = this
        )

        session.send("Where am I?")
        advanceUntilIdle()

        assertNotNull(session.permissionManager.pendingConfirmation.value)

        session.confirmPendingToolExecution()
        advanceUntilIdle()

        val messages = session.state.value.messages
        val toolResultMessage = messages.last { it.role == MessageRole.TOOL && it.toolKind == ToolMessageKind.RESULT }
        val payload = Json.parseToJsonElement(toolResultMessage.content).jsonObject

        assertEquals("success", payload["status"]?.jsonPrimitive?.content)
        assertEquals("location:1.23,4.56", payload["output"]?.jsonPrimitive?.content)
        assertEquals("Location received.", messages.last { it.role == MessageRole.ASSISTANT }.content)
        assertEquals(1, session.auditLogger.approvedCount)
    }

    @Test
    fun denyToolExecution_recordsDeniedPayload() = runTest {
        val provider = RecordingAIProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "tool-call-2",
                        toolName = "demo_location",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Okay, I skipped the tool."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = toolContext(DemoSensitiveTool()),
            provider = provider,
            scope = this
        )

        session.send("Use the tool")
        advanceUntilIdle()

        session.denyPendingToolExecution()
        advanceUntilIdle()

        val toolResultMessage = session.state.value.messages
            .last { it.role == MessageRole.TOOL && it.toolKind == ToolMessageKind.RESULT }
        val payload = Json.parseToJsonElement(toolResultMessage.content).jsonObject

        assertEquals("denied", payload["status"]?.jsonPrimitive?.content)
        assertTrue(session.auditLogger.deniedCount >= 1)
    }

    @Test
    fun transitionTool_updatesActivePhaseAndResumesWithPhasePrompt() = runTest {
        val seenPhases = mutableListOf<String?>()
        val seenPrompts = mutableListOf<String>()
        val provider = RecordingAIProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "phase-shift-1",
                        toolName = "transition_to_checkout",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Checkout ready."),
                    AIResponseChunk.End
                )
            )
        ) { context, systemPrompt ->
            seenPhases += context.activePhaseName
            seenPrompts += systemPrompt
        }
        val session = ChatSession(
            initialContext = phaseContext(DemoCriticalTool()),
            provider = provider,
            scope = this
        )

        session.send("I want to buy")
        advanceUntilIdle()

        assertEquals("checkout", session.state.value.activePhaseName)
        assertEquals(listOf<String?>("browse", "checkout"), seenPhases)
        assertTrue(seenPrompts.first().contains("CURRENT PHASE: browse"))
        assertTrue(seenPrompts.last().contains("CURRENT PHASE: checkout"))
        assertNull(session.permissionManager.pendingConfirmation.value)

        val toolResultMessage = session.state.value.messages
            .last { it.role == MessageRole.TOOL && it.toolKind == ToolMessageKind.RESULT }
        val payload = Json.parseToJsonElement(toolResultMessage.content).jsonObject

        assertEquals("success", payload["status"]?.jsonPrimitive?.content)
        assertEquals("Transitioned to phase 'checkout'", payload["output"]?.jsonPrimitive?.content)
        assertEquals("Checkout ready.", session.state.value.messages.last { it.role == MessageRole.ASSISTANT }.content)
    }

    @Test
    fun phaseScopedCriticalTool_requiresConfirmationAfterTransition() = runTest {
        val provider = RecordingAIProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "phase-shift-2",
                        toolName = "transition_to_checkout",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "payment-1",
                        toolName = "process_payment",
                        args = buildJsonObject {
                            put("amount", "1099")
                        }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Payment queued."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = phaseContext(DemoCriticalTool()),
            provider = provider,
            scope = this
        )

        session.send("Buy this item")
        advanceUntilIdle()

        assertEquals("checkout", session.state.value.activePhaseName)
        assertEquals(
            "process_payment",
            session.permissionManager.pendingConfirmation.value?.tool?.name
        )

        session.confirmPendingToolExecution()
        advanceUntilIdle()

        val toolResultMessage = session.state.value.messages
            .last { it.role == MessageRole.TOOL && it.toolKind == ToolMessageKind.RESULT }
        val payload = Json.parseToJsonElement(toolResultMessage.content).jsonObject

        assertEquals("success", payload["status"]?.jsonPrimitive?.content)
        assertEquals("payment:approved", payload["output"]?.jsonPrimitive?.content)
        assertEquals("Payment queued.", session.state.value.messages.last { it.role == MessageRole.ASSISTANT }.content)
        assertTrue(session.auditLogger.approvedCount >= 2)
    }

    @Test
    fun phaseScopedTool_isUnavailableBeforeTransition() = runTest {
        val provider = RecordingAIProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "payment-too-early",
                        toolName = "process_payment",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Still browsing."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = phaseContext(DemoCriticalTool()),
            provider = provider,
            scope = this
        )

        session.send("Pay right now")
        advanceUntilIdle()

        val toolResultMessage = session.state.value.messages
            .last { it.role == MessageRole.TOOL && it.toolKind == ToolMessageKind.RESULT }
        val payload = Json.parseToJsonElement(toolResultMessage.content).jsonObject

        assertEquals("browse", session.state.value.activePhaseName)
        assertNull(session.permissionManager.pendingConfirmation.value)
        assertEquals("error", payload["status"]?.jsonPrimitive?.content)
        assertEquals("Tool not registered: process_payment", payload["message"]?.jsonPrimitive?.content)
    }
}

private fun toolContext(tool: SecureTool): KoogComposeContext<Unit> = koogCompose<Unit> {
    provider {
        ollama(model = "demo-local")
    }
    prompt {
        default { "You are a test assistant." }
    }
    tools {
        register(tool)
    }
    config {
        requireConfirmationForSensitive = true
    }
}

private fun phaseContext(checkoutTool: SecureTool): KoogComposeContext<Unit> = koogCompose<Unit> {
    provider {
        ollama(model = "demo-local")
    }
    prompt {
        default { "You are a test assistant." }
    }
    phases {
        phase("browse") {
            instructions { "Help the user browse products." }
            onCondition(
                on = "the user is ready to purchase and checkout should begin",
                targetPhase = "checkout"
            )
        }
        phase("checkout") {
            instructions { "Complete checkout and prepare the payment action." }
            tool(checkoutTool)
        }
    }
    initialPhase("browse")
    config {
        requireConfirmationForSensitive = true
    }
}

private class DemoSensitiveTool : SecureTool {
    override val name: String = "demo_location"
    override val description: String = "Reads location"
    override val permissionLevel: PermissionLevel = PermissionLevel.SENSITIVE

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success("location:1.23,4.56")
}

private class DemoCriticalTool : SecureTool {
    override val name: String = "process_payment"
    override val description: String = "Process a payment"
    override val permissionLevel: PermissionLevel = PermissionLevel.CRITICAL

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success("payment:approved")
}

private class RecordingAIProvider(
    private val steps: List<List<AIResponseChunk>>,
    private val onStreamStart: (KoogComposeContext<*>, String) -> Unit = { _, _ -> }
) : AIProvider {
    private var index: Int = 0

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        onStreamStart(context, systemPrompt)
        val chunks = steps.getOrElse(index++) { listOf(AIResponseChunk.TextComplete("fallback"), AIResponseChunk.End) }
        chunks.forEach { emit(it) }
    }
}
