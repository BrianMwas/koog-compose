package io.github.koogcompose.workflow

import io.github.koogcompose.layout.AgentLayoutDirective
import io.github.koogcompose.layout.DirectiveId
import io.github.koogcompose.layout.DirectiveOutcome
import io.github.koogcompose.layout.LayoutDirectiveProcessor
import io.github.koogcompose.layout.UserRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Phase state machine that ties a [WorkflowDefinition] to a running
 * [LayoutDirectiveProcessor].
 *
 * Responsibilities:
 * - Apply the role's [DefaultLayout] at session start as synthetic [AgentLayoutDirective.ShowComponent]
 *   directives, flowing through the same reducer pipeline so policy is enforced.
 * - Expose [currentPhase] and [currentPhaseSystemPrompt] as [StateFlow]s so the agent
 *   loop and host UI react to phase changes.
 * - Evaluate phase transitions when external events arrive via [signal], or when a
 *   directive's reason field matches an [TransitionCondition.OnAgentReason] condition.
 *
 * Thread-safety: all phase mutations are launched on [scope]; [StateFlow] consumers
 * need no additional coordination.
 */
public class WorkflowRuntime(
    public val definition: WorkflowDefinition,
    public val role: UserRole,
    private val processor: LayoutDirectiveProcessor,
    private val scope: CoroutineScope,
) {
    private val _currentPhase = MutableStateFlow(definition.initialPhase)
    public val currentPhase: StateFlow<PhaseId> = _currentPhase.asStateFlow()

    private val _currentPhaseSystemPrompt = MutableStateFlow(
        definition.phase(definition.initialPhase).systemPrompt
    )

    /**
     * System prompt for the current phase. The agent loop reads this before each turn
     * and uses it as the agent's system prompt. Updates reactively on phase transitions.
     */
    public val currentPhaseSystemPrompt: StateFlow<String> =
        _currentPhaseSystemPrompt.asStateFlow()

    private var outcomeJob: Job? = null

    /**
     * Applies the role's default layout as synthetic directives and begins observing
     * directive outcomes for [TransitionCondition.OnAgentReason] transitions.
     */
    public suspend fun start() {
        val layout = definition.defaultLayouts[UserRoleId(role.id)]
        layout?.placements?.forEach { placement ->
            processor.send(
                AgentLayoutDirective.ShowComponent(
                    componentId   = placement.componentId,
                    slotId        = placement.slotId,
                    position      = placement.position,
                    props         = placement.props,
                    correlationId = DirectiveId("default-${placement.componentId.value}-${placement.slotId.value}"),
                    issuedAt      = Clock.System.now(),
                    reason        = "default_layout",
                )
            )
        }
        observeAgentReasons()
    }

    /**
     * Signals an external event. Transitions in the current phase's declaration order
     * are evaluated; the first matching one fires. Unmatched events are silently ignored.
     */
    public fun signal(event: WorkflowEvent) {
        scope.launch { evaluateTransitionsFor(event) }
    }

    /**
     * Returns true when [type] is permitted in the current phase. The agent tool uses
     * this to pre-validate directives before submitting them to the processor.
     */
    public fun isDirectivePermittedNow(type: DirectiveType): Boolean =
        type in definition.phase(_currentPhase.value).permittedDirectiveTypes

    public fun close() {
        outcomeJob?.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun observeAgentReasons() {
        outcomeJob?.cancel()
        outcomeJob = scope.launch {
            processor.outcomes.collect { outcome ->
                val reason = reasonFrom(outcome) ?: return@collect
                evaluateTransitionsFor(WorkflowEvent.AgentReason(reason))
            }
        }
    }

    private fun reasonFrom(outcome: DirectiveOutcome): String? = when (outcome) {
        is DirectiveOutcome.Accepted  -> outcome.directiveReason
        is DirectiveOutcome.Rewritten -> outcome.final.reason
        is DirectiveOutcome.Rejected  -> null
        is DirectiveOutcome.Coalesced -> null
    }

    private fun evaluateTransitionsFor(event: WorkflowEvent) {
        val phase = definition.phase(_currentPhase.value)
        for (transition in phase.transitions) {
            if (matches(transition.condition, event)) {
                transitionTo(transition.toPhase)
                return
            }
        }
    }

    private fun matches(condition: TransitionCondition, event: WorkflowEvent): Boolean =
        when (condition) {
            is TransitionCondition.OnAgentReason ->
                event is WorkflowEvent.AgentReason && condition.reasonContains in event.reason
            is TransitionCondition.OnUserAction  ->
                event is WorkflowEvent.UserAction && event.actionId == condition.actionId
            is TransitionCondition.OnDataChange  ->
                event is WorkflowEvent.DataChange &&
                    event.signalKey == condition.signalKey &&
                    event.value == condition.equals
            TransitionCondition.Always           -> true
        }

    private fun transitionTo(next: PhaseId) {
        val phase = definition.phase(next)
        _currentPhase.value = next
        _currentPhaseSystemPrompt.value = phase.systemPrompt
    }
}

/** An external event that may trigger a phase transition. */
public sealed class WorkflowEvent {
    public data class UserAction(val actionId: String) : WorkflowEvent()
    public data class DataChange(val signalKey: String, val value: String) : WorkflowEvent()

    /** Internal: emitted by the runtime when a directive's reason field is non-null. */
    internal data class AgentReason(val reason: String) : WorkflowEvent()
}
