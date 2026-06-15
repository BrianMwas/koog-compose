package io.github.koogcompose.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS entry point for the koog-compose sample.
 *
 * Shows [SampleSelectorApp], which lets the user pick between the
 * cross-platform showcases — the multi-phase home-tutor agent
 * (greet → assess → teach → practice → review → wrapup) and the
 * trip-planner agent (discover → itinerary → booking) with a live
 * budget panel and confirmation-gated booking tool. Both run without
 * platform-specific APIs:
 * - Compatible with cloud LLM providers (Anthropic, OpenAI)
 * - In-memory session (no persistence)
 * - Full Compose Multiplatform UI support on iOS 🎉
 *
 * **Integration with FoundationModels (iOS 18+)**:
 * For real on-device inference, bridge to Apple's FoundationModels:
 * ```swift
 * // In iOSApp.swift
 * let systemModel = SystemLanguageModel.default
 * let session = LanguageModelSession()
 * let response = try await session.respond(to: userInput)
 * // Wire response back to Kotlin via Kotlin/Native bridge
 * ```
 */
fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        SampleSelectorApp(modifier = Modifier.fillMaxSize())
    }
}
