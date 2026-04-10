package io.github.koogcompose.security

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLoggerTest {

    // ── Basic logging ──────────────────────────────────────────────────────────

    @Test
    fun `logApproved emits entry with APPROVED outcome`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("GetBalance", """{"account": "123"}""", "user1")
        val entry = logger.entries.replayCache.first()
        assertEquals("GetBalance", entry.toolName)
        assertEquals(AuditOutcome.APPROVED, entry.outcome)
        assertEquals("user1", entry.userId)
        assertFalse(entry.isRedacted)
    }

    @Test
    fun `logDenied emits entry with DENIED outcome`() = runTest {
        val logger = AuditLogger()
        logger.logDenied("SendMoney", """{"amount": 100}""", "user declined", "user1")
        val entry = logger.entries.replayCache.first()
        assertEquals("SendMoney", entry.toolName)
        assertEquals(AuditOutcome.DENIED, entry.outcome)
        assertEquals("user declined", entry.reason)
        assertFalse(entry.isRedacted)
    }

    @Test
    fun `logFailed emits entry with FAILED outcome`() = runTest {
        val logger = AuditLogger()
        logger.logFailed("Transfer", """{"to": "abc"}""", "network error", "user1")
        val entry = logger.entries.replayCache.first()
        assertEquals("Transfer", entry.toolName)
        assertEquals(AuditOutcome.FAILED, entry.outcome)
        assertEquals("network error", entry.reason)
        assertFalse(entry.isRedacted)
    }

    // ── Args redaction ─────────────────────────────────────────────────────────

    @Test
    fun `redactArgs replaces args with REDACTED marker`() = runTest {
        val logger = AuditLogger(redactArgs = true)
        logger.logApproved("SendMoney", """{"amount": 1000, "pin": "1234"}""")
        val entry = logger.entries.replayCache.first()
        assertEquals("[REDACTED]", entry.args)
        assertTrue(entry.isRedacted)
        // Tool name and outcome are still logged
        assertEquals("SendMoney", entry.toolName)
        assertEquals(AuditOutcome.APPROVED, entry.outcome)
    }

    @Test
    fun `redactArgs redacts denied args too`() = runTest {
        val logger = AuditLogger(redactArgs = true)
        logger.logDenied("DeleteAccount", """{"confirm": true}""", "user denied")
        val entry = logger.entries.replayCache.first()
        assertEquals("[REDACTED]", entry.args)
        assertTrue(entry.isRedacted)
        assertEquals("user denied", entry.reason)
    }

    @Test
    fun `redactArgs redacts failed args too`() = runTest {
        val logger = AuditLogger(redactArgs = true)
        logger.logFailed("Transfer", """{"iban": "DE8937040044"}""", "timeout")
        val entry = logger.entries.replayCache.first()
        assertEquals("[REDACTED]", entry.args)
        assertTrue(entry.isRedacted)
    }

    @Test
    fun `default redactArgs is false`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("GetBalance", """{"account": "123"}""")
        val entry = logger.entries.replayCache.first()
        assertEquals("""{"account": "123"}""", entry.args)
        assertFalse(entry.isRedacted)
    }

    // ── Query methods ──────────────────────────────────────────────────────────

    @Test
    fun `entriesFor filters by tool name`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("GetBalance", """{}""")
        logger.logApproved("SendMoney", """{}""")
        logger.logDenied("GetBalance", """{}""")

        val entries = logger.entriesFor("GetBalance")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.toolName == "GetBalance" })
    }

    @Test
    fun `entriesWithOutcome filters by outcome`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("ToolA", """{}""")
        logger.logApproved("ToolB", """{}""")
        logger.logDenied("ToolC", """{}""")
        logger.logFailed("ToolD", """{}""", "error")

        assertEquals(2, logger.entriesWithOutcome(AuditOutcome.APPROVED).size)
        assertEquals(1, logger.entriesWithOutcome(AuditOutcome.DENIED).size)
        assertEquals(1, logger.entriesWithOutcome(AuditOutcome.FAILED).size)
    }

    @Test
    fun `approvedCount returns correct count`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("ToolA", """{}""")
        logger.logApproved("ToolB", """{}""")
        logger.logDenied("ToolC", """{}""")
        assertEquals(2, logger.approvedCount)
    }

    @Test
    fun `deniedCount returns correct count`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("ToolA", """{}""")
        logger.logDenied("ToolB", """{}""")
        logger.logDenied("ToolC", """{}""")
        assertEquals(2, logger.deniedCount)
    }

    // ── Flow emission ──────────────────────────────────────────────────────────

    @Test
    fun `entries flow emits in real time`() = runTest {
        val logger = AuditLogger()
        logger.logApproved("ToolA", """{}""")
        // Flow has replay, so we can read from the cache
        assertEquals("ToolA", logger.entries.replayCache.first().toolName)
    }

    // ── Max entries cap ────────────────────────────────────────────────────────

    @Test
    fun `replayCache respects maxEntriesInMemory`() = runTest {
        val logger = AuditLogger(maxEntriesInMemory = 3)
        repeat(5) { i ->
            logger.logApproved("Tool$i", """{}""")
        }
        // Should only keep the last 3
        assertEquals(3, logger.replayedEntries.size)
    }
}
