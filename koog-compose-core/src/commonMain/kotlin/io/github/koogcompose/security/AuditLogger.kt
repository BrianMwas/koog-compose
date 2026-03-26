package io.github.koogcompose.security

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Clock
import kotlin.time.Instant

enum class AuditOutcome { APPROVED, DENIED, FAILED }

data class AuditEntry(
    val timestamp: Instant,
    val toolName: String,
    val args: String,
    val outcome: AuditOutcome,
    val userId: String? = null,
    val reason: String? = null
)

class AuditLogger(
    private val clock: Clock = Clock.System,
    private val maxEntriesInMemory: Int = 500
) {
    private val _entries = MutableSharedFlow<AuditEntry>(
        replay = maxEntriesInMemory,
        extraBufferCapacity = 64
    )

    val entries: SharedFlow<AuditEntry> = _entries.asSharedFlow()

    val replayedEntries: List<AuditEntry>
        get() = _entries.replayCache

    suspend fun logApproved(toolName: String, args: String, userId: String? = null) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.APPROVED, userId))
    }

    suspend fun logDenied(
        toolName: String,
        args: String,
        reason: String = "Permission denied",
        userId: String? = null
    ) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.DENIED, userId, reason))
    }

    suspend fun logFailed(
        toolName: String,
        args: String,
        reason: String,
        userId: String? = null
    ) {
        emit(AuditEntry(clock.now(), toolName, args, AuditOutcome.FAILED, userId, reason))
    }

    fun entriesFor(toolName: String): List<AuditEntry> =
        replayedEntries.filter { it.toolName == toolName }

    fun entriesWithOutcome(outcome: AuditOutcome): List<AuditEntry> =
        replayedEntries.filter { it.outcome == outcome }

    val approvedCount: Int get() = entriesWithOutcome(AuditOutcome.APPROVED).size
    val deniedCount: Int get() = entriesWithOutcome(AuditOutcome.DENIED).size

    private suspend fun emit(entry: AuditEntry) = _entries.emit(entry)
}