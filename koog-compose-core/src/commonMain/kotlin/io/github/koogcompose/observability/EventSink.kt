package io.github.koogcompose.observability

/**
 * Pluggable observability sink. Implement this to route [AgentEvent]s
 * to Firebase, Datadog, a local database, or any custom backend.
 *
 * [emit] is called from a coroutine — implementations can suspend safely.
 */
public interface EventSink {
    public suspend fun emit(event: AgentEvent)
}

/**
 * Default sink — prints a structured summary to stdout.
 * Safe to use in development. Replace in production.
 */
public object PrintlnEventSink : EventSink {
    override suspend fun emit(event: AgentEvent) {
        println("[koog-compose] ${event::class.simpleName} | $event")
    }
}

/**
 * Discards all events. Use in production when you have no observability backend,
 * or in tests where you don't want console noise.
 */
public object NoOpEventSink : EventSink {
    override suspend fun emit(event: AgentEvent) = Unit
}
