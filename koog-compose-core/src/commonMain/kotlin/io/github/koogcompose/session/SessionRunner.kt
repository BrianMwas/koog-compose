package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.phase.PhaseAwareAgent
import io.github.koogcompose.phase.StructuredPhaseExecutor
import io.github.koogcompose.phase.phaseOutput
import io.github.koogcompose.provider.buildExecutor
import io.github.koogcompose.provider.resolveModel
import io.github.koogcompose.tool.HandoffTool
import io.github.koogcompose.tool.HandoffContext
import io.github.koogcompose.tool.handoffToolName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Multi-agent runtime for a [KoogSession].
 *
 * Exposes the same three-layer activity state model as [PhaseSession]:
 * [activity], [activityDetail], and derived [isRunning].
 *
 * On handoff, activity transitions through Executing("handoff_to_x") and
 * then resets to Thinking when the specialist agent takes over, so the UI
 * always reflects which agent is active and what it is doing.
 */
public class SessionRunner<S>(
    internal val session: KoogSession<S>,
    override val sessionId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : KoogSessionHandle {

    // Build executor once from the session's main agent context.
    // ✅ Correct — build the executor the same way KoogAIProvider does internally
    private val executor: PromptExecutor =
        buildExecutor(session.contextFor(session.mainAgent).providerConfig)

    // ── Activity state ─────────────────────────────────────────────────────

    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    override val activity: StateFlow<AgentActivity> = _activity.asStateFlow()

    private val _activityDetail = MutableStateFlow("")
    override val activityDetail: StateFlow<String> = _activityDetail.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ── Other observable state ─────────────────────────────────────────────

    private val _activeAgentName = MutableStateFlow(session.mainAgent.name)
    public val activeAgentName: StateFlow<String> = _activeAgentName.asStateFlow()

    private val _currentPhase = MutableStateFlow(
        session.mainAgent.phaseRegistry.initialPhase?.name ?: ""
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

    public val appState: StateFlow<S>? = session.stateStore?.stateFlow

    // ── Internal runtime state ─────────────────────────────────────────────

    private var activeAgent: AIAgent<String, String>? = null
    private var activeDefinition: KoogAgentDefinition = session.mainAgent
    private var activeHistorySessionId: String = sessionId
    private var pendingHandoffTarget: String? = null
    private var reasoningBuffer = StringBuilder()

    init {
        scope.launch {
            _activity.collect { _isRunning.value = it.isRunning }
        }
    }

    // ── KoogSessionHandle ──────────────────────────────────────────────────

    override fun send(userMessage: String) {
        scope.launch {
            // Clear sticky states before starting a new turn.
            if (_activity.value.isSticky) {
                _activity.value = AgentActivity.Idle
                _activityDetail.value = ""
            }

            _activity.value = AgentActivity.Thinking
            _activityDetail.value = ""
            _error.value = null
            _turnId.value += 1

            val retryPolicy = session.config.retryPolicy
            var lastError: Throwable? = null
            var delayMs = retryPolicy.initialDelayMs

            repeat(retryPolicy.maxAttempts) { attempt ->
                if (lastError != null) {
                    _activity.value = AgentActivity.Thinking
                    _activityDetail.value = ""
                    delay(delayMs)
                    delayMs *= 2
                }
                try {
                    ensureAgentCreated(activeDefinition)
                    runTurn(userMessage)
                    lastError = null
                    return@repeat
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < retryPolicy.maxAttempts - 1) {
                        activeAgent = null
                    }
                }
            }

            if (lastError != null) {
                _error.value = lastError
                _activity.value = AgentActivity.Failed(lastError)
                _activityDetail.value = lastError.message ?: "Unknown error"
            }
        }
    }

    override fun reset() {
        scope.launch {
            session.store.delete(sessionId)
            activeAgent = null
            activeDefinition = session.mainAgent
            activeHistorySessionId = sessionId
            pendingHandoffTarget = null
            reasoningBuffer.clear()
            _activeAgentName.value = session.mainAgent.name
            _currentPhase.value = session.mainAgent.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
            _turnId.value = 0
            _activity.value = AgentActivity.Idle
            _activityDetail.value = ""
        }
    }

    // ── Turn execution ─────────────────────────────────────────────────────

    private suspend fun runTurn(userMessage: String) {
        var input = userMessage
        var hopsRemaining = session.config.maxAgentIterations

        while (hopsRemaining-- > 0) {
            val agent = requireNotNull(activeAgent) { "Agent not initialised." }

            pendingHandoffTarget = null

            val response = agent.run(input, activeHistorySessionId)
            _lastResponse.value = response

            val handoffName = pendingHandoffTarget ?: run {
                // No handoff — turn completed cleanly.
                _activity.value = AgentActivity.Completed(response)
                _activityDetail.value = response
                return
            }
            pendingHandoffTarget = null

            val targetDefinition = session.findAgent(handoffName)
                ?: error("koog-compose: Handoff target '$handoffName' not registered.")

            val handoffTool = findHandoffTool(activeDefinition, handoffName)
            handoffTool?.onHandoff?.invoke(HandoffContext(session.stateStore))

            val continueHistory = handoffTool?.continueHistory ?: true
            val historyId = if (continueHistory) sessionId
            else "$sessionId:${targetDefinition.name}"

            if (!continueHistory) {
                SessionStoreChatHistoryProvider(session.store, historyId).clearHistory()
            }

            activeHistorySessionId = historyId
            swapAgent(definition = targetDefinition, historySessionId = historyId)

            // After swap, reset to Thinking for the specialist's turn.
            _activity.value = AgentActivity.Thinking
            _activityDetail.value = ""
            reasoningBuffer.clear()

            input = userMessage
        }

        error(
            "koog-compose: Handoff chain exceeded maxAgentIterations " +
                    "(${session.config.maxAgentIterations}). Possible routing loop."
        )
    }

    // ── Activity event bridge ──────────────────────────────────────────────

    private fun onToolExecuted(toolName: String) {
        if (toolName.startsWith("handoff_to_")) {
            pendingHandoffTarget = toolName.removePrefix("handoff_to_")
        }
    }

    /**
     * One-shot structured extraction outside of any phase.
     *
     * ```kotlin
     * val sentiment = runner.extract<SentimentResult>(
     *     input = "I love this app but onboarding is confusing",
     *     instructions = "Extract sentiment as JSON."
     * )
     * ```
     */
    private suspend inline fun <reified T> extract(
        input: String,
        instructions: String,
        retries: Int = session.config.retryPolicy.structureFixingRetries,
        examples: List<T> = emptyList(),
    ): T {
        val context  = session.contextFor(session.mainAgent)
        val model    = resolveModel(context.providerConfig)
        val executor = buildExecutor(context.providerConfig)
        val output   = phaseOutput<T>(retries = retries, examples = examples)

        val extractPrompt = prompt("koog-compose-extract") {
            if (instructions.isNotBlank()) system(instructions)
            user { +input }
        }

        return StructuredPhaseExecutor(executor, output, model)
            .executeStructured(extractPrompt)
    }

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
            }
            is KoogEvent.ReasoningCompleted -> {
                _activity.value = AgentActivity.Thinking
                _activityDetail.value = ""
                reasoningBuffer.clear()
            }
            is KoogEvent.ProviderChunkReceived -> {
                when (event.chunk) {
                    is AIResponseChunk.TextDelta -> {
                        if (_activity.value is AgentActivity.Reasoning) {
                            _activity.value = AgentActivity.Thinking
                        }
                        _activityDetail.value = (_activityDetail.value) + event.chunk.text
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
                onToolExecuted(event.toolName)
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

    // ── Agent creation and swap ────────────────────────────────────────────

    private fun ensureAgentCreated(definition: KoogAgentDefinition) {
        if (activeAgent != null) return
        activeAgent = buildAgent(definition, activeHistorySessionId)
    }

    private fun swapAgent(definition: KoogAgentDefinition, historySessionId: String) {
        activeDefinition = definition
        _activeAgentName.value = definition.name
        _currentPhase.value = definition.phaseRegistry.initialPhase?.name ?: ""
        activeAgent = buildAgent(definition, historySessionId)
    }

    private fun buildAgent(
        definition: KoogAgentDefinition,
        historySessionId: String,
    ): AIAgent<String, String> {
        val context = session.contextFor(definition)

        val agentEventHandlers = EventHandlers {
            onEvent { event ->
                handleActivityEvent(event)
                session.eventHandlers.dispatch(event)
            }
        }

        return PhaseAwareAgent.create(
            context        = context,
            promptExecutor = executor,
            sessionId      = historySessionId,
            store          = session.store,
            strategyName   = "session-${definition.name}",
            tokenSink      = _responseStream,
            eventHandlers  = agentEventHandlers,
            currentTurnId  = { _turnId.value.toString() },
            coroutineScope = scope,
        )
    }

    private fun findHandoffTool(
        definition: KoogAgentDefinition,
        targetAgentName: String,
    ): HandoffTool? {
        val toolName = handoffToolName(targetAgentName)
        return definition.phaseRegistry.all
            .flatMap { it.toolRegistry.all }
            .filterIsInstance<HandoffTool>()
            .firstOrNull { it.name == toolName }
    }
}
