package io.github.koogcompose.sample

import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Placeholder kept for source compatibility while the sample app moves to the
 * shared OnDevice provider bridge. The real Swift bridge is installed through
 * koog-compose-mediapipe's AppleFoundationModelsBridge API.
 */
public class FoundationModelsProvider : AIProvider {
    public fun isAvailable(): Boolean = false

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>,
    ): Flow<AIResponseChunk> = flow {
        emit(AIResponseChunk.Error("FoundationModelsProvider sample shim is not installed."))
    }
}
