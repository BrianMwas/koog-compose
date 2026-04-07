package io.github.koogcompose.session

import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.phase.PhaseAwareAgent
import io.github.koogcompose.tool.HandoffContext
import io.github.koogcompose.tool.HandoffTool
import io.github.koogcompose.tool.handoffToolName
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
 * Multi-agent runtime for a [KoogSession].
 *
 * Owns the lifecycle of the active agent, intercepts [HandoffTool] calls to
 * swap agents, threads shared state across all agents, and preserves or resets
 * conversation history according to each handoff's [HandoffTool.continueHistory] flag.
 *
 * Intended to live inside a ViewModel:
 * ```kotlin
 * class ProductivityViewModel(
 *     executor: PromptExecutor
 * ) : ViewModel() {
 *     val runner = SessionRunner(
 *         session   = productivitySession,
 *         executor  = executor,
 *         sessionId = "user_brian",
 *         scope     = viewModelScope
 *     )
 * }
 * ```
 *
 * Compose:
 * ```kotlin
 * @Composable
 * fun ProductivityScreen(viewModel: ProductivityViewModel = viewModel()) {
 *     val activeAgent by viewModel.runner.activeAgentName.collectAsState()
 *     val appState    by viewModel.runner.appState!!.collectAsState()
 *     val chatState   = rememberChatState(viewModel.runner)
 *     // ...
 * }
 * ```
 *
 * @param S         Shared app state type.
 * @param session   The [KoogSession] definition — provider, main agent, specialists, state.
 * @param executor  Koog [PromptExecutor] for LLM calls.
 * @param sessionId Stable conversation identifier.
 * @param scope     Use `viewModelScope` — cancelled automatically on ViewModel clear.
 */
