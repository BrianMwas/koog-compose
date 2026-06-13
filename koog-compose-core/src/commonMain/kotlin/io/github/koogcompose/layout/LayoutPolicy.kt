package io.github.koogcompose.layout

/**
 * A single policy tier that can approve, rewrite, or reject a layout directive.
 *
 * Three tiers are assembled in priority order:
 *   SDK ([SdkLayoutPolicy]) → Host (registered at startup) → Workflow (optional per-workflow).
 * All tiers are chained via [LayoutPolicyChain].
 */
public interface LayoutPolicy {
    /**
     * Evaluate [directive] against the current [state] and [context].
     *
     * Return [PolicyDecision.Allow] to pass the directive unchanged, [PolicyDecision.Rewrite]
     * to substitute a modified directive, or [PolicyDecision.Deny] to reject it entirely.
     */
    public fun evaluate(
        directive: AgentLayoutDirective,
        state: LayoutState,
        context: WorkflowContext,
    ): PolicyDecision
}

/** The outcome of a single policy tier's evaluation. */
public sealed class PolicyDecision {
    public data object Allow : PolicyDecision()

    public data class Rewrite(
        val replacement: AgentLayoutDirective,
        val reason: String,
    ) : PolicyDecision()

    public data class Deny(
        val reason: String,
        val stage: PipelineStage = PipelineStage.PolicyCheck,
    ) : PolicyDecision()
}

/**
 * Chains multiple [LayoutPolicy] instances in order. First Deny short-circuits.
 * First Rewrite is applied and subsequent policies evaluate the rewritten directive.
 */
public class LayoutPolicyChain(
    internal val policies: List<LayoutPolicy>,
) : LayoutPolicy {

    override fun evaluate(
        directive: AgentLayoutDirective,
        state: LayoutState,
        context: WorkflowContext,
    ): PolicyDecision {
        var current: AgentLayoutDirective = directive
        var rewritten = false
        var rewriteReason = ""

        for (policy in policies) {
            when (val decision = policy.evaluate(current, state, context)) {
                is PolicyDecision.Allow  -> continue
                is PolicyDecision.Deny   -> return decision
                is PolicyDecision.Rewrite -> {
                    current = decision.replacement
                    rewritten = true
                    rewriteReason = decision.reason
                }
            }
        }

        return if (rewritten) PolicyDecision.Rewrite(current, rewriteReason)
        else PolicyDecision.Allow
    }

    public companion object {
        public val Empty: LayoutPolicyChain = LayoutPolicyChain(emptyList())
    }
}

/**
 * SDK-tier built-in policy. Enforces schema validity, tag compatibility,
 * permission requirements, and lock-strengthening rules.
 *
 * This is always the first policy in the chain assembled by
 * [DefaultLayoutDirectiveProcessor] — host and workflow policies run after.
 */
