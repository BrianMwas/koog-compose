package io.github.koogcompose.provider

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.cached.CachedPromptExecutor
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.github.koogcompose.security.AuditLogger
import io.github.koogcompose.security.GuardedTool
import io.github.koogcompose.security.GuardrailEnforcer
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.session.ToolMessageKind
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.toKoogTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

internal class KoogAIProvider<S>(
    private val context: KoogComposeContext<S>
) : AIProvider {
    private val enforcer: GuardrailEnforcer by lazy {
        GuardrailEnforcer(
            guardrails = context.config.guardrails,
            auditLogger = AuditLogger()
        )
    }

    // Wraps every SecureTool with the enforcer before use
    private fun List<SecureTool>.guarded(): List<SecureTool> =
        map { GuardedTool(it, enforcer) }

    private val executor: PromptExecutor by lazy {
        val base = buildExecutor(context.providerConfig)
        if (context.config.responseCache) {
            CachedPromptExecutor(
                cache = InMemoryPromptCache(maxEntries = null),
                nested = base
            )
        } else {
            base
        }
    }

    private var roundRobinIndex: Int = 0

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        val koogTools = buildKoogToolRegistry(context.resolveEffectiveTools().guarded())
        val toolDescriptors = koogTools.tools.map { it.descriptor }
        val effectiveHistory = history.withPendingAttachments(attachments)
        val attempts = providerAttempts(context.providerConfig)
        var lastError: Throwable? = null

        for ((index, providerConfig) in attempts.withIndex()) {
            val prompt = buildPrompt(
                history = effectiveHistory,
                systemPrompt = systemPrompt,
                providerConfig = providerConfig
            )
            val model = resolveModel(providerConfig)
            var emittedAny = false

            try {
                executor.executeStreaming(prompt, model, toolDescriptors).collect { frame ->
                    val chunk = frame.toAIResponseChunk() ?: return@collect
                    emittedAny = true
                    emit(chunk)
                }
                return@flow
            } catch (error: CancellationException) {
                throw error
            } catch (error: LLMClientException) {
                // Provider-level failure (auth, rate-limit, bad request).
                // Always store as lastError; only retry if no tokens were
                // streamed yet and more attempts remain.
                lastError = error
                if (emittedAny || index == attempts.lastIndex) throw error
            } catch (error: Throwable) {
                lastError = error
                if (emittedAny || index == attempts.lastIndex) throw error
            }
        }

        throw lastError ?: IllegalStateException("No provider attempts were available")
    }

    /**
     * Resolves the [LLModel] for the current [KoogComposeContext].
     * Exposes internal resolution logic to the graph bridge.
     */
    internal fun resolveModelForConfig(): LLModel = resolveModel(context.providerConfig)

    private fun buildPrompt(
        history: List<ChatMessage>,
        systemPrompt: String,
        providerConfig: ProviderConfig
    ): Prompt {
        return prompt(
            id = "koog-compose",
            params = buildPromptParams(providerConfig)
        ) {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }
            messages(history.map(ChatMessage::toKoogMessage))
        }
    }

    private fun buildPromptParams(providerConfig: ProviderConfig): LLMParams {
        val configParams = context.config.llmParams
        val additionalProperties = buildMap<String, JsonElement> {
            configParams?.topP?.let { put("top_p", JsonPrimitive(it)) }
            configParams?.topK?.let { put("top_k", JsonPrimitive(it)) }
            if (!configParams?.stopSequences.isNullOrEmpty()) {
                put(
                    "stop",
                    buildJsonArray {
                        configParams.stopSequences.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
        }.ifEmpty { null }

        return LLMParams(
            temperature = configParams?.temperature ?: providerConfig.defaultTemperature(),
            maxTokens = configParams?.maxTokens ?: providerConfig.defaultMaxTokens(),
            additionalProperties = additionalProperties
        )
    }

    private fun providerAttempts(config: ProviderConfig): List<ProviderConfig> = when (config) {
        is ProviderConfig.Router -> {
            if (config.providers.isEmpty()) {
                emptyList()
            } else {
                when (config.strategy) {
                    RouterStrategy.Fallback -> config.providers
                    RouterStrategy.RoundRobin -> {
                        val startIndex = roundRobinIndex % config.providers.size
                        roundRobinIndex = (roundRobinIndex + 1) % config.providers.size
                        config.providers.drop(startIndex) + config.providers.take(startIndex)
                    }
                }
            }
        }

        else -> listOf(config)
    }
}

/**
 * Creates a [PromptExecutor] for the given provider configuration.
 */
public fun buildExecutor(config: ProviderConfig): PromptExecutor {
    val base: PromptExecutor = when (config) {
        is ProviderConfig.Router -> MultiLLMPromptExecutor(
            config.providers.map(::buildClientForProvider)
        )

        else -> MultiLLMPromptExecutor(listOf(buildClientForProvider(config)))
    }
    return base
}

internal fun buildClientForProvider(config: ProviderConfig): LLMClient = when (config) {
    is ProviderConfig.OpenAI -> OpenAILLMClient(
        apiKey = config.apiKey,
        settings = OpenAIClientSettings(baseUrl = normalizeOpenAIBaseUrl(config.baseUrl))
    )

    is ProviderConfig.Anthropic -> AnthropicLLMClient(
        apiKey = config.apiKey,
        settings = AnthropicClientSettings()
    )

    is ProviderConfig.Google -> GoogleLLMClient(
        apiKey = config.apiKey,
        settings = GoogleClientSettings()
    )

    is ProviderConfig.Ollama -> OllamaClient(config.baseUrl)
    is ProviderConfig.LiteRtLm -> error(
        "koog-compose: LiteRT-LM requires :koog-compose-mediapipe module. " +
            "Use LiteRtLmProvider directly instead of KoogAIProvider."
    )
    is ProviderConfig.OnDevice -> error(
        "koog-compose: OnDevice provider requires :koog-compose-mediapipe module. " +
            "The on-device model runs through OnDeviceProvider, not the standard " +
            "KoogAIProvider. Use koogCompose { provider { onDevice(...) } } and the " +
            "framework will route through OnDeviceProvider automatically."
    )
    is ProviderConfig.Router -> error("Nested routers are not supported")
}

internal fun normalizeOpenAIBaseUrl(baseUrl: String): String =
    baseUrl.removeSuffix("/").removeSuffix("/v1")

internal fun resolveModel(config: ProviderConfig): LLModel = when (config) {
    is ProviderConfig.OpenAI ->
        OpenAIModels.modelsById()[config.model]
            ?: config.model.asCustomModel(OpenAIModels.Chat.GPT4o)

    is ProviderConfig.Anthropic ->
        AnthropicModels.modelsById()[config.model]
            ?: config.model.asCustomModel(AnthropicModels.Sonnet_4_5)

    is ProviderConfig.Google ->
        GoogleModels.modelsById()[config.model]
            ?: config.model.asCustomModel(GoogleModels.Gemini2_0Flash)

    is ProviderConfig.Ollama -> LLModel(
        provider = LLMProvider.Ollama,
        id = config.model,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.Schema.JSON.Basic
        )
    )

    is ProviderConfig.LiteRtLm -> LLModel(
        provider = LLMProvider.Ollama, // reuse Ollama provider type as placeholder
        id = "litertlm-gemma3",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        )
    )

    is ProviderConfig.OnDevice -> LLModel(
        provider = LLMProvider.Ollama,
        id = "on-device-gemma4",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        )
    )

    is ProviderConfig.Router ->
        resolveModel(config.providers.first())
}

