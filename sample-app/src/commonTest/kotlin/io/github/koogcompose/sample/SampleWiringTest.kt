package io.github.koogcompose.sample

import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke coverage for the cross-platform samples.
 *
 * These samples used to be reachable only from an Android APK, so a broken `koogCompose { }`
 * DSL call or a missing selector entry stayed invisible until someone installed the app.
 * Running on desktop keeps them honest: the phase graphs must still build, and the
 * selector metadata must still be complete.
 */
class SampleWiringTest {

    @Test
    fun everySelectorEntryHasCompleteMetadata() {
        assertTrue(Sample.entries.isNotEmpty(), "selector must offer at least one sample")

        for (sample in Sample.entries) {
            assertTrue(sample.title.isNotBlank(), "${sample.name} has a blank title")
            assertTrue(sample.description.isNotBlank(), "${sample.name} has a blank description")
            assertTrue(
                sample.library.isNotBlank(),
                "${sample.name} does not say which library features it shows",
            )
        }
    }

    @Test
    fun teachingContextBuildsTheFullSixPhaseGraph() {
        val store = KoogStateStore(TeachingState())

        @Suppress("UNCHECKED_CAST")
        val context = buildTeachingContext(stateStore = store) as KoogComposeContext<TeachingState>

        assertEquals(
            listOf("greet", "assess", "teach", "practice", "review", "wrapup"),
            context.phaseRegistry.all.map { it.name },
        )
        assertEquals("greet", context.phaseRegistry.initialPhase?.name)
    }

    @Test
    fun teachingContextHonoursTheSelectedProvider() {
        val store = KoogStateStore(TeachingState())

        @Suppress("UNCHECKED_CAST")
        val context = buildTeachingContext(
            stateStore = store,
            providerConfig = ProviderConfig.OpenAI(apiKey = "test-key", model = "gpt-4o-mini"),
        ) as KoogComposeContext<TeachingState>

        // The phase graph must not depend on which backend the user picked.
        assertEquals(6, context.phaseRegistry.all.size)
    }

    @Test
    fun providerSelectionMapsEachKindToItsConfig() {
        val openAi = SampleProviderSelection(
            kind = SampleProviderKind.OpenAI,
            openAiKey = "test-key",
            openAiModel = "gpt-4o-mini",
        )
        assertTrue(openAi.isReady)
        val openAiConfig = openAi.toProviderConfig()
        assertTrue(openAiConfig is ProviderConfig.OpenAI, "expected OpenAI, got $openAiConfig")
        assertEquals("gpt-4o-mini", openAiConfig.model)

        val ollama = SampleProviderSelection(kind = SampleProviderKind.Ollama, ollamaModel = "llama3.2")
        assertTrue(ollama.isReady)
        assertTrue(ollama.toProviderConfig() is ProviderConfig.Ollama)

        val onDevice = SampleProviderSelection(
            kind = SampleProviderKind.OnDeviceGemma,
            onDeviceModelPath = "/models/gemma.litertlm",
        )
        assertTrue(onDevice.isReady)
        assertTrue(onDevice.toProviderConfig() is ProviderConfig.OnDevice)
    }

    @Test
    fun openAiSelectionIsNotReadyWithoutAKey() {
        val blank = SampleProviderSelection(kind = SampleProviderKind.OpenAI, openAiKey = "")
        assertFalse(blank.isReady, "an empty API key must not count as configured")
    }
}
