package io.github.koogcompose.layout

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

/** Stable identifier for a single directive emission, used for tracing and correlation. */
@JvmInline
public value class DirectiveId(public val value: String) {
    init { require(value.isNotBlank()) { "DirectiveId must not be blank" } }
}

/**
 * Insertion position within a multi-component slot.
 *
 * - [Start] / [End]: relative to current contents.
 * - [Index]: absolute zero-based index, clamped to slot size at reduction time.
 * - [Before] / [After]: relative to a reference component; falls back to [End] if absent.
 */
public sealed class Position {
    public data object Start : Position()
    public data object End : Position()
    public data class Index(public val index: Int) : Position() {
        init { require(index >= 0) { "Position.Index must be non-negative" } }
    }
    public data class Before(public val reference: ComponentId) : Position()
    public data class After(public val reference: ComponentId) : Position()
}

/** How a component is locked in place. Visible to the user, unlike permission denials. */
public enum class LockMode { ReadOnly, Disabled, Hidden }

/**
 * Sealed hierarchy of mutations the agent can propose to the layout.
 *
 * Each subtype is a declarative DELTA asserting desired end-state, not an imperative
 * command. The vocabulary is intentionally small (5 subtypes) for reliable emission
 * from on-device models. All directives are idempotent at the reducer level — replay
 * is safe.
 *
 * Every directive carries a [correlationId] so the agent can match its emissions to
 * the [DirectiveOutcome]s it receives in the next turn.
 */
public sealed class AgentLayoutDirective {
    public abstract val correlationId: DirectiveId
    public abstract val issuedAt: Instant
    public abstract val reason: String?

    /**
     * Asserts [componentId] should be visible in [slotId] at [position].
     *
     * Semantics: already in slot → reorder; in different slot → move; not shown → add.
     */
    public data class ShowComponent(
        public val componentId: ComponentId,
        public val slotId: SlotId,
        public val position: Position = Position.End,
        public val props: ComponentProps = ComponentProps.Empty,
        override val correlationId: DirectiveId,
        override val issuedAt: Instant = Clock.System.now(),
        override val reason: String? = null,
    ) : AgentLayoutDirective()

    /**
     * Asserts [componentId] should not be visible.
     *
     * If [slotId] is non-null, removes from that slot only. If null, removes from all slots.
     */
    public data class HideComponent(
        public val componentId: ComponentId,
        public val slotId: SlotId? = null,
        override val correlationId: DirectiveId,
        override val issuedAt: Instant = Clock.System.now(),
        override val reason: String? = null,
    ) : AgentLayoutDirective()

    /**
     * Asserts an explicit ordering for components within [slotId].
     *
     * Components in [orderedComponentIds] not currently in the slot are ignored (this
     * directive does not add). Components currently in the slot but absent from
     * [orderedComponentIds] keep their relative order, appended after the explicit set.
     */
    public data class ReorderComponents(
        public val slotId: SlotId,
        public val orderedComponentIds: List<ComponentId>,
        override val correlationId: DirectiveId,
        override val issuedAt: Instant = Clock.System.now(),
        override val reason: String? = null,
    ) : AgentLayoutDirective()

    /**
     * Atomically replaces [removeComponentId] with [insertComponentId] in [slotId].
     *
     * Rejected (not silently converted) if [removeComponentId] is not in the slot,
     * because the agent's intent was specifically a swap.
     */
    public data class SwapComponent(
        public val slotId: SlotId,
        public val removeComponentId: ComponentId,
        public val insertComponentId: ComponentId,
        public val props: ComponentProps = ComponentProps.Empty,
        override val correlationId: DirectiveId,
        override val issuedAt: Instant = Clock.System.now(),
        override val reason: String? = null,
    ) : AgentLayoutDirective()

    /**
     * Applies [lockMode] to [componentId] in [slotId].
     *
     * Policy can strengthen a lock but never weaken it. Rejected if the component is
     * not currently in the slot.
     */
    public data class LockComponent(
        public val componentId: ComponentId,
        public val slotId: SlotId,
        public val lockMode: LockMode,
        override val correlationId: DirectiveId,
        override val issuedAt: Instant = Clock.System.now(),
        override val reason: String? = null,
    ) : AgentLayoutDirective()
}

/** Where in the system a state change originated. */
public enum class DirectiveSource { Agent, Policy, UserAction, Default, Restore }

/**
 * Result of a directive's trip through the pipeline. Published to the outcomes flow
 * the agent reads in subsequent turns. The Compose UI only consumes [LayoutState].
 */
public sealed class DirectiveOutcome {
    public abstract val correlationId: DirectiveId
    public abstract val resultingStateVersion: Long?

    /** Directive applied as-is. [directiveReason] mirrors the original directive's reason field. */
    public data class Accepted(
        override val correlationId: DirectiveId,
        override val resultingStateVersion: Long,
        public val directiveReason: String? = null,
    ) : DirectiveOutcome()

    /**
     * Directive was modified before application. Common rewrites: evict-then-show on
     * Single slots; agent-issued ReadOnly lock upgraded by policy to Hidden.
     */
    public data class Rewritten(
        override val correlationId: DirectiveId,
        public val original: AgentLayoutDirective,
        public val final: AgentLayoutDirective,
        public val reason: String,
        override val resultingStateVersion: Long,
    ) : DirectiveOutcome()

    /** Directive dropped entirely. State is unchanged. */
    public data class Rejected(
        override val correlationId: DirectiveId,
        public val reason: String,
        public val rejectedAt: PipelineStage,
    ) : DirectiveOutcome() {
        override val resultingStateVersion: Long? get() = null
    }

    /** Directive deduped against another in-flight directive in the coalescing window. */
    public data class Coalesced(
        override val correlationId: DirectiveId,
        public val coalescedWith: DirectiveId,
    ) : DirectiveOutcome() {
        override val resultingStateVersion: Long? get() = null
    }
}

/** Stage at which a directive was rejected. */
public enum class PipelineStage {
    SchemaValidation,
    PolicyCheck,
    SlotConstraintCheck,
    Reduce,
}
