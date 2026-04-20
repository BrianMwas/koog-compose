package io.github.koogcompose.session

import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.event.EventHandlers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Unified DSL builder for [PhaseSession].
 *
 * Instead of manually constructing:
 * ```kotlin
 * val session = PhaseSession(
 *     context = context,
 *     executor = executor,
 *     sessionId = "my_session",
 *     scope = viewModelScope,
 * )
 * ```
 *
 * Use the DSL for consistency with koogCompose:
 * ```kotlin
 * val session = phaseSession(context, executor) {
 *     sessionId = "my_session"
 *     scope = viewModelScope
 *     store = RedisSessionStore()
 * }
 * ```
 *
 * Or even simpler, if you have a [KoogDefinition]:
 * ```kotlin
 * val definition = koogCompose<AppState> { ... }
 * val session = definition.createPhaseSession(executor, viewModelScope) {
 *     sessionId = "my_session"
 * }
 * ```
 */
public class PhaseSessionBuilder<S>(
    private val context: KoogComposeContext<S>,
    private val executor: PromptExecutor,
) {
    public var sessionId: String = "default"
    public var scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    public var store: SessionStore = InMemorySessionStore()
    public var strategyName: String = "koog-compose-phases"
    public var eventHandlers: EventHandlers = EventHandlers.Empty

    public fun build(): PhaseSession<S> = PhaseSession(
        context = context,
        executor = executor,
        sessionId = sessionId,
        store = store,
        scope = scope,
        strategyName = strategyName,
        eventHandlers = eventHandlers,
    )
}

/**
 * Creates a [PhaseSession] with a DSL-friendly builder.
 *
 * ```kotlin
 * val session = phaseSession(context, executor) {
 *     sessionId = "my_run"
 *     scope = viewModelScope
 * }
 * ```
 *
 * This is the primary way to construct sessions in ViewModels or non-Compose code.
 */
public fun <S> phaseSession(
    context: KoogComposeContext<S>,
    executor: PromptExecutor,
    block: PhaseSessionBuilder<S>.() -> Unit = {}
): PhaseSession<S> = PhaseSessionBuilder(context, executor).apply(block).build()

/**
 * Extension on [KoogDefinition] to create a [PhaseSession] directly.
 *
 * Requires that the definition is a single-agent [KoogComposeContext].
 * For multi-agent [KoogSession], use [SessionRunner.createSession] instead.
 *
 * ```kotlin
 * val definition = koogCompose<AppState> {
 *     provider { anthropic(apiKey = "...") }
 *     phases { ... }
 * }
 *
 * val session = definition.createPhaseSession(executor, viewModelScope) {
 *     sessionId = "my_session"
 * }
 * ```
 */
public fun <S> KoogDefinition<S>.createPhaseSession(
    executor: PromptExecutor,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    block: PhaseSessionBuilder<S>.() -> Unit = {}
): PhaseSession<S> {
    val context = this as? KoogComposeContext<S>
        ?: throw IllegalArgumentException(
            "createPhaseSession is only available for single-agent definitions. " +
            "For multi-agent KoogSession, use SessionRunner instead."
        )
    return phaseSession(context, executor) {
        this.scope = scope
        block()
    }
}
