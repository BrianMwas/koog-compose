package io.github.koogcompose.workflow

import io.github.koogcompose.layout.LayoutDirectiveProcessor
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Bridges the agent's tool-calling loop to the Layer-1 layout engine.
 *
 * When the LLM calls [EmitLayoutDirectiveTool.TOOL_NAME], the directive JSON is parsed
 * and submitted to [processor], which updates [LayoutDirectiveProcessor.layoutState] and
 * therefore the on-screen UI. This is what makes the agent able to *drive* the layout —
 * the session auto-registers it whenever a `layout { }` block is configured.
 *
 * Streaming note: the atomic directive is committed here on tool execution. The streaming
 * preview (components animating in *as the JSON arrives*) is layered on top by
 * [io.github.koogcompose.session.StreamingFeature] tapping tool-call argument deltas; both
 * paths converge because [io.github.koogcompose.layout.AgentLayoutDirective]s are idempotent
 * per correlationId.
 */
public class EmitLayoutDirectiveSecureTool(
    processor: LayoutDirectiveProcessor,
    runtime: WorkflowRuntime? = null,
) : SecureTool {
    private val bridge = EmitLayoutDirectiveTool(processor, runtime)

    override val name: String = EmitLayoutDirectiveTool.TOOL_NAME

    override val description: String =
        "Modify the user interface by emitting a layout directive (show, hide, reorder, " +
            "swap, or lock a component in a slot). Each call performs exactly one directive."

    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    /** Reuse the published tool schema so the LLM sees the real parameter set. */
    override val parametersSchema: JsonObject =
        EmitLayoutDirectiveTool.json
            .parseToJsonElement(EmitLayoutDirectiveTool.TOOL_SCHEMA_JSON)
            .jsonObject["parameters"]!!
            .jsonObject

    override suspend fun execute(args: JsonObject): ToolResult =
        when (val result = bridge.handle(args.toString())) {
            is EmitLayoutDirectiveTool.ToolResult.Submitted ->
                ToolResult.Success("Layout directive submitted (correlationId=${result.correlationId}).")
            is EmitLayoutDirectiveTool.ToolResult.Error ->
                ToolResult.Failure(result.message, retryable = true)
        }
}
