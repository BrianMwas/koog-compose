package io.github.koogcompose.event

import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Represents a discrete event within the koog-compose runtime.
 * Events are used for logging, tracing, UI state monitoring, and orchestration.
 */
public sealed interface KoogEvent {
    public val timestampMs: Long
    public val turnId: String?
    public val phaseName: String?

    /** Triggered when an AI request is rejected due to rate limits. */
    public data class RateLimited(
        override val timestampMs: Long,
        override val phaseName: String?
    ) : KoogEvent {
        override val turnId: String? = null
    }

    /** Triggered when a new conversation turn begins. */
    public data class TurnStarted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val userMessageId: String,
        val text: String,
        val attachmentCount: Int
    ) : KoogEvent

    /** Fired when the agent visits the same phase with equivalent input N times consecutively. */
    public data class AgentStuck(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val consecutiveCount: Int,
        val fallbackMessage: String
    ) : KoogEvent

    /** Triggered when a provider starts a new generation pass. */
    public data class ProviderPassStarted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val availableTools: List<String>
    ) : KoogEvent

    /** Triggered when a raw chunk is received from the provider. */
    public data class ProviderChunkReceived(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val chunk: AIResponseChunk
    ) : KoogEvent

    /** Triggered when a provider pass completes successfully. */
    public data class ProviderPassCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int
    ) : KoogEvent

    /** Triggered when a provider pass fails. */
    public data class ProviderPassFailed(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val message: String
    ) : KoogEvent

    /** Triggered when the AI requests to execute a tool. */
    public data class ToolCallRequested(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject
    ) : KoogEvent

    /** Triggered when a sensitive or critical tool requires user approval. */
    public data class ToolConfirmationRequested(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val permissionLevel: PermissionLevel,
        val confirmationMessage: String
    ) : KoogEvent

    /** Triggered when the conversation moves from one phase to another. */
    public data class PhaseTransitioned(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val fromPhaseName: String?,
        val toPhaseName: String
    ) : KoogEvent

    /** Triggered when a tool execution finishes. */
    public data class ToolExecutionCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val result: ToolResult,
        val isPhaseTransition: Boolean = false
    ) : KoogEvent

    /** Triggered when a conversation turn completes fully. */
    public data class TurnCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val assistantMessageId: String,
        val toolNames: List<String>
    ) : KoogEvent

    /** Triggered when a turn fails. */
    public data class TurnFailed(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val message: String
    ) : KoogEvent

    /** Triggered when the user or system cancels an active turn. */
    public data class TurnCancelled(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?
    ) : KoogEvent

    // ── Routine & Graph Orchestration Events ────────────────────────────────

    /** Triggered when a specific node in an orchestration graph begins execution. */
    public data class NodeStarted(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val nodeId: String,
        val routineId: String
    ) : KoogEvent

    /** Triggered when a node in the graph completes. */
    public data class NodeCompleted(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val nodeId: String,
        val routineId: String,
        val output: JsonObject
    ) : KoogEvent

    /** Triggered when an entire routine (subgraph) starts. */
    public data class RoutineStarted(
        override val timestampMs: Long,
        override val phaseName: String?,
        val routineId: String
    ) : KoogEvent {
        override val turnId: String? = null
    }

    // ── Android-Specific Orchestration Events ────────────────────────────────

    /** Triggered when an Android intent is launched as part of a tool call. */
    public data class AndroidIntentLaunched(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val action: String,
        val data: String? = null,
        val packageName: String? = null
    ) : KoogEvent

    /** Triggered when an Activity result is received. */
    public data class ActivityResultReceived(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val resultCode: Int,
        val data: String? = null
    ) : KoogEvent

    /** Triggered when a screenshot is captured for context. */
    public data class ScreenshotCaptured(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val path: String
    ) : KoogEvent

    /** Triggered when a background sync phase begins or ends. */
    public data class SyncPhaseStarted(
        override val timestampMs: Long,
        override val phaseName: String?,
        val syncId: String,
        val source: String
    ) : KoogEvent {
        override val turnId: String? = null
    }

    /**
     * Fired whenever a background job changes state.
     *
     * @param jobId  The stable identifier supplied at [BackgroundJobProvider.enqueue].
     * @param status One of: "scheduled" | "running" | "completed" | "failed"
     * @param error  Human-readable failure reason; non-null only when [status] == "failed".
     */
    public data class BackgroundJobStatus(
        override val timestampMs: Long,
        override val phaseName: String?,
        val jobId: String,
        val status: String,
        val error: String? = null,
    ) : KoogEvent {
        override val turnId: String? = null

        public companion object {
            public const val SCHEDULED: String = "scheduled"
            public const val RUNNING: String = "running"
            public const val COMPLETED: String = "completed"
            public const val FAILED: String = "failed"
        }
    }
}

/**
 * Handler for dispatching and receiving [KoogEvent]s.
 */
public interface KoogEventHandler {
    public fun onEvent(event: KoogEvent): Unit
}

/**
 * Registry for multiple [KoogEventHandler]s.
 */
public class KoogEventBus {
    private val handlers = mutableListOf<KoogEventHandler>()

    public fun register(handler: KoogEventHandler): Unit {
        handlers.add(handler)
    }

    public fun unregister(handler: KoogEventHandler): Unit {
        handlers.remove(handler)
    }

    /**
     * Dispatches an event to all registered handlers.
     * Marked as public to allow orchestration layers in other modules to emit events.
     */
    public fun dispatch(event: KoogEvent): Unit {
        handlers.forEach { it.onEvent(event) }
    }
}
