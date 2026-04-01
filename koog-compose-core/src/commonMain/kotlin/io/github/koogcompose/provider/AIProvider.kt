package io.github.koogcompose.provider

import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.flow.Flow

public interface AIProvider {
    public fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment> = emptyList()
    ): Flow<AIResponseChunk>
}