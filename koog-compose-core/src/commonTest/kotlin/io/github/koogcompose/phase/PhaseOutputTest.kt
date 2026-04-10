package io.github.koogcompose.phase

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
data class TestIntent(
    val name: String,
    val confidence: Double,
)

class PhaseOutputTest {

    // ── parse() ────────────────────────────────────────────────────────────────

    @Test
    fun `parse returns valid JSON as typed object`() {
        val output = phaseOutput<TestIntent>()
        val result = output.parse("""{"name": "check_balance", "confidence": 0.92}""")
        assertEquals("check_balance", result.name)
        assertEquals(0.92, result.confidence)
    }

    // ── Markdown fence stripping ───────────────────────────────────────────────

    @Test
    fun `parse strips json code fences`() {
        val output = phaseOutput<TestIntent>()
        val result = output.parse(
            """
            ```json
            {"name": "send_money", "confidence": 0.88}
            ```
            """.trimIndent()
        )
        assertEquals("send_money", result.name)
    }

    @Test
    fun `parse strips generic code fences`() {
        val output = phaseOutput<TestIntent>()
        val result = output.parse(
            """
            ```
            {"name": "read_prefs", "confidence": 0.75}
            ```
            """.trimIndent()
        )
        assertEquals("read_prefs", result.name)
    }

    @Test
    fun `parse handles extra whitespace around fences`() {
        val output = phaseOutput<TestIntent>()
        val result = output.parse(
            """

            ```json
            {"name": "greet", "confidence": 0.5}
            ```

            """.trimIndent()
        )
        assertEquals("greet", result.name)
    }

    @Test
    fun `parse accepts clean JSON without fences`() {
        val output = phaseOutput<TestIntent>()
        val result = output.parse("""{"name": "no_fences", "confidence": 0.99}""")
        assertEquals("no_fences", result.name)
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    fun `validate returns Valid passes through`() {
        val output = phaseOutput<TestIntent>(
            validate = { ValidationResult.Valid }
        )
        val result = output.parse("""{"name": "test", "confidence": 0.5}""")
        assertEquals("test", result.name)
    }

    @Test
    fun `validate returns Invalid throws SerializationException with reason`() {
        val output = phaseOutput<TestIntent>(
            validate = {
                if (it.confidence < 0.8) {
                    ValidationResult.Invalid("confidence too low: ${it.confidence}")
                } else {
                    ValidationResult.Valid
                }
            }
        )
        val ex = assertFailsWith<SerializationException> {
            output.parse("""{"name": "test", "confidence": 0.3}""")
        }
        assertTrue(ex.message!!.contains("confidence too low: 0.3"))
    }

    @Test
    fun `validate can check required fields`() {
        val output = phaseOutput<TestIntent>(
            validate = {
                if (it.name.isBlank()) {
                    ValidationResult.Invalid("name must not be blank")
                } else {
                    ValidationResult.Valid
                }
            }
        )
        // Valid name passes
        output.parse("""{"name": "valid", "confidence": 0.5}""")
        // Blank name fails
        val ex = assertFailsWith<SerializationException> {
            output.parse("""{"name": "", "confidence": 0.5}""")
        }
        assertTrue(ex.message!!.contains("name must not be blank"))
    }

    // ── Schema versioning ──────────────────────────────────────────────────────

    @Test
    fun `PhaseOutput has default version 1`() {
        val output = phaseOutput<TestIntent>()
        assertEquals(1, output.version)
    }

    @Test
    fun `PhaseOutput respects custom version`() {
        val output = phaseOutput<TestIntent>(version = 3)
        assertEquals(3, output.version)
    }

    // ── phaseOutput factory ────────────────────────────────────────────────────

    @Test
    fun `factory accepts all parameters`() {
        val example = TestIntent("example", 0.95)
        val output = phaseOutput<TestIntent>(
            retries = 5,
            version = 2,
            examples = listOf(example),
            descriptionOverrides = mapOf("name" to "The intent name"),
            excludedProperties = emptySet(),
            validate = { ValidationResult.Valid },
        )
        assertEquals(5, output.retries)
        assertEquals(2, output.version)
        assertEquals(listOf(example), output.structure.examples)
    }
}
