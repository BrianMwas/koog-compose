package io.github.koogcompose.event

class EventHandlers private constructor(
    private val handlers: List<suspend (KoogEvent) -> Unit>
) {
    suspend fun dispatch(event: KoogEvent) {
        handlers.forEach { handler ->
            try {
                handler(event)
            } catch (_: Throwable) {
                // Event observers should not be able to break the runtime.
            }
        }
    }

    class Builder {
        private val handlers = mutableListOf<suspend (KoogEvent) -> Unit>()

        fun onEvent(handler: suspend (KoogEvent) -> Unit) {
            handlers += handler
        }

        fun onTurnStarted(handler: suspend (KoogEvent.TurnStarted) -> Unit) {
            onTyped(handler)
        }

        fun onTurnCompleted(handler: suspend (KoogEvent.TurnCompleted) -> Unit) {
            onTyped(handler)
        }

        fun onTurnFailed(handler: suspend (KoogEvent.TurnFailed) -> Unit) {
            onTyped(handler)
        }

        fun onPhaseTransitioned(handler: suspend (KoogEvent.PhaseTransitioned) -> Unit) {
            onTyped(handler)
        }

        fun onToolCallRequested(handler: suspend (KoogEvent.ToolCallRequested) -> Unit) {
            onTyped(handler)
        }

        fun onToolConfirmationRequested(handler: suspend (KoogEvent.ToolConfirmationRequested) -> Unit) {
            onTyped(handler)
        }

        fun onToolExecutionCompleted(handler: suspend (KoogEvent.ToolExecutionCompleted) -> Unit) {
            onTyped(handler)
        }

        fun onProviderChunkReceived(handler: suspend (KoogEvent.ProviderChunkReceived) -> Unit) {
            onTyped(handler)
        }

        fun build(): EventHandlers = EventHandlers(handlers.toList())

        private inline fun <reified T : KoogEvent> onTyped(noinline handler: suspend (T) -> Unit) {
            handlers += { event ->
                if (event is T) {
                    handler(event)
                }
            }
        }
    }

    companion object {
        val Empty: EventHandlers = EventHandlers(emptyList())

        operator fun invoke(block: Builder.() -> Unit): EventHandlers =
            Builder().apply(block).build()
    }
}
