package io.github.koogcompose.event

import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Represents a discrete event within the koog-compose runtime.
 * Events are used for logging, tracing, UI state monitoring, and orchestration.
 */
sealed interface KoogEvent {
    val timestampMs: Long
    val turnId: String?
    val phaseName: String?

    /** Triggered when an AI request is rejected due to rate limits. */
    data class RateLimited(
        override val timestampMs: Long,
        override val phaseName: String?
    ) : KoogEvent {
        override val turnId: String? = null
    }

    /** Triggered when a new conversation turn begins. */
    data class TurnStarted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val userMessageId: String,
        val text: String,
        val attachmentCount: Int
    ) : KoogEvent

    /** Triggered when a provider starts a new generation pass. */
    data class ProviderPassStarted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val availableTools: List<String>
    ) : KoogEvent

    /** Triggered when a raw chunk is received from the provider. */
    data class ProviderChunkReceived(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val chunk: AIResponseChunk
    ) : KoogEvent

    /** Triggered when a provider pass completes successfully. */
    data class ProviderPassCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int
    ) : KoogEvent

    /** Triggered when a provider pass fails. */
    data class ProviderPassFailed(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val passIndex: Int,
        val message: String
    ) : KoogEvent

    /** Triggered when the AI requests to execute a tool. */
    data class ToolCallRequested(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject
    ) : KoogEvent

    /** Triggered when a sensitive or critical tool requires user approval. */
    data class ToolConfirmationRequested(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val permissionLevel: PermissionLevel,
        val confirmationMessage: String
    ) : KoogEvent

    /** Triggered when the conversation moves from one phase to another. */
    data class PhaseTransitioned(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val fromPhaseName: String?,
        val toPhaseName: String
    ) : KoogEvent

    /** Triggered when a tool execution finishes. */
    data class ToolExecutionCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val toolCallId: String?,
        val toolName: String,
        val result: ToolResult,
        val isPhaseTransition: Boolean = false
    ) : KoogEvent

    /** Triggered when a conversation turn completes fully. */
    data class TurnCompleted(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val assistantMessageId: String,
        val toolNames: List<String>
    ) : KoogEvent

    /** Triggered when a turn fails. */
    data class TurnFailed(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?,
        val message: String
    ) : KoogEvent

    /** Triggered when the user or system cancels an active turn. */
    data class TurnCancelled(
        override val timestampMs: Long,
        override val turnId: String,
        override val phaseName: String?
    ) : KoogEvent

    // ── Routine & Graph Orchestration Events ────────────────────────────────

    /** Triggered when a specific node in an orchestration graph begins execution. */
    data class NodeStarted(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val nodeId: String,
        val routineId: String
    ) : KoogEvent

    /** Triggered when a node in the graph completes. */
    data class NodeCompleted(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val nodeId: String,
        val routineId: String,
        val output: JsonObject
    ) : KoogEvent

    /** Triggered when an entire routine (subgraph) starts. */
    data class RoutineStarted(
        override val timestampMs: Long,
        override val phaseName: String?,
        val routineId: String
    ) : KoogEvent {
        override val turnId: String? = null
    }

    // ── Android-Specific Orchestration Events ────────────────────────────────

    /** Triggered when an Android intent is launched as part of a tool call. */
    data class AndroidIntentLaunched(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val action: String,
        val data: String? = null,
        val packageName: String? = null
    ) : KoogEvent

    /** Triggered when an Activity result is received. */
    data class ActivityResultReceived(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val resultCode: Int,
        val data: String? = null
    ) : KoogEvent

    /** Triggered when a screenshot is captured for context. */
    data class ScreenshotCaptured(
        override val timestampMs: Long,
        override val turnId: String?,
        override val phaseName: String?,
        val path: String
    ) : KoogEvent

    /** Triggered when a background sync phase begins or ends. */
    data class SyncPhaseStarted(
        override val timestampMs: Long,
        override val phaseName: String?,
        val syncId: String,
        val source: String
    ) : KoogEvent {
        override val turnId: String? = null
    }

    /** Triggered when a WorkManager job is scheduled or updated. */
    data class BackgroundJobStatus(
        override val timestampMs: Long,
        override val phaseName: String?,
        val jobId: String,
        val status: String
    ) : KoogEvent {
        override val turnId: String? = null
    }
}

/**
 * Handler for dispatching and receiving [KoogEvent]s.
 */
interface KoogEventHandler {
    fun onEvent(event: KoogEvent)
}

/**
 * Registry for multiple [KoogEventHandler]s.
 */
class KoogEventBus internal constructor() {
    private val handlers = mutableListOf<KoogEventHandler>()

    fun register(handler: KoogEventHandler) {
        handlers.add(handler)
    }

    fun unregister(handler: KoogEventHandler) {
        handlers.remove(handler)
    }

    internal fun dispatch(event: KoogEvent) {
        handlers.forEach { it.onEvent(event) }
    }
}
