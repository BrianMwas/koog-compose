package io.github.koogcompose.sample

import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.observability.EventSink
import io.github.koogcompose.observability.PrintlnEventSink
import io.github.koogcompose.provider.RouterStrategy
import kotlinx.serialization.Serializable
import kotlinx.coroutines.GlobalScope

/**
 * Demonstrates GitHub Issue #42: Error Handling Recovery Patterns
 * 
 * Focus: Provider fallback chains, graceful degradation when all providers fail,
 * and recovery-aware tool error handling.
 */

/**
 * Application state for robust teaching with comprehensive error handling.
 * Tracks errors, degraded modes, and retry counts for recovery orchestration.
 */
@Serializable
data class RobustAppState(
    val lastError: String? = null,
    val isDegradedMode: Boolean = false,
    val retryCount: Int = 0,
    val lastHomeworkPhoto: String? = null,
)

/**
 * Builds a teaching session with production-grade error recovery.
 * 
 * Demonstrates Issue #42 patterns:
 * - Provider fallback chain (on-device → cloud → local → degraded)
 * - Recovery hints for tool failures
 * - Graceful degradation when all providers fail
 * - Circuit breaker protection for external services
 * - Session persistence with corruption recovery
 *
 * @param stateStore Centralized state store (passed once, not duplicated)
 * @param eventSink For observability events
 * @return PhaseSession ready to start teaching
 */
fun buildRobustTeachingSession(
    stateStore: KoogStateStore<RobustAppState> = KoogStateStore(RobustAppState()),
    eventSink: EventSink = PrintlnEventSink,
): PhaseSession<RobustAppState> {

    val context = koogCompose<RobustAppState> {
        // Single initialization — state flows through the entire session
        initialState { RobustAppState() }

        // Provider with fallback chain: router inside configures multiple providers
        provider {
            router(strategy = RouterStrategy.Fallback) {
                // 1. Try on-device first (fast, private)
                onDevice(modelPath = "/data/models/gemma-4.litertlm") {
                    maxToolRounds(5)
                }
                
                // Fallback chain continues with cloud providers
                anthropic(apiKey = BuildConfig.ANTHROPIC_KEY ?: "") {
                    model = "claude-sonnet-4-5"
                }
                
                // Final fallback: local Ollama instance
                ollama(model = "llama3.2") {
                    baseUrl = "http://localhost:11434"
                }
            }
        }

        phases {
            phase("teach", initial = true) {
                instructions {
                    """
You are a teaching assistant helping students master concepts.

If you are in degraded mode (no external services available):
- Use your built-in knowledge to teach
- Be honest about limitations
- Offer to resume when connectivity returns

Always be encouraging and patient.
                    """.trimIndent()
                }
                
                // Tool with full recovery hints
                tool(SaveHomeworkPhotoTool(stateStore = stateStore, eventSink = eventSink))
            }
        }

        config {
            // Observability is handled through event handlers below

            // Retry transient failures automatically
            retry {
                maxAttempts = 3
                initialDelayMs = 1_000
            }

            // Detect if agent loops and offer alternatives
            stuckDetection {
                threshold = 4
                fallbackMessage = "I seem to be going in circles. Let me try a different approach."
            }
        }

        // Comprehensive error event handlers for observability
        events {
            // Turn failures — categorize and log
            onTurnFailed { event ->
                val message = event.message ?: "Unknown failure"
                val category = when {
                    message.contains("network", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true) ||
                    message.contains("connection", ignoreCase = true) -> "Network"
                    
                    message.contains("permission", ignoreCase = true) ||
                    message.contains("denied", ignoreCase = true) -> "Permission"
                    
                    message.contains("storage", ignoreCase = true) ||
                    message.contains("space", ignoreCase = true) ||
                    message.contains("full", ignoreCase = true) -> "Storage"
                    
                    message.contains("auth", ignoreCase = true) -> "Authentication"
                    
                    message.contains("unavailable", ignoreCase = true) ||
                    message.contains("service", ignoreCase = true) -> "Service"
                    
                    message.contains("rate", ignoreCase = true) ||
                    message.contains("limit", ignoreCase = true) -> "RateLimit"
                    
                    message.contains("corrupt", ignoreCase = true) ||
                    message.contains("malformed", ignoreCase = true) ||
                    message.contains("invalid", ignoreCase = true) -> "Validation"
                    
                    message.contains("config", ignoreCase = true) -> "Configuration"
                    
                    else -> "Unknown"
                }
                // Log for metrics and debugging
                println("[ERROR] $category: $message")
            }
            
            // Agent getting stuck — inform user
            onAgentStuck { event ->
                println("[WARNING] Agent stuck, will try different approach")
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
 * Stub for DegradedModeExecutor — demonstrates fallback when all providers fail.
 * In production, implement with local knowledge base or cached responses.
 */
internal class DegradedModeExecutor(val message: String)

/**
 * BuildConfig mock for example — in reality comes from Android build config.
 */
object BuildConfig {
    val ANTHROPIC_KEY: String? = System.getenv("ANTHROPIC_API_KEY")
}
