package io.github.koogcompose.session

import kotlinx.serialization.Serializable

/**
 * Pluggable persistence layer for [PhaseSession].
 *
 * A session stores everything needed to resume an agent conversation exactly
 * where it left off — full LLM message history, current phase, and any
 * app-level context variables written during the session.
 *
 * koog-compose currently ships two maintained implementations:
 *  - [InMemorySessionStore]  — default, no setup required, lost on process death
 *  - [RoomSessionStore] — for persistent on-device storage
 *
 * For server-side or multi-device sync, provide a custom [SessionStore]
 * implementation backed by your own service or database.
 *
 * Custom implementation:
 * ```kotlin
 * class MyEncryptedSessionStore : SessionStore {
 *     override suspend fun load(sessionId: String): AgentSession? { ... }
 *     override suspend fun save(sessionId: String, session: AgentSession) { ... }
 *     override suspend fun delete(sessionId: String) { ... }
 *     override suspend fun exists(sessionId: String): Boolean { ... }
 * }
 * ```
 *
 * ## Schema Migration Warning
 *
 * The [AgentSession.serializedState] field stores your app state `S` as a raw
 * JSON string. If your app state is a `@Serializable` data class and you add,
 * remove, or rename fields between app versions, **deserialization will crash**
 * on existing sessions unless you handle migration.
 *
 * Mitigation options:
 * 1. Provide a [StateMigration] when constructing your session store to upgrade
 *    old JSON payloads to the new schema.
 * 2. Use `Json { ignoreUnknownKeys = true; coerceInputValues = true }` in your
 *    app's deserialization logic to tolerate minor schema drift.
 * 3. Version your app state explicitly — the [StateMigration] interface includes
 *    a [StateMigration.schemaVersion] so you can route old payloads through
 *    upgrade functions.
 */
public interface SessionStore {
    public suspend fun load(sessionId: String): AgentSession?
    public suspend fun save(sessionId: String, session: AgentSession)
    public suspend fun delete(sessionId: String)
    public suspend fun exists(sessionId: String): Boolean
}

/**
 * Handles schema migrations for serialized app state stored in [AgentSession.serializedState].
 *
 * When your app state evolves (new fields, renamed properties, removed fields),
 * old persisted sessions will fail to deserialize against the new schema.
 * Implement this interface to define upgrade paths between schema versions.
 *
 * Example:
 * ```kotlin
 * val migration = object : StateMigration<AppState> {
 *     override val schemaVersion: Int = 2
 *
 *     override suspend fun migrate(json: JsonObject, fromVersion: Int): JsonObject {
 *         return when (fromVersion) {
 *             0, 1 -> json + ("newField" to JsonPrimitive("default"))
 *             else -> json // unknown version, return as-is and let lenient parser try
 *         }
 *     }
 *
 *     override fun decodeMigrated(json: JsonObject): AppState {
 *         return Json.decodeFromJsonElement(serializer(), json)
 *     }
 * }
 * ```
 *
 * @param S The app state type.
 */
public interface StateMigration<S> {
    /**
     * The current schema version. Increment this whenever your app state
     * data class changes in a breaking way (added/removed/renamed fields).
     */
    public val schemaVersion: Int

    /**
     * Upgrade a raw JSON payload from [fromVersion] to [schemaVersion].
     *
     * @param json The stored JSON payload from an old session.
     * @param fromVersion The version the payload was written with (0 means
     *   the payload has no version stamp — it's from a pre-migration release).
     * @return The JSON payload upgraded to [schemaVersion].
     */
    public suspend fun migrate(json: kotlinx.serialization.json.JsonObject, fromVersion: Int): kotlinx.serialization.json.JsonObject

    /**
     * Decode a JSON object that has been migrated to the current schema version.
     *
     * Override this if you need custom deserialization logic after migration.
     * The default implementation uses `Json.decodeFromJsonElement` with
     * `ignoreUnknownKeys = true` and `coerceInputValues = true`.
     */
    public fun decodeMigrated(json: kotlinx.serialization.json.JsonObject): S

    public companion object {
        /**
         * A no-op migration that assumes the current schema is compatible
         * with stored data. Uses lenient deserialization to tolerate
         * unknown keys and missing values with defaults.
         */
        public fun <S : Any> lenient(
            serializer: kotlinx.serialization.KSerializer<S>,
            currentVersion: Int = 1
        ): StateMigration<S> = object : StateMigration<S> {
            private val lenientJson = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

            override val schemaVersion: Int = currentVersion

            override suspend fun migrate(
                json: kotlinx.serialization.json.JsonObject,
                fromVersion: Int
            ): kotlinx.serialization.json.JsonObject = json

            override fun decodeMigrated(json: kotlinx.serialization.json.JsonObject): S {
                return lenientJson.decodeFromJsonElement(serializer, json)
            }
        }
    }
}

/**
 * The full state required to resume an agent conversation.
 */
@Serializable
public data class AgentSession(
    val sessionId: String,
    val currentPhaseName: String,
    val messageHistory: List<SessionMessage>,
    /** JSON representation of the app state [S]. */
    val serializedState: String? = null,
    /**
     * Schema version of [serializedState]. Increment this when your app state
     * data class changes in a breaking way. Used by [StateMigration] to route
     * old payloads through upgrade functions.
     *
     * `0` means the payload was written before schema versioning was introduced.
     */
    val serializedStateVersion: Int = 0,
    /** App-level context variables (key-value pairs). */
    val contextVars: Map<String, String> = emptyMap(),
    /** Tool call frequency counts. Keyed by tool name, value is call count for this session. */
    val toolCallCounts: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A single message in the LLM history.
 *
 * Maps directly to Koog's internal message types so the full conversation
 * can be replayed into a new PromptExecutor session on resume.
 *
 * @param role "system" | "user" | "assistant" | "tool_result"
 * @param content The text content of the message.
 * @param toolName Non-null for tool_call and tool_result roles.
 * @param toolCallId Matches a tool_call to its tool_result.
 */
@Serializable
public data class SessionMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null
)
