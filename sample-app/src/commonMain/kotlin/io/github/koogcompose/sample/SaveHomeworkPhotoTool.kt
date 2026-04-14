package io.github.koogcompose.sample

import io.github.koogcompose.observability.AgentEvent
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.NoOpEventSink
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.RecoveryHint
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.session.KoogStateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for saving homework photos with full error handling and recovery strategies.
 *
 * Demonstrates:
 * - Classifying errors as transient vs. permanent
 * - Providing recovery hints to guide agent behavior
 * - Defensive catch-all for unknown exceptions
 * - Event emission for audit trails
 */
class SaveHomeworkPhotoTool(
    override val stateStore: KoogStateStore<RobustAppState>,
    private val eventSink: EventSink = NoOpEventSink,
    private val sessionId: String = "",
) : StatefulTool<RobustAppState>() {

    override val name = "SaveHomeworkPhoto"
    override val description = "Saves a homework photo to local storage"
    override val permissionLevel = PermissionLevel.SENSITIVE

    override suspend fun execute(args: JsonObject): ToolResult {
        val photoUri = args["photoUri"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure(
                message = "No photo URI provided — please try taking the photo again.",
                retryable = false,
            )

        return try {
            val savedPath = saveToDisk(photoUri)
            stateStore.update { it.copy(lastHomeworkPhoto = savedPath) }

            eventSink.emit(
                AgentEvent.ToolCalled(
                    sessionId = sessionId,
                    toolName  = name,
                    args      = args,
                    result    = ToolResult.Success("Saved to $savedPath"),
                )
            )

            ToolResult.Success("Photo saved successfully.")

        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            
            when {
                // Transient — safe to retry automatically
                isNetworkRelated(errorMessage) -> ToolResult.Failure(
                    message   = "Network hiccup while saving. Retrying shortly...",
                    retryable = true,
                    recoveryHint = RecoveryHint.RetryAfterDelay,
                )

                // User action required — don't retry, tell the user
                isStorageFull(errorMessage) -> ToolResult.Denied(
                    reason = "Your storage is full. Please free up space and try again.",
                    recoveryHint = RecoveryHint.RequiresUserAction(
                        "Please delete some files and say 'try again'."
                    ),
                )

                // Permission denied
                e is SecurityException -> ToolResult.Denied(
                    reason = "Storage permission was denied. Please grant it in Settings.",
                    recoveryHint = RecoveryHint.RequiresUserAction(
                        "Please go to Settings → Permissions → Storage and grant access."
                    ),
                )

                // Unknown error — log but don't crash
                else -> {
                    emitToolFailure(args, e)
                    ToolResult.Failure(
                        message  = "Couldn't save the photo right now. We'll try again next session.",
                        retryable = false,
                    )
                }
            }
        }
    }

    private suspend fun emitToolFailure(args: JsonObject, e: Exception) {
        eventSink.emit(
            AgentEvent.ToolCalled(
                sessionId = sessionId,
                toolName  = name,
                args      = args,
                result    = ToolResult.Failure(e.message ?: "Unknown"),
            )
        )
    }

    private fun saveToDisk(photoUri: String): String {
        // Stub implementation — in production, save to actual file system
        return "/storage/emulated/0/DCIM/homework_${System.currentTimeMillis()}.jpg"
    }

    private fun isNetworkRelated(message: String): Boolean =
        message.contains("network", ignoreCase = true) ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("connection", ignoreCase = true)

    private fun isStorageFull(message: String): Boolean =
        message.contains("space", ignoreCase = true) ||
        message.contains("full", ignoreCase = true) ||
        message.contains("No space left", ignoreCase = true)
}
