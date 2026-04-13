package io.github.koogcompose.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KoogStateStoreTest {

    data class CounterState(val counter: Int = 0)

    @Test
    fun `concurrent branch updates do not lose writes`() = runTest {
        val store = KoogStateStore(CounterState(counter = 0))

        (1..100).map {
            async { store.update { it.copy(counter = it.counter + 1) } }
        }.awaitAll()

        assertEquals(100, store.stateFlow.value.counter)
    }

    @Test
    fun `concurrent set and update are serialized`() = runTest {
        val store = KoogStateStore(CounterState(counter = 0))

        // Fire 50 concurrent increments and 10 concurrent sets
        val updates = (1..50).map {
            async { store.update { it.copy(counter = it.counter + 1) } }
        }
        val sets = (1..10).map {
            async { store.set(CounterState(counter = -1)) }
        }

        (updates + sets).awaitAll()

        // Final value should be either -1 (from a set) or some positive number
        // from increments — never a corrupted partial value
        val final = store.stateFlow.value.counter
        assert(final == -1 || final > 0) { "Expected -1 or >0, got $final" }
    }

    @Test
    fun `update reads latest value inside mutex`() = runTest {
        val store = KoogStateStore(CounterState(counter = 0))

        // Each update reads current counter and increments by 1
        // With proper mutex, no two updates can read the same value
        repeat(10) {
            store.update { it.copy(counter = it.counter + 1) }
        }

        assertEquals(10, store.stateFlow.value.counter)
    }
}
