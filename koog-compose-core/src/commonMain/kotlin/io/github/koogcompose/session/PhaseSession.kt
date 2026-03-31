package io.github.koogcompose.session

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.phase.PhaseAwareAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compose-friendly runtime for a phase-aware agent with pluggable session persistence.
 *
 * Handles the full lifecycle: first-run initialisation, history persistence after
 * each turn, and session resume from any [SessionStore] implementation.
 *
 * ```kotlin
 * val session = PhaseSession(
 *     context   = context,
 *     executor  = executor,
 *     sessionId = "user_brian"
 * )
 *
 * // Room persistence (koog-compose-device module)
 * val session = PhaseSession(
 *     context   = context,
 *     executor  = executor,
 *     sessionId = "user_brian",
 *     store     = RoomSessionStore(db.sessionDao())
 * )
 * ```
 *
 * @param context    Fully configured [KoogComposeContext].
 * @param executor   Koog [PromptExecutor] for LLM calls.
 * @param sessionId  Stable conversation identifier (e.g. userId or conversationId).
 * @param store      Persistence layer. Defaults to [InMemorySessionStore].
 * @param scope      CoroutineScope for agent runs. Use `viewModelScope` in a ViewModel.
 */
class PhaseSession(
    private val context: KoogComposeContext,
    private val executor: PromptExecutor,
    val sessionId: String,
    private val store: SessionStore = InMemorySessionStore(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val strategyName: String = "koog-compose-phases"
) {
    // ── Observable state ───────────────────────────────────────────────────

    private val _currentPhase = MutableStateFlow(
        context.activePhaseName ?: context.phaseRegistry.initialPhase?.name ?: ""
    )
    val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    /** App-level context variables written by the host app during the session. */
    private val contextVars = mutableMapOf<String, String>()

    // Agent is created once and reused across all turns in the session.
    private var agent: AIAgent<String, String>? = null
    private var sessionInitialised = false

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Sends [userMessage] to the agent. Initialises or resumes the session
     * from [store] on the first call.
     */
    fun run(userMessage: String) {
        scope.launch {
            _isRunning.value = true
            _error.value = null
            try {
                ensureInitialised()
                val result = requireNotNull(agent).run(userMessage)
                _lastResponse.value = result
                persistSession(userMessage, result)
            } catch (e: Throwable) {
                _error.value = e
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * Writes an app-level context variable. Persisted on the next [run].
     *
     * ```kotlin
     * session.setContext("account_id", "KE-4567")
     * session.setContext("user_name", "Brian")
     * ```
     */
    fun setContext(key: String, value: String) {
        contextVars[key] = value
    }

    fun getContext(key: String): String? = contextVars[key]

    /**
     * Clears the persisted session and resets all in-memory state.
     * The next [run] starts a fresh session from the initial phase.
     */
    fun clearSession() {
        scope.launch {
            store.delete(sessionId)
            contextVars.clear()
            agent = null
            sessionInitialised = false
            _currentPhase.value = context.phaseRegistry.initialPhase?.name ?: ""
            _lastResponse.value = null
            _error.value = null
        }
    }

    /**
     * Forces a phase transition without an LLM turn.
     * Useful for host-app logic that needs to override the agent mid-session
     * (e.g. a Cancel button that returns the user to the greeting phase).
     */
    fun forceTransitionTo(phaseName: String) {
        context.phaseRegistry.resolve(phaseName)
            ?: error("koog-compose: Phase '$phaseName' not found in registry.")
        _currentPhase.value = phaseName
    }

    // ── Initialisation and resume ──────────────────────────────────────────

    private suspend fun ensureInitialised() {
        if (sessionInitialised) return

        val existingSession = store.load(sessionId)

        if (existingSession != null) {
            contextVars.putAll(existingSession.contextVars)
            _currentPhase.value = existingSession.currentPhaseName

            // Build agent in the restored phase so its system prompt matches
            val resumeContext = context.withPhase(existingSession.currentPhaseName)
            agent = PhaseAwareAgent.create(resumeContext, executor, strategyName)

            // Replay history so the LLM has full prior-turn context
            replayHistory(agent!!, existingSession.messageHistory)
        } else {
            agent = PhaseAwareAgent.create(context, executor, strategyName)
        }

        sessionInitialised = true
    }

    /**
     * Replays [messages] into the agent's PromptExecutor session.
     *
     * Koog's [AIAgent.writeSession] API is used to inject each message in order
     * so the LLM context is fully restored before the next user turn.
     */
    private suspend fun replayHistory(
        agent: AIAgent<String, String>,
        messages: List<SessionMessage>
    ) {
        messages.forEach { msg ->
            runCatching {
                when (msg.role) {
                    "user"      -> agent.writeSession { addUserMessage(msg.content) }
                    "assistant" -> agent.writeSession { addAssistantMessage(msg.content) }
                    "tool_result" -> agent.writeSession {
                        addToolResult(
                            toolCallId = msg.toolCallId.orEmpty(),
                            toolName   = msg.toolName.orEmpty(),
                            result     = msg.content
                        )
                    }
                }
            }.onFailure { e ->
                // Log but don't abort replay — a partial history is better than none
                println("koog-compose: PhaseSession — failed to replay message (${msg.role}): ${e.message}")
            }
        }
    }

    private suspend fun persistSession(userMessage: String, assistantResponse: String) {
        val existing = store.load(sessionId)
        val history = existing?.messageHistory?.toMutableList() ?: mutableListOf()

        history.add(SessionMessage(role = "user",      content = userMessage))
        history.add(SessionMessage(role = "assistant", content = assistantResponse))

        store.save(
            sessionId,
            AgentSession(
                sessionId       = sessionId,
                currentPhaseName = _currentPhase.value,
                messageHistory  = history,
                contextVars     = contextVars.toMap(),
                createdAt       = existing?.createdAt ?: System.currentTimeMillis()
            )
        )
    }
}

// ── InMemorySessionStore ───────────────────────────────────────────────────────

/**
 * Default [SessionStore] — no setup, no persistence across process death.
 *
 * Swap for RoomSessionStore or RedisSessionStore for durable storage.
 */
class InMemorySessionStore : SessionStore {
    private val sessions = mutableMapOf<String, AgentSession>()

    override suspend fun load(sessionId: String): AgentSession? = sessions[sessionId]

    override suspend fun save(sessionId: String, session: AgentSession) {
        sessions[sessionId] = session
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }

    override suspend fun exists(sessionId: String): Boolean = sessions.containsKey(sessionId)
}




private fun AIAgent<*, *>.currentSystemPrompt(): String =
    runCatching { "" }.getOrDefault("")