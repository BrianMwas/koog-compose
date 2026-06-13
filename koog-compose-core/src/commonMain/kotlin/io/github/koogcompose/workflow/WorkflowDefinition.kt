package io.github.koogcompose.workflow

import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.Position
import io.github.koogcompose.layout.SlotId
import io.github.koogcompose.layout.UserRole
import io.github.koogcompose.layout.WorkflowId
import io.github.koogcompose.layout.WorkflowPolicy
import kotlin.jvm.JvmInline

/**
 * The top-level declarative description of a workflow. Businesses configure this
 * via the Kotlin DSL ([io.github.koogcompose.workflow.workflow]) or via JSON pushed
 * from Layer 3 — both paths produce the same in-memory object through the same
 * builders, so JSON workflows get identical validation.
 *
 * Compiles down to:
 * - A [WorkflowPolicy] consumed by the Layer 1 policy chain
 * - A list of [WorkflowPhase]s, each with its own system prompt and transitions
 * - A map of [DefaultLayout]s — the initial UI before the agent has acted, keyed by role
 */
public data class WorkflowDefinition(
    val id: WorkflowId,
    val displayName: String,
    val description: String,
    val phases: List<WorkflowPhase>,
    val initialPhase: PhaseId,
    val defaultLayouts: Map<UserRoleId, DefaultLayout>,
    val policy: WorkflowPolicy,
    val triggers: List<TriggerDefinition>,
) {
    init {
        require(phases.isNotEmpty()) { "WorkflowDefinition must declare at least one phase" }
        require(phases.any { it.id == initialPhase }) {
            "initialPhase ${initialPhase.value} must reference a declared phase"
        }
        val ids = phases.map { it.id }
        require(ids.toSet().size == ids.size) { "Duplicate phase ids in workflow ${id.value}" }
    }

    public fun phase(id: PhaseId): WorkflowPhase =
        phases.firstOrNull { it.id == id }
            ?: error("No phase '${id.value}' in workflow '${this.id.value}'")
}

/** Stable identifier for a workflow phase. */
@JvmInline
public value class PhaseId(public val value: String) {
    init { require(value.isNotBlank()) { "PhaseId must not be blank" } }
}

/** Stable string handle for a [UserRole], used in the JSON wire format. */
@JvmInline
public value class UserRoleId(public val value: String) {
    init { require(value.isNotBlank()) { "UserRoleId must not be blank" } }
}

/**
 * A discrete stage within a workflow. The agent receives [systemPrompt] when the
 * workflow enters this phase. [permittedDirectiveTypes] narrows the directive
 * vocabulary available to on-device models, reducing off-task behaviour.
 */
public data class WorkflowPhase(
    val id: PhaseId,
    val displayName: String,
    val systemPrompt: String,
    val permittedDirectiveTypes: Set<DirectiveType> = DirectiveType.All,
    val transitions: List<PhaseTransition> = emptyList(),
) {
    init {
        require(systemPrompt.isNotBlank()) { "Phase ${id.value} must have a non-blank systemPrompt" }
        require(permittedDirectiveTypes.isNotEmpty()) {
            "Phase ${id.value} must permit at least one directive type"
        }
    }
}

/** Which directive subtypes an agent may emit during a phase. */
public enum class DirectiveType {
    Show, Hide, Reorder, Swap, Lock;

    public companion object {
        public val All: Set<DirectiveType> = values().toSet()
    }
}

/** A phase transition evaluated against incoming [WorkflowEvent]s. */
public data class PhaseTransition(
    val toPhase: PhaseId,
    val condition: TransitionCondition,
)

public sealed class TransitionCondition {
    /** Fires when a directive's reason field contains [reasonContains]. */
    public data class OnAgentReason(val reasonContains: String) : TransitionCondition()

    /** Fires when a host-emitted user action of [actionId] arrives. */
    public data class OnUserAction(val actionId: String) : TransitionCondition()

    /** Fires when data signal [signalKey] equals [equals]. */
    public data class OnDataChange(val signalKey: String, val equals: String) : TransitionCondition()

    /** Always fires immediately — used for unconditional sequencing. */
    public data object Always : TransitionCondition()
}

/**
 * The starting layout for a role before the agent has emitted anything. Each
 * [DefaultPlacement] is applied as a synthetic [io.github.koogcompose.layout.AgentLayoutDirective.ShowComponent]
 * through the standard reducer pipeline — default layouts can never violate policy.
 */
public data class DefaultLayout(val placements: List<DefaultPlacement>)

public data class DefaultPlacement(
    val componentId: ComponentId,
    val slotId: SlotId,
    val position: Position = Position.End,
    val props: ComponentProps = ComponentProps.Empty,
)

/**
 * A trigger declaration describing WHEN the agent should activate outside of direct
 * user prompts. The host app wires its event sources to call
 * [WorkflowRuntime.signal] — the runtime evaluates which triggers are active.
 */
public data class TriggerDefinition(
    val id: TriggerId,
    val displayName: String,
    val activeInPhases: Set<PhaseId>,
    val source: TriggerSource,
)

@JvmInline
public value class TriggerId(public val value: String) {
    init { require(value.isNotBlank()) { "TriggerId must not be blank" } }
}

public sealed class TriggerSource {
    /** Agent runs every [intervalSeconds] while in an active phase. */
    public data class Interval(val intervalSeconds: Int) : TriggerSource() {
        init { require(intervalSeconds > 0) { "intervalSeconds must be positive" } }
    }

    /** Host fires this when a named UI action occurs. */
    public data class UserAction(val actionId: String) : TriggerSource()

    /** Host fires this when a watched data signal changes. */
    public data class DataChange(val signalKey: String) : TriggerSource()
}