internal fun buildKoogToolRegistry(tools: List<SecureTool>): ToolRegistry {
    if (tools.isEmpty()) return ToolRegistry.EMPTY
    return ToolRegistry {
        tools.forEach { tool ->
            tool(tool.toKoogTool())
        }
    }
}

private fun ChatMessage.toKoogMessage(): Message = when (role) {
    MessageRole.USER -> {
        val parts = buildList {
            if (content.isNotBlank()) {
                add(ContentPart.Text(content))
            }
            addAll(attachments.map(Attachment::toKoogContentPart))
        }
        Message.User(parts, RequestMetaInfo.create(Clock.System))
    }

    MessageRole.ASSISTANT ->
        Message.Assistant(content, ResponseMetaInfo.create(Clock.System))

    MessageRole.SYSTEM ->
        Message.System(content, RequestMetaInfo.create(Clock.System))

    MessageRole.TOOL -> when (toolKind) {
        ToolMessageKind.CALL -> Message.Tool.Call(
            id = toolCallId,
            tool = toolName ?: "unknown_tool",
            content = content,
            metaInfo = ResponseMetaInfo.create(Clock.System)
        )

        ToolMessageKind.RESULT -> Message.Tool.Result(
            id = toolCallId,
            tool = toolName ?: "unknown_tool",
            content = content,
            metaInfo = RequestMetaInfo.create(Clock.System)
        )

        null -> Message.Tool.Result(
            id = toolCallId,
            tool = toolName ?: "unknown_tool",
            content = content,
            metaInfo = RequestMetaInfo.create(Clock.System)
        )
    }
}

