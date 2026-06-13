package io.github.koogcompose.workflow

import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.LayoutInvariant
import io.github.koogcompose.layout.Position
import io.github.koogcompose.layout.RateLimit
import io.github.koogcompose.layout.SlotAccess
import io.github.koogcompose.layout.SlotId
import io.github.koogcompose.layout.UserRole
import io.github.koogcompose.layout.WorkflowId
import io.github.koogcompose.layout.WorkflowPolicy

/**
 * Top-level entry point for declaring a workflow in Kotlin.
 *
 * ```kotlin
 * val inventoryWorkflow = workflow(WorkflowId("daily_inventory")) {
 *     displayName = "Daily Inventory Check"
 *     description  = "Field agent walks SKUs; manager reviews on completion."
 *
 *     phase(PhaseId("intake")) {
 *         displayName  = "Intake"
 *         systemPrompt = "Show the inventory summary and the scan button."
 *         permittedDirectives(DirectiveType.Show, DirectiveType.Hide)
 *         transition(toPhase = PhaseId("scanning"), on = TransitionCondition.OnUserAction("scan_started"))
 *     }
 *
 *     initialPhase = PhaseId("intake")
 *
 *     defaultLayout(role = FieldAgent) {
 *         place(ComponentId("inventory_summary"), in_ = SlotId("primary"))
 *     }
 *
 *     policy {
 *         narrowSlotAccess(FieldAgent, SlotId("admin_panel"), SlotAccess.Hidden)
 *     }
 * }
 * ```
 */
public fun workflow(id: WorkflowId, block: WorkflowBuilder.() -> Unit): WorkflowDefinition =
    WorkflowBuilder(id).apply(block).build()

@DslMarker
public annotation class WorkflowDsl

@WorkflowDsl
public class WorkflowBuilder internal constructor(private val id: WorkflowId) {
    public var displayName: String = ""
    public var description: String = ""
    public var initialPhase: PhaseId? = null

    private val phases = mutableListOf<WorkflowPhase>()
    private val defaultLayouts = mutableMapOf<UserRoleId, DefaultLayout>()
    private val triggers = mutableListOf<TriggerDefinition>()
    private val policyBuilder = WorkflowPolicyBuilder()

    public fun phase(id: PhaseId, block: PhaseBuilder.() -> Unit) {
        phases += PhaseBuilder(id).apply(block).build()
    }

    public fun defaultLayout(role: UserRole, block: DefaultLayoutBuilder.() -> Unit) {
        defaultLayouts[UserRoleId(role.id)] = DefaultLayoutBuilder().apply(block).build()
    }

    public fun trigger(id: TriggerId, block: TriggerBuilder.() -> Unit) {
        triggers += TriggerBuilder(id).apply(block).build()
    }

    public fun policy(block: WorkflowPolicyBuilder.() -> Unit) {
        policyBuilder.apply(block)
    }

    internal fun build(): WorkflowDefinition {
        require(displayName.isNotBlank()) { "Workflow ${id.value} must have a displayName" }
        val initial = requireNotNull(initialPhase) { "Workflow ${id.value} must declare initialPhase" }
        return WorkflowDefinition(
            id             = id,
            displayName    = displayName,
            description    = description,
            phases         = phases.toList(),
            initialPhase   = initial,
            defaultLayouts = defaultLayouts.toMap(),
            policy         = policyBuilder.build(),
            triggers       = triggers.toList(),
        )
    }
}

@WorkflowDsl
public class PhaseBuilder internal constructor(private val id: PhaseId) {
    public var displayName: String = ""
    public var systemPrompt: String = ""

    private var permitted: Set<DirectiveType> = DirectiveType.All
    private val transitions = mutableListOf<PhaseTransition>()

    public fun permittedDirectives(vararg types: DirectiveType) {
        require(types.isNotEmpty()) { "Phase ${id.value}: permittedDirectives requires at least one type" }
        permitted = types.toSet()
    }

    public fun transition(toPhase: PhaseId, on: TransitionCondition) {
        transitions += PhaseTransition(toPhase, on)
    }

    internal fun build(): WorkflowPhase = WorkflowPhase(
        id                     = id,
        displayName            = displayName.ifBlank { id.value },
        systemPrompt           = systemPrompt,
        permittedDirectiveTypes = permitted,
        transitions            = transitions.toList(),
    )
}

@WorkflowDsl
public class DefaultLayoutBuilder internal constructor() {
    private val placements = mutableListOf<DefaultPlacement>()

    /**
     * Places [componentId] into slot [in_] at [position] with optional [props].
     * Trailing underscore because `in` is a Kotlin keyword.
     */
    public fun place(
        componentId: ComponentId,
        `in_`: SlotId,
        position: Position = Position.End,
        props: ComponentProps = ComponentProps.Empty,
    ) {
        placements += DefaultPlacement(componentId, `in_`, position, props)
    }

    internal fun build(): DefaultLayout = DefaultLayout(placements.toList())
}

@WorkflowDsl
public class TriggerBuilder internal constructor(private val id: TriggerId) {
    public var displayName: String = ""
    private var activeInPhases: Set<PhaseId> = emptySet()
    private var source: TriggerSource? = null

    public fun activeIn(vararg phases: PhaseId) { activeInPhases = phases.toSet() }
    public fun fromInterval(seconds: Int) { source = TriggerSource.Interval(seconds) }
    public fun fromUserAction(actionId: String) { source = TriggerSource.UserAction(actionId) }
    public fun fromDataChange(signalKey: String) { source = TriggerSource.DataChange(signalKey) }

    internal fun build(): TriggerDefinition {
        require(activeInPhases.isNotEmpty()) { "Trigger ${id.value} must declare at least one active phase" }
        return TriggerDefinition(
            id             = id,
            displayName    = displayName.ifBlank { id.value },
            activeInPhases = activeInPhases,
            source         = requireNotNull(source) { "Trigger ${id.value} must declare a source" },
        )
    }
}

@WorkflowDsl
public class WorkflowPolicyBuilder internal constructor() {
    private val slotOverrides = mutableMapOf<UserRole, MutableMap<SlotId, SlotAccess>>()
    private val componentOverrides = mutableMapOf<ComponentId, MutableSet<UserRole>>()
    private val invariants = mutableListOf<LayoutInvariant>()
    public var rateLimit: RateLimit = RateLimit.Default

    /** Narrows slot [slotId] access for [role]. Cannot widen what host policy granted. */
    public fun narrowSlotAccess(role: UserRole, slotId: SlotId, access: SlotAccess) {
        slotOverrides.getOrPut(role) { mutableMapOf() }[slotId] = access
    }

    /** Restricts [componentId] to [roles] for this workflow. Intersected with host-policy. */
    public fun restrictComponentToRoles(componentId: ComponentId, roles: Set<UserRole>) {
        require(roles.isNotEmpty()) { "Provide at least one role for component restriction" }
        componentOverrides[componentId] = roles.toMutableSet()
    }

    public fun invariant(inv: LayoutInvariant) { invariants += inv }

    internal fun build(): WorkflowPolicy = WorkflowPolicy(
        slotAccessOverrides     = slotOverrides.mapValues { it.value.toMap() },
        componentRoleOverrides  = componentOverrides.mapValues { it.value.toSet() },
        invariants              = invariants.toList(),
        rateLimit               = rateLimit,
    )
}
