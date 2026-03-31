package io.github.koogcompose.sample

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.koogcompose.device.location.GetCurrentLocationTool
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.testing.ScriptedAIProvider
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.confirmation.ConfirmationObserver
import io.github.koogcompose.ui.confirmation.rememberDialogConfirmationHandler
import io.github.koogcompose.ui.events.EventObserver
import io.github.koogcompose.ui.state.ChatState
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.serialization.json.buildJsonObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleApp()
                }
            }
        }
    }
}

@Composable
private fun SampleApp() {
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    val realContext = remember(androidContext) { buildRealContext(androidContext) }
    val demoContext = remember(androidContext) { buildDemoContext(androidContext) }
    val scriptedProvider = remember {
        ScriptedAIProvider(
            steps = listOf(
                ScriptedAIProvider.Step.Text("Hello from the scripted provider. Ask for your location to trigger a sensitive tool."),
                ScriptedAIProvider.Step.ToolCall(
                    toolCallId = "location-demo-1",
                    toolName = "get_current_location",
                    args = buildJsonObject { }
                ),
                ScriptedAIProvider.Step.Text("I used the approved Android location tool and added the structured result above."),
            ),
            fallback = ScriptedAIProvider.Step.Text("Demo mode is active. Wire a real provider with KOOG_SAMPLE_PROVIDER to use live inference.")
        )
    }

    val chatState = rememberSampleChatState(
        realContext = realContext,
        demoContext = demoContext,
        scriptedProvider = scriptedProvider
    )
    val sessionState by chatState.sessionStateFlow.collectAsState()
    var lastEventLabel by remember { mutableStateOf("Idle") }
    val confirmationHandler = rememberDialogConfirmationHandler()
    val requestLocationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    ConfirmationObserver(
        chatState = chatState,
        handler = confirmationHandler
    )
    EventObserver(chatState = chatState) { event ->
        lastEventLabel = event.describe()
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        bottomBar = {
            ChatInputBar(chatState = chatState)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (realContext == null) {
                    "Mode: scripted demo"
                } else {
                    "Mode: live provider (${BuildConfig.SAMPLE_PROVIDER})"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "The sample screen demonstrates a plain response flow, a sensitive tool confirmation, and the Android location tool.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Active phase: ${sessionState.activePhaseName ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Last event: $lastEventLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                ) {
                    Text("Allow Location")
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { chatState.sendMessage("Say hello") }
                ) {
                    Text("Basic Demo")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { chatState.sendMessage("Where am I right now?") }
                ) {
                    Text("Location Demo")
                }
            }

            if (sessionState.error != null) {
                Text(
                    text = sessionState.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            ChatMessageList(
                chatState = chatState,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun KoogEvent.describe(): String = when (this) {
    is KoogEvent.RateLimited -> "Rate limited"
    is KoogEvent.TurnStarted -> "Turn started"
    is KoogEvent.ProviderPassStarted -> "Provider pass $passIndex started"
    is KoogEvent.ProviderChunkReceived -> when (val currentChunk = chunk) {
        is io.github.koogcompose.session.AIResponseChunk.TextDelta -> "Text delta"
        is io.github.koogcompose.session.AIResponseChunk.TextComplete -> "Text complete"
        is io.github.koogcompose.session.AIResponseChunk.ReasoningDelta -> "Reasoning delta"
        is io.github.koogcompose.session.AIResponseChunk.ToolCallRequest -> "Tool requested: ${currentChunk.toolName}"
        io.github.koogcompose.session.AIResponseChunk.End -> "Provider end"
        is io.github.koogcompose.session.AIResponseChunk.Error -> "Provider error"
    }
    is KoogEvent.ProviderPassCompleted -> "Provider pass $passIndex completed"
    is KoogEvent.ProviderPassFailed -> "Provider pass $passIndex failed"
    is KoogEvent.ToolCallRequested -> "Tool call: $toolName"
    is KoogEvent.ToolConfirmationRequested -> "Awaiting confirmation for $toolName"
    is KoogEvent.PhaseTransitioned -> "Phase: ${fromPhaseName ?: "none"} -> $toPhaseName"
    is KoogEvent.ToolExecutionCompleted -> "Tool result: $toolName"
    is KoogEvent.TurnCompleted -> "Turn completed"
    is KoogEvent.TurnFailed -> "Turn failed"
    is KoogEvent.TurnCancelled -> "Turn cancelled"
}

@Composable
private fun rememberSampleChatState(
    realContext: KoogComposeContext?,
    demoContext: KoogComposeContext,
    scriptedProvider: ScriptedAIProvider
): ChatState {
    return if (realContext != null) {
        rememberChatState(context = realContext)
    } else {
        rememberChatState(
            provider = scriptedProvider,
            context = demoContext
        )
    }
}

private fun buildDemoContext(context: Context): KoogComposeContext = koogCompose {
    provider {
        ollama(model = "demo-local")
    }
    prompt {
        default { "You are a concise Android demo assistant." }
    }
    tools {
        register(GetCurrentLocationTool(context))
    }
    config {
        requireConfirmationForSensitive = true
    }
}

private fun buildRealContext(context: Context): KoogComposeContext? {
    val provider = BuildConfig.SAMPLE_PROVIDER.trim().lowercase()
    if (provider.isBlank()) return null

    return when (provider) {
        "openai" -> BuildConfig.SAMPLE_API_KEY.takeIf { it.isNotBlank() }?.let { apiKey ->
            koogCompose {
                provider {
                    openAI(apiKey = apiKey) {
                        if (BuildConfig.SAMPLE_MODEL.isNotBlank()) model = BuildConfig.SAMPLE_MODEL
                        if (BuildConfig.SAMPLE_BASE_URL.isNotBlank()) baseUrl = BuildConfig.SAMPLE_BASE_URL
                    }
                }
                prompt {
                    default { "You are a concise Android demo assistant." }
                }
                tools {
                    register(GetCurrentLocationTool(context))
                }
                config {
                    requireConfirmationForSensitive = true
                }
            }
        }

        "anthropic" -> BuildConfig.SAMPLE_API_KEY.takeIf { it.isNotBlank() }?.let { apiKey ->
            koogCompose {
                provider {
                    anthropic(apiKey = apiKey) {
                        if (BuildConfig.SAMPLE_MODEL.isNotBlank()) model = BuildConfig.SAMPLE_MODEL
                    }
                }
                prompt {
                    default { "You are a concise Android demo assistant." }
                }
                tools {
                    register(GetCurrentLocationTool(context))
                }
                config {
                    requireConfirmationForSensitive = true
                }
            }
        }

        "google" -> BuildConfig.SAMPLE_API_KEY.takeIf { it.isNotBlank() }?.let { apiKey ->
            koogCompose {
                provider {
                    google(apiKey = apiKey) {
                        if (BuildConfig.SAMPLE_MODEL.isNotBlank()) model = BuildConfig.SAMPLE_MODEL
                    }
                }
                prompt {
                    default { "You are a concise Android demo assistant." }
                }
                tools {
                    register(GetCurrentLocationTool(context))
                }
                config {
                    requireConfirmationForSensitive = true
                }
            }
        }

        "ollama" -> koogCompose {
            provider {
                ollama(model = BuildConfig.SAMPLE_MODEL.ifBlank { "llama3.2" }) {
                    if (BuildConfig.SAMPLE_BASE_URL.isNotBlank()) baseUrl = BuildConfig.SAMPLE_BASE_URL
                }
            }
            prompt {
                default { "You are a concise Android demo assistant." }
            }
            tools {
                register(GetCurrentLocationTool(context))
            }
            config {
                requireConfirmationForSensitive = true
            }
        }

        else -> null
    }
}
