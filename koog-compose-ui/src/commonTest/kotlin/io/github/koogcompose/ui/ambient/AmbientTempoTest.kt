package io.github.koogcompose.ui.ambient

import io.github.koogcompose.session.AgentActivity
import io.github.koogcompose.session.isRunning
import io.github.koogcompose.session.isSticky
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientTempoTest {

    private val allActivities: List<AgentActivity> = listOf(
        AgentActivity.Idle,
        AgentActivity.Reasoning,
        AgentActivity.Thinking,
        AgentActivity.Executing("SearchWeb"),
        AgentActivity.WaitingForInput,
        AgentActivity.Blocked,
        AgentActivity.Completed("done"),
        AgentActivity.Failed(RuntimeException("boom")),
    )

    @Test
    fun idleDrawsNothing() {
        val tempo = ambientTempoFor(AgentActivity.Idle)

        assertEquals(AmbientRole.Dormant, tempo.role)
        assertEquals(0f, tempo.intensity, "an idle agent must cost no frames")
        assertFalse(tempo.isAnimated)
    }

    @Test
    fun everyRunningActivityIsVisibleAndMoving() {
        for (activity in allActivities.filter { it.isRunning }) {
            val tempo = ambientTempoFor(activity)
            assertTrue(
                tempo.isAnimated,
                "$activity is a running state so the gradient must animate, got $tempo",
            )
        }
    }

    @Test
    fun terminalStatesHoldStill() {
        for (activity in allActivities.filter { it.isSticky }) {
            val tempo = ambientTempoFor(activity)
            assertEquals(
                0,
                tempo.cycleMillis,
                "$activity has finished, so the gradient must not keep sweeping",
            )
            assertFalse(tempo.isAnimated, "$activity must not read as work in flight")
        }
    }

    @Test
    fun streamingMovesFasterAndBrighterThanReasoning() {
        val reasoning = ambientTempoFor(AgentActivity.Reasoning)
        val speaking = ambientTempoFor(AgentActivity.Thinking)

        assertTrue(
            speaking.cycleMillis < reasoning.cycleMillis,
            "visible streaming should sweep faster than background reasoning",
        )
        assertTrue(
            speaking.intensity > reasoning.intensity,
            "visible streaming should be the more prominent of the two",
        )
    }

    @Test
    fun toolCallsAndConfirmationPulse() {
        assertTrue(ambientTempoFor(AgentActivity.Executing("Book")).pulse)
        assertTrue(ambientTempoFor(AgentActivity.WaitingForInput).pulse)
    }

    @Test
    fun eachActivityMapsToItsOwnRole() {
        val roles = allActivities.map { ambientTempoFor(it).role }

        assertEquals(
            roles.size,
            roles.toSet().size,
            "two different activities collapsed onto the same colour role: $roles",
        )
    }

    @Test
    fun toolNameDoesNotChangeTheTempo() {
        assertEquals(
            ambientTempoFor(AgentActivity.Executing("A")),
            ambientTempoFor(AgentActivity.Executing("B")),
        )
    }

    @Test
    fun everyActivityProducesAValidTempo() {
        for (activity in allActivities) {
            val tempo = ambientTempoFor(activity)
            assertTrue(tempo.intensity in 0f..1f, "$activity produced ${tempo.intensity}")
            assertTrue(tempo.cycleMillis >= 0, "$activity produced ${tempo.cycleMillis}")
        }
    }
}
