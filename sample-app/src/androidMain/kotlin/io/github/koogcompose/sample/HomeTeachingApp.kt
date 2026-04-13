package io.github.koogcompose.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogDefinition
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.session.room.RoomSessionStore
import io.github.koogcompose.session.room.createKoogDatabase
import io.github.koogcompose.session.room.getDatabaseBuilder
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.ValidationResult
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.state.ChatState
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

// ── Teaching State ────────────────────────────────────────────────────────────

/** Difficulty adapts based on student performance. */
enum class Difficulty { Beginner, Intermediate, Advanced }

@Serializable
data class TeachingState(
    val studentName: String = "",
    val age: Int = 0,
    val currentSubject: String = "math",
    val difficulty: Difficulty = Difficulty.Beginner,
    val conceptsCovered: List<String> = emptyList(),
    val correctAnswers: Int = 0,
    val totalQuestions: Int = 0,
    val sessionCount: Int = 0,
    val lastTopicPhoto: String? = null,
    val encouragement: String = "",
)

// ── Teaching Tools ────────────────────────────────────────────────────────────

class RecordConceptTool(
    override val stateStore: KoogStateStore<TeachingState>
) : StatefulTool<TeachingState>() {
    override val name = "RecordConcept"
    override val description = "Record a concept the student has mastered"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val concept = args["concept"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        stateStore.update {
            it.copy(
                conceptsCovered = it.conceptsCovered + concept,
                encouragement = when (it.difficulty) {
                    Difficulty.Beginner -> "Amazing work! You're learning so fast! 🌟"
                    Difficulty.Intermediate -> "Excellent! You're really getting this! ⭐"
                    Difficulty.Advanced -> "Outstanding! You're mastering this! 🏆"
                }
            )
        }
        return ToolResult.Success("Recorded: $concept")
    }
}

class AdjustDifficultyTool(
    override val stateStore: KoogStateStore<TeachingState>
) : StatefulTool<TeachingState>() {
    override val name = "AdjustDifficulty"
    override val description = "Adjust teaching difficulty based on student performance"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull
        val newDifficulty = when (direction) {
            "easier" -> Difficulty.values().getOrNull(
                maxOf(0, stateStore.current.difficulty.ordinal - 1)
            ) ?: stateStore.current.difficulty
            "harder" -> Difficulty.values().getOrNull(
                minOf(Difficulty.values().size - 1, stateStore.current.difficulty.ordinal + 1)
            ) ?: stateStore.current.difficulty
            else -> stateStore.current.difficulty
        }
        stateStore.update { it.copy(difficulty = newDifficulty) }
        return ToolResult.Success("Difficulty set to $newDifficulty")
    }
}

class TrackProgressTool(
    override val stateStore: KoogStateStore<TeachingState>
) : StatefulTool<TeachingState>() {
    override val name = "TrackProgress"
    override val description = "Track whether the student answered correctly or needs help"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val correct = args["correct"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        stateStore.update {
            it.copy(
                correctAnswers = it.correctAnswers + if (correct) 1 else 0,
                totalQuestions = it.totalQuestions + 1
            )
        }
        return ToolResult.Success(if (correct) "Correct! 🎉" else "Let's try again! 💪")
    }
}

class SaveTopicPhotoTool(
    override val stateStore: KoogStateStore<TeachingState>
) : StatefulTool<TeachingState>() {
    override val name = "SaveTopicPhoto"
    override val description = "Save a photo of the student's work or textbook page"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val photoRef = args["photoRef"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        stateStore.update { it.copy(lastTopicPhoto = photoRef) }
        return ToolResult.Success("Photo saved: $photoRef")
    }
}

// ── Home Teaching App ────────────────────────────────────────────────────────

/**
 * Builds the koog-compose context for home-based teaching.
 *
 * Uses a real phase workflow:
 *   Greet → Assess → Teach → Practice → Review → WrapUp
 *
 * Each phase has specific tools and instructions tailored for
 * teaching children at home.
 */
