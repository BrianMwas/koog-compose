package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.phase.PhaseAwareAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Compose/ViewModel-friendly runtime for a single phase-aware agent.
 *
 * **What changed from the original:**
 * - Manual `messageHistory` list removed — [ChatMemory] (installed inside
 *   [PhaseAwareAgent]) now owns LLM history, keyed by [sessionId].
 * - `persistTurn` removed — [SessionStoreChatHistoryProvider] writes history
 *   into [store] automatically after each turn via the Koog feature pipeline.
 * - `agent.run(userMessage, sessionId)` is now called with two args so
 *   [ChatMemory] can scope history correctly per session.
 * - [eventHandlers] and [store] are forwarded to [PhaseAwareAgent.create] so
 *   the Koog [EventHandler] feature is installed and callbacks fire correctly.
 * - [_turnId] is exposed as a lambda to [PhaseAwareAgent] so the event bridge
 *   can stamp events with the correct turn number.
 */
public class PhaseSession<S>(
    private val context: KoogComposeContext<S>,
    private val executor: PromptExecutor,
    override val sessionId: String,
    private val store: SessionStore = InMemorySessionStore(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val strategyName: String = "koog-compose-phases",
    private val eventHandlers: EventHandlers = EventHandlers.Empty,
) : KoogSessionHandle {

    // ── Observable UI state ────────────────────────────────────────────────

    private val _currentPhase = MutableStateFlow(
        context.activePhaseName ?: context.phaseRegistry.initialPhase?.name ?: ""
    )
    public val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    public val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private val _turnId = MutableStateFlow(0)
    public val turnId: StateFlow<Int> = _turnId.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val responseStream: Flow<String> = _responseStream.asSharedFlow()

    /** Direct access to the typed app state — collect in Compose UI. */
    public val appState: StateFlow<S>? = context.stateStore?.stateFlow

    // ── Stuck detection ────────────────────────────────────────────────────
    private var lastPhaseInput: String? = null
    private var consecutivePhaseHits: Int = 0

    // ── Agent lifecycle ────────────────────────────────────────────────────
    // Agent is created once per session and reused across turns.
    // ChatMemory handles history threading — no need to rebuild on resume.
    private var agent: AIAgent<String, String>? = null



    // ── Public API ─────────────────────────────────────────────────────────

    override fun send(userMessage: String) {
        scope.launch {
            _isRunning.value = true
            _error.value = null
            _turnId.value += 1

            // ── Stuck detection ────────────────────────────────────────────
            val stuckConfig = context.config.stuckDetection
            if (stuckConfig != null) {
                val key = "${_currentPhase.value}:$userMessage"
                if (lastPhaseInput == key) {
                    consecutivePhaseHits++
                } else {
                    consecutivePhaseHits = 1
                    lastPhaseInput = key
                }
                if (consecutivePhaseHits >= stuckConfig.threshold) {
                    consecutivePhaseHits = 0
                    lastPhaseInput = null
                    _lastResponse.value = stuckConfig.fallbackMessage
                    eventHandlers.dispatch(
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
                    ensureAgentCreated()
                    // Two-arg run: ChatMemory uses sessionId to scope history.
                    val result = requireNotNull(agent) { "Agent failed to initialise." }
                        .run(userMessage, sessionId)
                    _lastResponse.value = result
                    lastError = null
                    return@repeat
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < retryPolicy.maxAttempts - 1) {
                        // Reset so ensureAgentCreated() rebuilds on next attempt.
                        // ChatMemory will reload history from the store automatically.
                        agent = null
                    }
                }
            }

            if (lastError != null) {
                _error.value = lastError
                eventHandlers.dispatch(
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

    override fun reset() {
        scope.launch {
            // Delete from SessionStore — ChatMemory will find no history on next turn.
            store.delete(sessionId)
            agent = null
            _currentPhase.value = context.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
            _turnId.value = 0
            lastPhaseInput = null
            consecutivePhaseHits = 0
        }
    }

    /**
     * Forces a phase transition without an LLM turn.
     * Useful for host-app overrides (e.g. a Cancel button).
     */
    public fun forceTransitionTo(phaseName: String) {
        requireNotNull(context.phaseRegistry.resolve(phaseName)) {
            "koog-compose: Phase '$phaseName' not found in registry."
        }
        _currentPhase.value = phaseName
    }

    // ── Agent creation ─────────────────────────────────────────────────────

    private fun ensureAgentCreated() {
        if (agent != null) return
        agent = PhaseAwareAgent.create(
            context = context,
            promptExecutor = executor,
            sessionId = sessionId,
            store = store,
            strategyName = strategyName,
            tokenSink = _responseStream,
            eventHandlers = eventHandlers,
            currentTurnId = { _turnId.value.toString() },
            coroutineScope = scope,
        )
    }
}