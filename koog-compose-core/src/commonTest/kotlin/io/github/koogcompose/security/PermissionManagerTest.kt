package io.github.koogcompose.security

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerTest {

    // ── check() ────────────────────────────────────────────────────────────────

    @Test
    fun safeTool_isGrantedWithoutConfirmation() {
        val pm = PermissionManager(AuditLogger())

        val result = pm.check(TestTool("Safe", PermissionLevel.SAFE), EMPTY_ARGS)

        assertEquals(PermissionCheckResult.Granted, result)
    }

    @Test
    fun sensitiveTool_requiresConfirmationByDefault() {
        val pm = PermissionManager(AuditLogger())

        val result = pm.check(TestTool("Edit", PermissionLevel.SENSITIVE), EMPTY_ARGS)

        assertTrue(result is PermissionCheckResult.RequiresConfirmation)
        result as PermissionCheckResult.RequiresConfirmation
        assertEquals(PermissionLevel.SENSITIVE, result.permissionLevel)
        assertEquals("Edit", result.toolName)
    }

    @Test
    fun sensitiveTool_grantedWhenConfirmationDisabled() {
        val pm = PermissionManager(AuditLogger(), requireConfirmationForSensitive = false)

        val result = pm.check(TestTool("Edit", PermissionLevel.SENSITIVE), EMPTY_ARGS)

        assertEquals(PermissionCheckResult.Granted, result)
    }

    @Test
    fun criticalTool_alwaysRequiresConfirmation() {
        val pm = PermissionManager(AuditLogger(), requireConfirmationForSensitive = false)

        val result = pm.check(TestTool("Delete", PermissionLevel.CRITICAL), EMPTY_ARGS)

        assertTrue(result is PermissionCheckResult.RequiresConfirmation)
        assertEquals(PermissionLevel.CRITICAL, (result as PermissionCheckResult.RequiresConfirmation).permissionLevel)
    }

    @Test
    fun confirmationMessage_comesFromTool() {
        val pm = PermissionManager(AuditLogger())

        val result = pm.check(TestTool("Delete", PermissionLevel.CRITICAL, confirmation = "Really delete?"), EMPTY_ARGS)

        assertEquals("Really delete?", (result as PermissionCheckResult.RequiresConfirmation).confirmationMessage)
    }

    // ── requestApproval() flow ──────────────────────────────────────────────────

    @Test
    fun requestApproval_confirmed_returnsTrueAndClearsPending() = runTest {
        val pm = PermissionManager(AuditLogger())
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()
        assertTrue(pm.hasPendingConfirmation)

        pm.onUserConfirmed()

        assertTrue(deferred.await())
        assertFalse(pm.hasPendingConfirmation)
    }

    @Test
    fun requestApproval_denied_returnsFalse() = runTest {
        val pm = PermissionManager(AuditLogger())
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()

        pm.onUserDenied()

        assertFalse(deferred.await())
        assertFalse(pm.hasPendingConfirmation)
    }

    @Test
    fun confirmedApproval_isAudited() = runTest {
        val audit = AuditLogger()
        val pm = PermissionManager(audit)
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()
        pm.onUserConfirmed()
        deferred.await()

        assertEquals(1, audit.approvedCount)
    }

    @Test
    fun deniedApproval_isAudited() = runTest {
        val audit = AuditLogger()
        val pm = PermissionManager(audit)
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()
        pm.onUserDenied()
        deferred.await()

        assertEquals(1, audit.deniedCount)
    }

    @Test
    fun pendingConfirmation_exposesPendingTool() = runTest {
        val pm = PermissionManager(AuditLogger())
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()

        val pending = pm.pendingConfirmation.value
        assertEquals("Delete", pending?.tool?.name)
        assertEquals(PermissionLevel.CRITICAL, pending?.permissionLevel)

        pm.onUserConfirmed()
        deferred.await()
    }

    // ── onUserConfirmed / onUserDenied without pending ──────────────────────────

    @Test
    fun onUserConfirmed_withoutPending_returnsDenied() = runTest {
        val pm = PermissionManager(AuditLogger())

        val result = pm.onUserConfirmed()

        assertTrue(result is ToolResult.Denied)
    }

    @Test
    fun clearPending_resetsState() = runTest {
        val pm = PermissionManager(AuditLogger())
        val tool = TestTool("Delete", PermissionLevel.CRITICAL)

        val deferred = async { pm.requestApproval(tool, EMPTY_ARGS) }
        advanceUntilIdle()
        assertTrue(pm.hasPendingConfirmation)

        pm.clearPending()

        assertFalse(pm.hasPendingConfirmation)
        deferred.cancel()
    }
}

private val EMPTY_ARGS = JsonObject(emptyMap())

private class TestTool(
    override val name: String,
    override val permissionLevel: PermissionLevel,
    private val confirmation: String? = null,
) : SecureTool {
    override val description: String = "test tool"
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.Success("ok")
    override fun confirmationMessage(args: JsonObject): String = confirmation ?: description
}
