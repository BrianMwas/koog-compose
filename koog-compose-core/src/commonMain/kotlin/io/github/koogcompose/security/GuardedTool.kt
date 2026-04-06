package io.github.koogcompose.security

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
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
    private val userId: String? = null
) : SecureTool by delegate {

    override suspend fun execute(args: JsonObject): ToolResult {
        // 1. Guardrail check (rate limits, allowlists)
        val denial = enforcer.validate(delegate.name, args, userId)
        if (denial != null) return denial

        // 2. Permission gate — SENSITIVE/CRITICAL require confirmation
        if (delegate.permissionLevel != PermissionLevel.SAFE) {
            val confirmed = enforcer.requestConfirmation(
                toolName = delegate.name,
                message  = delegate.confirmationMessage(args),
                level    = delegate.permissionLevel
            )
            if (!confirmed) return ToolResult.Denied("User did not confirm ${delegate.name}")
        }
        return delegate.execute(args)
    }
}