package io.github.koogcompose.security

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Outcome of an audited tool execution.
 */
public enum class AuditOutcome {
    /** The execution was approved by the user or framework. */
    APPROVED,
    /** The execution was denied by the user. */
    DENIED,
    /** The execution failed with an error. */
    FAILED
}

/**
 * A single entry in the audit log.
 *
 * When [args] is `"[REDACTED]"`, the caller has requested args be withheld
 * for privacy reasons. The tool name, outcome, and timestamp are still logged
 * so production monitoring work without PII exposure.
 */
public data class AuditEntry(
    val timestamp: Instant,
    val toolName: String,
    val args: String,
    val outcome: AuditOutcome,
    val userId: String? = null,
    val reason: String? = null
) {
    /** True when args were withheld for privacy. */
    public val isRedacted: Boolean get() = args == REDACTED_MARKER

    private companion object {
        const val REDACTED_MARKER = "[REDACTED]"
    }
}

/**
 * Manages the auditing of tool executions for security and monitoring.
 *
 * Audit logs are **in-memory only** — they never leave the device unless the
 * app owner explicitly subscribes to [entries] and forwards them externally.
 *
 * Set [redactArgs] to `true` for apps that handle PII (phone numbers, auth tokens,
 * addresses). Tool names, outcomes, and timestamps are still logged so production
 * monitoring works — just not the raw arguments.
 *
 * This logger is always-on by design. Production apps need audit trails for
 * compliance, debugging, and detecting tool call loops.
 */
public class AuditLogger(
    private val clock: Clock = Clock.System,
    private val maxEntriesInMemory: Int = 500,
    private val redactArgs: Boolean = false,
) {
    private val _entries = MutableSharedFlow<AuditEntry>(
        replay = maxEntriesInMemory,
        extraBufferCapacity = 64
    )

    /**
     * A flow of all audit entries as they are logged.
     *
     * Subscribing to this flow does not affect runtime behavior — it's purely
     * for observability. Data never leaves the device unless the subscriber
     * explicitly forwards it to an external service.
     */
    public val entries: SharedFlow<AuditEntry> = _entries.asSharedFlow()

    /**
     * Returns the current cache of audit entries.
     */
    public val replayedEntries: List<AuditEntry>
        get() = _entries.replayCache

    internal suspend fun logApproved(
        toolName: String,
        args: String,
        userId: String? = null,
    ) {
        emit(AuditEntry(clock.now(), toolName, maybeRedact(args), AuditOutcome.APPROVED, userId))
    }

    internal suspend fun logDenied(
        toolName: String,
        args: String,
        reason: String = "Permission denied",
        userId: String? = null,
    ) {
        emit(
            AuditEntry(
                clock.now(),
                toolName,
                maybeRedact(args),
                AuditOutcome.DENIED,
                userId,
                reason,
            )
        )
    }

    internal suspend fun logFailed(
        toolName: String,
        args: String,
        reason: String,
        userId: String? = null,
    ) {
        emit(
            AuditEntry(
                clock.now(),
                toolName,
                maybeRedact(args),
                AuditOutcome.FAILED,
                userId,
                reason,
            )
        )
    }

    /** Returns all logged entries for a specific tool. */
    public fun entriesFor(toolName: String): List<AuditEntry> =
        replayedEntries.filter { it.toolName == toolName }

    /** Returns all logged entries with a specific outcome. */
    public fun entriesWithOutcome(outcome: AuditOutcome): List<AuditEntry> =
        replayedEntries.filter { it.outcome == outcome }

    /** The total count of approved executions. */
    public val approvedCount: Int get() = entriesWithOutcome(AuditOutcome.APPROVED).size
    /** The total count of denied executions. */
    public val deniedCount: Int get() = entriesWithOutcome(AuditOutcome.DENIED).size

    private fun maybeRedact(args: String): String =
        if (redactArgs) "[REDACTED]" else args

    private suspend fun emit(entry: AuditEntry) = _entries.emit(entry)
}
