package io.github.koogcompose.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CircuitBreakerTest {

    @Test
    fun closed_passesThroughSuccessfulCalls() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3)

        val result = breaker.call { "ok" }

        assertEquals("ok", result)
        assertFalse(breaker.isOpen)
    }

    @Test
    fun failuresBelowThreshold_keepCircuitClosed() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3)

        repeat(2) {
            assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        }

        assertFalse(breaker.isOpen)
        assertEquals(2, breaker.failuresSinceLastSuccess)
    }

    @Test
    fun reachingFailureThreshold_opensCircuit() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3)

        repeat(3) {
            assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        }

        assertTrue(breaker.isOpen)
    }

    @Test
    fun openCircuit_rejectsCallsWithCircuitOpenException() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 60_000) { 0L }

        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        assertTrue(breaker.isOpen)

        // Block is never invoked while open.
        var invoked = false
        assertFailsWith<CircuitOpenException> {
            breaker.call { invoked = true; "unreachable" }
        }
        assertFalse(invoked)
    }

    @Test
    fun successResetsFailureCount() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3)

        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        breaker.call { "recovered" }

        assertEquals(0, breaker.failuresSinceLastSuccess)
        assertFalse(breaker.isOpen)
    }

    @Test
    fun afterCooldown_circuitTransitionsToHalfOpen() = runTest {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 1_000) { now }

        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        assertTrue(breaker.isOpen)

        now = 1_000
        breaker.call { "trial" }
        assertTrue(breaker.isHalfOpen)
    }

    @Test
    fun halfOpen_successThresholdSuccesses_closesCircuit() = runTest {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 1_000, successThreshold = 2) { now }

        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        now = 1_000

        breaker.call { "trial-1" }
        assertTrue(breaker.isHalfOpen)
        breaker.call { "trial-2" }
        assertFalse(breaker.isHalfOpen)
        assertFalse(breaker.isOpen)
    }

    @Test
    fun halfOpen_failure_reopensCircuit() = runTest {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 1_000, successThreshold = 2) { now }

        assertFailsWith<IllegalStateException> { breaker.call { error("boom") } }
        now = 1_000
        breaker.call { "trial" }
        assertTrue(breaker.isHalfOpen)

        assertFailsWith<IllegalStateException> { breaker.call { error("boom again") } }
        assertTrue(breaker.isOpen)
    }

    @Test
    fun recordSuccess_returnsTrueOnlyWhenItClosesTheCircuit() = runTest {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 1, cooldownMs = 1_000, successThreshold = 2) { now }

        // Closed → success is not a transition.
        assertFalse(breaker.recordSuccess())

        assertTrue(breaker.recordFailure()) // CLOSED → OPEN
        now = 1_000
        // Enter HALF_OPEN via call(), then drive successes.
        breaker.call { "trial-1" }
        // One more success crosses successThreshold → CLOSED.
        assertTrue(breaker.recordSuccess())
    }

    @Test
    fun recordFailure_returnsTrueOnlyOnTransitionToOpen() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 2)

        assertFalse(breaker.recordFailure()) // 1st failure, still closed
        assertTrue(breaker.recordFailure())  // 2nd failure → OPEN
    }
}