fun buildTeachingContext(
    stateStore: KoogStateStore<TeachingState>,
): KoogDefinition<TeachingState> {
    val recordConcept = RecordConceptTool(stateStore)
    val adjustDifficulty = AdjustDifficultyTool(stateStore)
    val trackProgress = TrackProgressTool(stateStore)
    val saveTopicPhoto = SaveTopicPhotoTool(stateStore)

    return koogCompose<TeachingState> {
        // Use Ollama by default (on-device via llama.cpp, no API key needed)
        // Swap to anthropic() or openAI() for cloud providers
        provider {
            ollama(model = "llama3.2")
        }

        // Child-safe content filtering
        config {
            guardrails {
                rateLimit("TrackProgress", max = 30, per = 1.minutes)
            }
            stuckDetection {
                threshold = 4
                fallbackMessage = "Let's try a different approach! Sometimes taking a break helps."
            }
        }

        phases {
            // Phase 1: Greet the student and learn about them
            phase("greet", initial = true) {
                instructions {
                    val state = stateStore.current
                    """
                    You are a warm, patient, encouraging home tutor for children.
                    The student's name is ${if (state.studentName.isNotBlank()) state.studentName else "unknown"}.
                    ${if (state.age > 0) "They are ${state.age} years old." else ""}
                    
                    Greet them enthusiastically. Learn their name and age if you don't know them.
                    Ask what subject they want to work on today (math, reading, science).
                    Keep your language simple and encouraging. Use emojis! 🌟
                    When you know their name and subject, use [RecordConcept] to note it.
                    Then transition to the assess phase.
                    """.trimIndent()
                }
                tool(recordConcept)
            }

            // Phase 2: Assess current understanding
            phase("assess") {
                instructions {
                    """
                    Assess the student's current level in ${stateStore.current.currentSubject}.
                    Ask a simple question appropriate for their level:
                    - Beginner: very basic concepts (counting, simple addition)
                    - Intermediate: grade-level problems
                    - Advanced: challenge problems
                    
                    Use [TrackProgress] to record if they got it right.
                    Use [AdjustDifficulty] to adapt based on their performance.
                    After 2-3 questions, move to the teach phase.
                    """.trimIndent()
                }
                tool(trackProgress)
                tool(adjustDifficulty)
            }

            // Phase 3: Teach new concepts
            phase("teach") {
                instructions {
                    """
                    Teach the student new concepts in ${stateStore.current.currentSubject}.
                    Explain step by step. Never just give answers — teach the method.
                    Use examples they can relate to (food, animals, games, friends).
                    
                    If they show you a photo of their homework, analyze it carefully.
                    Use [SaveTopicPhoto] to reference the photo.
                    Use [RecordConcept] when they master a concept.
                    
                    Check understanding with a practice question before moving on.
                    """.trimIndent()
                }
                tool(recordConcept)
                tool(saveTopicPhoto)
            }

            // Phase 4: Practice with guided exercises
            phase("practice") {
                instructions {
                    """
                    Give the student practice problems at their level:
                    ${when (stateStore.current.difficulty) {
                        Difficulty.Beginner -> "Very simple problems. Lots of encouragement!"
                        Difficulty.Intermediate -> "Grade-appropriate problems. Gentle correction when wrong."
                        Difficulty.Advanced -> "Challenge problems. Push them to think deeply."
                    }}
                    
                    Use [TrackProgress] for each question.
                    If they get 3+ correct in a row, use [AdjustDifficulty] to make it harder.
                    If they struggle, use [AdjustDifficulty] to make it easier and re-explain.
                    When they've done 5+ problems, move to review.
                    """.trimIndent()
                }
                tool(trackProgress)
                tool(adjustDifficulty)
            }

            // Phase 5: Review what they learned
            phase("review") {
                instructions {
                    val state = stateStore.current
                    val concepts = state.conceptsCovered.takeLast(5).joinToString(", ")
                    val accuracy = if (state.totalQuestions > 0) {
                        "${(state.correctAnswers * 100) / state.totalQuestions}%"
                    } else "N/A"
                    """
                    Review what the student learned today.
                    Concepts covered: $concepts
                    Accuracy: $accuracy
                    
                    Summarize their progress. Celebrate what they did well! 🎉
                    If they struggled with something, note it gently and suggest practice.
                    Ask if they want to do more or wrap up for today.
                    """.trimIndent()
                }
            }

            // Phase 6: Wrap up and encourage
            phase("wrapup") {
                instructions {
                    val state = stateStore.current
                    """
                    End the session on a positive note.
                    Remind ${state.studentName} what they accomplished today!
                    Tell them what to practice before next time.
                    Say goodbye warmly. They should feel proud! 🌟
                    """.trimIndent()
                }
            }
        }
    }
}

