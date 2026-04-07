package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.EventHandlers
import io.github.koogcompose.phase.PhaseAwareAgent
import io.github.koogcompose.tool.HandoffContext
import io.github.koogcompose.tool.HandoffTool
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
 * **What changed from the original:**
 * - Manual `messageHistory` list and `persistTurn()` removed entirely.
 *   [ChatMemory] (installed in [PhaseAwareAgent]) owns LLM history per [sessionId].
 *   [SessionStoreChatHistoryProvider] writes it into the session's [SessionStore]
 *   automatically — no manual persistence needed.
 * - `agent.run(userMessage, sessionId)` is called with two args so [ChatMemory]
 *   scopes history correctly. This was the core bug — single-arg `run()` meant
 *   each agent started with a blank slate on every turn.
 * - `continueHistory = false` is now implemented correctly: we pass a *different*
 *   synthetic sessionId for the incoming agent so [ChatMemory] gives it a clean
 *   context window. The real sessionId's history is preserved in the store.
 * - Handoff detection is moved to `extractHandoffTarget()` which checks the
 *   agent response for the deterministic `handoff_to_<name>` tool name pattern.
 */
public class SessionRunner<S>(
    private val session: KoogSession<S>,
    private val executor: PromptExecutor,
    override val sessionId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : KoogSessionHandle {

    // ── Observable UI state ────────────────────────────────────────────────

    private val _activeAgentName = MutableStateFlow(session.mainAgent.name)
    public val activeAgentName: StateFlow<String> = _activeAgentName.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val responseStream: Flow<String> = _responseStream.asSharedFlow()

    private val _turnId = MutableStateFlow(0)

    /** Observable shared app state. Null if no `state { }` block was declared. */
    public val appState: StateFlow<S>? = session.stateStore?.stateFlow

    // ── Internal runtime state ─────────────────────────────────────────────

    private var activeAgent: AIAgent<String, String>? = null
    private var activeDefinition: KoogAgentDefinition = session.mainAgent

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
            _activeAgentName.value = session.mainAgent.name
            _error.value = null
            _turnId.value = 0
        }
    }

    // ── Turn execution ─────────────────────────────────────────────────────

    /**
     * Runs a single turn, following any handoff chain until an agent responds
     * without requesting a handoff.
     *
     * History threading works as follows:
     * - `continueHistory = true` (default): both agents share the same [sessionId],
     *   so [ChatMemory] gives the specialist the full conversation history.
     * - `continueHistory = false`: the specialist receives a derived sessionId
     *   (`"$sessionId:${agent.name}"`), giving it a clean context window while
     *   the main session's history is preserved.
     */
    private suspend fun runTurn(userMessage: String) {
        var input = userMessage
        var hopsRemaining = session.config.maxAgentIterations

        while (hopsRemaining-- > 0) {
            val agent = requireNotNull(activeAgent) { "Agent not initialised." }

            // Two-arg run: ChatMemory scopes history to this sessionId.
            val response = agent.run(input, sessionId)

            val handoffName = extractHandoffTarget(response) ?: return // normal response — done

            val targetDefinition = session.findAgent(handoffName)
                ?: error(
                    "koog-compose: Handoff target '$handoffName' is not registered. " +
                            "Add it via agents($handoffName) in your koogSession { } block."
                )

            val handoff = findHandoffTool(activeDefinition, handoffName)

            // Fire onHandoff callback before swapping agents.
            handoff?.onHandoff?.invoke(HandoffContext(session.stateStore))

            val continueHistory = handoff?.continueHistory ?: true

            // Swap the active agent. History scoping is determined by continueHistory.
            swapAgent(
                definition = targetDefinition,
                historySessionId = if (continueHistory) sessionId
                else "$sessionId:${targetDefinition.name}",
            )

            input = userMessage
        }

        error(
            "koog-compose: Handoff chain exceeded maxAgentIterations " +
                    "(${session.config.maxAgentIterations}). Possible routing loop."
        )
    }

    // ── Agent creation and swap ────────────────────────────────────────────

    private fun ensureAgentCreated(definition: KoogAgentDefinition) {
        if (activeAgent != null) return
        activeAgent = buildAgent(definition, sessionId)
    }

    /**
     * Swaps the active agent to [definition].
     *
     * @param historySessionId The sessionId passed to [ChatMemory] — controls
     *   whether the new agent sees the full history or starts clean.
     */
    private fun swapAgent(
        definition: KoogAgentDefinition,
        historySessionId: String,
    ) {
        activeDefinition = definition
        _activeAgentName.value = definition.name
        activeAgent = buildAgent(definition, historySessionId)
    }

    private fun buildAgent(
        definition: KoogAgentDefinition,
        historySessionId: String,
    ): AIAgent<String, String> {
        val context = session.contextFor(definition)
        return PhaseAwareAgent.create(
            context = context,
            promptExecutor = executor,
            sessionId = historySessionId,
            store = session.store,
            strategyName = "session-${definition.name}",
            tokenSink = _responseStream,
            // EventHandlers are not wired at session level yet —
            // add session.eventHandlers to KoogSession when needed.
            eventHandlers = EventHandlers.Empty,
            currentTurnId = { _turnId.value.toString() },
        )
    }

    // ── Handoff detection ──────────────────────────────────────────────────

    private fun extractHandoffTarget(agentResponse: String): String? =
        HANDOFF_PATTERN.find(agentResponse)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

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

    public companion object {
        private val HANDOFF_PATTERN = Regex("""handoff_to_(\w+)""")
    }
}