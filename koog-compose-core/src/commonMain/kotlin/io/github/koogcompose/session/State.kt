package io.github.koogcompose.session


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe observable state store for a koog-compose agent session.
 *
 * Passed into [StatefulTool] implementations so tools can read and update
 * shared app state. The agent and UI can observe changes via [stateFlow].
 *
 * All updates are serialized through a [Mutex] to prevent lost writes
 * when parallel branches call [update] concurrently.
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

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)

    /** Observable stream of state — collect in Compose UI. */
    public val stateFlow: StateFlow<S> = _state.asStateFlow()

    /** Current snapshot of state. */
    public val current: S get() = _state.value

    /**
     * Atomically update state through a mutex.
     *
     * This is `suspend` to ensure exclusive access — two parallel branches
     * calling [update] concurrently will never read the same stale value.
     */
    public suspend fun update(transform: (S) -> S): Unit {
        mutex.withLock {
            _state.value = transform(_state.value)
        }
    }

    /** Replace state entirely (also mutex-protected). */
    public suspend fun set(newState: S): Unit {
        mutex.withLock {
            _state.value = newState
        }
    }
}
