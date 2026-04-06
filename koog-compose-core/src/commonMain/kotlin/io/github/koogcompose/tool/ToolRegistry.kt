package io.github.koogcompose.tool

import kotlinx.serialization.json.JsonObject

/**
 * Immutable registry of tools available to the runtime.
 */
public class ToolRegistry private constructor(
    private val tools: Map<String, SecureTool>
) {
    public fun resolve(name: String): SecureTool? = tools[name]
    public val all: List<SecureTool>
        get() = tools.values.toList()

    public fun toolsRequiring(level: PermissionLevel): List<SecureTool> =
        tools.values.filter { it.permissionLevel >= level }

    public fun contains(name: String): Boolean = tools.containsKey(name)

    public fun plus(tool: SecureTool): ToolRegistry =
        ToolRegistry(tools + (tool.name to tool))

    public suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = tools[name] ?: return ToolResult.Failure("Tool not found: $name")
        return tool.execute(args)
    }

    public class Builder {
        private val tools = mutableMapOf<String, SecureTool>()

        public fun register(tool: SecureTool): Builder = apply {
            if (tools.containsKey(tool.name)) {
                println("koog-compose: ToolRegistry warning — replacing existing tool '${tool.name}'")
            }
            tools[tool.name] = tool
        }

        public fun build(): ToolRegistry = ToolRegistry(tools.toMap())
    }

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): ToolRegistry = Builder()
            .apply(block)
            .build()

        public val Empty: ToolRegistry = ToolRegistry(emptyMap())
    }
}