// ── ViewModel ────────────────────────────────────────────────────────────────

class HomeTeachingViewModel(
    agentDef: KoogDefinition<TeachingState>,
) : ViewModel() {

    private val context = agentDef as KoogComposeContext<TeachingState>
    private val executor = agentDef.createExecutor()

    val session = PhaseSession(
        context = context,
        executor = executor,
        sessionId = "home_teaching",
        scope = viewModelScope,
    )

    val currentPhase = session.currentPhase
    val isRunning = session.isRunning
    val appState: StateFlow<TeachingState> = session.appState
        ?: context.stateStore?.stateFlow
        ?: kotlinx.coroutines.flow.MutableStateFlow(TeachingState()).asStateFlow()

    fun send(message: String) {
        viewModelScope.launch {
            session.send(message)
        }
    }
}

// ── Composable ───────────────────────────────────────────────────────────────

@Composable
fun HomeTeachingApp(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Build agent definition
    val agentDef = remember {
        val stateStore = KoogStateStore(TeachingState())
        buildTeachingContext(stateStore)
    }

    val viewModel: HomeTeachingViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeTeachingViewModel(agentDef) as T
            }
        }
    )

    val chatState = rememberChatState(
        handle = viewModel.session,
        context = viewModel.session.context,
    )
    val currentPhase by viewModel.currentPhase.collectAsState(initial = "")
    val state by viewModel.appState.collectAsState()

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val file = saveBitmapToCache(context, bitmap)
            chatState.addAttachment(
                Attachment.Image(
                    uri = file.absolutePath,
                    displayName = "Student's work",
                )
            )
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    HomeTeachingScreen(
        chatState = chatState,
        state = state,
        currentPhase = currentPhase,
        onCameraClick = {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                cameraLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onSend = { viewModel.send(it) },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
private fun HomeTeachingScreen(
    chatState: ChatState,
    state: TeachingState,
    currentPhase: String,
    onCameraClick: () -> Unit,
    onSend: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TeachingTopBar(state, currentPhase)
        },
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Type a question, show me homework, or say 'help'...",
                leadingActions = {
                    IconButton(onClick = onCameraClick) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "Photograph homework",
                        )
                    }
                },
            )
        },
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
}

@Composable
private fun TeachingTopBar(
    state: TeachingState,
    currentPhase: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Phase indicator
            Text(
                text = when (currentPhase) {
                    "greet" -> "👋 Getting to know you"
                    "assess" -> "📝 Checking your level"
                    "teach" -> "📚 Learning time"
                    "practice" -> "✏️ Practice time"
                    "review" -> "🌟 Review"
                    "wrapup" -> "🎉 Great session!"
                    else -> "📖 Home Tutor"
                },
                style = MaterialTheme.typography.titleMedium,
            )

            // Student info
            if (state.studentName.isNotBlank()) {
                Text(
                    text = "${state.studentName} • ${state.difficulty.name} • ${state.currentSubject}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar
            if (state.totalQuestions > 0) {
                val accuracy = state.correctAnswers.toFloat() / state.totalQuestions.toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    LinearProgressIndicator(
                        progress = { accuracy },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.correctAnswers}/${state.totalQuestions}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // Concepts learned
            if (state.conceptsCovered.isNotEmpty()) {
                Text(
                    text = "Learned: ${state.conceptsCovered.takeLast(3).joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewHomeTeachingApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Home Teaching Tutor", style = MaterialTheme.typography.headlineMedium)
                    Text("Powered by koog-compose + Ollama", style = MaterialTheme.typography.bodyMedium)
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}
