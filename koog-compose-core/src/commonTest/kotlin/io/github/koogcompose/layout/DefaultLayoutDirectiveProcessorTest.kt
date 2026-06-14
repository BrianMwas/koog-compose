package io.github.koogcompose.layout

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLayoutDirectiveProcessorTest {

    @Test
    fun validShowComponent_isAccepted_andUpdatesLayoutState() = runTest {
        val processor = DefaultLayoutDirectiveProcessor(testConfig(), scope = this)

        processor.outcomes.test {
            processor.send(show("a", HERO, Position.End, DirectiveId("d1")))
            advanceUntilIdle()

            val outcome = awaitItem()
            assertTrue(outcome is DirectiveOutcome.Accepted)
            outcome as DirectiveOutcome.Accepted
            assertEquals(DirectiveId("d1"), outcome.correlationId)
            assertEquals(1L, outcome.resultingStateVersion)
            assertFalse(outcome.positionFallback)

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("a"), processor.layoutState.value.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_unknownComponent_isRejected() = runTest {
        val processor = DefaultLayoutDirectiveProcessor(testConfig(), scope = this)

        processor.outcomes.test {
            processor.send(show("unknown", HERO, Position.End, DirectiveId("d1")))
            advanceUntilIdle()

            val outcome = awaitItem()
            assertTrue(outcome is DirectiveOutcome.Rejected)
            outcome as DirectiveOutcome.Rejected
            assertEquals(PipelineStage.SchemaValidation, outcome.rejectedAt)

            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(processor.layoutState.value.isEmpty())
    }

    @Test
    fun duplicateCorrelationId_withinTurn_isCoalesced() = runTest {
        val processor = DefaultLayoutDirectiveProcessor(testConfig(), scope = this)
        val directive = show("a", HERO, Position.End, DirectiveId("dup"))

        processor.outcomes.test {
            processor.send(directive)
            advanceUntilIdle()
            assertTrue(awaitItem() is DirectiveOutcome.Accepted)

            processor.send(directive)
            advanceUntilIdle()
            assertTrue(awaitItem() is DirectiveOutcome.Coalesced)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun beginTurn_resetsCoalescing_soRepeatedCorrelationIdIsProcessedAgain() = runTest {
        val processor = DefaultLayoutDirectiveProcessor(testConfig(), scope = this)
        val directive = show("a", HERO, Position.End, DirectiveId("dup"))

        processor.outcomes.test {
            processor.send(directive)
            advanceUntilIdle()
            assertTrue(awaitItem() is DirectiveOutcome.Accepted)

            processor.beginTurn()
            processor.send(directive)
            advanceUntilIdle()
            assertTrue(awaitItem() is DirectiveOutcome.Accepted)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun showComponent_positionBeforeMissingReference_surfacesPositionFallback() = runTest {
        val processor = DefaultLayoutDirectiveProcessor(testConfig(), scope = this)

        processor.outcomes.test {
            processor.send(show("a", HERO, Position.Before(ComponentId("b")), DirectiveId("d1")))
            advanceUntilIdle()

            val outcome = awaitItem()
            assertTrue(outcome is DirectiveOutcome.Accepted)
            outcome as DirectiveOutcome.Accepted
            assertTrue(outcome.positionFallback)

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("a"), processor.layoutState.value.entriesFor(HERO).map { it.componentId.value })
    }
}

private val HERO = SlotId("hero")
private val PROMO_TAG = Tag("promo")

private fun testConfig(): LayoutEngineConfig = LayoutEngineConfig(
    workflowContext = WorkflowContext(
        businessId = BusinessId("biz-1"),
        userRole = TestUserRole(setOf(Permissions.Read)),
        activeWorkflow = WorkflowId("wf-1"),
        deviceContext = DeviceContext(Platform.Android, FormFactor.Phone, NetworkClass.Online, "en-US"),
    ),
    slotRegistry = SlotRegistry(listOf(LayoutSlot(HERO, SlotCapacity.Multiple(3), setOf(PROMO_TAG)))),
    componentRegistry = ComponentRegistry(
        listOf(
            ComponentDefinition(ComponentId("a"), setOf(PROMO_TAG)),
            ComponentDefinition(ComponentId("b"), setOf(PROMO_TAG)),
        )
    ),
)

private fun show(
    componentId: String,
    slotId: SlotId,
    position: Position,
    correlationId: DirectiveId,
): AgentLayoutDirective.ShowComponent = AgentLayoutDirective.ShowComponent(
    componentId = ComponentId(componentId),
    slotId = slotId,
    position = position,
    correlationId = correlationId,
)

private class TestUserRole(permissions: Set<Permission>) : UserRole(
    id = "viewer",
    displayName = "Viewer",
    permissions = permissions,
)
