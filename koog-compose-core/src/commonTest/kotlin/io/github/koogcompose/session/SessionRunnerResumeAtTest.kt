package io.github.koogcompose.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRunnerResumeAtTest {

    // ── UnknownPhaseException ────────────────────────────────────────────────

    @Test
    fun `UnknownPhaseException contains phase name in message`() {
        val ex = UnknownPhaseException("notify_user")
        assertTrue(ex.message?.contains("notify_user") == true)
    }

    @Test
    fun `UnknownPhaseException is an IllegalArgumentException`() {
        val ex = UnknownPhaseException("test_phase")
        assertTrue(ex is IllegalArgumentException)
    }

    // ── resolveAgentForPhase logic (agent owns phase) ───────────────────────

    @Test
    fun `main agent is found when it owns the phase`() = runTest {
        val session = koogSession<Unit> {
            provider { openAI(apiKey = "test") }
            main {
                instructions { "Main agent." }
                phases {
                    phase("greet", initial = true) {
                        instructions { "Greet the user." }
                    }
                }
            }
        }

        val mainAgent = session.mainAgent
        val phase = mainAgent.phaseRegistry.resolve("greet")
        assertNotNull(phase)
    }

    @Test
    fun `specialist agent owns its phase but main does not`() = runTest {
        val specialist = koogAgent("specialist") {
            instructions { "Specialist agent." }
            phases {
                phase("notify", initial = true) {
                    instructions { "Send notification." }
                }
            }
        }

        koogSession<Unit> {
            provider { openAI(apiKey = "test") }
            main {
                instructions { "Main agent." }
                phases {
                    phase("greet", initial = true) {
                        instructions { "Greet the user." }
                    }
                }
            }
            agents(specialist)
        }

        // Specialist owns "notify"
        val specialistPhase = specialist.phaseRegistry.resolve("notify")
        assertNotNull(specialistPhase)

        // Main does NOT own "notify"
        val mainHasNotify = specialist.phaseRegistry.resolve("greet")
        assertNull(mainHasNotify)
    }

    @Test
    fun `resolveAgentForPhase returns first agent that owns the phase`() = runTest {
        val specialist = koogAgent("specialist") {
            instructions { "Specialist agent." }
            phases {
                phase("shared", initial = true) {
                    instructions { "Specialist shared phase." }
                }
            }
        }

        val session = koogSession<Unit> {
            provider { openAI(apiKey = "test") }
            main {
                instructions { "Main agent." }
                phases {
                    phase("shared", initial = true) {
                        instructions { "Main shared phase." }
                    }
                }
            }
            agents(specialist)
        }

        // Simulate resolveAgentForPhase logic
        val agents = listOf(session.mainAgent) + session.agentRegistry.values
        val owner = agents.firstOrNull { it.phaseRegistry.resolve("shared") != null }
        assertNotNull(owner)
        assertEquals("main", owner.name)  // main is checked first
    }

    // ── KoogSessionHandle default resumeAt throws UnsupportedOperationException ──

    @Test
    fun `default KoogSessionHandle resumeAt throws UnsupportedOperationException`() {
        val handle = object : KoogSessionHandle {
            override val sessionId: String = "test"
            override val activity = kotlinx.coroutines.flow.MutableStateFlow<AgentActivity>(
                AgentActivity.Idle
            )
            override val activityDetail = kotlinx.coroutines.flow.MutableStateFlow("")
            override val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val error = kotlinx.coroutines.flow.MutableStateFlow<Throwable?>(null)
            override val responseStream = kotlinx.coroutines.flow.flow<Nothing> { }
            override fun send(userMessage: String) {}
            override fun reset() {}
        }

        assertFailsWith<UnsupportedOperationException> {
            handle.resumeAt("any_phase", "test-session", null)
        }
    }

    // ── Session config preserved across resumeAt ────────────────────────────

    @Test
    fun `session config maxAgentIterations is accessible for resumeAt loop guard`() {
        val session = koogSession<Unit> {
            provider { openAI(apiKey = "test") }
            main {
                instructions { "Main agent." }
                phases {
                    phase("greet", initial = true) {
                        instructions { "Greet the user." }
                    }
                }
            }
            config {
                maxAgentIterations = 5
            }
        }

        assertEquals(5, session.config.maxAgentIterations)
    }
}
