package io.github.koogcompose.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.koogcompose.session.*
import io.github.koogcompose.ui.state.rememberChatState
import io.github.koogcompose.ui.components.ChatMessageList
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel that wires a [PhaseSession] to Compose UI state.
 * Works on Android, iOS, and Desktop — no platform code needed.
 */
class DemoViewModel(
    agentDef: KoogDefinition<AppState>,
) : ViewModel() {

    private val context: KoogComposeContext<AppState> = agentDef as KoogComposeContext<AppState>
    private val executor: ai.koog.prompt.executor.model.PromptExecutor = agentDef.createExecutor()

    val session = PhaseSession(
        context = context,
        executor = executor,
        sessionId = "demo_session",
        scope = viewModelScope,
    )

    val isRunning: StateFlow<Boolean> = session.isRunning
    val currentPhase: StateFlow<String> = session.currentPhase
    val appState: StateFlow<AppState> = session.appState
        ?: MutableStateFlow(AppState()).asStateFlow()
    val error: StateFlow<Throwable?> = session.error

    fun send(message: String) {
        viewModelScope.launch {
            session.send(message)
        }
    }
}

/**
 * Demo screen showing koog-compose in action.
 * Includes: phase-driven chat, typed state display, and error handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    viewModel: DemoViewModel,
    modifier: Modifier = Modifier,
) {
    val isRunning by viewModel.isRunning.collectAsState()
    val currentPhase by viewModel.currentPhase.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    val chatState = rememberChatState(
        handle = viewModel.session,
        context = viewModel.session.context,
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("koog-compose Demo — $currentPhase") },
            )
        },
        snackbarHost = {
            SnackbarHost(remember { SnackbarHostState() }) {
                Snackbar(
                    modifier = Modifier.padding(8.dp),
                    action = { TextButton(onClick = { }) { Text("Dismiss") } }
                ) {
                    Text("Error: ${errorMessage?.message}")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Typed state display
            AppStateDisplay(appState)

            // Message list from koog-compose-ui
            ChatMessageList(
                chatState = chatState,
                modifier = Modifier.weight(1f),
                showToolCallMessages = true,
            )

            // Input bar
            ChatInputRow(
                onSend = { viewModel.send(it) },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AppStateDisplay(state: AppState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("State:", style = MaterialTheme.typography.labelMedium)
            state.userName?.let { Text("User: $it", style = MaterialTheme.typography.bodySmall) }
            state.lastTopic?.let { Text("Topic: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ChatInputRow(
    onSend: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = enabled,
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
            ) {
                Text("➤")
            }
        }
    }
}
