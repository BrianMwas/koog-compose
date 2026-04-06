package io.github.koogcompose.session

import kotlin.time.Clock

/**
 * Default in-memory [SessionStore]. No setup required.
 *
 * Sessions live as long as the process is alive. Suitable for:
 *  - Development and testing
 *  - Single-session UIs where history loss on app restart is acceptable
 *  - Short-lived assistant flows (e.g. a checkout wizard)
 *
 * For persistence across app restarts, use
 * [io.github.koogcompose.session.room.RoomSessionStore] from `koog-compose-session-room`.
 * For multi-device sync, provide a custom [SessionStore] implementation.
 *
 * Usage (this is the default — you don't need to specify it):
 * ```kotlin
 * val session = PhaseSession(
 *     context = context,
 *     executor = executor,
 *     sessionId = "user_brian",
 *     store = InMemorySessionStore()   // optional, this is the default
 * )
 * ```
 */
public class InMemorySessionStore : SessionStore {

    private val store: MutableMap<String, AgentSession> = mutableMapOf()

    override suspend fun load(sessionId: String): AgentSession? =
        store[sessionId]

    override suspend fun save(sessionId: String, session: AgentSession): Unit {
        store[sessionId] = session.copy(updatedAt = Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun delete(sessionId: String): Unit {
        store.remove(sessionId)
    }

    override suspend fun exists(sessionId: String): Boolean =
        store.containsKey(sessionId)

    /** Returns the number of sessions currently held in memory. */
    public val size: Int
        get() = store.size

    /** Clears all sessions. Useful in tests. */
    public fun clear(): Unit = store.clear()
}
