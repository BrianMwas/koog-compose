package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.phase.PhaseAwareAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
/**
 * Compose/ViewModel-friendly runtime for a phase-aware agent.
 *
 * Owns the agent lifecycle, exposes observable state for the UI,
 * and delegates persistence to a [SessionStore].
 *
 * Intended to live inside a ViewModel:
 * ```kotlin
 * class ChatViewModel(
 *     context: KoogComposeContext<AppState>,
 *     executor: PromptExecutor
 * ) : ViewModel() {
 *     val session = PhaseSession(
 *         context   = context,
 *         executor  = executor,
 *         sessionId = "user_brian",
 *         scope     = viewModelScope
 *     )
 * }
 * ```
 *
 * @param S          Your app state type (e.g. `AppState`).
 * @param context    Fully configured [KoogComposeContext].
 * @param executor   Koog [PromptExecutor] for LLM calls.
 * @param sessionId  Stable conversation identifier.
 * @param store      Persistence layer. Defaults to [InMemorySessionStore].
 * @param scope      Use `viewModelScope` — cancelled automatically on clear.
 */
public class PhaseSession<S>(
    private val context: KoogComposeContext<S>,
    private val executor: PromptExecutor,
    public val sessionId: String,
    private val store: SessionStore = InMemorySessionStore(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val strategyName: String = "koog-compose-phases"
) {
    // ── Observable UI state ────────────────────────────────────────────────

    private val _currentPhase = MutableStateFlow(
        context.activePhaseName ?: context.phaseRegistry.initialPhase?.name ?: ""
    )
    public val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    public var isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    public val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private val _turnId = MutableStateFlow(0)
    public val turnId: StateFlow<Int> = _turnId.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    public val error: StateFlow<Throwable?> = _error.asStateFlow()

    /**
     * Direct access to the typed app state — collect in Compose UI.
     * Null if no [KoogStateStore] was configured via `initialState { }`.
     */
    public val appState: StateFlow<S>? = context.stateStore?.stateFlow

    // Agent is created once and reused across all turns.
    private var agent: AIAgent<String, String>? = null
    private var sessionInitialised = false

    // Add alongside your existing state flows
    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    public val responseStream: Flow<String> = _responseStream.asSharedFlow()

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Sends [userMessage] to the agent.
     * Initialises or resumes from [store] on the first call.
     */
    public fun send(userMessage: String): Unit {
        scope.launch {
            _isRunning.value = true
            _error.value = null
            _turnId.value += 1


            try {
                ensureInitialised()
                val result = requireNotNull(agent) { "Agent failed to initialise." }
                    .run(userMessage)
                _lastResponse.value = result
                persistTurn(userMessage, result)
            } catch (e: Throwable) {
                _error.value = e
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * Clears persisted session and resets all in-memory state.
     * The next [send] starts fresh from the initial phase.
     */
    public fun reset(): Unit {
        scope.launch {
            store.delete(sessionId)
            agent = null
            sessionInitialised = false
            _currentPhase.value = context.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
        }
    }

    /**
     * Forces a phase transition without an LLM turn.
     * Useful for host-app overrides (e.g. a Cancel button).
     */
    public fun forceTransitionTo(phaseName: String): Unit {
        requireNotNull(context.phaseRegistry.resolve(phaseName)) {
            "koog-compose: Phase '$phaseName' not found in registry."
        }
        _currentPhase.value = phaseName
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    // Replace ensureInitialised()
    private suspend fun ensureInitialised() {
        if (sessionInitialised) return

        val savedSession = store.load(sessionId)
        val activeContext = if (savedSession != null) {
            _currentPhase.value = savedSession.currentPhaseName
            context.withPhase(savedSession.currentPhaseName)
        } else {
            context
        }

        val tappingExecutor = StreamTappingExecutor(executor, _responseStream)
        agent = PhaseAwareAgent.create(activeContext, tappingExecutor, strategyName)
        sessionInitialised = true
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private suspend fun persistTurn(userMessage: String, assistantResponse: String) {
        val existing = store.load(sessionId)
        val history = existing?.messageHistory?.toMutableList() ?: mutableListOf()

        history += SessionMessage(role = "user", content = userMessage)
        history += SessionMessage(role = "assistant", content = assistantResponse)

        store.save(
            sessionId,
            AgentSession(
                sessionId        = sessionId,
                currentPhaseName = _currentPhase.value,
                messageHistory   = history,
                createdAt        = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds()
            )
        )
    }
}



private fun AIAgent<*, *>.currentSystemPrompt(): String =
    runCatching { "" }.getOrDefault("")
