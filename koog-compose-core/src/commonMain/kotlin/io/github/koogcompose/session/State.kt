package io.github.koogcompose.session


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe observable state store for a koog-compose agent session.
 *
 * Passed into [StatefulTool] implementations so tools can read and update
 * shared app state. The agent and UI can observe changes via [stateFlow].
 *
 * ```kotlin
 * val store = KoogStateStore(AppState(userId = "brian"))
 *
 * // In a tool:
 * store.update { it.copy(balance = 5000) }
 *
 * // In UI:
 * val state by store.stateFlow.collectAsState()
 * ```
 */
public class KoogStateStore<S>(initialState: S) {

    private val _state = MutableStateFlow(initialState)

    /** Observable stream of state — collect in Compose UI. */
    public val stateFlow: StateFlow<S> = _state.asStateFlow()

    /** Current snapshot of state. */
    public val current: S get() = _state.value

    /** Atomically update state. */
    public fun update(transform: (S) -> S): Unit {
        _state.value = transform(_state.value)
    }

    /** Replace state entirely. */
    public fun set(newState: S): Unit {
        _state.value = newState
    }
}
