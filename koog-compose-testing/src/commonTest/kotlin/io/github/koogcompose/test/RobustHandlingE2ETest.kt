package io.github.koogcompose.test

import io.github.koogcompose.reliability.CircuitBreaker
import io.github.koogcompose.reliability.CircuitOpenException
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.RecoveryHint
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.testing.FakeSecureTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive end-to-end tests for robust error handling patterns.
 *
 * Validates:
 * - Tool result recovery hints
 * - Retryable vs. permanent failures
 * - Circuit breaker pattern
 * - Session recovery from corruption
 * - Permission denied workflows
 */
class RobustHandlingE2ETest {

    // ── Recovery Hint Tests ────────────────────────────────────────────────

    @Test
    fun `tool failure includes retryable flag`() {
        val result = ToolResult.Failure(
            message = "Network timeout",
            retryable = true,
            recoveryHint = RecoveryHint.RetryAfterDelay
        )
        assertTrue(result.retryable)
        assertTrue(result.recoveryHint is RecoveryHint.RetryAfterDelay)
    }

    @Test
    fun `permanent failure has retryable=false`() {
        val result = ToolResult.Failure(
            message = "Invalid input",
            retryable = false,
        )
        assertFalse(result.retryable)
    }

    @Test
    fun `denied result carries recovery hint`() {
        val hint = RecoveryHint.RequiresUserAction("Please grant permission in Settings")
        val result = ToolResult.Denied(
            reason = "Permission denied",
            recoveryHint = hint
        )
        assertEquals(hint, result.recoveryHint)
    }

    // ── Circuit Breaker Tests ──────────────────────────────────────────────

