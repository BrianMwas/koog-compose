package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.confirmation.ConfirmationObserver
import io.github.koogcompose.ui.confirmation.rememberDialogConfirmationHandler
import io.github.koogcompose.ui.events.EventObserver
import io.github.koogcompose.ui.state.rememberChatState

/**
 * Shared Compose UI for the sample app.
 *
 * Demonstrates a conversational AI assistant with:
 * - A phase-based flow (greeting -> help -> done)
 * - Token streaming via ChatMessageList
 * - Event observation for phase transitions
 * - Dialog-based tool confirmation
 */
@Composable
fun KoogSampleApp(
    modifier: Modifier = Modifier,
) {
    val context = KoogComposeContext<Unit> {
        provider {
            ollama(model = "llama3.2")
        }
        prompt {
            default {
                """
                You are a friendly, concise AI assistant running on the user's device.
                Keep responses brief and helpful.
                """.trimIndent()
            }
        }
        phases {
            phase("greeting", initial = true) {
                instructions {
                    """
                    You are in the greeting phase.
                    Welcome the user warmly and briefly explain what you can help with.
                    When the user seems ready to proceed, transition to the help phase.
                    """.trimIndent()
                }
                onCondition(
                    on = "the user has been greeted and is ready to ask for help",
                    targetPhase = "help"
                )
            }
            phase("help") {
                instructions {
                    """
                    You are in the help phase.
                    Answer the user's questions concisely.
                    When the conversation appears complete and the user seems satisfied,
                    transition to the done phase.
                    """.trimIndent()
                }
                onCondition(
                    on = "the user's request has been fully addressed and they seem satisfied",
                    targetPhase = "done"
                )
            }
            phase("done") {
                instructions {
                    """
                    You are in the done phase.
                    Politely confirm that the conversation is wrapping up.
                    Offer to help again if the user has more questions.
                    """.trimIndent()
                }
            }
        }
        config {
            streamingEnabled = true
            requireConfirmationForSensitive = false
        }
    }

    val chatState = rememberChatState(context = context)

    // Track active phase reactively via events
    var currentPhase by remember { mutableStateOf("greeting") }
    var lastEventDescription by remember { mutableStateOf("Ready") }

    EventObserver(chatState = chatState) { event ->
        when (event) {
            is KoogEvent.PhaseTransitioned -> {
                currentPhase = event.toPhaseName ?: currentPhase
                lastEventDescription = "Phase: ${event.fromPhaseName ?: "start"} -> ${event.toPhaseName}"
            }
            is KoogEvent.TurnCompleted -> lastEventDescription = "Response received"
            is KoogEvent.TurnStarted -> lastEventDescription = "Thinking..."
            is KoogEvent.ToolCallRequested -> lastEventDescription = "Using tool: ${event.toolName}"
            is KoogEvent.ProviderChunkReceived -> lastEventDescription = "Streaming..."
            else -> {}
        }
    }

    val confirmationHandler = rememberDialogConfirmationHandler()
    ConfirmationObserver(
        chatState = chatState,
        handler = confirmationHandler
    )

    MaterialTheme {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
            topBar = {
                Text(
                    text = "Koog Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            },
            bottomBar = {
                ChatInputBar(
                    chatState = chatState,
                    placeholder = "Ask me anything..."
                )
            }
        ) { innerPadding ->
            ChatMessageList(
                chatState = chatState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                showSystemMessages = false,
                showToolCallMessages = true,
            )
        }

        // Phase indicator overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Phase: $currentPhase  |  $lastEventDescription",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewKoogSampleApp() {
    KoogSampleApp()
}
