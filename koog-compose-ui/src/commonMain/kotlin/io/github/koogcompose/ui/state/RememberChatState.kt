package io.github.koogcompose.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogSessionHandle

/**
 * Creates and remembers a stable [ChatState] from an AI provider and context.
 *
 * This is the primary entry point for the legacy single-agent [ChatSession] path.
 * The session owns its own LLM loop via [AIProvider] and does not use Koog's
 * [AIAgent] pipeline internally.
 *
 * @param provider The [AIProvider] that handles streaming responses.
 * @param context  The [KoogComposeContext] configured with phases, tools, and instructions.
 * @param userId   Optional user identifier for multi-user scenarios or analytics.
 */
@Composable
public fun rememberChatState(
    provider: AIProvider,
    context: KoogComposeContext<*>,
    userId: String? = null,
): ChatState {
    val scope = rememberCoroutineScope()

    val chatState = remember(provider, context, userId) {
        ChatState(
            session = ChatSession(
                initialContext = context,
                provider = provider,
                scope = scope,
                userId = userId,
            ),
            scope = scope,
        )
    }

    DisposableEffect(chatState) {
        onDispose { chatState.close() }
    }

    return chatState
}

/**
 * Inline DSL variant — builds the [KoogComposeContext] in the call site.
 */
@Composable
public fun rememberChatState(
    provider: AIProvider,
    userId: String? = null,
    context: KoogComposeContext.Builder<Unit>.() -> Unit,
): ChatState = rememberChatState(
    provider = provider,
    context  = KoogComposeContext<Unit>(context),
    userId   = userId,
)

/**
 * Context-only variant — derives the [AIProvider] from the context's configured provider.
 */
@Composable
public fun rememberChatState(
    context: KoogComposeContext<*>,
    userId: String? = null,
): ChatState {
    val provider = remember(context) { context.createProvider() }
    return rememberChatState(
        provider = provider,
        context  = context,
        userId   = userId,
    )
}

/**
 * Fully inline DSL variant with no explicit provider or context arguments.
 */
@Composable
public fun rememberChatState(
    userId: String? = null,
    context: KoogComposeContext.Builder<Unit>.() -> Unit,
): ChatState {
    val resolvedContext = remember(context) { KoogComposeContext<Unit>(context) }
    return rememberChatState(
        context = resolvedContext,
        userId  = userId,
    )
}

/**
 * Creates and remembers a stable [ChatState] backed by a [KoogSessionHandle].
 *
 * This is the entry point for the [PhaseSession] and [SessionRunner] paths, both
 * of which run through Koog's [AIAgent] pipeline rather than [ChatSession]'s own
 * [AIProvider] loop.
 *
 * The bridge works by wrapping the handle in a [HandleBackedChatSession] that
 * implements the [ChatSession] contract — message accumulation, event forwarding,
 * permission stub — so that [ChatState] and every UI component that consumes it
 * (ChatMessageList, EventObserver, ConfirmationObserver) require zero changes.
 *
 * [context] defaults to [handle.context] which is exposed by both
 * [PhaseSessionHandle] (via delegate.context) and [SessionRunnerHandle]
 * (via delegate.session.contextFor(mainAgent)). Pass it explicitly if you
 * need a different context for the UI layer.
 *
 * @param handle  Any [KoogSessionHandle] — [PhaseSessionHandle] or [SessionRunnerHandle].
 * @param context The [KoogComposeContext] to expose on [ChatState.context].
 */
@Composable
public fun rememberChatState(
    handle: KoogSessionHandle,
    context: KoogComposeContext<*>,
): ChatState {
    val scope = rememberCoroutineScope()

    val chatState = remember(handle) {
        ChatState(
            session = HandleBackedChatSession(
                handle = handle,
                scope = scope,
                initialContext = context,
                provider = context.provider,
            ),
            scope = scope,
        )
    }

    DisposableEffect(chatState) {
        onDispose { chatState.close() }
    }

    return chatState
}