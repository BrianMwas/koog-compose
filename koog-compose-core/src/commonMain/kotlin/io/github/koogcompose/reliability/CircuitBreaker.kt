package io.github.koogcompose.reliability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prevents cascading failures by stopping calls to a repeatedly failing resource
 * until a cooldown period has passed.
 *
 * States:
 * - **CLOSED** — normal operation, calls go through
 * - **OPEN** — too many failures, calls are rejected immediately
 * - **HALF_OPEN** — cooldown passed, trial calls are allowed
 *
 * Typical sequence:
 * 1. CLOSED → success calls go through
 * 2. failureThreshold consecutive failures → OPEN
 * 3. cooldownMs time passes → HALF_OPEN (trial phase)
 * 4. successThreshold successes in HALF_OPEN → back to CLOSED
 * 5. Any failure in HALF_OPEN → back to OPEN
 *
 * Usage:
 * ```kotlin
 * val breaker = CircuitBreaker(failureThreshold = 5, cooldownMs = 60_000)
 * try {
 *     breaker.call { callExternalService() }
 * } catch (e: CircuitOpenException) {
 *     // Circuit is open, fallback to degraded mode
 * }
 * ```
 */
public class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val cooldownMs: Long = 60_000,
    private val successThreshold: Int = 2,
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failureCount = 0
    private var successCount = 0
    private var openedAtMs = 0L
    private val mutex = Mutex()

    /**
     * Wraps a call with circuit breaker protection.
     * Throws [CircuitOpenException] immediately when the circuit is OPEN.
     *
     * @param block The operation to protect
     * @return Result of the operation if successful
     * @throws CircuitOpenException if circuit is open and cooldown hasn't passed
     * @throws Any exception from the block if the circuit is not open
     */
    public suspend fun <T> call(block: suspend () -> T): T {
        // Check state and potentially transition to HALF_OPEN
        mutex.withLock {
            when (state) {
                State.OPEN -> {
                    if (io.github.koogcompose.observability.currentTimeMs() - openedAtMs >= cooldownMs) {
                        state = State.HALF_OPEN
                        successCount = 0
                    } else {
                        throw CircuitOpenException(
                            "Circuit is open. Cooldown expires in " +
                            "${cooldownMs - (io.github.koogcompose.observability.currentTimeMs() - openedAtMs)}ms"
                        )
                    }
                }
                State.CLOSED, State.HALF_OPEN -> Unit
            }
        }

        // Execute the operation
        return try {
            val result = block()
            recordSuccess()
            result
        } catch (e: Exception) {
            recordFailure()
            throw e
        }
    }

    private suspend fun recordSuccess() = mutex.withLock {
        failureCount = 0
        if (state == State.HALF_OPEN) {
            successCount++
            if (successCount >= successThreshold) {
                state = State.CLOSED
            }
        }
    }

    private suspend fun recordFailure() = mutex.withLock {
        failureCount++
        if (state == State.HALF_OPEN || failureCount >= failureThreshold) {
            state = State.OPEN
            openedAtMs = io.github.koogcompose.observability.currentTimeMs()
            failureCount = 0
        }
    }

    /** Returns true if the circuit is currently open and rejecting calls. */
    public val isOpen: Boolean
        get() = state == State.OPEN

    /** Returns true if the circuit is in half-open state (trial phase). */
    public val isHalfOpen: Boolean
        get() = state == State.HALF_OPEN

    /** Current failure count (resets on success or state change). */
    public val failuresSinceLastSuccess: Int
        get() = failureCount
}

/**
 * Thrown by [CircuitBreaker.call] when the circuit is open.
 * Indicates the resource is unavailable and will not be called.
 */
public class CircuitOpenException(message: String) : Exception(message)


/**
 * Wraps a tool with circuit breaker protection to prevent cascading failures.
 * When the circuit is open, the tool returns a user-friendly error instead of
 * throwing or calling the unreliable resource.
 *
 * Usage:
 * ```kotlin
 * val tool = SaveHomeworkPhotoTool(stateStore)
 * val protected = CircuitBreakerGuard(
 *     delegate = tool,
 *     circuitBreaker = CircuitBreaker(failureThreshold = 5)
 * )
 * ```
 */
public class CircuitBreakerGuard(
    private val delegate: io.github.koogcompose.tool.SecureTool,
    private val circuitBreaker: CircuitBreaker,
) : io.github.koogcompose.tool.SecureTool by delegate {

    override suspend fun execute(args: kotlinx.serialization.json.JsonObject)
            : io.github.koogcompose.tool.ToolResult {
        return try {
            circuitBreaker.call { delegate.execute(args) }
        } catch (e: CircuitOpenException) {
            io.github.koogcompose.tool.ToolResult.Failure(
                message  = "This feature is temporarily unavailable. Please try again in a minute.",
                retryable = false,
                recoveryHint = io.github.koogcompose.tool.RecoveryHint.RetryAfterDelay,
            )
        }
    }
}
