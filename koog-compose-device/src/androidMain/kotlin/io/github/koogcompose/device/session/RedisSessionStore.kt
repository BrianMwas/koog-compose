package io.github.koogcompose.device.session


import io.github.koogcompose.session.AgentSession
import io.github.koogcompose.session.SessionStore
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Redis-backed [SessionStore] for koog-compose-device.
 *
 * Persists full LLM message history to Redis. Suitable for:
 *  - Server-assisted Android apps that need multi-device session continuity
 *  - Apps where the backend needs to inspect or replay conversation history
 *  - Long-running sessions (customer support, tutoring) that must survive reinstalls
 *
 * Sessions are stored as JSON strings under the key `koog:session:{sessionId}`.
 * TTL is optional — leave null for no expiry.
 *
 * Setup:
 * ```kotlin
 * // In your DI module (Hilt, Koin, etc.)
 * val redisStore = RedisSessionStore(
 *     host = "your-redis-host.upstash.io",
 *     port = 6379,
 *     password = BuildConfig.REDIS_PASSWORD,
 *     ttlSeconds = 60 * 60 * 24 * 7   // 7-day TTL
 * )
 *
 * val session = PhaseSession(
 *     context = context,
 *     executor = executor,
 *     sessionId = "user_${userId}",
 *     store = redisStore
 * )
 * ```
 *
 * Dependencies (add to koog-compose-device build.gradle.kts):
 * ```kotlin
 * implementation("redis.clients:jedis:5.1.0")
 * // or for Lettuce (coroutine-native):
 * // implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
 * ```
 *
 * NOTE: Network I/O is dispatched on [Dispatchers.IO]. Redis calls are
 * blocking (Jedis) — the dispatcher prevents them from blocking the main thread.
 *
 * Lifecycle: Call [close()] when your DI container is destroyed to release
 * the connection pool. No-op if the pool was never initialised.
 */
class RedisSessionStore(
    private val host: String,
    private val port: Int = 6379,
    private val password: String? = null,
    private val useSsl: Boolean = true,
    private val ttlSeconds: Long? = null,
    private val keyPrefix: String = "koog:session:"
) : SessionStore {

    private val json = Json { ignoreUnknownKeys = true }

    // Jedis pool — lazily initialised so the store can be created on the main thread
    private var _pool: redis.clients.jedis.JedisPool? = null
    private val pool: redis.clients.jedis.JedisPool
        get() = _pool ?: redis.clients.jedis.JedisPoolConfig().let { config ->
            config.maxTotal = 8
            config.maxIdle = 4
            val newPool = if (password != null) {
                redis.clients.jedis.JedisPool(config, host, port, 2000, password, useSsl)
            } else {
                redis.clients.jedis.JedisPool(config, host, port, 2000, null, 0, useSsl)
            }
            _pool = newPool
            newPool
        }

    private fun key(sessionId: String) = "$keyPrefix$sessionId"

    override suspend fun load(sessionId: String): AgentSession? =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                jedis.get(key(sessionId))
                    ?.let { json.decodeFromString<AgentSession>(it) }
            }
        }

    override suspend fun save(sessionId: String, session: AgentSession) =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            val serialized = json.encodeToString(
                session.copy(updatedAt = now)
            )
            pool.resource.use { jedis ->
                if (ttlSeconds != null) {
                    jedis.setex(key(sessionId), ttlSeconds, serialized)
                } else {
                    jedis.set(key(sessionId), serialized)
                }
            }
        }

    override suspend fun delete(sessionId: String) =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis -> jedis.del(key(sessionId)) }
            Unit
        }

    override suspend fun exists(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis -> jedis.exists(key(sessionId)) }
        }

    /**
     * Closes the Jedis connection pool. Call from your DI teardown or
     * when the app process is shutting down to release network resources.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun close() {
        _pool?.close()
        _pool = null
    }
}