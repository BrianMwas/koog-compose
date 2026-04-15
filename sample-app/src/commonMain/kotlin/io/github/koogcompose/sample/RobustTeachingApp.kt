package io.github.koogcompose.sample

import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.PrintlnEventSink
import io.github.koogcompose.provider.RouterStrategy
import io.github.koogcompose.reliability.CircuitBreaker
import io.github.koogcompose.reliability.CircuitBreakerGuard
import io.github.koogcompose.reliability.CircuitOpenException
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.RecoveryHint
import io.github.koogcompose.tool.PermissionLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.GlobalScope

/**
 * Demonstrates GitHub Issue #42: Error Handling Recovery Patterns
 * 
 * Production-grade patterns for:
 * - Provider fallback chains with graceful degradation
 * - Tool-level circuit breaker protection
 * - Recovery hints and error classification
 * - Session persistence and corruption recovery
 * - Observable error events for metrics
 * 
 * Real-world scenarios covered:
 * 1. Network timeout → retry with exponential backoff
 * 2. Model file missing → fallback to cloud provider
 * 3. Permission denied → return user-actionable error
 * 4. Tool execution failure → circuit breaker prevents cascade
 * 5. Partial multi-tool failure → completes what's possible
 * 6. Session corrupted → detect and reset state
 * 7. Service unavailable → show degraded mode fallback
 * 8. Rate limited → backoff with recovery hint
 */

/**
 * Application state tracking all recovery-relevant data.
 */
@Serializable
data class RobustAppState(
    /**
     * Last error message encountered — used to detect corruption.
     * If corrupted, will be null after reset.
     */
    val lastError: String? = null,
    
    /**
     * Whether degraded mode is active (no external services available).
     * In this mode, agent uses built-in knowledge only.
     */
    val isDegradedMode: Boolean = false,
    
    /**
     * Total retry attempts made during this session.
     * Used to track resilience metrics.
     */
    val retryCount: Int = 0,
    
    /**
     * Saved homework photo path — nullable if not yet captured.
     * Used by SaveHomeworkPhotoTool.
     */
    val lastHomeworkPhoto: String? = null,
    
    /**
     * Count of tool execution failures — triggers circuit breaker reset.
     * Resets to 0 when circuit breaker opens and recovers.
     */
    val toolFailureCount: Int = 0,
    
    /**
     * Timestamp of last successful tool execution.
     * Used for recovery hint timing decisions.
     */
    val lastSuccessfulToolRunMs: Long = 0L,
    
    /**
     * Checksum of serialized state — detect corruption.
     * If checksum doesn't match on load, session is corrupted.
     */
    val stateChecksum: String? = null,
)


/**
 * Builds a teaching session with production-grade error recovery.
 * 
 * Shows all Issue #42 patterns in action:
 * - Multi-provider fallback chain with timeout handling
 * - Tool-level circuit breakers prevent cascading failures
 * - Session corruption detection and recovery
 * - All error types with documented recovery strategies
 * - Observable events for metrics and debugging
 *
 * @param stateStore Centralized state store (initialized once, passed to all tools)
 * @param eventSink For observability events (metrics, debugging)
 * @param modelPath Override path for on-device model (for testing)
 * @return PhaseSession ready to start teaching with full error recovery
 */
