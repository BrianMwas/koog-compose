package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.phase.PhaseAwareAgent
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
import ai.koog.agents.chatMemory.feature.ChatMemory

/**
 * Multi-agent runtime for a [KoogSession].
 *
 * Owns the agent-swap lifecycle: receives user messages, runs the active agent,
 * detects handoff tool calls, and activates the target specialist.
 *
 * History threading:
 * - continueHistory = true  → specialist shares the base [sessionId], so [ChatMemory]
 *   gives it the full conversation history.
 * - continueHistory = false → specialist receives a derived ID ("$sessionId:agentName"),
 *   so [ChatMemory] gives it a clean context window. The provider for that derived ID
 *   is created fresh, and its history is wiped before the first turn so the specialist
 *   truly starts empty even if the slot was previously used.
 *
 * Handoff detection:
 * - Moved from regex-on-response-text to tool-name interception at agent-build time.
 * - [EventHandlers] passed to [PhaseAwareAgent.create] fires [onToolExecuted] when the
 *   Koog EventHandler feature reports a tool call completing. The handoff target is
 *   captured there and consumed at the top of the next hop in [runTurn].
 */
public class SessionRunner<S>(
    internal val session: KoogSession<S>,
    override val sessionId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : KoogSessionHandle {

    // Build the executor once from the session's configured provider.
    // KoogAIProvider wraps KoogComposeContext and implements PromptExecutor directly,
    // so we construct a minimal context for the main agent and cast its provider.
    // This keeps PromptExecutor out of the public multiAgentHandle API so callers
    // don't need a direct koog-agents-core dependency.
    private val executor: PromptExecutor =
        session.contextFor(session.mainAgent).createProvider() as PromptExecutor

    // ── Observable UI state ────────────────────────────────────────────────

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

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val responseStream: Flow<String> = _responseStream.asSharedFlow()

    public val appState: StateFlow<S>? = session.stateStore?.stateFlow

    // ── Internal runtime state ─────────────────────────────────────────────

    private var activeAgent: AIAgent<String, String>? = null
    private var activeDefinition: KoogAgentDefinition = session.mainAgent

    // The history session ID used for the current active agent.
    // Normally equals sessionId, but diverges when continueHistory=false triggers a swap.
    private var activeHistorySessionId: String = sessionId

    // Handoff target set by onToolExecuted() during a turn, consumed at the top of
    // the next hop. Using a field rather than a return value keeps runTurn() clean
    // and avoids threading the signal through agent.run()'s return value.
    private var pendingHandoffTarget: String? = null

    // ── KoogSessionHandle ──────────────────────────────────────────────────

    override fun send(userMessage: String) {
        scope.launch {
            _isRunning.value = true
            _error.value = null
            _turnId.value += 1

            val retryPolicy = session.config.retryPolicy
            var lastError: Throwable? = null
            var delayMs = retryPolicy.initialDelayMs

            repeat(retryPolicy.maxAttempts) { attempt ->
                if (lastError != null) {
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

            if (lastError != null) _error.value = lastError
            _isRunning.value = false
        }
    }

    override fun reset() {
        scope.launch {
            session.store.delete(sessionId)
            activeAgent = null
            activeDefinition = session.mainAgent
            activeHistorySessionId = sessionId
            pendingHandoffTarget = null
            _activeAgentName.value = session.mainAgent.name
            _currentPhase.value = session.mainAgent.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
            _turnId.value = 0
        }
    }

    // ── Turn execution ─────────────────────────────────────────────────────

    private suspend fun runTurn(userMessage: String) {
        var input = userMessage
        var hopsRemaining = session.config.maxAgentIterations

        while (hopsRemaining-- > 0) {
            val agent = requireNotNull(activeAgent) { "Agent not initialised." }

            // pendingHandoffTarget is set by onToolExecuted() which fires during agent.run()
            // via the EventHandler feature installed in buildAgent(). Clear it before
            // each hop so a stale value from a previous hop cannot cause a spurious swap.
            pendingHandoffTarget = null

            val response = agent.run(input, activeHistorySessionId)
            _lastResponse.value = response

            // Consume whatever handoff the agent signalled during this hop.
            val handoffName = pendingHandoffTarget ?: return  // no handoff → normal response
            pendingHandoffTarget = null

            val targetDefinition = session.findAgent(handoffName)
                ?: error(
                    "koog-compose: Handoff target '$handoffName' is not registered. " +
                            "Add it via agents($handoffName) in your koogSession { } block."
                )

            val handoffTool = findHandoffTool(activeDefinition, handoffName)
            handoffTool?.onHandoff?.invoke(HandoffContext(session.stateStore))

            val continueHistory = handoffTool?.continueHistory ?: true

            val historyId = if (continueHistory) {
                sessionId
            } else {
                "$sessionId:${targetDefinition.name}"
            }

            // If the specialist gets a clean slate, wipe its history slot before the
            // first turn so it doesn't pick up leftovers from a previous activation.
            if (!continueHistory) {
                SessionStoreChatHistoryProvider(session.store, historyId).clearHistory()
            }

            activeHistorySessionId = historyId
            swapAgent(definition = targetDefinition, historySessionId = historyId)
            input = userMessage
        }

        error(
            "koog-compose: Handoff chain exceeded maxAgentIterations " +
                    "(${session.config.maxAgentIterations}). Possible routing loop."
        )
    }

    // ── Handoff detection ──────────────────────────────────────────────────

    /**
     * Called by the [EventHandlers] wired into [buildAgent] whenever a tool
     * execution completes. If the tool name matches the handoff prefix pattern
     * we record the target agent name so [runTurn] can act on it after [agent.run]
     * returns.
     *
     * This replaces the previous regex-on-response-text approach which was broken
     * because:
     * 1. [HandoffTool.execute] returns "Handing off to $name..." — not "handoff_to_$name".
     *    The regex pattern `handoff_to_(\w+)` would never match that text.
     * 2. Response text is assembled from streaming tokens and may not be complete
     *    when detection was attempted.
     *
     * Tool names are deterministic: [handoffToolName] always produces "handoff_to_$name",
     * so prefix matching here is exact and race-free.
     */
    private fun onToolExecuted(toolName: String) {
        if (toolName.startsWith("handoff_to_")) {
            pendingHandoffTarget = toolName.removePrefix("handoff_to_")
        }
    }

    // ── Agent creation and swap ────────────────────────────────────────────

    private fun ensureAgentCreated(definition: KoogAgentDefinition) {
        if (activeAgent != null) return
        activeAgent = buildAgent(definition, activeHistorySessionId)
    }

    private fun swapAgent(
        definition: KoogAgentDefinition,
        historySessionId: String,
    ) {
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

        // Merge session-level event handlers with the tool-execution hook needed
        // for handoff detection. We build a new EventHandlers that dispatches both.
        val agentEventHandlers = EventHandlers {
            // Forward all session-level handlers.
            onEvent { event -> session.eventHandlers.dispatch(event) }
            // Intercept tool completions to detect handoff calls.
            onToolExecutionCompleted { event -> onToolExecuted(event.toolName) }
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

    // ── Helpers ────────────────────────────────────────────────────────────

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