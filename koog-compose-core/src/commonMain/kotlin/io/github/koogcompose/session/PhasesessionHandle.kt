package io.github.koogcompose.session

import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts [PhaseSession] to [KoogSessionHandle].
 *
 * Rather than modifying [PhaseSession] directly (preserving it as-is),
 * this thin wrapper delegates all [KoogSessionHandle] calls to the
 * underlying session. The Compose layer receives a [KoogSessionHandle]
 * and never needs to know which runtime backs it.
 *
 * Usage — you rarely construct this directly. Prefer [singleAgentHandle]:
 * ```kotlin
 * val handle = singleAgentHandle(context, executor, "session-id", viewModelScope)
 * val chatState = rememberChatState(handle)
 * ```
 */
public class PhaseSessionHandle<S>(
    private val delegate: PhaseSession<S>,
) : KoogSessionHandle {

    override val sessionId: String
        get() = delegate.sessionId

    override val isRunning: StateFlow<Boolean>
        get() = delegate.isRunning

    override val error: StateFlow<Throwable?>
        get() = delegate.error

    override val responseStream: Flow<String>
        get() = delegate.responseStream

    override fun send(userMessage: String): Unit = delegate.send(userMessage)

    override fun reset(): Unit = delegate.reset()

    /**
     * Direct access to the typed app state — pass through for Compose consumers
     * that want to observe [PhaseSession.appState] directly.
     */
    public val appState: StateFlow<S>?
        get() = delegate.appState
}

/**
 * Convenience factory: wraps a [KoogComposeContext] in a [PhaseSession]
 * and returns it as a [KoogSessionHandle].
 *
 * ```kotlin
 * class ChatViewModel(
 *     context: KoogComposeContext<AppState>,
 *     executor: PromptExecutor
 * ) : ViewModel() {
 *     val handle: KoogSessionHandle = singleAgentHandle(
 *         context   = context,
 *         executor  = executor,
 *         sessionId = "user_brian",
 *         scope     = viewModelScope
 *     )
 * }
 * ```
 */
public fun <S> singleAgentHandle(
    context: KoogComposeContext<S>,
    executor: PromptExecutor,
    sessionId: String,
    store: SessionStore = InMemorySessionStore(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): KoogSessionHandle = PhaseSessionHandle(
    PhaseSession(
        context = context,
        executor = executor,
        sessionId = sessionId,
        store = store,
        scope = scope,
    )
)