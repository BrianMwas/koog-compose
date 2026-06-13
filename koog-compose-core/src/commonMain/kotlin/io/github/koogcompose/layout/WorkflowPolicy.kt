package io.github.koogcompose.layout

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A predicate that must hold after every successful directive application. Functions
 * cannot be serialized to JSON, so invariants are a code-only concept — OTA workflows
 * silently receive no invariants.
 */
public fun interface LayoutInvariant {
    public fun check(state: LayoutState): Boolean
}

/**
 * Coarse access restriction for an entire slot, used by workflow-tier policy to
 * narrow what was granted at the host tier. Policy can only restrict, never widen.
 */
public enum class SlotAccess {
    /** Slot is visible and interactive. Default when no override is set. */
    Normal,
    /** Slot is visible but not interactive (same semantics as [LockMode.ReadOnly]). */
    ReadOnly,
    /** Slot contents are shown but greyed out (same as [LockMode.Disabled]). */
    Disabled,
    /** Slot is completely invisible to this role (same as [LockMode.Hidden]). */
    Hidden,
}

/**
 * Per-session rate limit for agent-emitted directives.
 *
 * [perTurn] caps how many directives a single agent turn may emit. [perSession]
 * caps the total across the session lifetime. [coalescingWindow] is the window
 * within which duplicate [DirectiveId]s are deduplicated.
 */
public data class RateLimit(
    val perTurn: Int = 20,
    val perSession: Int = 500,
    val coalescingWindow: Duration = 16.milliseconds,
) {
    init {
        require(perTurn > 0) { "RateLimit.perTurn must be positive" }
        require(perSession > 0) { "RateLimit.perSession must be positive" }
    }

    public companion object {
        public val Default: RateLimit = RateLimit()
    }
}

/**
 * Workflow-tier policy snapshot. Built by [io.github.koogcompose.workflow.WorkflowPolicyBuilder]
 * and consumed by the policy chain. Can only narrow what host policy granted.
 *
 * [invariants] are omitted when the workflow is loaded from JSON — they are
 * code-only constructs that cannot be expressed in a wire format.
 */
public data class WorkflowPolicy(
    val slotAccessOverrides: Map<UserRole, Map<SlotId, SlotAccess>> = emptyMap(),
    val componentRoleOverrides: Map<ComponentId, Set<UserRole>> = emptyMap(),
    val invariants: List<LayoutInvariant> = emptyList(),
    val rateLimit: RateLimit = RateLimit.Default,
) {
    public companion object {
        public val Empty: WorkflowPolicy = WorkflowPolicy()
    }
}