private fun Attachment.toKoogContentPart(): ContentPart = when (this) {
    is Attachment.Image -> ContentPart.Image(
        content = AttachmentContent.URL(uri),
        format = uri.inferFormat("png"),
        fileName = uri.inferFormat("png") // Use inferFormat for fileName if it's likely a path
    )

    is Attachment.Document -> ContentPart.File(
        content = AttachmentContent.URL(uri),
        format = mimeType.substringAfterLast('/', missingDelimiterValue = uri.inferFormat("txt")),
        mimeType = mimeType,
        fileName = uri.inferFormat("txt") // Same here
    )

    is Attachment.Audio -> ContentPart.Audio(
        content = AttachmentContent.URL(uri),
        format = uri.inferFormat("mp3"),
        fileName = uri.inferFormat("mp3") // Same here
    )
}

private fun List<ChatMessage>.withPendingAttachments(attachments: List<Attachment>): List<ChatMessage> {
    if (attachments.isEmpty()) return this
    val lastUserIndex = indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex == -1) return this

    return mapIndexed { index, message ->
        if (index == lastUserIndex) {
            message.copy(attachments = message.attachments + attachments)
        } else {
            message
        }
    }
}

private fun StreamFrame.toAIResponseChunk(): AIResponseChunk? = when (this) {
    is StreamFrame.TextDelta -> AIResponseChunk.TextDelta(text)
    is StreamFrame.TextComplete -> AIResponseChunk.TextComplete(text)
    is StreamFrame.ReasoningDelta -> text?.let(AIResponseChunk::ReasoningDelta)
    is StreamFrame.ToolCallComplete -> AIResponseChunk.ToolCallRequest(
        toolCallId = id,
        toolName = name,
        args = content.toJsonObjectSafe()
    )

    is StreamFrame.End -> AIResponseChunk.End
    else -> null
}

private fun String.asCustomModel(fallback: LLModel): LLModel = LLModel(
    provider = fallback.provider,
    id = this,
    capabilities = fallback.capabilities,
    contextLength = fallback.contextLength,
    maxOutputTokens = fallback.maxOutputTokens
)

private fun ProviderConfig.defaultTemperature(): Double? = when (this) {
    is ProviderConfig.Anthropic -> temperature
    is ProviderConfig.Google -> temperature
    is ProviderConfig.Ollama -> temperature
    is ProviderConfig.OpenAI -> temperature
    is ProviderConfig.LiteRtLm -> null
    is ProviderConfig.OnDevice -> null
    is ProviderConfig.Router -> providers.firstOrNull()?.defaultTemperature()
}

private fun ProviderConfig.defaultMaxTokens(): Int? = when (this) {
    is ProviderConfig.Anthropic -> maxTokens
    is ProviderConfig.Google -> maxTokens
    is ProviderConfig.Ollama -> maxTokens
    is ProviderConfig.OpenAI -> maxTokens
    is ProviderConfig.LiteRtLm -> maxTokens
    is ProviderConfig.OnDevice -> null
    is ProviderConfig.Router -> providers.firstOrNull()?.defaultMaxTokens()
}

private fun String.toJsonObjectSafe(): JsonObject = try {
    koogProviderJson.parseToJsonElement(this) as? JsonObject ?: buildJsonObject { }
} catch (_: Exception) {
    buildJsonObject { }
}

private fun String.inferFormat(defaultFormat: String): String {
    val sanitized = substringBefore('?').substringBefore('#')
    val extension = sanitized.substringAfterLast('.', missingDelimiterValue = "")
    return extension.takeIf { it.isNotBlank() } ?: defaultFormat
}

private val koogProviderJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
