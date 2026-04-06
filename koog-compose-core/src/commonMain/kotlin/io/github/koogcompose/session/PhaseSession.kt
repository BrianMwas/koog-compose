package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.KoogEvent
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

    // ── Stuck detection tracking ───────────────────────────────────────────
    private var lastPhaseInput: String? = null
    private var consecutivePhaseHits: Int = 0

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

            // ── Stuck detection ────────────────────────────────────────────
            val stuckConfig = context.config.stuckDetection
            if (stuckConfig != null) {
                if (lastPhaseInput == "${_currentPhase.value}:$userMessage") {
                    consecutivePhaseHits++
                } else {
                    consecutivePhaseHits = 1
                    lastPhaseInput = "${_currentPhase.value}:$userMessage"
                }

                if (consecutivePhaseHits >= stuckConfig.threshold) {
                    consecutivePhaseHits = 0
                    lastPhaseInput = null
                    _lastResponse.value = stuckConfig.fallbackMessage
                    context.eventHandlers.dispatch(
                        KoogEvent.AgentStuck(
                            timestampMs = Clock.System.now().toEpochMilliseconds(),
                            turnId = _turnId.value.toString(),
                            phaseName = _currentPhase.value,
                            consecutiveCount = stuckConfig.threshold,
                            fallbackMessage = stuckConfig.fallbackMessage
                        )
                    )
                    _isRunning.value = false
                    return@launch
                }
            }

            // ── Retry with backoff ─────────────────────────────────────────
            val retryPolicy = context.config.retryPolicy
            var lastError: Throwable? = null
            var delayMs = retryPolicy.initialDelayMs

            repeat(retryPolicy.maxAttempts) { attempt ->
                if (lastError != null) {
                    kotlinx.coroutines.delay(delayMs)
                    delayMs *= 2
                }
                try {
                    ensureInitialised()
                    val result = requireNotNull(agent) { "Agent failed to initialise." }
                        .run(userMessage)
                    _lastResponse.value = result
                    persistTurn(userMessage, result)
                    lastError = null
                    return@repeat  // success — exit retry loop
                } catch (e: Throwable) {
                    lastError = e
                    // reset agent on failure so ensureInitialised rebuilds it
                    if (attempt < retryPolicy.maxAttempts - 1) {
                        agent = null
                        sessionInitialised = false
                    }
                }
            }

            // All attempts exhausted
            if (lastError != null) {
                _error.value = lastError
                context.eventHandlers.dispatch(
                    KoogEvent.TurnFailed(
                        timestampMs = Clock.System.now().toEpochMilliseconds(),
                        turnId = _turnId.value.toString(),
                        phaseName = _currentPhase.value,
                        message = lastError!!.message ?: "Unknown error after ${retryPolicy.maxAttempts} attempts"
                    )
                )
            }

            _isRunning.value = false
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

        agent = PhaseAwareAgent.create(
            context = activeContext,
            promptExecutor = executor,
            strategyName = strategyName,
            tokenSink = _responseStream
        )
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