public class SdkLayoutPolicy(
    private val slotRegistry: SlotRegistry,
    private val componentRegistry: ComponentRegistry,
) : LayoutPolicy {

    override fun evaluate(
        directive: AgentLayoutDirective,
        state: LayoutState,
        context: WorkflowContext,
    ): PolicyDecision = when (directive) {
        is AgentLayoutDirective.ShowComponent     -> validateShow(directive, context)
        is AgentLayoutDirective.HideComponent     -> validateHide(directive)
        is AgentLayoutDirective.ReorderComponents -> validateSlot(directive.slotId)
        is AgentLayoutDirective.SwapComponent     -> validateSwap(directive, state, context)
        is AgentLayoutDirective.LockComponent     -> validateLock(directive, state)
    }

    private fun validateShow(
        d: AgentLayoutDirective.ShowComponent,
        ctx: WorkflowContext,
    ): PolicyDecision {
        val slot = slotRegistry[d.slotId]
            ?: return PolicyDecision.Deny(
                "Unknown slot '${d.slotId.value}'",
                PipelineStage.SchemaValidation,
            )
        val component = componentRegistry[d.componentId]
            ?: return PolicyDecision.Deny(
                "Unknown component '${d.componentId.value}'",
                PipelineStage.SchemaValidation,
            )
        if (component.tags.none { it in slot.allowedComponentTags }) {
            return PolicyDecision.Deny(
                "Component '${d.componentId.value}' has no tag matching slot '${d.slotId.value}'",
                PipelineStage.SlotConstraintCheck,
            )
        }
        if (!ctx.userRole.permissions.containsAll(component.requiredPermissions)) {
            return PolicyDecision.Deny(
                "Insufficient permissions to show '${d.componentId.value}'",
                PipelineStage.PolicyCheck,
            )
        }
        return PolicyDecision.Allow
    }

    private fun validateHide(d: AgentLayoutDirective.HideComponent): PolicyDecision {
        if (d.slotId != null && !slotRegistry.contains(d.slotId)) {
            return PolicyDecision.Deny(
                "Unknown slot '${d.slotId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        if (!componentRegistry.contains(d.componentId)) {
            return PolicyDecision.Deny(
                "Unknown component '${d.componentId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        return PolicyDecision.Allow
    }

    private fun validateSlot(slotId: SlotId): PolicyDecision {
        if (!slotRegistry.contains(slotId)) {
            return PolicyDecision.Deny(
                "Unknown slot '${slotId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        return PolicyDecision.Allow
    }

    private fun validateSwap(
        d: AgentLayoutDirective.SwapComponent,
        state: LayoutState,
        ctx: WorkflowContext,
    ): PolicyDecision {
        val slot = slotRegistry[d.slotId]
            ?: return PolicyDecision.Deny(
                "Unknown slot '${d.slotId.value}'",
                PipelineStage.SchemaValidation,
            )
        val insertComponent = componentRegistry[d.insertComponentId]
            ?: return PolicyDecision.Deny(
                "Unknown component '${d.insertComponentId.value}'",
                PipelineStage.SchemaValidation,
            )
        if (!componentRegistry.contains(d.removeComponentId)) {
            return PolicyDecision.Deny(
                "Unknown component '${d.removeComponentId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        if (state.entriesFor(d.slotId).none { it.componentId == d.removeComponentId }) {
            return PolicyDecision.Deny(
                "Component '${d.removeComponentId.value}' is not in slot '${d.slotId.value}'",
                PipelineStage.Reduce,
            )
        }
        if (insertComponent.tags.none { it in slot.allowedComponentTags }) {
            return PolicyDecision.Deny(
                "Component '${d.insertComponentId.value}' has no tag matching slot '${d.slotId.value}'",
                PipelineStage.SlotConstraintCheck,
            )
        }
        if (!ctx.userRole.permissions.containsAll(insertComponent.requiredPermissions)) {
            return PolicyDecision.Deny(
                "Insufficient permissions to show '${d.insertComponentId.value}'",
                PipelineStage.PolicyCheck,
            )
        }
        return PolicyDecision.Allow
    }

    private fun validateLock(
        d: AgentLayoutDirective.LockComponent,
        state: LayoutState,
    ): PolicyDecision {
        if (!slotRegistry.contains(d.slotId)) {
            return PolicyDecision.Deny(
                "Unknown slot '${d.slotId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        if (!componentRegistry.contains(d.componentId)) {
            return PolicyDecision.Deny(
                "Unknown component '${d.componentId.value}'",
                PipelineStage.SchemaValidation,
            )
        }
        val entry = state.entriesFor(d.slotId).find { it.componentId == d.componentId }
            ?: return PolicyDecision.Deny(
                "Component '${d.componentId.value}' is not in slot '${d.slotId.value}'",
                PipelineStage.Reduce,
            )
        val existing = entry.lockMode
        if (existing != null && !d.lockMode.isStrongerOrEqualTo(existing)) {
            return PolicyDecision.Deny(
                "Cannot weaken lock from $existing to ${d.lockMode} — policy can only strengthen locks",
                PipelineStage.PolicyCheck,
            )
        }
        return PolicyDecision.Allow
    }
}

private fun LockMode.isStrongerOrEqualTo(other: LockMode): Boolean = when (this) {
    LockMode.ReadOnly -> other == LockMode.ReadOnly
    LockMode.Disabled -> other == LockMode.ReadOnly || other == LockMode.Disabled
    LockMode.Hidden   -> true
}
