package io.github.koogcompose.testing

import io.github.koogcompose.security.PendingConfirmation

/**
 * Decides how a [TestPhaseSession] should resolve tool confirmations.
 */
public fun interface TestConfirmationHandler {
    public fun decide(pendingConfirmation: PendingConfirmation): TestConfirmationDecision
}

/**
 * The confirmation action chosen by a [TestConfirmationHandler].
 */
public enum class TestConfirmationDecision {
    APPROVE,
    DENY
}

/**
 * Automatically approves every pending confirmation.
 */
public object AutoApproveConfirmationHandler : TestConfirmationHandler {
    override fun decide(pendingConfirmation: PendingConfirmation): TestConfirmationDecision {
        return TestConfirmationDecision.APPROVE
    }
}

/**
 * Automatically denies every pending confirmation.
 */
public object AutoDenyConfirmationHandler : TestConfirmationHandler {
    override fun decide(pendingConfirmation: PendingConfirmation): TestConfirmationDecision {
        return TestConfirmationDecision.DENY
    }
}

/**
 * Builds a confirmation handler that resolves decisions per tool name.
 */
public fun testConfirmationHandler(
    fallback: TestConfirmationDecision = TestConfirmationDecision.APPROVE,
    block: MutableMap<String, TestConfirmationDecision>.() -> Unit
): TestConfirmationHandler {
    val decisions = mutableMapOf<String, TestConfirmationDecision>().apply(block).toMap()
    return TestConfirmationHandler { pendingConfirmation ->
        decisions[pendingConfirmation.tool.name] ?: fallback
    }
}
