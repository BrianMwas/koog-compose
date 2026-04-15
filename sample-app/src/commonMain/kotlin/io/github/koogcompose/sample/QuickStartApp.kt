package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Simple cross-platform tutor app that works on iOS and Android.
 * 
 * No platform-specific features:
 * - No photo capture (SaveTopicPhoto)
 * - No database persistence (uses in-memory only)
 * - No microphone recording (HoldToRecordButton)
 * 
 * Perfect for demonstrating Compose Multiplatform on iOS!
 */

@Serializable
data class QuickStartState(
    val studentName: String = "",
    val conceptsCovered: List<String> = emptyList(),
    val messageCount: Int = 0,
)

class RecordConceptToolQuickStart(
    override val stateStore: KoogStateStore<QuickStartState>
) : StatefulTool<QuickStartState>() {
    override val name = "RecordConcept"
    override val description = "Record a concept the student has learned"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val concept = args["concept"]?.jsonPrimitive?.contentOrNull ?: "unknown concept"
        stateStore.update {
            it.copy(conceptsCovered = it.conceptsCovered + concept)
        }
        return ToolResult.Success(
            message = "✓ Concept recorded: $concept"
        )
    }
}

class UpdateNameToolQuickStart(
    override val stateStore: KoogStateStore<QuickStartState>
) : StatefulTool<QuickStartState>() {
    override val name = "UpdateStudentName"
    override val description = "Record the student's name"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull ?: "Friend"
        stateStore.update {
            it.copy(studentName = name)
        }
        return ToolResult.Success(
            message = "✓ Nice to meet you, $name!"
        )
    }
}

/**
 * Creates a simple Koog teaching session.
 * 
 * Features:
 * - Multi-phase flow: greet → learn → teach → done
 * - Tool calls for recording concepts and student name
 * - Works on iOS and Android (no platform-specific APIs)
 * - In-memory session (no persistence)
 */
suspend fun createQuickStartSession(
    stateStore: KoogStateStore<QuickStartState>,
): PhaseSession<QuickStartState> {
    val recordConcept = RecordConceptToolQuickStart(stateStore)
    val updateName = UpdateNameToolQuickStart(stateStore)

    val context: KoogComposeContext<QuickStartState> = koogCompose<QuickStartState> {
        // Use the scripted test provider
        provider {
            ollama(model = "test-model")
        }

        initialState { stateStore.current }

        phases {
            // Phase 1: Greet
            phase("greet", initial = true) {
                instructions {
                    val state = stateStore.current
                    """
                    You are a friendly tutor helping students learn.
                    ${if (state.studentName.isNotBlank()) 
                        "You're chatting with ${state.studentName}." 
                    else 
                        "First, ask the student their name."
                    }
                    
                    Be warm and encouraging! Use emojis. 🌟
                    Ask what they'd like to learn today (math, reading, science).
                    Use [UpdateStudentName] to record their name if needed.
                    After learning their name and subject, move to the 'teach' phase.
                    """.trimIndent()
                }
                tool(updateName)
                tool(recordConcept)
            }

            // Phase 2: Teach
            phase("teach") {
                instructions {
                    """
                    Teach the student in a fun, engaging way!
                    Explain concepts step-by-step with simple language.
                    Use examples they can relate to.
                    Use [RecordConcept] when they learn something new.
                    Ask a question to check if they understand.
                    After 2-3 concepts, suggest moving to the done phase.
                    """.trimIndent()
                }
                tool(recordConcept)
            }

            // Phase 3: Done
            phase("done") {
                instructions {
                    val state = stateStore.current
                    val concepts = state.conceptsCovered.joinToString(", ")
                    """
                    Great job learning today! 🎉
                    Concepts covered: ${if (concepts.isNotEmpty()) concepts else "Let's keep learning!"}
                    
                    Say goodbye and offer to help them learn more another time.
                    """.trimIndent()
                }
            }
        }
    }

    val executor = context.createExecutor()
    return PhaseSession(
        context = context,
        executor = executor,
        sessionId = "quickstart_${io.github.koogcompose.observability.currentTimeMs()}",
    )
}

/**
 * iOS/Android compatible composable that shows the QuickStart app.
 * 
 * This is the main entry point for sample-app on all platforms.
 */
@Composable
fun QuickStartAppUI(
    session: PhaseSession<QuickStartState>,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val chatState = rememberChatState(session)
    val state by session.state.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header showing student name and concepts count
                Card(
                    modifier = Modifier
                        .fillMaxSize(0.15f)
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = if (state.studentName.isNotEmpty())
                                "Hi, ${state.studentName}! 👋"
                            else
                                "Welcome to QuickStart Tutor! 📚",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.conceptsCovered.isNotEmpty()) {
                            Text(
                                text = "Concepts learned: ${state.conceptsCovered.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Chat area
                ChatMessageList(
                    session = session,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                )

                // Input bar
                ChatInputBar(
                    chatState = chatState,
                    onSendMessage = { message ->
                        chatState.sendMessage(message)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
