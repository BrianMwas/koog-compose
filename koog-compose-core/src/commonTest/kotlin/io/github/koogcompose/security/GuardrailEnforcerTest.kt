package io.github.koogcompose.security

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GuardrailEnforcerTest {

    // ── Rate limiting ────────────────────────────────────────────────────────

    @Test
    fun underRateLimit_isAllowed() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(toolRateLimits = mapOf("FetchTool" to Guardrails.RateLimit(max = 2, window = 1.hours))),
            AuditLogger(),
        )

        assertNull(enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1"))
        assertNull(enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1"))
    }

    @Test
    fun exceedingRateLimit_isDenied() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(toolRateLimits = mapOf("FetchTool" to Guardrails.RateLimit(max = 1, window = 1.hours))),
            AuditLogger(),
        )

        assertNull(enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1"))
        val denied = enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1")

        assertNotNull(denied)
        assertTrue(denied.reason.contains("Rate limit"))
    }

    @Test
    fun rateLimitDenial_isAudited() = runTest {
        val audit = AuditLogger()
        val enforcer = GuardrailEnforcer(
            Guardrails(toolRateLimits = mapOf("FetchTool" to Guardrails.RateLimit(max = 1, window = 1.hours))),
            audit,
        )

        enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1")
        enforcer.validate("FetchTool", EMPTY_ARGS, userId = "u1")

        assertEquals(1, audit.deniedCount)
    }

    @Test
    fun toolWithoutRateLimit_isAlwaysAllowed() = runTest {
        val enforcer = GuardrailEnforcer(Guardrails(), AuditLogger())

        repeat(10) { assertNull(enforcer.validate("AnyTool", EMPTY_ARGS, userId = "u1")) }
    }

    // ── WorkManager tag allowlist ─────────────────────────────────────────────

    @Test
    fun workManagerTool_disallowedTag_isDenied() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedWorkTags = setOf("sync")),
            AuditLogger(),
        )

        val denied = enforcer.validate(
            "WorkManagerScheduleTool",
            buildJsonObject { put("tag", "evil") },
            userId = "u1",
        )

        assertNotNull(denied)
        assertTrue(denied.reason.contains("not in the allowlist"))
    }

    @Test
    fun workManagerTool_allowedTag_isAllowed() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedWorkTags = setOf("sync")),
            AuditLogger(),
        )

        assertNull(
            enforcer.validate("WorkManagerScheduleTool", buildJsonObject { put("tag", "sync") }, userId = "u1")
        )
    }

    @Test
    fun workManagerTool_emptyAllowlist_skipsTagCheck() = runTest {
        val enforcer = GuardrailEnforcer(Guardrails(), AuditLogger())

        assertNull(
            enforcer.validate("WorkManagerScheduleTool", buildJsonObject { put("tag", "anything") }, userId = "u1")
        )
    }

    @Test
    fun maxScheduledJobsReached_isDenied() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedWorkTags = setOf("sync"), maxScheduledJobs = 1),
            AuditLogger(),
        )

        enforcer.notifyJobStarted()
        val denied = enforcer.validate(
            "WorkManagerScheduleTool",
            buildJsonObject { put("tag", "sync") },
            userId = "u1",
        )

        assertNotNull(denied)
        assertTrue(denied.reason.contains("Maximum background jobs"))
    }

    @Test
    fun notifyJobFinished_freesUpCapacity() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedWorkTags = setOf("sync"), maxScheduledJobs = 1),
            AuditLogger(),
        )

        enforcer.notifyJobStarted()
        enforcer.notifyJobFinished()

        assertNull(
            enforcer.validate("WorkManagerScheduleTool", buildJsonObject { put("tag", "sync") }, userId = "u1")
        )
    }

    // ── Intent action allowlist ───────────────────────────────────────────────

    @Test
    fun intentTool_disallowedAction_isDenied() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedIntentActions = setOf("android.intent.action.VIEW")),
            AuditLogger(),
        )

        val denied = enforcer.validate(
            "SendIntentTool",
            buildJsonObject { put("action", "android.intent.action.CALL") },
            userId = "u1",
        )

        assertNotNull(denied)
        assertTrue(denied.reason.contains("not in the allowlist"))
    }

    @Test
    fun intentTool_allowedAction_isAllowed() = runTest {
        val enforcer = GuardrailEnforcer(
            Guardrails(allowedIntentActions = setOf("android.intent.action.VIEW")),
            AuditLogger(),
        )

        assertNull(
            enforcer.validate(
                "SendIntentTool",
                buildJsonObject { put("action", "android.intent.action.VIEW") },
                userId = "u1",
            )
        )
    }

    // ── Confirmation hook ─────────────────────────────────────────────────────

    @Test
    fun requestConfirmation_defaultsToDenied() = runTest {
        val enforcer = GuardrailEnforcer(Guardrails(), AuditLogger())

        assertEquals(false, enforcer.requestConfirmation(FakeTool, EMPTY_ARGS))
    }

    @Test
    fun requestConfirmation_usesInstalledHandler() = runTest {
        val enforcer = GuardrailEnforcer(Guardrails(), AuditLogger())
        enforcer.onConfirmationRequired = { _, _ -> true }

        assertEquals(true, enforcer.requestConfirmation(FakeTool, EMPTY_ARGS))
    }
}

private val EMPTY_ARGS = JsonObject(emptyMap())

private object FakeTool : SecureTool {
    override val name: String = "FakeTool"
    override val description: String = "fake"
    override val permissionLevel: PermissionLevel = PermissionLevel.SENSITIVE
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.Success("ok")
}
