package io.github.koogcompose.event

/**
 * Registry of coroutine-based event handlers declared through the koog-compose DSL.
 */
public class EventHandlers private constructor(
    private val handlers: List<suspend (KoogEvent) -> Unit>
) {
    /**
     * Dispatches [event] to every registered handler.
     */
    public suspend fun dispatch(event: KoogEvent): Unit {
        handlers.forEach { handler ->
            try {
                handler(event)
            } catch (_: Throwable) {
                // Event observers should not be able to break the runtime.
            }
        }
    }

    /**
     * Mutable builder used by `events { ... }`.
     */
    public class Builder {
        private val handlers = mutableListOf<suspend (KoogEvent) -> Unit>()

        public fun onEvent(handler: suspend (KoogEvent) -> Unit): Unit {
            handlers += handler
        }

        public fun onTurnStarted(handler: suspend (KoogEvent.TurnStarted) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onTurnCompleted(handler: suspend (KoogEvent.TurnCompleted) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onTurnFailed(handler: suspend (KoogEvent.TurnFailed) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onPhaseTransitioned(handler: suspend (KoogEvent.PhaseTransitioned) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onToolCallRequested(handler: suspend (KoogEvent.ToolCallRequested) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onToolConfirmationRequested(handler: suspend (KoogEvent.ToolConfirmationRequested) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onToolExecutionCompleted(handler: suspend (KoogEvent.ToolExecutionCompleted) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onAgentStuck(handler: suspend (KoogEvent.AgentStuck) -> Unit): Unit {
            onTyped(handler)
        }

        public fun onProviderChunkReceived(handler: suspend (KoogEvent.ProviderChunkReceived) -> Unit): Unit {
            onTyped(handler)
        }

        public fun build(): EventHandlers = EventHandlers(handlers.toList())

        private inline fun <reified T : KoogEvent> onTyped(noinline handler: suspend (T) -> Unit) {
            handlers += { event ->
                if (event is T) {
                    handler(event)
                }
            }
        }
    }

    public companion object {
        public val Empty: EventHandlers = EventHandlers(emptyList())

        public operator fun invoke(block: Builder.() -> Unit): EventHandlers =
            Builder().apply(block).build()
    }
}
