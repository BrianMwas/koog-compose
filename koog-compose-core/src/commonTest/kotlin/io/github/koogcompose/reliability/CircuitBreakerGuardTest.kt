package io.github.koogcompose.reliability

import io.github.koogcompose.observability.AgentEvent
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class CircuitBreakerGuardTest {

    @Test
    fun openingTransition_emitsCircuitBreakerOpened() = runTest {
        val tool = FakeTool(result = ToolResult.Failure("service down"))
        val sink = RecordingEventSink()
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 60_000) { 0L }
        val guard = CircuitBreakerGuard(tool, breaker, sessionId = "s1", eventSink = sink)

        guard.execute(EMPTY_ARGS)

        assertTrue(breaker.isOpen)
        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertTrue(event is AgentEvent.CircuitBreakerOpened)
        event as AgentEvent.CircuitBreakerOpened
        assertEquals("s1", event.sessionId)
        assertEquals("fake", event.toolName)
        assertEquals(60_000, event.cooldownMs)
    }

    @Test
    fun recoveryTransition_emitsCircuitBreakerClosed() = runTest {
        var now = 0L
        val tool = FakeTool(result = ToolResult.Failure("service down"))
        val sink = RecordingEventSink()
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 1_000, successThreshold = 1) { now }
        val guard = CircuitBreakerGuard(tool, breaker, sessionId = "s1", eventSink = sink)

        guard.execute(EMPTY_ARGS) // → OPEN (emits Opened)
        assertTrue(breaker.isOpen)

        now = 1_000
        tool.result = ToolResult.Success("recovered")
        guard.execute(EMPTY_ARGS) // open-check → HALF_OPEN, success → CLOSED (emits Closed)

        assertFalse(breaker.isOpen)
        assertTrue(sink.events.any { it is AgentEvent.CircuitBreakerClosed })
        val closed = sink.events.filterIsInstance<AgentEvent.CircuitBreakerClosed>().single()
        assertEquals("s1", closed.sessionId)
        assertEquals("fake", closed.toolName)
    }

    @Test
    fun openCircuit_returnsFriendlyFailure_withoutCallingDelegate() = runTest {
        val tool = FakeTool(result = ToolResult.Failure("service down"))
        val sink = RecordingEventSink()
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 60_000) { 0L }
        val guard = CircuitBreakerGuard(tool, breaker, eventSink = sink)

        guard.execute(EMPTY_ARGS) // → OPEN
        tool.callCount = 0

        val result = guard.execute(EMPTY_ARGS)

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, tool.callCount) // delegate not invoked while open
        // No new transition event from the short-circuited call.
        assertEquals(1, sink.events.size)
    }

    @Test
    fun deniedResult_doesNotCountAsFailure_norEmitsEvent() = runTest {
        val tool = FakeTool(result = ToolResult.Denied("policy"))
        val sink = RecordingEventSink()
        val breaker = CircuitBreaker(failureThreshold = 1) { 0L }
        val guard = CircuitBreakerGuard(tool, breaker, eventSink = sink)

        guard.execute(EMPTY_ARGS)

        assertFalse(breaker.isOpen)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun successWhileClosed_emitsNoEvent() = runTest {
        val tool = FakeTool(result = ToolResult.Success("ok"))
        val sink = RecordingEventSink()
        val breaker = CircuitBreaker(failureThreshold = 3) { 0L }
        val guard = CircuitBreakerGuard(tool, breaker, eventSink = sink)

        guard.execute(EMPTY_ARGS)

        assertTrue(sink.events.isEmpty())
    }
}

private val EMPTY_ARGS = JsonObject(emptyMap())

private class RecordingEventSink : EventSink {
    val events = mutableListOf<AgentEvent>()
    override suspend fun emit(event: AgentEvent) {
        events.add(event)
    }
}

private class FakeTool(
    override val name: String = "fake",
    var result: ToolResult,
) : SecureTool {
    override val description: String = "fake tool"
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE
    var callCount: Int = 0

    override suspend fun execute(args: JsonObject): ToolResult {
        callCount++
        return result
    }
}
