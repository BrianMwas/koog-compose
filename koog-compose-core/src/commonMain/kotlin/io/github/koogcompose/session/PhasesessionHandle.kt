package io.github.koogcompose.session

import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts [PhaseSession] to [KoogSessionHandle].
 *
 * Exposes [context] so that [rememberChatState(handle, context)] can derive
 * the correct [KoogComposeContext] without casting or reaching into internals.
 */
public class PhaseSessionHandle<S>(
    private val delegate: PhaseSession<S>,
) : KoogSessionHandle {

    public val context: KoogComposeContext<S>
        get() = delegate.context

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

    public val appState: StateFlow<S>?
        get() = delegate.appState

    public val currentPhase: StateFlow<String>
        get() = delegate.currentPhase

    public val lastResponse: StateFlow<String?>
        get() = delegate.lastResponse

    public val turnId: StateFlow<Int>
        get() = delegate.turnId
}

/**
 * Creates a [PhaseSessionHandle] for a single-agent context.
 *
 * The [executor] is passed explicitly here because [PhaseSession] is wired
 * directly to Koog's pipeline and cannot build its own executor — the
 * caller (typically a ViewModel) owns the executor lifetime.
 */
public fun <S> singleAgentHandle(
    context: KoogComposeContext<S>,
    executor: PromptExecutor,
    sessionId: String,
    store: SessionStore = InMemorySessionStore(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): PhaseSessionHandle<S> = PhaseSessionHandle(
    PhaseSession(
        context   = context,
        executor  = executor,
        sessionId = sessionId,
        store     = store,
        scope     = scope,
    )
)

// ── Multi-agent handle ────────────────────────────────────────────────────────

/**
 * Adapts [SessionRunner] to [KoogSessionHandle].
 *
 * [context] exposes the main agent's [KoogComposeContext] for [rememberChatState].
 */
public class SessionRunnerHandle<S>(
    private val delegate: SessionRunner<S>,
) : KoogSessionHandle {

    public val context: KoogComposeContext<S>
        get() = delegate.session.contextFor(delegate.session.mainAgent)

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

    public val activeAgentName: StateFlow<String>
        get() = delegate.activeAgentName

    public val currentPhase: StateFlow<String>
        get() = delegate.currentPhase

    public val lastResponse: StateFlow<String?>
        get() = delegate.lastResponse

    public val turnId: StateFlow<Int>
        get() = delegate.turnId

    public val appState: StateFlow<S>?
        get() = delegate.appState
}

/**
 * Creates a [SessionRunnerHandle] for a multi-agent [KoogSession].
 *
 * The executor is built internally from [session.globalProvider] so the
 * call site (sample app, ViewModel) never needs to import [PromptExecutor]
 * or depend on koog-agents-core directly. [SessionRunner] constructs the
 * executor once and reuses it across all agent builds.
 */
public fun <S> multiAgentHandle(
    session: KoogSession<S>,
    sessionId: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): SessionRunnerHandle<S> = SessionRunnerHandle(
    SessionRunner(
        session = session,
        sessionId = sessionId,
        scope = scope,
        executor = TODO(),
    )
)