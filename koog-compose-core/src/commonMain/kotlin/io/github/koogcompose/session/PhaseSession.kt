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
 * ## Activity state model
 * Exposes a three-layer state model inspired by Scion:
 *  - [activity] — the current [AgentActivity] (Idle, Reasoning, Thinking,
 *    Executing, WaitingForInput, Blocked, Completed, Failed)
 *  - [activityDetail] — freeform context for the current activity
 *  - [isRunning] — derived from activity for backward compatibility
 *
 * Sticky states (Blocked, Completed, Failed) persist until the next [send].
 * They are cleared at the top of send(), not in reset(), so a completed agent
 * is resumable without wiping session history.
 *
 * ## Reasoning support
 * When the provider emits [AIResponseChunk.ReasoningDelta], the activity
 * transitions to [AgentActivity.Reasoning] and reasoning tokens accumulate
 * in [activityDetail]. When reasoning ends ([AIResponseChunk.TextDelta] arrives),
 * activity transitions to [AgentActivity.Thinking]. Models that don't emit
 * reasoning tokens skip Reasoning entirely: Idle → Thinking.
 */
public class PhaseSession<S>(
    public val context: KoogComposeContext<S>,
    private val executor: PromptExecutor,
    override val sessionId: String,
    private val store: SessionStore = InMemorySessionStore(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val strategyName: String = "koog-compose-phases",
    private val eventHandlers: EventHandlers = EventHandlers.Empty,
) : KoogSessionHandle {

    // ── Activity state ─────────────────────────────────────────────────────

    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    override val activity: StateFlow<AgentActivity> = _activity.asStateFlow()

    private val _isRunningDerived = MutableStateFlow(false)


    private val _activityDetail = MutableStateFlow("")
    override val activityDetail: StateFlow<String> = _activityDetail.asStateFlow()

    // Derived from activity — backward-compatible with existing code that reads isRunning.
    override val isRunning: StateFlow<Boolean> = MutableStateFlow(false).also { derived ->
        scope.launch {
            _activity.collect { derived.value = it.isRunning }
        }
    }.let {
        // Return the underlying StateFlow via the activity map.
        // We use a dedicated MutableStateFlow updated via collect to avoid
        // needing stateIn (which requires a lifecycle scope we don't own).
        _isRunningDerived
    }



    // ── Other observable state ─────────────────────────────────────────────

    private val _currentPhase = MutableStateFlow(
        context.activePhaseName ?: context.phaseRegistry.initialPhase?.name ?: ""
    )
    public val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    public val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private val _turnId = MutableStateFlow(0)
    public val turnId: StateFlow<Int> = _turnId.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val responseStream: Flow<String> = _responseStream.asSharedFlow()

    public val appState: StateFlow<S>? = context.stateStore?.stateFlow

    // ── Stuck detection tracking ───────────────────────────────────────────
    private var lastPhaseInput: String? = null
    private var consecutivePhaseHits: Int = 0

    // ── Agent lifecycle ────────────────────────────────────────────────────

    private var agent: AIAgent<String, String>? = null

    init {
        // Keep _isRunningDerived in sync with _activity.
        scope.launch {
            _activity.collect { _isRunningDerived.value = it.isRunning }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    override fun send(userMessage: String) {
        scope.launch {
            // Clear sticky states before starting — per Scion's "Action over pondering":
            // a completed or failed agent is resumable without a full reset().
            if (_activity.value.isSticky) {
                _activity.value = AgentActivity.Idle
                _activityDetail.value = ""
            }

            _activity.value = AgentActivity.Thinking
            _activityDetail.value = ""
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
                    // Blocked is sticky — stays until next send().
                    _activity.value = AgentActivity.Blocked
                    _activityDetail.value = stuckConfig.fallbackMessage
                    eventHandlers.dispatch(
                        KoogEvent.AgentStuck(
                            timestampMs      = Clock.System.now().toEpochMilliseconds(),
                            turnId           = _turnId.value.toString(),
                            phaseName        = _currentPhase.value,
                            consecutiveCount = stuckConfig.threshold,
                            fallbackMessage  = stuckConfig.fallbackMessage,
                        )
                    )
                    return@launch
                }
            }

            // ── Event-driven activity transitions ──────────────────────────
            // Install a per-turn event handler that drives activity state from
            // the events PhaseAwareAgent emits. This bridges Koog's event pipeline
            // into our AgentActivity model.
            val turnEventHandlers = EventHandlers {
                onEvent { event -> handleActivityEvent(event) }
                // Forward to caller's handlers too.
                onEvent { event -> eventHandlers.dispatch(event) }
            }

            // ── Retry with backoff ─────────────────────────────────────────
            val retryPolicy = context.config.retryPolicy
            var lastError: Throwable? = null
            var delayMs = retryPolicy.initialDelayMs

            repeat(retryPolicy.maxAttempts) { attempt ->
                if (lastError != null) {
                    _activity.value = AgentActivity.Thinking
                    _activityDetail.value = ""
                    kotlinx.coroutines.delay(delayMs)
                    delayMs *= 2
                }
                try {
                    ensureAgentCreated(turnEventHandlers)
                    val result = requireNotNull(agent) { "Agent failed to initialise." }
                        .run(userMessage, sessionId)
                    _lastResponse.value = result
                    // Completed is sticky — persists until next send().
                    _activity.value = AgentActivity.Completed(result)
                    _activityDetail.value = result
                    lastError = null
                    return@repeat  // success — exit retry loop
                } catch (e: Throwable) {
                    lastError = e
                    // reset agent on failure so ensureInitialised rebuilds it
                    if (attempt < retryPolicy.maxAttempts - 1) {
                        agent = null
                    }
                }
            }

            if (lastError != null) {
                _error.value = lastError
                // Failed is sticky — persists until next send().
                _activity.value = AgentActivity.Failed(lastError!!)
                _activityDetail.value = lastError!!.message ?: "Unknown error"
                eventHandlers.dispatch(
                    KoogEvent.TurnFailed(
                        timestampMs = Clock.System.now().toEpochMilliseconds(),
                        turnId      = _turnId.value.toString(),
                        phaseName   = _currentPhase.value,
                        message     = lastError!!.message
                            ?: "Unknown error after ${retryPolicy.maxAttempts} attempts",
                    )
                )
            }
        }
    }

    override fun reset() {
        scope.launch {
            store.delete(sessionId)
            agent = null
            _currentPhase.value = context.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
            _turnId.value = 0
            _activity.value = AgentActivity.Idle
            _activityDetail.value = ""
            lastPhaseInput = null
            consecutivePhaseHits = 0
        }
    }

    public fun forceTransitionTo(phaseName: String) {
        requireNotNull(context.phaseRegistry.resolve(phaseName)) {
            "koog-compose: Phase '$phaseName' not found in registry."
        }
        _currentPhase.value = phaseName
    }

    // ── Activity event bridge ──────────────────────────────────────────────

    private var reasoningBuffer = StringBuilder()

    private suspend fun handleActivityEvent(event: KoogEvent) {
        when (event) {
            is KoogEvent.ReasoningStarted -> {
                reasoningBuffer.clear()
                _activity.value = AgentActivity.Reasoning
                _activityDetail.value = ""
            }
            is KoogEvent.ReasoningDelta -> {
                reasoningBuffer.append(event.token)
                _activityDetail.value = reasoningBuffer.toString()
                // Activity stays Reasoning — detail accumulates token by token.
            }
            is KoogEvent.ReasoningCompleted -> {
                // Reasoning done — transition to generating visible response.
                _activity.value = AgentActivity.Thinking
                _activityDetail.value = ""
                reasoningBuffer.clear()
            }
            is KoogEvent.ProviderChunkReceived -> {
                // If we receive a text delta and we're still in Reasoning,
                // the model transitioned without a ReasoningCompleted event.
                // Snap to Thinking defensively.
                when (event.chunk) {
                    is AIResponseChunk.TextDelta -> {
                        if (_activity.value is AgentActivity.Reasoning) {
                            _activity.value = AgentActivity.Thinking
                        }
                        _activityDetail.value += event.chunk.text
                    }
                    is AIResponseChunk.ReasoningDelta -> {
                        if (_activity.value !is AgentActivity.Reasoning) {
                            reasoningBuffer.clear()
                            _activity.value = AgentActivity.Reasoning
                        }
                        reasoningBuffer.append(event.chunk.text)
                        _activityDetail.value = reasoningBuffer.toString()
                    }
                    else -> Unit
                }
            }
            is KoogEvent.ToolCallRequested -> {
                _activity.value = AgentActivity.Executing(event.toolName)
                _activityDetail.value = event.toolName
            }
            is KoogEvent.ToolConfirmationRequested -> {
                _activity.value = AgentActivity.WaitingForInput
                _activityDetail.value = event.confirmationMessage
            }
            is KoogEvent.ToolExecutionCompleted -> {
                // Tool done — back to Thinking for the next LLM pass.
                if (_activity.value is AgentActivity.Executing
                    || _activity.value is AgentActivity.WaitingForInput) {
                    _activity.value = AgentActivity.Thinking
                    _activityDetail.value = ""
                }
            }
            is KoogEvent.PhaseTransitioned -> {
                _currentPhase.value = event.toPhaseName
            }
            else -> Unit
        }
    }

    // ── Agent creation ─────────────────────────────────────────────────────

    private fun ensureAgentCreated(turnEventHandlers: EventHandlers) {
        if (agent != null) return
        agent = PhaseAwareAgent.create(
            context        = context,
            promptExecutor = executor,
            sessionId      = sessionId,
            store          = store,
            strategyName   = strategyName,
            tokenSink      = _responseStream,
            eventHandlers  = turnEventHandlers,
            currentTurnId  = { _turnId.value.toString() },
            coroutineScope = scope,
        )
    }
}
