package io.github.koogcompose.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.koogcompose.session.AIProvider
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext

@Composable
fun rememberChatState(
    provider: AIProvider,
    context: KoogComposeContext,
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
fun rememberChatState(
    provider: AIProvider,
    userId: String? = null,
    context: KoogComposeContext.Builder.() -> Unit
): ChatState = rememberChatState(
    provider = provider,
    context = KoogComposeContext(context),
    userId = userId
)

@Composable
fun rememberChatState(
    context: KoogComposeContext,
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
fun rememberChatState(
    userId: String? = null,
    context: KoogComposeContext.Builder.() -> Unit
): ChatState {
    val resolvedContext = remember(context) { KoogComposeContext(context) }
    return rememberChatState(
        context = resolvedContext,
        userId = userId
    )
}