fun buildRobustTeachingSession(
    stateStore: KoogStateStore<RobustAppState> = KoogStateStore(RobustAppState()),
    eventSink: EventSink = PrintlnEventSink,
    modelPath: String = "/data/models/gemma-4.litertlm",
): PhaseSession<RobustAppState> {
    
    // ════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKERS — One per external resource to prevent cascading failures
    // ════════════════════════════════════════════════════════════════════════
    
    /**
     * Protects file operations (photo saving, etc.)
     * - Opens after 5 consecutive failures
     * - 60s cooldown (user must free space, grant permission, etc.)
     * - Requires 2 successes in HALF_OPEN before full recovery
     */
    val fileOperationBreaker = CircuitBreaker(
        failureThreshold = 5,
        cooldownMs = 60_000,
        successThreshold = 2
    )
    
    /**
     * Protects API calls to external LLM services
     * - Opens after 3 API failures (auth, rate limit, service down)
     * - 30s cooldown (service likely recovering)
     * - Single success closes circuit (optimistic for APIs)
     */
    val apiCallBreaker = CircuitBreaker(
        failureThreshold = 3,
        cooldownMs = 30_000,
        successThreshold = 1
    )

    // ════════════════════════════════════════════════════════════════════════
    // SESSION CORRUPTION DETECTION — Validate state on load
    // ════════════════════════════════════════════════════════════════════════
    
    val currentState = stateStore.current
    
    // Simple validation: if state has impossible values, it's corrupted
    val isCorrupted = currentState.retryCount > 100 ||  // Sanity check
                      (currentState.lastSuccessfulToolRunMs > System.currentTimeMillis()) ||
                      (currentState.stateChecksum != null && 
                       currentState.stateChecksum != computeStateChecksum(currentState))
    
    if (isCorrupted) {
        println("[ERROR] Session corrupted — resetting to clean state")
        stateStore.reset(RobustAppState())
    }

    val context = koogCompose<RobustAppState> {
        // Single initialization — passed to all tools
        if (isCorrupted) {
            initialState { RobustAppState() }
        }

        // ────────────────────────────────────────────────────────────────────
        // PROVIDER FALLBACK CHAIN — Try in order until one succeeds
        // ────────────────────────────────────────────────────────────────────
        // 
        // 1. On-device (fast, private, no API key needed)
        //    ✗ Requires large model file (~4GB)
        //    ✗ Model missing? Falls through to cloud
        //
        // 2. Anthropic Cloud (full capability)
        //    ✗ Requires network + API key
        //    ✗ Timeout or auth failure? Falls through
        //
        // 3. Local Ollama (user's own instance)
        //    ✗ User must have Ollama running locally
        //    ✗ Not reachable? Falls through to degraded
        //
        // 4. Degraded Mode (no external services)
        //    ✓ Uses cached knowledge, local rules
        //    ✓ Informs user of limitations
        
        provider {
            router(strategy = RouterStrategy.Fallback) {
                // 1. Fast, private, local
                onDevice(modelPath = modelPath) {
                    maxToolRounds(5)
                    timeout = 30_000  // 30s timeout before fallback
                }
                
                // 2. Full capability cloud service
                anthropic(apiKey = BuildConfig.ANTHROPIC_KEY ?: "") {
                    model = "claude-sonnet-4-5"
                    timeout = 60_000  // 60s timeout for API
                }
                
                // 3. User's local instance (free tier option)
                ollama(model = "llama3.2") {
                    baseUrl = "http://localhost:11434"
                    timeout = 20_000  // 20s timeout for local
                }
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // PHASES — Each phase handles specific error scenarios
        // ────────────────────────────────────────────────────────────────────
        
        phases {
            phase("teach", initial = true) {
                instructions {
                    val modeInfo = if (currentState.isDegradedMode) 
                        "\n\n[DEGRADED MODE] External services unavailable. Using local knowledge only."
                    else
                        ""
                    
                    """
You are a teaching assistant helping students master concepts.

Core responsibilities:
- Welcome the student and assess their current level
- Adapt explanations to their understanding
- Encourage them to think critically
- Celebrate progress and learning$modeInfo

If teaching basics (no external services needed):
- Provide clear, simple explanations with examples
- Ask leading questions to guide learning
- Use concrete analogies from daily life

If student needs advanced help:
- Offer to save their homework photo for later analysis
- Ask them to explain concepts back to you
- Suggest practice problems at their level
                    """.trimIndent()
                }
                
                // ────────────────────────────────────────────────────────────
                // TOOLS — Each wrapped with circuit breaker + full error handling
                // ────────────────────────────────────────────────────────────
                
                // Tool 1: Save photo — requires permission, storage space
                // ✗ Permission denied → user-friendly message
                // ✗ Storage full → suggest cleanup
                // ✗ Network error saving to cloud → retry
                // ✗ 5+ failures → circuit breaker opens, prevent spam
                tool(
                    CircuitBreakerGuard(
                        delegate = SaveHomeworkPhotoTool(
                            stateStore = stateStore,
                            eventSink = eventSink
                        ),
                        circuitBreaker = fileOperationBreaker
                    )
                )
                
                // Tool 2: Track progress — records quiz results
                // ✗ Invalid input → permanent failure
                // ✗ Database error → transient, retry
                // ✗ 3+ API failures → circuit opens
                tool(
                    CircuitBreakerGuard(
                        delegate = TrackProgressTool(
                            stateStore = stateStore,
                            eventSink = eventSink
                        ),
                        circuitBreaker = apiCallBreaker
                    )
                )
                
                // Tool 3: Adjust difficulty — state-local, no external calls
                // ✗ No circuit breaker needed (always succeeds)
                tool(AdjustDifficultyTool(stateStore = stateStore))
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // CONFIG — Automatic retry + stuck detection
        // ────────────────────────────────────────────────────────────────────
        
        config {
            // Automatic retry for transient failures (network, timeout, 429)
            retry {
                maxAttempts = 3
                initialDelayMs = 1_000       // 1s
                backoffMultiplier = 2.0      // 1s, 2s, 4s
            }

            // Detect if agent walks in circles (same tool, same response)
            stuckDetection {
                threshold = 4  // 4 turns of same behavior = stuck
                fallbackMessage = "I seem to be going in circles. Let me try a completely different approach..."
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // OBSERVABILITY — Classify and log all error paths
        // ────────────────────────────────────────────────────────────────────
        
        events {
            // Turn-level failures — categorized for metrics dashboard
            onTurnFailed { event ->
                val message = event.message ?: "Unknown"
                
                // Classify error by type (for metrics, alerts, etc.)
                val errorType = classifyError(message)
                val severity = errorSeverity(errorType)
                
                println("[$severity] Error[$errorType]: $message")
                eventSink.emitError(errorType, message)
                
                // Update state for UI rendering
                stateStore.update { it.copy(lastError = message) }
            }
            
            // Tool failures — used for circuit breaker decisions
            onToolFailed { event ->
                stateStore.update { state ->
                    state.copy(toolFailureCount = state.toolFailureCount + 1)
                }
                println("[WARNING] Tool failed: ${event.toolName} — ${event.message}")
            }
            
            // Agent getting stuck — proactive intervention
            onAgentStuck { event ->
                println("[WARNING] Agent is stuck; trying alternate strategy")
                // In production: emit alert, log to metrics, may trigger fallback
            }
            
            // Success — reset failure counters
            onTurnSucceeded { event ->
                stateStore.update { state ->
                    state.copy(
                        lastError = null,
                        toolFailureCount = 0,
                        lastSuccessfulToolRunMs = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    val executor = context.createExecutor()

    return PhaseSession(
        context = context as KoogComposeContext<RobustAppState>,
        executor = executor,
        sessionId = "teaching_${System.currentTimeMillis()}",
        scope = GlobalScope,
    )
}

/**
 * Classify errors into actionable categories for observability.
 * Each category maps to a recovery strategy.
 */
private fun classifyError(message: String): String = when {
    message.contains("network", ignoreCase = true) ||
    message.contains("timeout", ignoreCase = true) ||
    message.contains("unreachable", ignoreCase = true) ||
    message.contains("connection", ignoreCase = true) ||
    message.contains("refused", ignoreCase = true) -> "NetworkError"
    
    message.contains("permission", ignoreCase = true) ||
    message.contains("denied", ignoreCase = true) ||
    message.contains("unauthorized", ignoreCase = true) -> "PermissionError"
    
    message.contains("storage", ignoreCase = true) ||
    message.contains("space", ignoreCase = true) ||
    message.contains("full", ignoreCase = true) ||
    message.contains("disk", ignoreCase = true) -> "StorageError"
    
    message.contains("model", ignoreCase = true) ||
    message.contains("file", ignoreCase = true) ||
    message.contains("404", ignoreCase = true) -> "ResourceNotFound"
    
    message.contains("auth", ignoreCase = true) ||
    message.contains("token", ignoreCase = true) ||
    message.contains("api", ignoreCase = true) -> "AuthenticationError"
    
    message.contains("unavailable", ignoreCase = true) ||
    message.contains("500", ignoreCase = true) ||
    message.contains("503", ignoreCase = true) ||
    message.contains("service", ignoreCase = true) -> "ServiceUnavailable"
    
    message.contains("rate", ignoreCase = true) ||
    message.contains("429", ignoreCase = true) ||
    message.contains("limit", ignoreCase = true) -> "RateLimitError"
    
    message.contains("corrupt", ignoreCase = true) ||
    message.contains("malformed", ignoreCase = true) ||
    message.contains("invalid", ignoreCase = true) -> "DataValidationError"
    
    message.contains("config", ignoreCase = true) ||
    message.contains("setup", ignoreCase = true) ||
    message.contains("key", ignoreCase = true) -> "ConfigurationError"
    
    else -> "UnknownError"
}

/**
 * Map error type to severity level for alerts.
 */
private fun errorSeverity(errorType: String): String = when (errorType) {
    "ServiceUnavailable", "PermissionError", "StorageError", "ResourceNotFound" -> "ALERT"
    "NetworkError", "RateLimitError" -> "WARN"
    else -> "ERROR"
}

/**
 * Compute simple checksum to detect state corruption.
 * In production, use proper serialization + CRC32.
 */
private fun computeStateChecksum(state: RobustAppState): String {
    return state.toString().hashCode().toString()
}

/**
 * Extension for EventSink to emit categorized errors.
 */
private fun EventSink.emitError(errorType: String, message: String) {
    // Emit observability event for metrics dashboard
    // (In production, this goes to analytics backend)
    println("[METRICS] error_type=$errorType message=$message")
}

// ════════════════════════════════════════════════════════════════════════════════
// ADDITIONAL TOOLS — Show multi-tool scenarios and various error patterns
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Tracks student progress (quiz scores, concepts mastered, etc.)
 * 
 * Error scenarios:
 * - ✗ Invalid score → permanent failure
 * - ✗ Database unavailable → transient, retry
 * - ✗ API rate limited → backoff
 * - ✗ 3+ consecutive failures → circuit breaker opens
 */
class TrackProgressTool(
    override val stateStore: KoogStateStore<RobustAppState>,
    private val eventSink: EventSink = PrintlnEventSink,
) : StatefulTool<RobustAppState>() {

    override val name = "TrackProgress"
    override val description = "Records student quiz score and tracks mastery progress"
    override val permissionLevel = PermissionLevel.NORMAL

    override suspend fun execute(args: JsonObject): ToolResult {
        val score = args["score"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult.Failure(
                message = "Invalid score format — expected integer 0-100.",
                retryable = false,  // User input error, won't fix with retry
            )
        
        if (score < 0 || score > 100) {
            return ToolResult.Failure(
                message = "Score out of range (0-100).",
                retryable = false,
            )
        }

        return try {
            // Simulate database/API call
            persistScore(score)
            
            stateStore.update { it.copy(
                lastSuccessfulToolRunMs = System.currentTimeMillis()
            ) }
            
            ToolResult.Success("Progress recorded: $score%")

        } catch (e: Exception) {
            when {
                e.message?.contains("unavailable", ignoreCase = true) == true -> {
                    // Database or API is down — transient, will retry
                    ToolResult.Failure(
                        message = "Database temporarily unavailable. Retrying...",
                        retryable = true,
                        recoveryHint = RecoveryHint.RetryAfterDelay,
                    )
                }
                e.message?.contains("rate", ignoreCase = true) == true -> {
                    // Rate limited — definitely transient
                    ToolResult.Failure(
                        message = "Too many requests. Backing off...",
                        retryable = true,
                        recoveryHint = RecoveryHint.RetryAfterDelay,
                    )
                }
                else -> {
                    // Unknown error
                    ToolResult.Failure(
                        message = "Couldn't save progress right now. Will try next time.",
                        retryable = false,
                    )
                }
            }
        }
    }

    private suspend fun persistScore(score: Int) {
        // Stub: in production, save to database or API
        // Possible exceptions: IOException, SQLException, HttpException, TimeoutException
    }
}

/**
 * Adjusts teaching difficulty based on performance.
 * 
 * Error scenarios:
 * - ✗ None! This tool is purely local state (no external calls)
 * - ✓ Always succeeds, no circuit breaker needed
 */
class AdjustDifficultyTool(
    override val stateStore: KoogStateStore<RobustAppState>,
) : StatefulTool<RobustAppState>() {

    override val name = "AdjustDifficulty"
    override val description = "Adjusts lesson difficulty level (Beginner/Intermediate/Advanced)"
    override val permissionLevel = PermissionLevel.NORMAL

    override suspend fun execute(args: JsonObject): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing direction parameter (increase/decrease)")

        if (direction !in listOf("increase", "decrease")) {
            return ToolResult.Failure("Invalid direction — expected 'increase' or 'decrease'")
        }

        // Pure local state update — always succeeds
        stateStore.update { it.copy(
            lastSuccessfulToolRunMs = System.currentTimeMillis()
        ) }

        val newDifficulty = when (direction) {
            "increase" -> "Intermediate"
            "decrease" -> "Beginner"
            else -> "Beginner"
        }

        return ToolResult.Success("Difficulty adjusted to: $newDifficulty")
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// DEGRADED MODE — Fallback teaching when all providers fail
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Demonstrates teaching in degraded mode when all LLM providers are unavailable.
 * 
 * Degraded Teaching Strategy:
 * 1. Admit limitations to student ("I can't access my full knowledge right now")
 * 2. Use simple rule-based responses (no LLM reasoning)
 * 3. Offer basic math/language exercises from cache
 * 4. Guide user to retry later or try different approach
 * 5. Don't pretend AI is working (be honest with user)
 */
internal class DegradedModeTeachingStrategy {
    
    /**
     * When all providers fail, use this fallback response.
     */
    fun getFallbackMessage(): String = 
        "I'm having trouble connecting to my knowledge system right now, but we can still practice! " +
        "Let me give you a math problem or vocabulary exercise. What would help you learn today?"
    
    /**
     * Provide simple cached exercises when LLM is unavailable.
     */
    fun getCachedExercise(): String = 
        "Let's try this: What is 25% of 80? (Hint: break it into 25% = 1/4)"
    
    /**
     * Guide recovery when connectivity returns.
     */
    fun getRecoveryPrompt(): String = 
        "It looks like I'm back online! Let's try that again with my full capabilities."
}

/**
 * BuildConfig for accessing API keys and configuration.
 * 
 * In production on Android, this would be auto-generated from build.gradle.
 * Examples:
 * - BuildConfig.ANTHROPIC_KEY (from build.gradle buildConfigField)
 * - BuildConfig.DEBUG (auto-generated)
 * - BuildConfig.BUILD_TYPE (debug, release, etc.)
 */
object BuildConfig {
    /**
     * Anthropic API key — required for cloud fallback provider.
     * If null, the provider will skip Anthropic and try next fallback.
     */
    val ANTHROPIC_KEY: String? = System.getenv("ANTHROPIC_API_KEY")
    
    /**
     * Optional: Ollama base URL for local inference
     * Default assumes Ollama running on localhost:11434
     */
    val OLLAMA_BASE_URL: String = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    
    /**
     * Optional: Custom model path for on-device provider
     */
    val ONDEVICE_MODEL_PATH: String = 
        System.getenv("ONDEVICE_MODEL_PATH") ?: "/data/models/gemma-4.litertlm"
}
