package io.github.koogcompose.testing

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts the current phase of a [TestPhaseSession].
 */
public fun assertPhase(
    session: TestPhaseSession<*>,
    expected: String
): Unit {
    assertEquals(
        expected = expected,
        actual = session.currentPhase,
        message = "Expected active phase '$expected' but was '${session.currentPhase}'."
    )
}

/**
 * Runs assertions against the current typed state snapshot.
 */
public fun <S> assertState(
    session: TestPhaseSession<S>,
    assertion: (S) -> Unit
): Unit {
    val state = session.appState ?: fail("Expected a state store, but this test session has no state configured.")
    assertion(state)
}

/**
 * Asserts that a scripted turn caused a specific tool to be called.
 */
public fun assertToolCalled(
    session: TestPhaseSession<*>,
    toolName: String
): Unit {
    assertTrue(
        actual = session.promptExecutor.transcript.toolCalls.any { record -> record.toolName == toolName },
        message = "Expected tool '$toolName' to be called, but it was never requested."
    )
}

/**
 * Asserts that a tool call completed with a denial result.
 */
public fun assertGuardrailDenied(
    session: TestPhaseSession<*>,
    toolName: String
): Unit {
    assertTrue(
        actual = session.promptExecutor.transcript.toolResults.any { record ->
            record.toolName == toolName && record.denied
        },
        message = "Expected a denial result for tool '$toolName', but no denial was observed."
    )
}
