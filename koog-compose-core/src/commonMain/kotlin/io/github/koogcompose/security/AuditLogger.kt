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
 */
public data class AuditEntry(
    val timestamp: Instant,
    val toolName: String,
    val args: String,
    val outcome: AuditOutcome,
    val userId: String? = null,
    val reason: String? = null
)

/**
 * Manages the auditing of tool executions for security and monitoring.
 */
public class AuditLogger(
    private val clock: Clock = Clock.System,
    private val maxEntriesInMemory: Int = 500
) {
    private val _entries = MutableSharedFlow<AuditEntry>(
        replay = maxEntriesInMemory,
        extraBufferCapacity = 64
    )

    /**
     * A flow of all audit entries as they are logged.
     */
    public val entries: SharedFlow<AuditEntry> = _entries.asSharedFlow()

    /**
     * Returns the current cache of audit entries.
     */
    public val replayedEntries: List<AuditEntry>
        get() = _entries.replayCache

    internal suspend fun logApproved(toolName: String, args: String, userId: String? = null) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.APPROVED, userId))
    }

    internal suspend fun logDenied(
        toolName: String,
        args: String,
        reason: String = "Permission denied",
        userId: String? = null
    ) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.DENIED, userId, reason))
    }

    internal suspend fun logFailed(
        toolName: String,
        args: String,
        reason: String,
        userId: String? = null
    ) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.FAILED, userId, reason))
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

    private suspend fun emit(entry: AuditEntry) = _entries.emit(entry)
}