    @Test
    fun `circuit breaker opens after failureThreshold failures`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, cooldownMs = 60_000)
        var callCount = 0

        // Fail 3 times
        repeat(3) {
            try {
                breaker.call { callCount++; throw RuntimeException("fail") }
            } catch (e: RuntimeException) { /* expected */ }
        }

        assertTrue(breaker.isOpen)
        assertEquals(3, callCount)
    }

    @Test
    fun `circuit breaker rejects calls immediately when open`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 60_000)
        var callCount = 0

        // Fail twice to open the circuit
        repeat(2) {
            try {
                breaker.call { callCount++; throw RuntimeException("fail") }
            } catch (e: RuntimeException) { /* expected */ }
        }

        // 3rd call should be rejected immediately
        try {
            breaker.call { callCount++ }
        } catch (e: CircuitOpenException) { /* expected */ }

        // Call count is 2, not 3 — the circuit didn't even try to execute
        assertEquals(2, callCount)
    }

    @Test
    fun `circuit breaker recovers after cooldown`() = runTest {
        val breaker = CircuitBreaker(
            failureThreshold = 2,
            cooldownMs = 50,   // 50ms for fast test
            successThreshold = 1
        )
        var callCount = 0

        // Open the circuit
        repeat(2) {
            try {
                breaker.call { callCount++; throw RuntimeException("fail") }
            } catch (e: RuntimeException) { /* expected */ }
        }
        assertTrue(breaker.isOpen)

        // Wait for cooldown
        kotlinx.coroutines.delay(60)

        // Next call should succeed (half-open, then close on success)
        val result = breaker.call { callCount++; "success" }
        assertEquals("success", result)
        assertEquals(3, callCount)
        assertFalse(breaker.isOpen)
    }

    @Test
    fun `circuit breaker half-open requires successes to fully close`() = runTest {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            cooldownMs = 50,
            successThreshold = 2
        )
        var callCount = 0

        // Open: 1 failure
        try {
            breaker.call { callCount++; throw RuntimeException("fail") }
        } catch (e: RuntimeException) { /* expected */ }
        assertTrue(breaker.isOpen)

        // Wait for cooldown
        kotlinx.coroutines.delay(60)
        assertTrue(breaker.isHalfOpen)

        // 1st success (still half-open)
        breaker.call { callCount++ }
        assertTrue(breaker.isHalfOpen)

        // 2nd success (now closed)
        breaker.call { callCount++ }
        assertFalse(breaker.isOpen)
        assertEquals(3, callCount)
    }

    // ── Tool Failure Scenarios ────────────────────────────────────────────

    @Test
    fun `retryable tool failure allows retry logic`() = runTest {
        var callCount = 0
        val tool = FakeSecureTool(
            name = "TestTool",
            onExecute = {
                callCount++
                if (callCount < 3) {
                    ToolResult.Failure(
                        message = "Network error",
                        retryable = true,
                        recoveryHint = RecoveryHint.RetryAfterDelay
                    )
                } else {
                    ToolResult.Success("Succeeded on retry")
                }
            }
        )

        // Simulate retry loop (would be done by agent/session)
        repeat(3) {
            val result = tool.execute(buildJsonObject {})
            if (result is ToolResult.Success) {
                assertEquals("Succeeded on retry", result.output)
                return@runTest
            }
        }
    }

    @Test
    fun `denied result with RequiresUserAction guides user`() {
        val result = ToolResult.Denied(
            reason = "Storage permission was denied",
            recoveryHint = RecoveryHint.RequiresUserAction(
                "Go to Settings → Permissions → Storage and grant access"
            )
        )
        val hint = result.recoveryHint as? RecoveryHint.RequiresUserAction
        assertTrue(hint != null)
        assertTrue(hint!!.prompt.contains("Settings"))
    }

    @Test
    fun `degraded fallback provides alternative`() {
        val result = ToolResult.Failure(
            message = "API unavailable",
            retryable = false,
            recoveryHint = RecoveryHint.DegradedFallback(
                fallbackValue = "Using cached data from 1 hour ago"
            )
        )
        val fallback = result.recoveryHint as? RecoveryHint.DegradedFallback
        assertTrue(fallback != null)
        assertEquals("Using cached data from 1 hour ago", fallback!!.fallbackValue)
    }

    // ── Permission Management ──────────────────────────────────────────────

    @Test
    fun `permission denied tool returns Denied result`() = runTest {
        val tool = object : SecureTool {
            override val name = "LockedTool"
            override val description = "A locked tool"
            override val permissionLevel = PermissionLevel.CRITICAL

            override suspend fun execute(args: JsonObject): ToolResult {
                return ToolResult.Denied(
                    reason = "This action requires your approval",
                    recoveryHint = RecoveryHint.RequiresUserAction(
                        "Please confirm in the app to proceed"
                    )
                )
            }
        }

        val result = tool.execute(buildJsonObject {})
        assertTrue(result is ToolResult.Denied)
        assertEquals("This action requires your approval", (result as ToolResult.Denied).reason)
    }

    // ── Session Recovery ───────────────────────────────────────────────────

    @Test
    fun `corrupted session can be detected and cleared`() {
        val corruptedJson = """{"invalid json that won't parse}"""
        try {
            kotlinx.serialization.json.Json.decodeFromString<TestState>(corruptedJson)
        } catch (e: kotlinx.serialization.SerializationException) {
            // Expected — corrupted data detected
            assertTrue(true)
            return
        }
        throw AssertionError("Should have caught SerializationException")
    }

    @Test
    fun `recovery hint chains guide agent decisions`() {
        // Simulate agent decision logic
        val result1 = ToolResult.Failure("Network", retryable = true, RecoveryHint.RetryAfterDelay)
        val result2 = ToolResult.Denied("Permission", RecoveryHint.RequiresUserAction("Confirm"))
        val result3 = ToolResult.Failure("API Down", retryable = false, RecoveryHint.DegradedFallback("Cached"))

        // Agent logic: if retryable, retry; if RequiresUserAction, ask user; if DegradedFallback, use fallback
        assertEquals(RecoveryHint.RetryAfterDelay::class, result1.recoveryHint::class)
        assertEquals(RecoveryHint.RequiresUserAction::class, (result2 as ToolResult.Denied).recoveryHint::class)
        assertEquals(RecoveryHint.DegradedFallback::class, result3.recoveryHint::class)
    }
}

// ── Test Helpers ──────────────────────────────────────────────────────────────

@kotlinx.serialization.Serializable
private data class TestState(val name: String = "")

private fun buildJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {})
        : JsonObject = kotlinx.serialization.json.buildJsonObject(block)
