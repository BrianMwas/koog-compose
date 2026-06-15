package io.github.koogcompose.sample

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogDefinition
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File

// ── Android-only Teaching Tool ─────────────────────────────────────────────────

class SaveTopicPhotoTool(
    override val stateStore: KoogStateStore<TeachingState>,
    private val registry: KoogActivityResultRegistry,
    private val context: Context,
) : StatefulTool<TeachingState>() {
    override val name = "SaveTopicPhoto"
    override val description =
        "Open the camera to photograph homework, textbook pages, or written work. " +
        "The photo is saved and referenced in the session."
    override val permissionLevel = PermissionLevel.SENSITIVE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val uri = registry.capturePhoto()
            ?: return ToolResult.Denied("Photo capture was cancelled")

        val photoRef = savePhotoLocally(uri)
        stateStore.update { it.copy(lastTopicPhoto = photoRef) }
        return ToolResult.Success("Photo saved and attached to session")
    }

    private fun savePhotoLocally(uri: Uri): String {
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val destFile = File(photosDir, "homework_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        return destFile.absolutePath
    }
}

// ── Android wiring: shared agent + camera capture + Room persistence ──────────

fun buildTeachingContextWithPersistence(
    stateStore: KoogStateStore<TeachingState>,
    activityResults: KoogActivityResultRegistry,
    context: Context,
): KoogDefinition<TeachingState> = buildTeachingContext(
    stateStore = stateStore,
    extraTools = listOf(SaveTopicPhotoTool(stateStore, activityResults, context)),
)

// ── ViewModel (legacy, no persistence) ─────────────────────────────────────────

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

// ── Composables ──────────────────────────────────────────────────────────────

/**
 * Android entry point for the home-tutor sample: full feature set with
 * camera-based homework capture and Room-backed session persistence.
 *
 * See [SimpleHomeTeachingApp] in commonMain for the cross-platform,
 * in-memory variant used on iOS.
 */
@Composable
fun HomeTeachingApp(
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Optional: Show legacy mode without persistence (for quick demo)
    // Set this to true to show old behavior, false for new persistence mode
    val useLegacyMode = false

    if (useLegacyMode) {
        // Legacy mode: simple teaching app without session persistence
        HomeTeachingAppLegacy(
            modifier = modifier,
            snackbarHostState = snackbarHostState,
        )
    } else {
        // New mode: with session persistence and resume capability
        HomeTeachingAppWithPersistence(
            modifier = modifier,
            snackbarHostState = snackbarHostState,
        )
    }
}

@Composable
private fun CameraInputAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = "Photograph homework",
        )
    }
}

@Composable
private fun HomeTeachingAppLegacy(
    modifier: Modifier,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current

    // ActivityResult registry — registered once at Activity startup
    val activityResults = remember { KoogActivityResultRegistry(context) }

    // Build agent definition with registry
    val agentDef = remember {
        val stateStore = KoogStateStore(TeachingState())
        buildTeachingContextWithPersistence(stateStore, activityResults, context)
    }

    val viewModel: HomeTeachingViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
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

    HomeTeachingScreen(
        chatState = chatState,
        state = state,
        currentPhase = currentPhase,
        onSend = { viewModel.send(it) },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        leadingInputActions = { CameraInputAction(onClick = {}) },
    )
}

@Composable
private fun HomeTeachingAppWithPersistence(
    modifier: Modifier,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val persistenceManager = remember { SessionPersistenceManager(context) }

    // ActivityResult registry — registered once at Activity startup
    val activityResults = remember { KoogActivityResultRegistry(context) }

    val agentDef = remember {
        val stateStore = KoogStateStore(TeachingState())
        buildTeachingContextWithPersistence(stateStore, activityResults, context)
    }

    val applicationContext = context.applicationContext

    val viewModel: HomeTeachingViewModelWithPersistence = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeTeachingViewModelWithPersistence(agentDef, persistenceManager, applicationContext) as T
            }
        }
    )

    val savedSessions by viewModel.savedSessions.collectAsState()
    val session by viewModel.session.collectAsState()

    if (session == null) {
        SessionListScreen(
            sessions = savedSessions,
            onSessionSelected = { sessionId ->
                viewModel.resumeSession(sessionId)
            },
            onSessionDelete = { sessionId ->
                viewModel.deleteSession(sessionId)
            },
            onNewSession = {
                viewModel.startNewSession()
            },
            onBackClick = {},
            modifier = modifier,
        )
    } else {
        val currentSession = session
        if (currentSession != null) {
            val chatState = rememberChatState(
                handle = currentSession,
                context = currentSession.context,
            )
            val currentPhase by viewModel.currentPhase.collectAsState(initial = "")
            val state by viewModel.appState.collectAsState()

            HomeTeachingScreen(
                chatState = chatState,
                state = state,
                currentPhase = currentPhase,
                onSend = { viewModel.send(it) },
                snackbarHostState = snackbarHostState,
                modifier = modifier,
                leadingInputActions = { CameraInputAction(onClick = {}) },
            )
        }
    }
}
