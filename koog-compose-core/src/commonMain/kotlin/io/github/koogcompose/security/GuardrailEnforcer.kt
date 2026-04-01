package io.github.koogcompose.security

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Hard enforcement of [Guardrails] at runtime.
 * This acts as a secondary safety layer behind the system prompt instructions.
 */
internal class GuardrailEnforcer(
    private val guardrails: Guardrails,
    private val auditLogger: AuditLogger
) {
    // Tracks call timestamps per tool for rate limiting: Map<ToolName, List<Timestamp>>
    private val callHistory = mutableMapOf<String, MutableList<Long>>()
    
    // Tracks currently scheduled jobs (for maxScheduledJobs enforcement)
    private var activeJobCount = 0

    // In GuardrailEnforcer
    var onConfirmationRequired: suspend (toolName: String, message: String, level: PermissionLevel) -> Boolean =
        { _, _, _ -> true } // default: auto-approve, override in ViewModel

    suspend fun requestConfirmation(
        toolName: String,
        message: String,
        level: PermissionLevel
    ): Boolean = onConfirmationRequired(toolName, message, level)
    /**
     * Validates a tool call against defined guardrails.
     * @return [ToolResult.Denied] if guardrails are violated, null otherwise.
     */
    suspend fun validate(
        toolName: String, 
        args: JsonObject, 
        userId: String?
    ): ToolResult.Denied? {
        val now = Clock.System.now().toEpochMilliseconds()

        // 1. Check Rate Limits
        guardrails.toolRateLimits[toolName]?.let { limit ->
            val windowStart = now - limit.window.inWholeMilliseconds
            val recentCalls = callHistory.getOrPut(toolName) { mutableListOf() }
            
            // Purge old entries
            recentCalls.removeAll { it < windowStart }
            
            if (recentCalls.size >= limit.max) {
                val reason = "Guardrail: Rate limit exceeded for $toolName (${limit.max} per ${limit.window})"
                auditLogger.logDenied(toolName, args.toString(), reason, userId)
                return ToolResult.Denied(reason)
            }
            recentCalls.add(now)
        }

        // 2. Check WorkManager Tags (Allowlist)
        if (toolName.contains("WorkManager", ignoreCase = true) || toolName.contains("background", ignoreCase = true)) {
            val tag = args["tag"]?.jsonPrimitive?.contentOrNull
            if (guardrails.allowedWorkTags.isNotEmpty() && tag !in guardrails.allowedWorkTags) {
                val reason = "Guardrail: Work tag '$tag' is not in the allowlist."
                auditLogger.logDenied(toolName, args.toString(), reason, userId)
                return ToolResult.Denied(reason)
            }
            
            if (activeJobCount >= guardrails.maxScheduledJobs) {
                val reason = "Guardrail: Maximum background jobs reached (${guardrails.maxScheduledJobs})."
                auditLogger.logDenied(toolName, args.toString(), reason, userId)
                return ToolResult.Denied(reason)
            }
        }

        // 3. Check Intent Actions (Allowlist)
        if (toolName.contains("Intent", ignoreCase = true)) {
            val action = args["action"]?.jsonPrimitive?.contentOrNull
            if (guardrails.allowedIntentActions.isNotEmpty() && action !in guardrails.allowedIntentActions) {
                val reason = "Guardrail: Intent action '$action' is not in the allowlist."
                auditLogger.logDenied(toolName, args.toString(), reason, userId)
                return ToolResult.Denied(reason)
            }
        }

        return null // All checks passed
    }

    /** Call this when a background job starts to track [maxScheduledJobs] */
    fun notifyJobStarted() { activeJobCount++ }
    
    /** Call this when a background job finishes */
    fun notifyJobFinished() { if (activeJobCount > 0) activeJobCount-- }
}
