package io.github.koogcompose.layout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Processes [AgentLayoutDirective]s in FIFO order and publishes [DirectiveOutcome]s.
 *
 * Callers emit directives via [send] (fire-and-forget) and observe live layout
 * state via [layoutState]. The agent reads back [outcomes] in subsequent turns
 * to learn whether its directives were accepted, rewritten, rejected, or coalesced.
 */
public interface LayoutDirectiveProcessor {
    /** Live, immediately consistent snapshot of the current layout. */
    public val layoutState: StateFlow<LayoutState>

    /** Hot stream of outcomes — one per processed directive. */
    public val outcomes: SharedFlow<DirectiveOutcome>

    /** Queue a directive for processing. Returns immediately (non-suspending). */
    public fun send(directive: AgentLayoutDirective)

    /**
     * Signal the start of a new agent turn. Clears in-flight coalescing state so
     * the same [DirectiveId] can be re-used across turns without being dropped.
     *
     * Called by [io.github.koogcompose.session.PhaseSession] at the top of every
     * [io.github.koogcompose.session.PhaseSession.send] invocation.
     */
    public fun beginTurn()
}

/**
 * Configuration bundle required to build a [DefaultLayoutDirectiveProcessor].
 *
 * Registered in [io.github.koogcompose.session.KoogComposeContext] at session
 * initialization and passed into the processor when the session scope is ready.
 */
public data class LayoutEngineConfig(
    val workflowContext: WorkflowContext,
    val slotRegistry: SlotRegistry,
    val componentRegistry: ComponentRegistry,
    /** Host / workflow policy tiers applied after the built-in [SdkLayoutPolicy]. */
    val policy: LayoutPolicy = LayoutPolicyChain.Empty,
)

/**
 * Single-threaded actor implementation of [LayoutDirectiveProcessor].
 *
 * All mutations — both [send] directives and [beginTurn] signals — funnel through
 * a single [Channel.UNLIMITED] channel consumed by one coroutine in [scope].
 * This guarantees that [LayoutState] and the deduplication set are mutated
 * sequentially without any external locks.
 *
 * Policy chain assembled at construction: [SdkLayoutPolicy] → host [LayoutEngineConfig.policy].
 */
public class DefaultLayoutDirectiveProcessor(
    private val config: LayoutEngineConfig,
    scope: CoroutineScope,
    initialState: LayoutState = LayoutState.Empty,
) : LayoutDirectiveProcessor {

    // ── Actor message types ────────────────────────────────────────────────

    private sealed interface ActorMessage
    private data class Emit(val directive: AgentLayoutDirective) : ActorMessage
    private data object BeginTurn : ActorMessage

    // ── Policy ─────────────────────────────────────────────────────────────

    private val sdkPolicy = SdkLayoutPolicy(config.slotRegistry, config.componentRegistry)
    private val effectivePolicy: LayoutPolicy = LayoutPolicyChain(
        buildList {
            add(sdkPolicy)
            if (config.policy != LayoutPolicyChain.Empty) add(config.policy)
        }
    )

    // ── Observable state ───────────────────────────────────────────────────

    private val _layoutState = MutableStateFlow(initialState)
    override val layoutState: StateFlow<LayoutState> = _layoutState.asStateFlow()

    private val _outcomes = MutableSharedFlow<DirectiveOutcome>(extraBufferCapacity = 64)
    override val outcomes: SharedFlow<DirectiveOutcome> = _outcomes.asSharedFlow()

    // ── Actor channel ──────────────────────────────────────────────────────

    private val channel = Channel<ActorMessage>(Channel.UNLIMITED)

    /** Correlation IDs seen in the current turn — reset by [BeginTurn] messages. */
    private val inFlightIds = mutableSetOf<DirectiveId>()

    init {
        scope.launch {
            for (message in channel) {
                when (message) {
                    is BeginTurn -> inFlightIds.clear()
                    is Emit      -> processOne(message.directive)
                }
            }
        }
    }

    override fun send(directive: AgentLayoutDirective) {
        channel.trySend(Emit(directive))
    }

    override fun beginTurn() {
        channel.trySend(BeginTurn)
    }

    private suspend fun processOne(directive: AgentLayoutDirective) {
        // Within-turn deduplication: same correlationId → Coalesced outcome.
        if (directive.correlationId in inFlightIds) {
            _outcomes.emit(
                DirectiveOutcome.Coalesced(
                    correlationId = directive.correlationId,
                    coalescedWith = directive.correlationId,
                )
            )
            return
        }
        inFlightIds += directive.correlationId

        val state = _layoutState.value
        when (val decision = effectivePolicy.evaluate(directive, state, config.workflowContext)) {
            is PolicyDecision.Allow -> {
                val newState = LayoutReducer.apply(state, directive, config.slotRegistry)
                _layoutState.value = newState
                _outcomes.emit(
                    DirectiveOutcome.Accepted(
                        correlationId = directive.correlationId,
                        resultingStateVersion = newState.version,
                    )
                )
            }

            is PolicyDecision.Rewrite -> {
                val finalDirective = decision.replacement
                val newState = LayoutReducer.apply(state, finalDirective, config.slotRegistry)
                _layoutState.value = newState
                _outcomes.emit(
                    DirectiveOutcome.Rewritten(
                        correlationId = directive.correlationId,
                        original = directive,
                        final = finalDirective,
                        reason = decision.reason,
                        resultingStateVersion = newState.version,
                    )
                )
            }

            is PolicyDecision.Deny -> {
                _outcomes.emit(
                    DirectiveOutcome.Rejected(
                        correlationId = directive.correlationId,
                        reason = decision.reason,
                        rejectedAt = decision.stage,
                    )
                )
            }
        }
    }
}
