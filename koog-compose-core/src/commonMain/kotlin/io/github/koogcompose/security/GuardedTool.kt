package io.github.koogcompose.security

import io.github.koogcompose.observability.AgentEvent
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.NoOpEventSink
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.ValidationResult
import kotlinx.serialization.json.JsonObject

/**
 * Wraps a [io.github.koogcompose.tool.SecureTool] with guardrail enforcement.
 *
 * All tool calls flow through [GuardrailEnforcer] before reaching
 * the underlying tool. This is the single enforcement choke point.
 *
 * Created internally by [io.github.koogcompose.tool.ToolRegistry] — tool authors never see this.
 */
internal class GuardedTool(
    private val delegate: SecureTool,
    private val enforcer: GuardrailEnforcer,
    private val userId: String? = null,
    private val sessionId: String = "",
    private val eventSink: EventSink = NoOpEventSink,
) : SecureTool by delegate {

    override suspend fun execute(args: JsonObject): ToolResult {
        // 0. Arg validation — catches hallucinated fields/types before anything else runs
        when (val validation = delegate.validateArgs(args)) {
            is ValidationResult.Invalid -> return ToolResult.Failure(
                "Invalid args for ${delegate.name}: ${validation.reason}"
            )
            is ValidationResult.Valid -> Unit
        }

        // 1. Guardrail check (rate limits, allowlists)
        val denial = enforcer.validate(delegate.name, args, userId)
        if (denial != null) {
            eventSink.emit(
                AgentEvent.GuardrailDenied(
                    sessionId = sessionId,
                    toolName  = delegate.name,
                    reason    = denial.toString(),
                )
            )
            return denial
        }

        // 2. Permission gate — SENSITIVE/CRITICAL require confirmation
        if (delegate.permissionLevel != PermissionLevel.SAFE) {
            val confirmed = enforcer.requestConfirmation(
                toolName = delegate.name,
                message  = delegate.confirmationMessage(args),
                level    = delegate.permissionLevel
            )
            if (!confirmed) {
                eventSink.emit(
                    AgentEvent.GuardrailDenied(
                        sessionId = sessionId,
                        toolName  = delegate.name,
                        reason    = "User did not confirm ${delegate.name}",
                    )
                )
                return ToolResult.Denied("User did not confirm ${delegate.name}")
            }
        }

        // 3. Execute and emit ToolCalled regardless of success/failure
        val result = delegate.execute(args)
        eventSink.emit(
            AgentEvent.ToolCalled(
                sessionId = sessionId,
                toolName  = delegate.name,
                args      = args,
                result    = result,
            )
        )
        return result
    }
}