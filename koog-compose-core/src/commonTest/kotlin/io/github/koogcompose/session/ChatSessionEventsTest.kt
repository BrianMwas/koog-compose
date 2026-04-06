package io.github.koogcompose.session

import app.cash.turbine.test
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionEventsTest {
    @Test
    fun eventFlow_reports_phase_transition_and_confirmed_tool_execution() = runTest {
        val provider = EventTestProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "phase-tool-1",
                        toolName = "transition_to_checkout",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "critical-tool-1",
                        toolName = "process_payment",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Done."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = eventContext(),
            provider = provider,
            scope = this
        )
        session.events.test {
            val events = mutableListOf<KoogEvent>()

            session.send("Start checkout")
            advanceUntilIdle()
            session.confirmPendingToolExecution()
            advanceUntilIdle()

            while (true) {
                val event = awaitItem()
                events += event
                if (event is KoogEvent.TurnCompleted || event is KoogEvent.TurnFailed) {
                    break
                }
            }

            assertTrue(
                events.any { event ->
                    event is KoogEvent.PhaseTransitioned &&
                        event.fromPhaseName == "browse" &&
                        event.toPhaseName == "checkout"
                },
                events.joinToString(separator = "\n")
            )
            assertTrue(events.any { event ->
                event is KoogEvent.ToolConfirmationRequested &&
                    event.toolName == "process_payment"
            })
            assertTrue(
                events.any { event ->
                    event is KoogEvent.ToolExecutionCompleted &&
                        event.toolName == "process_payment" &&
                        event.result is ToolResult.Success
                },
                events.joinToString(separator = "\n")
            )
            assertTrue(events.any { event ->
                event is KoogEvent.TurnCompleted &&
                    event.phaseName == "checkout"
            })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun contextEventHandlers_receive_runtime_events() = runTest {
        val handled = mutableListOf<String>()
        val provider = EventTestProvider(
            steps = listOf(
                listOf(
                    AIResponseChunk.ToolCallRequest(
                        toolCallId = "phase-tool-2",
                        toolName = "transition_to_checkout",
                        args = buildJsonObject { }
                    ),
                    AIResponseChunk.End
                ),
                listOf(
                    AIResponseChunk.TextComplete("Checkout mode active."),
                    AIResponseChunk.End
                )
            )
        )
        val session = ChatSession(
            initialContext = eventContext { event ->
                when (event) {
                    is KoogEvent.PhaseTransitioned -> handled += "phase:${event.toPhaseName}"
                    is KoogEvent.TurnCompleted -> handled += "turn:${event.phaseName}"
                    else -> Unit
                }
            },
            provider = provider,
            scope = this
        )

        session.send("Move to checkout")
        advanceUntilIdle()

        assertEquals(listOf("phase:checkout", "turn:checkout"), handled)
    }
}

private fun eventContext(onEvent: (suspend (KoogEvent) -> Unit)? = null): KoogComposeContext<Unit> = koogCompose {
    provider {
        ollama(model = "demo-local")
    }
    prompt {
        default { "You are a test assistant." }
    }
    phases {
        phase("browse") {
            instructions { "Browse products." }
            onCondition(
                on = "checkout should begin",
                targetPhase = "checkout"
            )
        }
        phase("checkout") {
            instructions { "Process the order carefully." }
            tool(EventCriticalTool())
        }
    }
    initialPhase("browse")
    if (onEvent != null) {
        events {
            onEvent(onEvent)
        }
    }
    config {
        requireConfirmationForSensitive = true
    }
}

private class EventCriticalTool : SecureTool {
    override val name: String = "process_payment"
    override val description: String = "Process payment"
    override val permissionLevel: PermissionLevel = PermissionLevel.CRITICAL

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success("ok")
}

private class EventTestProvider(
    private val steps: List<List<AIResponseChunk>>
) : AIProvider {
    private var index: Int = 0

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        steps.getOrElse(index++) {
            listOf(AIResponseChunk.TextComplete("fallback"), AIResponseChunk.End)
        }.forEach { emit(it) }
    }
}
