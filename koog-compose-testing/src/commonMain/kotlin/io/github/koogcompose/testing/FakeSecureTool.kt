package io.github.koogcompose.testing

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.json.JsonObject

public class FakeSecureTool(
    override val name: String = "fake_tool",
    override val description: String = "Testing tool",
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE,
    override val parametersSchema: JsonObject? = null,
    private val confirmationText: String = description,
    private val onExecute: suspend (JsonObject) -> ToolResult = {
        ToolResult.Success(it.toString())
    }
) : SecureTool {
    override suspend fun execute(args: JsonObject): ToolResult = onExecute(args)

    override fun confirmationMessage(args: JsonObject): String = confirmationText
}
