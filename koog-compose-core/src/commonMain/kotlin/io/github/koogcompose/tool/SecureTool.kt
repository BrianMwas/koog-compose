package io.github.koogcompose.tool

import kotlinx.serialization.json.JsonObject

/**
 * Describes a single parameter of a [SecureTool] for inline schema rendering and bridge conversion.
 */
public data class ParameterDescriptor(
    val name: String,
    val type: String,
    val required: Boolean = true,
    val description: String = "",
    val default: String? = null
)

/**
 * Permission levels for AI tools within the koog-compose framework.
 */
public enum class PermissionLevel {
    /** Tools that are safe to execute without user intervention. */
    SAFE,
    /** Tools that require explicit user confirmation before execution. */
    SENSITIVE,
    /** Tools that require high-friction confirmation. */
    CRITICAL;
}

/**
 * The result of a tool execution.
 */
public sealed class ToolResult {
    /** Execution was successful with the provided [output]. */
    public data class Success(val output: String) : ToolResult()
    /** Execution was denied, either by the user or the system. */
    public data class Denied(val reason: String = "User denied permission") : ToolResult()
    /** Execution failed due to an [message]. */
    public data class Failure(val message: String) : ToolResult()
}

/**
 * Represents a tool that can be executed by the AI agent with security constraints.
 */
public interface SecureTool {
    /** The unique name of the tool, used by the LLM to identify it. */
    public val name: String
    /** A description of what the tool does, used by the LLM for discovery. */
    public val description: String
    /** The framework-level [PermissionLevel] required for this tool. */
    public val permissionLevel: PermissionLevel
    /** Optional JSON schema for the tool's parameters. */
    public val parametersSchema: JsonObject? get() = null

    /**
     * Describes the parameters this tool accepts.
     */
    public fun describeParameters(): List<ParameterDescriptor> = emptyList()
    
    /** 
     * Optional list of platform-specific system permissions required by this tool.
     */
    public val requiredSystemPermissions: List<String> get() = emptyList()

    /** Executes the tool with the provided [args]. */
    public suspend fun execute(args: JsonObject): ToolResult

    /** Returns a human-readable message to show the user when requesting confirmation. */
    public fun confirmationMessage(args: JsonObject): String = description
}

/**
 * A simple tool that does nothing, useful for testing or placeholders.
 */
public class NoOpTool(
    override val name: String,
    override val description: String,
    override val permissionLevel: PermissionLevel
) : SecureTool {
    override suspend fun execute(args: JsonObject): ToolResult {
        return ToolResult.Success("No-op tool executed")
    }
}
