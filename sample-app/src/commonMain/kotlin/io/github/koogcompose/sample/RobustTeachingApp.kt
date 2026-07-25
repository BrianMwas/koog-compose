package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.PrintlnEventSink
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.reliability.CircuitBreaker
import io.github.koogcompose.reliability.CircuitBreakerGuard
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.RecoveryHint
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * A compact AIAgent-based robustness sample.
 *
 * The older version of this file mixed stale provider DSL calls and legacy
 * ChatSession events. Keep this sample on the PhaseSession/AIAgent path so it
 * catches breakage in the runtime we want to support.
 */
@Serializable
data class RobustAppState(
    val lastError: String? = null,
    val isDegradedMode: Boolean = false,
    val retryCount: Int = 0,
    val lastHomeworkPhoto: String? = null,
    val toolFailureCount: Int = 0,
    val lastSuccessfulToolRunMs: Long = 0L,
    val stateChecksum: String? = null,
)

/**
 * Builds the circuit-breaker / degraded-mode robustness sample.
 *
 * [modelPath] documents the on-device Gemma 4 model this sample is designed
 * to eventually run against via `ProviderConfig.OnDevice` (see the README's
 * "On-device models" section) — it isn't wired into [providerConfig]
 * automatically since on-device wiring is a per-app choice (mediapipe
 * module + a downloaded `.litertlm` file). Pass
 * `providerConfig = ProviderConfig.OnDevice(modelPath = modelPath)` yourself
 * once you have both in place.
 */
fun buildRobustTeachingSession(
    stateStore: KoogStateStore<RobustAppState> = KoogStateStore(RobustAppState()),
    eventSink: EventSink = PrintlnEventSink,
    modelPath: String = "/data/models/gemma-4-E2B.litertlm",
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
): PhaseSession<RobustAppState> {
    val fileOperationBreaker = CircuitBreaker(
        failureThreshold = 5,
        cooldownMs = 60_000,
        successThreshold = 2,
    )
    val progressBreaker = CircuitBreaker(
        failureThreshold = 3,
        cooldownMs = 30_000,
        successThreshold = 1,
    )

    val context = koogCompose<RobustAppState> {
        provider {
            use(providerConfig)
        }

        initialState { stateStore.current }

        phases {
            phase("teach", initial = true) {
                instructions {
                    """
                    You are a teaching assistant helping students master concepts.
                    Keep explanations short, ask one check-for-understanding question,
                    and use tools only when they improve the lesson state.
                    """.trimIndent()
                }
                tool(
                    CircuitBreakerGuard(
                        delegate = SaveHomeworkPhotoTool(
                            stateStore = stateStore,
                            eventSink = eventSink,
                        ),
                        circuitBreaker = fileOperationBreaker,
                    )
                )
                tool(
                    CircuitBreakerGuard(
                        delegate = RobustTrackProgressTool(stateStore),
                        circuitBreaker = progressBreaker,
                    )
                )
                tool(RobustAdjustDifficultyTool(stateStore))
            }
        }

        config {
            retry {
                maxAttempts = 3
                initialDelayMs = 1_000
                backoffMultiplier = 2.0
            }
            stuckDetection {
                threshold = 4
                fallbackMessage = "I seem to be going in circles. Let me try a different approach."
            }
            this.eventSink = eventSink
        }

        events {
            onTurnFailed { event ->
                stateStore.update { it.copy(lastError = event.message) }
            }
            onToolExecutionCompleted { event ->
                if (event.result is ToolResult.Failure) {
                    stateStore.update { state ->
                        state.copy(toolFailureCount = state.toolFailureCount + 1)
                    }
                }
            }
            onTurnCompleted {
                stateStore.update { state ->
                    state.copy(
                        lastError = null,
                        toolFailureCount = 0,
                        lastSuccessfulToolRunMs = Clock.System.now().toEpochMilliseconds(),
                    )
                }
            }
            onAgentStuck { event ->
                stateStore.update { it.copy(lastError = event.fallbackMessage) }
            }
        }
    } as KoogComposeContext<RobustAppState>

    return PhaseSession(
        context = context,
        executor = context.createExecutor(),
        sessionId = "robust_teaching_${Clock.System.now().toEpochMilliseconds()}",
    )
}

private class RobustTrackProgressTool(
    override val stateStore: KoogStateStore<RobustAppState>,
) : StatefulTool<RobustAppState>() {
    override val name = "TrackProgress"
    override val description = "Records student quiz score and tracks mastery progress"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val score = args["score"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Failure(
                message = "Invalid score format. Expected integer 0-100.",
                retryable = false,
            )

        if (score !in 0..100) {
            return ToolResult.Failure(
                message = "Score out of range. Expected 0-100.",
                retryable = false,
            )
        }

        stateStore.update {
            it.copy(lastSuccessfulToolRunMs = Clock.System.now().toEpochMilliseconds())
        }
        return ToolResult.Success("Progress recorded: $score%")
    }
}

private class RobustAdjustDifficultyTool(
    override val stateStore: KoogStateStore<RobustAppState>,
) : StatefulTool<RobustAppState>() {
    override val name = "AdjustDifficulty"
    override val description = "Adjusts lesson difficulty level"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Failure("Missing direction parameter: increase or decrease.")

        if (direction !in listOf("increase", "decrease")) {
            return ToolResult.Failure("Invalid direction. Expected increase or decrease.")
        }

        stateStore.update {
            it.copy(lastSuccessfulToolRunMs = Clock.System.now().toEpochMilliseconds())
        }

        val newDifficulty = if (direction == "increase") "Intermediate" else "Beginner"
        return ToolResult.Success("Difficulty adjusted to: $newDifficulty")
    }
}

internal class DegradedModeTeachingStrategy {
    fun getFallbackMessage(): String =
        "I cannot reach the model right now, but we can still practice with a short exercise."

    fun getCachedExercise(): String =
        "What is 25% of 80? Hint: 25% is one quarter."

    fun getRecoveryPrompt(): String =
        "The model is available again. Let's continue with the full tutor."
}

/**
 * Cross-platform entry point for the robustness sample: a single-phase
 * teaching agent wrapped in circuit breakers, retry/backoff, and stuck
 * detection, showing degraded-mode messaging when a tool keeps failing.
 */
@Composable
fun SimpleRobustTeachingApp(
    modifier: Modifier = Modifier,
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
) {
    val session = remember(providerConfig) {
        buildRobustTeachingSession(providerConfig = providerConfig)
    }
    val chatState = rememberChatState(handle = session, context = session.context)
    val state by requireNotNull(session.appState).collectAsState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            RobustTeachingTopBar(state)
        },
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Ask for help with a lesson...",
            )
        },
    ) { innerPadding ->
        ChatMessageList(
            chatState = chatState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            showSystemMessages = false,
            showToolCallMessages = true,
        )
    }
}

@Composable
private fun RobustTeachingTopBar(state: RobustAppState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (state.isDegradedMode) "⚠️ Robust Teaching — degraded mode" else "🛡️ Robust Teaching",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.lastError != null) {
                Text(
                    text = "Last error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.toolFailureCount > 0) {
                Text(
                    text = "Tool failures this session: ${state.toolFailureCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