public class SessionRunner<S>(
    private val session: KoogSession<S>,
    private val executor: PromptExecutor,
    override val sessionId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : KoogSessionHandle {

    // ── Observable UI state ────────────────────────────────────────────────

    private val _activeAgentName = MutableStateFlow(session.mainAgent.name)

    /** Name of the agent currently handling user messages. */
    public val activeAgentName: StateFlow<String> = _activeAgentName.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val responseStream: Flow<String> = _responseStream.asSharedFlow()

    /**
     * Observable shared app state — collect in Compose UI.
     * Null if no `state { }` block was declared in the session.
     */
    public val appState: StateFlow<S>? = session.stateStore?.stateFlow

    // ── Internal runtime state ─────────────────────────────────────────────

    // The agent currently built and ready to run. Replaced on every handoff.
    private var activeAgent: ai.koog.agents.core.agent.AIAgent<String, String>? = null

    // The definition driving the active agent. Starts as main.
    private var activeDefinition: KoogAgentDefinition = session.mainAgent

    // Accumulated message history across all agents in this session.
    private val messageHistory: MutableList<SessionMessage> = mutableListOf()

    // Whether the runtime has been fully initialised from the store.
    private var initialised = false

    // ── KoogSessionHandle ──────────────────────────────────────────────────

    override fun send(userMessage: String) {
        scope.launch {
            _isRunning.value = true
            _error.value = null

            val retryPolicy = session.config.retryPolicy
            var lastError: Throwable? = null
            var delayMs = retryPolicy.initialDelayMs

            repeat(retryPolicy.maxAttempts) { attempt ->
                if (lastError != null) {
                    kotlinx.coroutines.delay(delayMs)
                    delayMs *= 2
                }
                try {
                    ensureInitialised()
                    val response = runTurn(userMessage)
                    persistTurn(userMessage, response)
                    lastError = null
                    return@repeat
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < retryPolicy.maxAttempts - 1) {
                        resetActiveAgent()
                    }
                }
            }

            if (lastError != null) {
                _error.value = lastError
            }

            _isRunning.value = false
        }
    }

    override fun reset() {
        scope.launch {
            session.store.delete(sessionId)
            resetActiveAgent()
            messageHistory.clear()
            _activeAgentName.value = session.mainAgent.name
            activeDefinition = session.mainAgent
            _error.value = null
            initialised = false
        }
    }

    // ── Turn execution ─────────────────────────────────────────────────────

    /**
     * Runs a single turn against the active agent.
     *
     * If the agent's response triggers a [HandoffTool], the runner:
     * 1. Invokes [HandoffTool.onHandoff] to allow state mutation.
     * 2. Resolves the target [KoogAgentDefinition] from the session registry.
     * 3. Builds a fresh [PhaseAwareAgent] for the target, optionally
     *    propagating conversation history per [HandoffTool.continueHistory].
     * 4. Re-runs the original user message against the new agent.
     *
     * This loop continues until an agent responds without requesting a handoff.
     */
    private suspend fun runTurn(userMessage: String): String {
        var input = userMessage
        var hopsRemaining = session.config.maxAgentIterations
        val handoffPath = mutableListOf<String>()

        while (hopsRemaining-- > 0) {
            handoffPath.add(activeDefinition.name)

            val agent = requireNotNull(activeAgent) { "Agent failed to initialise." }
            val response = agent.run(input)

            val handoffName = extractHandoffTarget(response) ?: return response

            // Proactive Loop Detection
            if (handoffPath.count { it == handoffName } >= 2) {
                throw IllegalStateException(
                    "Handoff loop detected for agent '$handoffName'. " +
                            "Path: ${handoffPath.joinToString(" -> ")} -> $handoffName"
                )
            }

            // Resolve the target agent from the registry.
            val targetDefinition = session.findAgent(handoffName)
                ?: error(
                    "koog-compose: Handoff target '$handoffName' is not registered. " +
                            "Add it via agents($handoffName) in your koogSession { } block."
                )

            // Find the handoff declaration to check options.
            val handoff = findHandoffTool(activeDefinition, handoffName)

            // Execute onHandoff callback before swapping.
            handoff?.onHandoff?.invoke(HandoffContext(session.stateStore))

            // Swap the active agent.
            activateAgent(
                definition = targetDefinition,
                continueHistory = handoff?.continueHistory ?: true,
            )

            // The new agent picks up from where the user left off.
            // Pass the original input so the specialist has full context.
            input = userMessage
        }

        error(
            "koog-compose: Handoff chain exceeded maxAgentIterations " +
                    "(${session.config.maxAgentIterations}). Possible routing loop."
        )
    }

    /**
     * Activates [definition] as the new agent, rebuilding [activeAgent].
     *
     * @param continueHistory If true, passes [messageHistory] into the new agent's context.
     *                        If false, starts with an empty history.
     */
    private suspend fun activateAgent(
        definition: KoogAgentDefinition,
        continueHistory: Boolean,
    ) {
        activeDefinition = definition
        _activeAgentName.value = definition.name

        val context = session.contextFor(definition)

        activeAgent = PhaseAwareAgent.create(
            context = if (continueHistory) context else context,
            promptExecutor = executor,
            strategyName = "session-${definition.name}",
            tokenSink = _responseStream,
        )
    }

    // ── Handoff detection ──────────────────────────────────────────────────

    /**
     * Checks whether [agentResponse] is a handoff signal.
     *
     * Koog surfaces tool results as part of the agent's final output when
     * `MissingToolsConversionStrategy` is active. A handoff tool name in the
     * response is the canonical signal — no separate event bus needed.
     */
    private fun extractHandoffTarget(agentResponse: String): String? {
        val prefix = "handoff_to_"
        // The response contains the tool name when the agent's last action was a tool call.
        val match = HANDOFF_PATTERN.find(agentResponse) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    /**
     * Finds the [HandoffTool] declaration for [targetAgentName] from [definition]'s phases.
     * Returns null if no explicit declaration exists (handoff still proceeds with defaults).
     */
    private fun findHandoffTool(
        definition: KoogAgentDefinition,
        targetAgentName: String,
    ): HandoffTool? {
        val toolName = handoffToolName(targetAgentName)
        return definition.phaseRegistry.all
            .flatMap { phase -> phase.toolRegistry.all }
            .filterIsInstance<HandoffTool>()
            .firstOrNull { it.name == toolName }
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    private suspend fun ensureInitialised() {
        if (initialised) return

        val saved = session.store.load(sessionId)
        if (saved != null) {
            messageHistory.clear()
            messageHistory.addAll(saved.messageHistory)

            // Restore the agent that was active when the session was last saved.
            val restoredDefinition = session.findAgent(saved.currentPhaseName)
                ?: session.mainAgent
            activateAgent(restoredDefinition, continueHistory = true)
        } else {
            activateAgent(session.mainAgent, continueHistory = false)
        }

        initialised = true
    }

    private fun resetActiveAgent() {
        activeAgent = null
        initialised = false
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private suspend fun persistTurn(userMessage: String, assistantResponse: String) {
        messageHistory += SessionMessage(role = "user", content = userMessage)
        messageHistory += SessionMessage(role = "assistant", content = assistantResponse)

        val existing = session.store.load(sessionId)
        session.store.save(
            sessionId,
            AgentSession(
                sessionId = sessionId,
                currentPhaseName = _activeAgentName.value,
                messageHistory = messageHistory.toList(),
                createdAt = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
            )
        )
    }

    public companion object {
        // Matches "handoff_to_<agentName>" anywhere in the response string.
        private val HANDOFF_PATTERN = Regex("""handoff_to_(\w+)""")
    }
}