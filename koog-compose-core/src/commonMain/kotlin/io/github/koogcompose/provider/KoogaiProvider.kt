package io.github.koogcompose.provider

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.cached.CachedPromptExecutor
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.github.koogcompose.session.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * KoogAIProvider — the production [AIProvider] backed by Koog 0.7.2.
 *
 * This is the only file in the library with a hard compile-time
 * dependency on Koog internals. Everything else in the library
 * talks to [AIProvider] — so if Koog's API changes, only this file
 * needs updating.
 *
 * What it does:
 * - Builds a Koog [AIAgent] from the developer's [KoogComposeContext]
 * - Maps [ChatMessage] history → Koog [Message] types
 * - Maps [SecureTool] → Koog [ToolDescriptor]
 * - Maps Koog streaming frames → [AIResponseChunk]
 * - Wires in Koog features: history compression, persistence, caching
 * - Applies LLM parameter overrides (temperature, maxTokens, etc.)
 *
 * Developers never instantiate this directly — it's created internally
 * by [rememberChatState] from the [KoogComposeContext].
 */
internal class KoogAIProvider(
    private val context: KoogComposeContext
) : AIProvider {
    // Build the executor once per context — it's stateless and safe to reuse
    private val executor: PromptExecutor by lazy {
        buildExecutor(context.providerConfig, context.config.responseCache)
    }

    private val model: LLModel by lazy {
        resolveModel(context.providerConfig)
    }

    override fun stream(
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        // Channel bridges the Koog agent coroutine and our Flow
        val channel = Channel<AIResponseChunk>(Channel.UNLIMITED)

        coroutineScope {
            // Koog streaming happens inside a strategy node via requestLLMStreaming()
            // We use a graph strategy with one node that streams and sends to the channel
            val agentStrategy = strategy("koog-compose-stream") {
                val streamNode by node<String, Unit> { _ ->
                    llm.writeSession {
                        // Inject conversation history (all messages except the last user message
                        // which is passed as agentInput to agent.run())
                        history.dropLast(1).forEach { msg ->
                            when (msg.role) {
                                MessageRole.USER ->
                                    appendPrompt { user(msg.content) }
                                MessageRole.ASSISTANT ->
                                    appendPrompt { assistant(msg.content) }
                                MessageRole.SYSTEM -> Unit
                            }
                        }

                        // Core streaming call — this is Koog's actual streaming API
                        val streamFrames = requestLLMStreaming()

                        streamFrames.collect { frame ->
                            val chunk = frame.toAIResponseChunk()
                            if (chunk != null) channel.send(chunk)
                        }
                    }

                    channel.send(AIResponseChunk.End)
                    channel.close()
                }

                edge(nodeStart forwardTo streamNode)
                edge(streamNode forwardTo nodeFinish)
            }

            val agentConfig = AIAgentConfig(
                prompt = prompt("koog-compose") {
                    system(
                        systemPrompt.takeIf { it.isNotBlank() }
                            ?: "You are a helpful assistant."
                    )
                },
                model = model,
                maxAgentIterations = 50
            )

            val koogTools = buildKoogToolRegistry(context.toolRegistry.all)

            val agent = AIAgent(
                promptExecutor = executor,
                agentConfig = agentConfig,
                strategy = agentStrategy,
                toolRegistry = koogTools
            ) {
                // Persistence — keeps agent state across requests
                // Developers can extend this with their own storage provider
                install(Persistence) {
                    storage = InMemoryPersistenceStorageProvider()
                    enableAutomaticPersistence = true
                }
            }

            // Run agent in a child coroutine, collect channel in the parent
            launch {
                try {
                    val userInput = history
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.content ?: ""
                    agent.run(userInput)
                } catch (e: Exception) {
                    channel.trySend(AIResponseChunk.Error(e.message ?: "Agent execution failed"))
                    channel.close()
                }
            }

            // Emit everything the agent sends through the channel
            for (chunk in channel) {
                emit(chunk)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildAgentConfig(
        systemPrompt: String,
        history: List<Message>
    ): AIAgentConfig {
        val llmParams = context.config.llmParams

        return AIAgentConfig(
            prompt = ai.koog.prompt.message.Prompt(
                systemMessage = systemPrompt.takeIf { it.isNotBlank() }
            ).let { prompt ->
                // Inject history into the prompt
                if (history.isNotEmpty()) {
                    prompt.withMessages { history }
                } else prompt
            },
            model = model,
            maxAgentIterations = 50
        )
    }

    private fun buildClientFor(config: ProviderConfig) = when (config) {
        is ProviderConfig.Anthropic -> AnthropicLLMClient(config.apiKey)
        is ProviderConfig.OpenAI -> OpenAILLMClient(config.apiKey)
        is ProviderConfig.Google -> GoogleLLMClient(config.apiKey)
        is ProviderConfig.Ollama ->
            OpenAILLMClient(apiKey = "ollama", baseUrl = config.baseUrl)
        is ProviderConfig.Router -> error("Nested routers are not supported")
    }

    // ── Executor ──────────────────────────────────────────────────────────────

    private fun buildExecutor(config: ProviderConfig, useCache: Boolean): PromptExecutor {
        val base: PromptExecutor = when (config) {
            is ProviderConfig.Anthropic ->
                SingleLLMPromptExecutor(AnthropicLLMClient(config.apiKey))
            is ProviderConfig.OpenAI ->
                SingleLLMPromptExecutor(OpenAILLMClient(config.apiKey))
            is ProviderConfig.Google ->
                SingleLLMPromptExecutor(GoogleLLMClient(config.apiKey))
            is ProviderConfig.Ollama ->
                SingleLLMPromptExecutor(
                    OpenAILLMClient(apiKey = "ollama", baseUrl = config.baseUrl)
                )
            is ProviderConfig.Router -> {
                val clients = config.providers.map { buildClientForProvider(it) }
                MultiLLMPromptExecutor(clients)
            }
        }
        return if (useCache) {
            CachedPromptExecutor(cache = InMemoryPromptCache(), nested = base)
        } else base
    }

    private fun buildClientForProvider(config: ProviderConfig) = when (config) {
        is ProviderConfig.Anthropic -> AnthropicLLMClient(config.apiKey)
        is ProviderConfig.OpenAI -> OpenAILLMClient(config.apiKey)
        is ProviderConfig.Google -> GoogleLLMClient(config.apiKey)
        is ProviderConfig.Ollama -> OpenAILLMClient(apiKey = "ollama", baseUrl = config.baseUrl)
        is ProviderConfig.Router -> error("Nested routers are not supported")
    }

    private fun resolveModel(config: ProviderConfig): LLModel = when (config) {
        is ProviderConfig.Anthropic -> when {
            config.model.contains("sonnet") -> AnthropicModels.Sonnet_4_5
            config.model.contains("haiku") -> AnthropicModels.Haiku_4_5
            config.model.contains("opus") -> AnthropicModels.Opus_4
            else -> AnthropicModels.Sonnet_4_5
        }
        is ProviderConfig.OpenAI -> when {
            config.model.contains("gpt-4o") -> OpenAIModels.Chat.GPT4o
            config.model.contains("gpt-4") -> OpenAIModels.Chat.GPT4Turbo
            else -> OpenAIModels.Chat.GPT4o
        }
        is ProviderConfig.Google -> when {
            config.model.contains("flash") -> GoogleModels.Gemini2_0Flash
            config.model.contains("pro") -> GoogleModels.Gemini2_5Pro
            else -> GoogleModels.Gemini2_0FlashLite
        }
        is ProviderConfig.Ollama ->
            OpenAIModels.custom(config.model)
        is ProviderConfig.Router ->
            resolveModel(config.providers.first())
    }

    private fun buildKoogToolRegistry(tools: List<SecureTool>): KoogToolRegistry {
        if (tools.isEmpty()) return KoogToolRegistry.EMPTY
        return KoogToolRegistry {
            tools.forEach { tool ->
                tool(tool.toKoogToolDescriptor())
            }
        }
    }
}

// ── StreamFrame → AIResponseChunk ────────────────────────────────────────────

/**
 * Maps a Koog [StreamFrame] to our [AIResponseChunk].
 * Returns null for frames we don't surface to the UI layer.
 */
private fun StreamFrame.toAIResponseChunk(): AIResponseChunk? = when (this) {
    is StreamFrame.TextDelta ->
        AIResponseChunk.TextDelta(text)
    is StreamFrame.TextComplete ->
        AIResponseChunk.TextComplete(text)
    is StreamFrame.ReasoningDelta ->
        text?.let { AIResponseChunk.ReasoningDelta(it) }
    is StreamFrame.ToolCallComplete ->
        AIResponseChunk.ToolCallRequest(
            toolName = name,
            args = content.toJsonObjectSafe()
        )
    is StreamFrame.End ->
        AIResponseChunk.End
    else -> null // ToolCallDelta, ReasoningComplete handled internally by Koog
}

// ── SecureTool → ToolDescriptor ───────────────────────────────────────────────

/**
 * Maps our [SecureTool] to Koog's [ToolDescriptor] using the 0.7.2 API.
 *
 * ToolParameterDescriptor(name, description, type: ToolParameterType)
 * ToolDescriptor(name, description, requiredParameters, optionalParameters)
 *
 * We infer required/optional from the "required" field in parametersSchema.
 * We infer ToolParameterType from the "type" field, defaulting to String.
 */
private fun SecureTool.toKoogToolDescriptor(): ToolDescriptor {
    val schema = parametersSchema
        ?: return ToolDescriptor(
            name = name,
            description = description,
            requiredParameters = emptyList(),
            optionalParameters = emptyList()
        )

    val required = mutableListOf<ToolParameterDescriptor>()
    val optional = mutableListOf<ToolParameterDescriptor>()

    schema.entries.forEach { (key, value) ->
        val obj = value as? JsonObject
        val desc = obj?.get("description")
            ?.let { (it as? JsonPrimitive)?.content } ?: key
        val isRequired = obj?.get("required")
            ?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: true
        val typeStr = obj?.get("type")
            ?.let { (it as? JsonPrimitive)?.content } ?: "string"

        val paramType = when (typeStr.lowercase()) {
            "integer", "int" -> ToolParameterType.Integer
            "number", "float", "double" -> ToolParameterType.Number
            "boolean", "bool" -> ToolParameterType.Boolean
            "array" -> ToolParameterType.List
            else -> ToolParameterType.String
        }

        val descriptor = ToolParameterDescriptor(
            name = key,
            description = desc,
            type = paramType
        )

        if (isRequired) required.add(descriptor)
        else optional.add(descriptor)
    }

    return ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = required,
        optionalParameters = optional
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun String.toJsonObjectSafe(): JsonObject = try {
    Json.parseToJsonElement(this) as? JsonObject ?: buildJsonObject {}
} catch (e: Exception) {
    buildJsonObject {}
}