package io.github.koogcompose.event

import io.github.koogcompose.session.AIResponseChunk
import kotlinx.serialization.json.JsonObject

/**
 * A tracing sink that outputs events to a platform-specific log.
 */
interface TracingSink : KoogEventHandler

/**
 * A basic implementation of [TracingSink] that logs events to the console/logcat.
 */
class ConsoleTracingSink(private val tag: String = "KoogCompose") : TracingSink {
    override fun onEvent(event: KoogEvent) {
        val message = when (event) {
            is KoogEvent.TurnStarted -> "Turn Started: ${event.turnId} (${event.phaseName ?: "default"})"
            is KoogEvent.PhaseTransitioned -> "Phase Transition: ${event.fromPhaseName} -> ${event.toPhaseName}"
            is KoogEvent.ToolCallRequested -> "Tool Call: ${event.toolName} with args ${event.args}"
            is KoogEvent.ToolExecutionCompleted -> "Tool Finished: ${event.toolName} -> ${event.result}"
            is KoogEvent.TurnCompleted -> "Turn Completed: ${event.turnId}"
            is KoogEvent.TurnFailed -> "Turn Failed: ${event.message}"
            is KoogEvent.NodeStarted -> "Node Started: ${event.nodeId} in routine ${event.routineId}"
            is KoogEvent.NodeCompleted -> "Node Completed: ${event.nodeId}"
            is KoogEvent.RoutineStarted -> "Routine Started: ${event.routineId}"
            is KoogEvent.AndroidIntentLaunched -> "Android Intent: ${event.action}"
            else -> null
        }
        if (message != null) {
            println("[$tag] $message")
        }
    }
}

/**
 * Orchestration engine for running structured routines (graphs) on top of Koog.
 */
class RoutineOrchestrator(
    private val eventBus: KoogEventBus
) {
    suspend fun runRoutine(routineId: String, input: JsonObject) {
        dispatch(KoogEvent.RoutineStarted(currentTimeMs(), null, routineId))
        // Actual graph execution logic will go here in the next pass
    }

    private fun dispatch(event: KoogEvent) {
        eventBus.dispatch(event)
    }

    private fun currentTimeMs(): Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}
