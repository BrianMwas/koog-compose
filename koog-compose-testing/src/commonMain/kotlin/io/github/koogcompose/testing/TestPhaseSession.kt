package io.github.koogcompose.testing

import io.github.koogcompose.session.ChatSession
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Synchronous test harness for the chat and phase runtime.
 *
 * Unlike the live Koog agent graph, this harness drives the existing [ChatSession] tool loop
 * through a deterministic [FakePromptExecutorAIProvider]. Tests can assert phase transitions,
 * tool calls, confirmation handling, and shared-state mutations without hitting a real LLM.
 */
public class TestPhaseSession<S> internal constructor(
    private val context: KoogComposeContext<S>,
    public val promptExecutor: FakePromptExecutor,
    public val sessionId: String,
    public val confirmationHandler: TestConfirmationHandler = AutoApproveConfirmationHandler
) : AutoCloseable {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val provider = FakePromptExecutorAIProvider(promptExecutor)
    private var runtimeSession: ChatSession = newChatSession(currentPhaseName())

    public var currentPhase: String = currentPhaseName()
        private set

    /**
     * The most recent assistant response returned by the agent.
     */
    public var lastResponse: String? = null
        private set

    /**
     * The last error thrown by [send], if any.
     */
    public var error: Throwable? = null
        private set

    /**
     * The typed app-state snapshot currently held by the shared state store.
     */
    public val appState: S?
        get() = context.stateStore?.current

    /**
     * Snapshot of the underlying chat state after the last deterministic step.
     */
    public val chatSession: ChatSession
        get() = runtimeSession

    /**
     * Blocking equivalent of `send(...)` for deterministic unit tests.
     */
    public fun send(userMessage: String): String = runBlocking {
        error = null
        try {
            runtimeSession.send(userMessage)
            driveUntilSettled()

            val failure = runtimeSession.state.value.error
            if (failure != null) {
                throw IllegalStateException(failure)
            }

            syncFromSession()
            val result = runtimeSession.state.value.messages
                .lastOrNull { message -> message.role == io.github.koogcompose.session.MessageRole.ASSISTANT }
                ?.content
                ?: ""
            lastResponse = result
            result
        } catch (throwable: Throwable) {
            error = throwable
            throw throwable
        }
    }

    /**
     * Clears persisted state and resets the in-memory test session.
     */
    public fun reset(): Unit {
        runtimeSession.close()
        promptExecutor.reset()
        currentPhase = currentPhaseName()
        lastResponse = null
        error = null
        runtimeSession = newChatSession(currentPhase)
    }

    /**
     * Forces the current phase without going through the LLM.
     */
    public fun forceTransitionTo(phaseName: String): Unit {
        requireNotNull(context.phaseRegistry.resolve(phaseName)) {
            "koog-compose-testing: Phase '$phaseName' not found in registry."
        }
        runtimeSession.close()
        promptExecutor.forcePhase(phaseName)
        currentPhase = phaseName
        lastResponse = null
        error = null
        runtimeSession = newChatSession(phaseName)
    }

    override fun close(): Unit {
        runtimeSession.close()
        scope.cancel()
        promptExecutor.close()
    }

    private suspend fun driveUntilSettled(): Unit {
        var settled = false
        withTimeout(TEST_TIMEOUT_MS) {
            repeat(MAX_DRAIN_PASSES) {
                val pending = runtimeSession.permissionManager.pendingConfirmation.value
                if (pending != null) {
                    when (confirmationHandler.decide(pending)) {
                        TestConfirmationDecision.APPROVE -> runtimeSession.confirmPendingToolExecution()
                        TestConfirmationDecision.DENY -> runtimeSession.denyPendingToolExecution()
                    }
                    yield()
                    return@repeat
                }

                if (!runtimeSession.state.value.isStreaming || runtimeSession.state.value.error != null) {
                    settled = true
                    return@withTimeout
                }

                yield()
            }
        }

        if (settled) {
            return
        }

        val messageSummary = runtimeSession.state.value.messages.joinToString { message ->
            "${message.role}:${message.content}"
        }
        error(
            "koog-compose-testing: scripted session did not settle after $MAX_DRAIN_PASSES drain cycles. " +
                "phase=${runtimeSession.state.value.activePhaseName}, " +
                "isStreaming=${runtimeSession.state.value.isStreaming}, " +
                "error=${runtimeSession.state.value.error}, " +
                "pendingConfirmation=${runtimeSession.permissionManager.pendingConfirmation.value?.tool?.name}, " +
                "messages=$messageSummary. " +
                "Check for a missing respondWith(...) step or a stuck confirmation handler."
        )
    }

    private fun syncFromSession(): Unit {
        currentPhase = runtimeSession.state.value.activePhaseName ?: currentPhase
    }

    private fun currentPhaseName(): String {
        return context.activePhaseName ?: context.phaseRegistry.initialPhase?.name.orEmpty()
    }

    private fun newChatSession(phaseName: String): ChatSession {
        return ChatSession(
            initialContext = context.withPhase(phaseName),
            provider = provider,
            scope = scope,
            userId = sessionId,
        )
    }
}

/**
 * Creates a synchronous [TestPhaseSession] backed by a scripted [FakePromptExecutor].
 */
public fun <S> testPhaseSession(
    context: KoogComposeContext<S>,
    sessionId: String = "test-session",
    confirmationHandler: TestConfirmationHandler = AutoApproveConfirmationHandler,
    block: FakePromptExecutor.Builder.() -> Unit
): TestPhaseSession<S> {
    val executor = FakePromptExecutor.Builder()
        .apply(block)
        .build(
            phaseCatalog = PhaseCatalog.from(context.phaseRegistry),
            initialPhaseName = context.activePhaseName ?: context.phaseRegistry.initialPhase?.name
        )

    return TestPhaseSession(
        context = context,
        promptExecutor = executor,
        sessionId = sessionId,
        confirmationHandler = confirmationHandler
    )
}

/**
 * Creates a synchronous [TestPhaseSession] from a prebuilt [FakePromptExecutor].
 */
public fun <S> testPhaseSession(
    context: KoogComposeContext<S>,
    promptExecutor: FakePromptExecutor,
    sessionId: String = "test-session",
    confirmationHandler: TestConfirmationHandler = AutoApproveConfirmationHandler
): TestPhaseSession<S> {
    return TestPhaseSession(
        context = context,
        promptExecutor = promptExecutor,
        sessionId = sessionId,
        confirmationHandler = confirmationHandler
    )
}

private const val MAX_DRAIN_PASSES: Int = 32
private const val TEST_TIMEOUT_MS: Long = 5_000L
