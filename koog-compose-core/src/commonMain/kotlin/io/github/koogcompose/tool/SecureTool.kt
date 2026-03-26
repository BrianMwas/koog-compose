package io.github.koogcompose.tool

import kotlinx.serialization.json.JsonObject

enum class PermissionLevel {
    SAFE,
    SENSITIVE,
    CRITICAL,
}


sealed class ToolResult {
    data class Success(val output: String): ToolResult()
    data class Denied(val reason: String = "User denied permission") : ToolResult()
    data class Failure(val message: String): ToolResult()
}

interface SecureTool {
    val name: String
    val description: String
    val permissionLevel: PermissionLevel
    val parametersSchema: JsonObject? get() = null

    suspend fun execute(args: JsonObject): ToolResult

    fun confirmationMessage(args: JsonObject): String = description
}


class NoOpTool(
    override val name: String,
    override val description: String,
    override val permissionLevel: PermissionLevel
) : SecureTool {
    override suspend fun execute(args: JsonObject): ToolResult {
        return ToolResult.Success("No-op tool executed")
    }
}

