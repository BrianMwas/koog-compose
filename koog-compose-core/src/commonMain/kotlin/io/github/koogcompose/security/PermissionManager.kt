package io.github.koogcompose.security

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

sealed class PermissionCheckResult {
    object Granted : PermissionCheckResult()
    data class RequiresConfirmation(
        val toolName: String,
        val confirmationMessage: String,
        val permissionLevel: PermissionLevel
    ): PermissionCheckResult()
    data class Denied(val reason: String): PermissionCheckResult()
}


data class PendingConfirmation(
    val tool: SecureTool,
    val args: JsonObject,
    val confirmationMessage: String,
    val permissionLevel: PermissionLevel
)

class PermissionManager(
    private val auditLogger: AuditLogger,
    private val requireConfirmationForSensitive: Boolean = true,
    private val userId: String? = null
) {
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private var onConfirmed: (suspend () -> ToolResult)? = null
    private var onDenied: (suspend () -> ToolResult)? = null
    private var continuation: CancellableContinuation<ToolResult>? = null

    fun check(tool: SecureTool, args: JsonObject): PermissionCheckResult {
        return when (tool.permissionLevel) {
            PermissionLevel.SAFE -> PermissionCheckResult.Granted
            PermissionLevel.SENSITIVE -> {
                if (requireConfirmationForSensitive) {
                    PermissionCheckResult.RequiresConfirmation(
                        toolName = tool.name,
                        confirmationMessage = tool.confirmationMessage(args),
                        permissionLevel = PermissionLevel.SENSITIVE
                    )
                } else {
                    PermissionCheckResult.Granted
                }
            }
            PermissionLevel.CRITICAL -> PermissionCheckResult.RequiresConfirmation(
                toolName = tool.name,
                confirmationMessage = tool.confirmationMessage(args),
                permissionLevel = PermissionLevel.CRITICAL
            )
        }
    }

    suspend fun requestConfirmation(
        tool: SecureTool,
        args: JsonObject,
        executeBlock: suspend () -> ToolResult
    ): ToolResult = suspendCancellableCoroutine { pendingContinuation ->
        continuation = pendingContinuation
        onConfirmed = executeBlock
        onDenied = {
            ToolResult.Denied("User denied confirmation")
        }
        _pendingConfirmation.value = PendingConfirmation(
            tool = tool,
            args = args,
            confirmationMessage = tool.confirmationMessage(args),
            permissionLevel = tool.permissionLevel
        )
        pendingContinuation.invokeOnCancellation {
            if (continuation === pendingContinuation) {
                continuation = null
            }
            clearPending()
        }
    }

    suspend fun onUserConfirmed(): ToolResult {
        val pending = _pendingConfirmation.value
            ?: return ToolResult.Denied("No pending confirmation")
        val execute = onConfirmed
            ?: return ToolResult.Denied("No execution callback registered")
        val pendingContinuation = continuation

        clearPending()
        val result = executeAndAudit(pending.tool.name, pending.args, execute)
        pendingContinuation?.resume(result)
        continuation = null
        return result
    }

    suspend fun onUserDenied(): ToolResult {
        val pending = _pendingConfirmation.value
            ?: return ToolResult.Denied("No pending confirmation")
        val deny = onDenied
            ?: return ToolResult.Denied("No deny callback registered")
        val pendingContinuation = continuation

        clearPending()
        val result = executeAndAudit(pending.tool.name, pending.args, deny)
        pendingContinuation?.resume(result)
        continuation = null
        return result
    }

    val hasPendingConfirmation: Boolean
        get() = _pendingConfirmation.value != null

    fun clearPending() {
        _pendingConfirmation.value = null
        onConfirmed = null
        onDenied = null
    }

    private suspend fun executeAndAudit(
        toolName: String,
        args: JsonObject,
        executeBlock: suspend () -> ToolResult
    ): ToolResult {
        val result = executeBlock()
        when (result) {
            is ToolResult.Success -> auditLogger.logApproved(toolName, args.toString(), userId)
            is ToolResult.Failure -> auditLogger.logFailed(toolName, args.toString(), result.message, userId)
            is ToolResult.Denied -> auditLogger.logDenied(toolName, args.toString(), result.reason, userId)
        }
        return result
    }
}
