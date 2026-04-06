package io.github.koogcompose.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.koogcompose.session.AIProvider
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext

/**
 * Creates and remembers a stable [ChatState] from an AI provider and context.
 *
 * This is the recommended way to initialize chat state in Compose. It:
 * - Creates a [ChatSession] with the given provider and context
 * - Wraps it in a [ChatState] for UI consumption
 * - Automatically cleans up the session when the composable leaves composition
 * - Maintains stability across recompositions
 *
 * @param provider The [AIProvider] (e.g., Anthropic, OpenAI) that will handle streaming responses.
 * @param context The [KoogComposeContext] configured with phases, tools, and instructions.
 * @param userId Optional user identifier for multi-user scenarios or analytics.
 * @return A stable [ChatState] instance tied to this composition.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun ChatScreen() {
 *     val chatState = rememberChatState(
 *         provider = anthropicProvider,
 *         context = koogContext,
 *         userId = "user_123"
 *     )
 *     ChatMessageList(chatState)
 *     ChatInputBar(chatState)
 * }
 * ```
 */
@Composable
public fun rememberChatState(
    provider: AIProvider,
    context: KoogComposeContext<*>,
    userId: String? = null
): ChatState {
    val scope = rememberCoroutineScope()

    val chatState = remember(provider, context, userId) {
        ChatState(
            session = ChatSession(
                initialContext = context,
                provider = provider,
                scope = scope,
                userId = userId
            ),
            scope = scope
        )
    }

    DisposableEffect(chatState) {
        onDispose { chatState.close() }
    }

    return chatState
}

@Composable
public fun rememberChatState(
    provider: AIProvider,
    userId: String? = null,
    context: KoogComposeContext.Builder<Unit>.() -> Unit
): ChatState = rememberChatState(
    provider = provider,
    context = KoogComposeContext<Unit>(context),
    userId = userId
)

@Composable
public fun rememberChatState(
    context: KoogComposeContext<*>,
    userId: String? = null
): ChatState {
    val provider = remember(context) { context.createProvider() }
    return rememberChatState(
        provider = provider,
        context = context,
        userId = userId
    )
}

@Composable
public fun rememberChatState(
    userId: String? = null,
    context: KoogComposeContext.Builder<Unit>.() -> Unit
): ChatState {
    val resolvedContext = remember(context) { KoogComposeContext<Unit>(context) }
    return rememberChatState(
        context = resolvedContext,
        userId = userId
    )
}
