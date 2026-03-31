package io.github.koogcompose.provider

import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.flow.Flow

/**
 * Interface for AI providers that support streaming responses.
 */
interface AIProvider {
    /**
     * Streams a response from the AI.
     *
     * @param context The current [KoogComposeContext].
     * @param history The conversation history.
     * @param systemPrompt The effective system instructions.
     * @param attachments Any new attachments from the user.
     */
    fun stream(
        context: KoogComposeContext,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment> = emptyList()
    ): Flow<AIResponseChunk>
}