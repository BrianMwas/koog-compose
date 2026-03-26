package io.github.koogcompose.tool

import kotlinx.serialization.json.JsonObject

class ToolRegistry private constructor(
    private val tools: Map<String, SecureTool>
) {
    fun resolve(name: String): SecureTool? = tools[name]
    val all: List<SecureTool> get() = tools.values.toList()

    fun toolsRequiring(level: PermissionLevel): List<SecureTool> =
        tools.values.filter { it.permissionLevel >= level }

    fun contains(name: String): Boolean = tools.containsKey(name)

    fun plus(tool: SecureTool): ToolRegistry =
        ToolRegistry(tools + (tool.name to tool))

    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = tools[name] ?: return ToolResult.Failure("Tool not found: $name")
        return tool.execute(args)
    }

    class Builder {
        private val tools = mutableMapOf<String, SecureTool>()

        fun register(tool: SecureTool): Builder = apply {
            if (tools.containsKey(tool.name)) {
                println("koog-compose: ToolRegistry warning — replacing existing tool '${tool.name}'")
            }
            tools[tool.name] = tool
        }

        fun build(): ToolRegistry = ToolRegistry(tools.toMap())
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): ToolRegistry = Builder()
            .apply(block)
            .build()

        val Empty: ToolRegistry = ToolRegistry(emptyMap())
    }
}