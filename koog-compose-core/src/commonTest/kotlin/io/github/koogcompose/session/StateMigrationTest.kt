package io.github.koogcompose.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class StateMigrationTest {

    // ── Test fixtures ────────────────────────────────────────────────────────

    @Serializable
    data class AppStateV1(
        val userId: String,
        val intent: String? = null
    )

    @Serializable
    data class AppStateV2(
        val userId: String,
        val intent: String? = null,
        val themeMode: String = "System" // new field
    )

    @Serializable
    data class AppStateV3(
        val userId: String,
        val intent: String? = null,
        val themeMode: String = "System",
        val location: String? = null // another new field
    )

    private val strictJson = Json
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ── Lenient migration tests ──────────────────────────────────────────────

    @Test
    fun `lenient migration handles added field with default`() = runTest {
        val v1Json = buildJsonObject {
            put("userId", JsonPrimitive("user123"))
            put("intent", JsonPrimitive("greeting"))
        }

        val migration = StateMigration.lenient(AppStateV2.serializer(), currentVersion = 2)
        val result = migration.decodeMigrated(v1Json)

        assertEquals("user123", result.userId)
        assertEquals("greeting", result.intent)
        assertEquals("System", result.themeMode) // default applied
    }

    @Test
    fun `lenient migration handles removed field`() = runTest {
        val v2Json = buildJsonObject {
            put("userId", JsonPrimitive("user123"))
            put("intent", JsonPrimitive("greeting"))
            put("themeMode", JsonPrimitive("Dark"))
        }

        // AppStateV1 doesn't have themeMode — lenient should ignore it
        val migration = StateMigration.lenient(AppStateV1.serializer(), currentVersion = 1)
        val result = migration.decodeMigrated(v2Json)

        assertEquals("user123", result.userId)
        assertEquals("greeting", result.intent)
    }

    @Test
    fun `lenient migration passes through unknown keys`() = runTest {
        val jsonWithUnknown = buildJsonObject {
            put("userId", JsonPrimitive("user123"))
            put("unknownField", JsonPrimitive("should be ignored"))
        }

        val migration = StateMigration.lenient(AppStateV1.serializer(), currentVersion = 1)
        val result = migration.decodeMigrated(jsonWithUnknown)

        assertEquals("user123", result.userId)
        assertNull(result.intent)
    }

    // ── Custom migration tests ───────────────────────────────────────────────

    @Test
    fun `custom migration adds missing field`() = runTest {
        val v1Json = buildJsonObject {
            put("userId", JsonPrimitive("user123"))
            put("intent", JsonPrimitive("greeting"))
        }

        val migration = object : StateMigration<AppStateV2> {
            override val schemaVersion: Int = 2

            override suspend fun migrate(
                json: JsonObject,
                fromVersion: Int
            ): JsonObject {
                return when (fromVersion) {
                    0, 1 -> JsonObject(json + ("themeMode" to JsonPrimitive("Dark")))
                    else -> json
                }
            }

            override fun decodeMigrated(json: JsonObject): AppStateV2 {
                return Json.decodeFromJsonElement(AppStateV2.serializer(), json)
            }
        }

        val migrated = migration.migrate(v1Json, fromVersion = 1)
        val result = migration.decodeMigrated(migrated)

        assertEquals("user123", result.userId)
        assertEquals("greeting", result.intent)
        assertEquals("Dark", result.themeMode)
    }

    @Test
    fun `custom migration handles version 0 as pre-versioning`() = runTest {
        val v0Json = buildJsonObject {
            put("userId", JsonPrimitive("user456"))
        }

        val migration = object : StateMigration<AppStateV3> {
            override val schemaVersion: Int = 3

            override suspend fun migrate(
                json: JsonObject,
                fromVersion: Int
            ): JsonObject {
                var current = json
                if (fromVersion < 2) {
                    current = JsonObject(current + ("themeMode" to JsonPrimitive("Light")))
                }
                if (fromVersion < 3) {
                    current = JsonObject(current + ("location" to JsonPrimitive("unknown")))
                }
                return current
            }

            override fun decodeMigrated(json: JsonObject): AppStateV3 {
                return Json.decodeFromJsonElement(AppStateV3.serializer(), json)
            }
        }

        val migrated = migration.migrate(v0Json, fromVersion = 0)
        val result = migration.decodeMigrated(migrated)

        assertEquals("user456", result.userId)
        assertEquals("Light", result.themeMode)
        assertEquals("unknown", result.location)
    }

    // ── AgentSession version tests ───────────────────────────────────────────

    @Test
    fun `AgentSession has default serializedStateVersion 0`() {
        val session = AgentSession(
            sessionId = "test",
            currentPhaseName = "greeting",
            messageHistory = emptyList(),
            createdAt = 1000,
            updatedAt = 2000
        )

        assertEquals(0, session.serializedStateVersion)
    }

    @Test
    fun `AgentSession preserves serializedStateVersion`() {
        val session = AgentSession(
            sessionId = "test",
            currentPhaseName = "greeting",
            messageHistory = emptyList(),
            serializedState = """{"userId":"123"}""",
            serializedStateVersion = 3,
            createdAt = 1000,
            updatedAt = 2000
        )

        assertEquals(3, session.serializedStateVersion)
    }

    @Test
    fun `AgentSession deserializes without serializedStateVersion field`() {
        // Simulates old payload from before the field existed
        val oldJson = """{
            "sessionId": "test",
            "currentPhaseName": "greeting",
            "messageHistory": [],
            "createdAt": 1000,
            "updatedAt": 2000
        }"""

        val session = lenientJson.decodeFromString<AgentSession>(oldJson)

        assertEquals(0, session.serializedStateVersion)
        assertEquals("test", session.sessionId)
    }
}
