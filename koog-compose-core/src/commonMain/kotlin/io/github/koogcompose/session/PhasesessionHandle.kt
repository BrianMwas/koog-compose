package io.github.koogcompose.session

import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.layout.DefaultLayoutDirectiveProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts [PhaseSession] to [KoogSessionHandle].
 *
 * Every property delegates directly to [delegate] — including [activity] and
 * [activityDetail] which are now owned by [PhaseSession] itself. The handle
 * is a thin pass-through; it adds no state of its own.
 */
public class PhaseSessionHandle<S>(
    private val delegate: PhaseSession<S>,
) : KoogSessionHandle {

    public val context: KoogComposeContext<S>
        get() = delegate.context

    override val sessionId: String
        get() = delegate.sessionId

    // Activity state — delegated directly from PhaseSession.
    override val activity: StateFlow<AgentActivity>
        get() = delegate.activity

    override val activityDetail: StateFlow<String>
        get() = delegate.activityDetail

    override val isRunning: StateFlow<Boolean>
        get() = delegate.isRunning

    override val error: StateFlow<Throwable?>
        get() = delegate.error

    override val responseStream: Flow<String>
        get() = delegate.responseStream

    override fun send(userMessage: String): Unit = delegate.send(userMessage)

    override fun reset(): Unit = delegate.reset()

    /**
     * Resume at a named [phaseName] from any external trigger — push notification,
     * deep link, background task.
     *
     * @param phaseName The phase to resume at.
     * @param sessionId Override the conversation ID (defaults to [this.sessionId]).
     * @param userMessage Optional user message. When null, a sentinel is used so
     *   nothing is written to history.
     * @throws IllegalArgumentException if the phase is not found.
     */
    override fun supportsResumeAt(): Boolean = true

    override fun resumeAt(phaseName: String, sessionId: String, userMessage: String?): Unit =
        delegate.resumeAt(phaseName, sessionId, userMessage)

    override val toolCallCounts: StateFlow<Map<String, Int>>
        get() = delegate.toolCallCounts

    public val appState: StateFlow<S>?
        get() = delegate.appState

    public val currentPhase: StateFlow<String>
        get() = delegate.currentPhase

    public val lastResponse: StateFlow<String?>
        get() = delegate.lastResponse

    public val turnId: StateFlow<Int>
        get() = delegate.turnId

    /** Non-null when the session was built with a `layout { }` block. */
    public val layoutProcessor: DefaultLayoutDirectiveProcessor?
        get() = delegate.layoutProcessor
}

/**
 * Creates a [PhaseSessionHandle] for a single-agent context.
 *
 * [executor] is passed explicitly — [PhaseSession] is wired directly to
 * Koog's AIAgent pipeline and requires a real [PromptExecutor]. The caller
 * (ViewModel) owns the executor lifetime.
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
 * Every property delegates directly to [delegate], including [activity] and
 * [activityDetail] which are owned by [SessionRunner].
 */
public class SessionRunnerHandle<S>(
    private val delegate: SessionRunner<S>,
) : KoogSessionHandle {

    public val context: KoogComposeContext<S>
        get() = delegate.session.contextFor(delegate.session.mainAgent)

    override val sessionId: String
        get() = delegate.sessionId

    // Activity state — delegated directly from SessionRunner.
    override val activity: StateFlow<AgentActivity>
        get() = delegate.activity

    override val activityDetail: StateFlow<String>
        get() = delegate.activityDetail

    override val isRunning: StateFlow<Boolean>
        get() = delegate.isRunning

    override val error: StateFlow<Throwable?>
        get() = delegate.error

    override val responseStream: Flow<String>
        get() = delegate.responseStream

    override fun send(userMessage: String): Unit = delegate.send(userMessage)

    override fun reset(): Unit = delegate.reset()

    /**
     * Resume the agent at a named [phaseName] from any external trigger —
     * push notification, deep link, background task.
     *
     * @param phaseName The phase to resume at.
     * @param sessionId Override the conversation ID (defaults to [this.sessionId]).
     * @param userMessage Optional user message to feed into the turn. When
     *   null, a sentinel is used so nothing is written to history.
     * @throws io.github.koogcompose.session.UnknownPhaseException if no registered
     *   agent owns the given phase.
     */
    override fun supportsResumeAt(): Boolean = true

    override fun resumeAt(phaseName: String, sessionId: String, userMessage: String?): Unit =
        delegate.resumeAt(phaseName, sessionId, userMessage)

    override val toolCallCounts: StateFlow<Map<String, Int>>
        get() = delegate.toolCallCounts

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
 * The executor is built inside [SessionRunner] from the session's provider
 * config — call sites never need to import [PromptExecutor] directly.
 *
 * @param testExecutor Optional executor for testing. When null, built from
 *   the session's provider config.
 */
public fun <S> multiAgentHandle(
    session: KoogSession<S>,
    sessionId: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    testExecutor: PromptExecutor? = null,
): SessionRunnerHandle<S> = SessionRunnerHandle(
    SessionRunner(
        session      = session,
        sessionId    = sessionId,
        scope        = scope,
        testExecutor = testExecutor,
    )
)