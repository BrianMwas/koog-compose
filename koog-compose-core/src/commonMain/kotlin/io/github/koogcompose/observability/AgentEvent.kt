package io.github.koogcompose.observability

import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Represents a significant lifecycle moment in the agent runtime.
 *
 * Consumers implement [EventSink] and receive these events in real time.
 * Use them to route to Firebase, Datadog, local logs, or any custom backend.
 */
public sealed class AgentEvent {
    abstract val timestampMs: Long

    public data class SessionStarted(
        val sessionId: String,
        val initialPhase: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class PhaseTransitioned(
        val sessionId: String,
        val from: String,
        val to: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class ToolCalled(
        val sessionId: String,
        val toolName: String,
        val args: JsonObject,
        val result: ToolResult,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class GuardrailDenied(
        val sessionId: String,
        val toolName: String,
        val reason: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class LLMRequested(
        val sessionId: String,
        val phase: String,
        val turnId: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class AgentStuck(
        val sessionId: String,
        val phase: String,
        val consecutiveCount: Int,
        val fallbackMessage: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()

    public data class TurnFailed(
        val sessionId: String,
        val phase: String,
        val turnId: String,
        val message: String,
        override val timestampMs: Long = currentTimeMs(),
    ) : AgentEvent()
}

// expect/actual so commonMain compiles without java.lang.System
internal expect fun currentTimeMs(): Long
