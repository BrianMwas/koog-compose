package io.github.koogcompose.phase

import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class TypedOutputScopeTest {

    class NoOpTool : SecureTool {
        override val name: String = "noop"
        override val description: String = "Does nothing"
        override val permissionLevel: PermissionLevel = PermissionLevel.SAFE
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult.Success("ok")
    }

    @Serializable
    data class PhaseOutputType(val result: String)

    @Test
    fun `typedOutput throws on subphase`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            PhaseRegistry.Builder().apply {
                phase("parent") {
                    subphase("child") {
                        typedOutput<PhaseOutputType>()
                    }
                }
            }.build()
        }
        assertTrue(ex.message!!.contains("typedOutput"), "Message: ${ex.message}")
        assertTrue(ex.message!!.contains("top-level"), "Message: ${ex.message}")
    }

    @Test
    fun `typedOutput throws on parallel branch`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            PhaseRegistry.Builder().apply {
                phase("parent") {
                    parallel {
                        branch("branch1") {
                            typedOutput<PhaseOutputType>()
                        }
                    }
                }
            }.build()
        }
        assertTrue(ex.message!!.contains("typedOutput"), "Message: ${ex.message}")
        assertTrue(ex.message!!.contains("top-level"), "Message: ${ex.message}")
    }

    @Test
    fun `typedOutput succeeds on top-level phase`() {
        val registry = PhaseRegistry.Builder().apply {
            phase("greeting") {
                typedOutput<PhaseOutputType>()
            }
        }.build()

        val phase = registry.all.first()
        assertNotNull(phase.outputStructure)
    }
}
