package io.github.koogcompose.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.koogcompose.session.AIProvider
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext

/**
 * Creates and remembers a [ChatState] for the current composition.
 *
 * The session is automatically closed when the composable leaves composition.
 *
 * Usage — minimal:
 * ```kotlin
 * val chatState = rememberChatState(
 *     provider = KoogAIProvider(KoogProviderConfig.Anthropic(apiKey = "...")),
 *     context = koogCompose {
 *         prompt { default { "You are a helpful assistant." } }
 *     }
 * )
 * ```
 *
 * Usage — banking:
 * ```kotlin
 * val chatState = rememberChatState(
 *     provider = KoogAIProvider(KoogProviderConfig.Anthropic(apiKey = "...")),
 *     userId = currentUser.id
 * ) {
 *     prompt {
 *         enforce { "Never transfer funds without confirmation." }
 *         default { "You are a secure banking assistant." }
 *         session { "Balance: KES 12,500" }
 *     }
 *     tools {
 *         register(SendMoneyTool())
 *         register(GetBalanceTool())
 *     }
 *     config {
 *         rateLimitPerMinute = 10
 *         auditLoggingEnabled = true
 *     }
 * }
 * ```
 */
@Composable
fun rememberChatState(
    provider: AIProvider,
    context: KoogComposeContext,
    userId: String? = null
): ChatState {
    val scope = rememberCoroutineScope()

    val chatState = remember(provider, context, userId) {
        val session = ChatSession(
            context = context,
            provider = provider,
            scope = scope,
            userId = userId
        )
        ChatState(session = session, scope = scope)
    }

    DisposableEffect(chatState) {
        onDispose { chatState.close() }
    }

    return chatState
}

/**
 * Convenience overload — builds the [KoogComposeContext] inline via DSL.
 *
 * ```kotlin
 * val chatState = rememberChatState(provider = provider) {
 *     prompt {
 *         enforce { "Never transfer funds without confirmation." }
 *         default { "You are a banking assistant." }
 *     }
 *     tools {
 *         register(SendMoneyTool())
 *     }
 * }
 * ```
 */
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