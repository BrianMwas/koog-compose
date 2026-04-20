package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * iOS entry point using QuickStart app.
 * 
 * Features:
 * - Works without Platform-specific APIs
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
        QuickStartContainer()
    }
}

/**
 * Container that initializes the session and displays the UI.
 */
@Composable
private fun QuickStartContainer() {
    val sessionState = remember { mutableStateOf<PhaseSession<QuickStartState>?>(null) }
    val errorState = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        GlobalScope.launch {
            try {
                // For iOS MVP, use scripted AI responses (no API key needed)
                // In production:
                // 1. Read ANTHROPIC_API_KEY or OPENAI_API_KEY from environment
                // 2. Use anthropic() or openAI() provider in koogCompose block
                // 3. For on-device: use FoundationModels via Swift bridge
                val testProvider = createScriptedTestProvider()
                val stateStore = KoogStateStore(QuickStartState())
                
                val session = createQuickStartSession(
                    stateStore = stateStore,
                )
                sessionState.value = session
            } catch (e: Exception) {
                errorState.value = buildString {
                    append("Error initializing session: ${e.message}\n\n")
                    append("Make sure to configure:\n")
                    append("1. Cloud provider (Anthropic/OpenAI) - set environment variable\n")
                    append("2. Or local Ollama instance running on device\n")
                    append("3. Or iOS 18+ FoundationModels bridge\n\n")
                    // Include shortened stacktrace for debugging
                    append(e.stackTraceToString().lines().take(5).joinToString("\n"))
                }
            }
        }
    }

    val session = sessionState.value
    val error = errorState.value

    when {
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        session != null -> {
            QuickStartAppUI(session)
        }
        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
