package io.github.koogcompose.session

import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolRegistry

data class KoogConfig(
    val streamingEnabled: Boolean = true,
    val maxRetries: Int = 3,
    val rateLimitPerMinute: Int = 0,
    val auditLoggingEnabled: Boolean = true,
    val requireConfirmationForSensitive: Boolean = true
) {
    class Builder {
        var streamingEnabled: Boolean = true
        var maxRetries: Int = 3
        var rateLimitPerMinute: Int = 0
        var auditLoggingEnabled: Boolean = true
        var requireConfirmationForSensitive: Boolean = true

        fun build() = KoogConfig(
            streamingEnabled = streamingEnabled,
            maxRetries = maxRetries,
            rateLimitPerMinute = rateLimitPerMinute,
            auditLoggingEnabled = auditLoggingEnabled,
            requireConfirmationForSensitive = requireConfirmationForSensitive
        )
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): KoogConfig =
            Builder().apply(block).build()
    }
}

class KoogComposeContext private constructor(
    val promptStack: PromptStack,
    val config: KoogConfig,
    val toolRegistry: ToolRegistry,
) {
    fun withSessionContext(context: String): KoogComposeContext = KoogComposeContext(
        promptStack = promptStack.withSessionContext(context),
        toolRegistry = toolRegistry,
        config = config
    )

    fun withTool(tool: SecureTool): KoogComposeContext = KoogComposeContext(
        promptStack = promptStack,
        toolRegistry = toolRegistry.plus(tool),
        config = config
    )

    class Builder {
        private var promptStack: PromptStack = PromptStack.Empty
        private var toolRegistry: ToolRegistry = ToolRegistry.Empty
        private var config: KoogConfig = KoogConfig()

        fun prompt(block: PromptStack.Builder.() -> Unit) {
            promptStack = PromptStack(block)
        }

        fun tools(block: ToolRegistry.Builder.() -> Unit) {
            toolRegistry = ToolRegistry(block)
        }

        fun config(block: KoogConfig.Builder.() -> Unit) {
            config = KoogConfig(block)
        }

        fun build(): KoogComposeContext = KoogComposeContext(
            promptStack = promptStack,
            toolRegistry = toolRegistry,
            config = config
        )
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): KoogComposeContext =
            Builder().apply(block).build()
    }
}

fun koogCompose(block: KoogComposeContext.Builder.() -> Unit): KoogComposeContext =
    KoogComposeContext(block)