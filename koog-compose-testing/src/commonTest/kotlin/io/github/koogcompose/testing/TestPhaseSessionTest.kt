package io.github.koogcompose.testing

import io.github.koogcompose.phase.PhaseRegistry
import io.github.koogcompose.phase.PhaseRegistry.Builder
import io.github.koogcompose.prompt.PromptStack
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.security.Guardrails
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogConfig
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolRegistry
import io.github.koogcompose.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class TestPhaseSessionTest {

    @Test
    fun scriptedToolFlowTransitionsPhaseAndMutatesState() {
        val stateStore = KoogStateStore(TestAppState())
        val context = testContext(
            stateStore = stateStore,
            locationTool = RecordLocationIntentTool(stateStore)
        )

        val session = testPhaseSession(context) {
            on("I need help with my location", phase = "greeting") {
                transitionTo("location_check")
                callTool("RecordLocationIntent")
                respondWith("Sure, fetching location now.")
            }
        }

        val response = session.send("I need help with my location")

        assertEquals("Sure, fetching location now.", response)
        assertPhase(session, "location_check")
        assertToolCalled(session, "RecordLocationIntent")
        assertState(session) { state ->
            assertEquals(TestIntent.LOCATION_REQUEST, state.intent)
            assertEquals(true, state.locationRequested)
        }
    }

    @Test
    fun simpleTextTurnWorksWithoutToolCalls() {
        val stateStore = KoogStateStore(TestAppState())
        val context = testContext(
            stateStore = stateStore,
            locationTool = RecordLocationIntentTool(stateStore)
        )

        val session = testPhaseSession(context) {
            on("Hello") respondWith "Hi there."
        }

        val response = session.send("Hello")

        assertEquals("Hi there.", response)
        assertPhase(session, "greeting")
    }

    @Test
    fun deniedToolCallsAreObservableInAssertions() {
        val stateStore = KoogStateStore(TestAppState())
        val context = testContext(
            stateStore = stateStore,
            locationTool = DeniedLocationTool()
        )

        val session = testPhaseSession(context) {
            on("Get my location", phase = "greeting") {
                transitionTo("location_check")
                callTool("RecordLocationIntent")
                respondWith("I could not access location.")
            }
        }

        val response = session.send("Get my location")

        assertEquals("I could not access location.", response)
        assertPhase(session, "location_check")
        assertGuardrailDenied(session, "RecordLocationIntent")
        assertState(session) { state ->
            assertEquals(null, state.intent)
            assertEquals(false, state.locationRequested)
        }
    }

    @Test
    fun sensitiveToolsCanBeDeniedByConfirmationHandler() {
        val stateStore = KoogStateStore(TestAppState())
        val context = testContext(
            stateStore = stateStore,
            locationTool = RecordLocationIntentTool(
                stateStore = stateStore,
                permissionLevel = PermissionLevel.SENSITIVE
            )
        )

        val session = testPhaseSession(
            context = context,
            confirmationHandler = AutoDenyConfirmationHandler,
        ) {
            on("Share my location", phase = "greeting") {
                transitionTo("location_check")
                callTool("RecordLocationIntent")
                respondWith("I could not access location.")
            }
        }

        val response = session.send("Share my location")

        assertEquals("I could not access location.", response)
        assertPhase(session, "location_check")
        assertGuardrailDenied(session, "RecordLocationIntent")
        assertState(session) { state ->
            assertEquals(null, state.intent)
            assertEquals(false, state.locationRequested)
        }
    }

    private fun testContext(
        stateStore: KoogStateStore<TestAppState>,
        locationTool: SecureTool
    ): KoogComposeContext<TestAppState> {
        val phaseRegistry = Builder()
            .apply {
                phase(name = "greeting", initial = true) {
                    instructions { "Greet the user and move to location help when needed." }
                    onCondition(
                        on = "the user needs help with location",
                        targetPhase = "location_check"
                    )
                }
                phase(name = "location_check") {
                    instructions { "Use the location tool when the user asks for location help." }
                    tool(locationTool)
                }
            }
            .build()

        return KoogComposeContext(
            providerConfig = ProviderConfig.Ollama(model = "test-model"),
            promptStack = PromptStack.Empty,
            toolRegistry = ToolRegistry.Empty,
            phaseRegistry = phaseRegistry,
            activePhaseName = phaseRegistry.initialPhase?.name,
            stateStore = stateStore,
            eventHandlers = io.github.koogcompose.event.EventHandlers.Empty,
            config = KoogConfig(
                guardrails = Guardrails.Builder()
                    .apply {
                        rateLimit("RecordLocationIntent", max = 1, per = 1.days)
                    }
                    .build()
            )
        )
    }
}

private data class TestAppState(
    val intent: TestIntent? = null,
    val locationRequested: Boolean = false
)

private enum class TestIntent {
    LOCATION_REQUEST
}

private class RecordLocationIntentTool(
    override val stateStore: KoogStateStore<TestAppState>,
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE
) : StatefulTool<TestAppState>() {
    override val name: String = "RecordLocationIntent"
    override val description: String = "Marks the current app state as a location request."

    override suspend fun execute(args: JsonObject): ToolResult {
        stateStore.update { current ->
            current.copy(
                intent = TestIntent.LOCATION_REQUEST,
                locationRequested = true
            )
        }
        return ToolResult.Success("Location request captured.")
    }
}

private class DeniedLocationTool : SecureTool {
    override val name: String = "RecordLocationIntent"
    override val description: String = "Always denies location access."
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        return ToolResult.Denied("Location permission not granted in test.")
    }
}
