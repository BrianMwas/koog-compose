package io.github.koogcompose.testing

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.session.ToolMessageKind
import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

internal class FakePromptExecutorAIProvider(
    private val promptExecutor: FakePromptExecutor
) : AIProvider {

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        val prompt = prompt(
            id = "koog-compose-testing",
        ) {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }
            messages(history.withPendingAttachments(attachments).map(ChatMessage::toPromptMessage))
        }
        val responses = promptExecutor.execute(
            prompt = prompt,
            model = TEST_MODEL,
            tools = context.resolveEffectiveTools().map(SecureTool::toToolDescriptor)
        )

        responses.forEach { response ->
            emit(response.toResponseChunk())
        }
        emit(AIResponseChunk.End)
    }
}

private fun SecureTool.toToolDescriptor(): ToolDescriptor {
    return ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = emptyList(),
        optionalParameters = emptyList()
    )
}

private fun ChatMessage.toPromptMessage(): Message = when (role) {
    MessageRole.USER -> {
        val parts = buildList {
            if (content.isNotBlank()) {
                add(ContentPart.Text(content))
            }
            addAll(attachments.map(Attachment::toContentPart))
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

        ToolMessageKind.RESULT,
        null -> Message.Tool.Result(
            id = toolCallId,
            tool = toolName ?: "unknown_tool",
            content = content,
            metaInfo = RequestMetaInfo.create(Clock.System)
        )
    }
}

private fun Attachment.toContentPart(): ContentPart = when (this) {
    is Attachment.Image -> ContentPart.Image(
        content = AttachmentContent.URL(uri),
        format = uri.inferFormat("png"),
        fileName = uri.inferFormat("png")
    )

    is Attachment.Document -> ContentPart.File(
        content = AttachmentContent.URL(uri),
        format = mimeType.substringAfterLast('/', missingDelimiterValue = uri.inferFormat("txt")),
        mimeType = mimeType,
        fileName = displayName.ifBlank { uri.inferFormat("txt") }
    )

    is Attachment.Audio -> ContentPart.Audio(
        content = AttachmentContent.URL(uri),
        format = uri.inferFormat("mp3"),
        fileName = displayName.ifBlank { uri.inferFormat("mp3") }
    )
}

private fun List<ChatMessage>.withPendingAttachments(attachments: List<Attachment>): List<ChatMessage> {
    if (attachments.isEmpty()) {
        return this
    }

    val lastUserIndex = indexOfLast { message -> message.role == MessageRole.USER }
    if (lastUserIndex == -1) {
        return this
    }

    return mapIndexed { index, message ->
        if (index == lastUserIndex) {
            message.copy(attachments = message.attachments + attachments)
        } else {
            message
        }
    }
}

private fun Message.Response.toResponseChunk(): AIResponseChunk = when (this) {
    is Message.Assistant -> AIResponseChunk.TextComplete(content)
    is Message.Reasoning -> AIResponseChunk.ReasoningDelta(content)
    is Message.Tool.Call -> AIResponseChunk.ToolCallRequest(
        toolCallId = id,
        toolName = tool,
        args = content.toJsonObjectSafe()
    )
}

private fun String.toJsonObjectSafe(): JsonObject = try {
    testProviderJson.parseToJsonElement(this) as? JsonObject ?: buildJsonObject { }
} catch (_: Exception) {
    buildJsonObject { }
}

private fun String.inferFormat(defaultFormat: String): String {
    val sanitized = substringBefore('?').substringBefore('#')
    val extension = sanitized.substringAfterLast('.', missingDelimiterValue = "")
    return extension.takeIf { it.isNotBlank() } ?: defaultFormat
}

private val TEST_MODEL: LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = "koog-compose-testing",
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Tools
    )
)

private val testProviderJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
