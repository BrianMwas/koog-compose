package io.github.koogcompose.sample

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkerParameters
import io.github.koogcompose.device.background.BackgroundSecureTool
import io.github.koogcompose.device.background.KoogCoroutineWorker
import io.github.koogcompose.device.background.WorkManagerJobProvider
import io.github.koogcompose.device.background.WorkManagerObserver
import io.github.koogcompose.device.location.GetCurrentLocationTool
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.event.KoogEventBus
import io.github.koogcompose.event.KoogEventHandler
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.confirmation.ConfirmationObserver
import io.github.koogcompose.ui.confirmation.rememberDialogConfirmationHandler
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Run Coach — the sample that backs the README's headline pitch: the agent
 * reads the device's GPS location and starts a background job (WorkManager)
 * that keeps running even if the app is backgrounded or closed. Reopen the
 * app later and the check-in status is still there, dispatched through
 * [KoogEventBus] while the app wasn't running.
 *
 * Unlike the other samples, this one is Android-only — it exercises
 * `koog-compose-device`'s GPS and WorkManager integration, which have no
 * cross-platform equivalent in this repo yet.
 */

// ── Run State ────────────────────────────────────────────────────────────────

@Serializable
data class RunState(
    val runnerName: String = "",
    val isRunning: Boolean = false,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val checkInStatus: String? = null,
    val isFinished: Boolean = false,
)

// ── Tools ────────────────────────────────────────────────────────────────────

/**
 * Persists the location [GetCurrentLocationTool] read into [RunState] and
 * marks the run as started. The LLM calls this after reading the raw
 * coordinates from [GetCurrentLocationTool]'s result.
 */
class RecordRunStartTool(
    override val stateStore: KoogStateStore<RunState>,
) : StatefulTool<RunState>() {
    override val name = "RecordRunStart"
    override val description =
        "Record the runner's name and starting GPS coordinates, marking the run as started"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull
        val latitude = args["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = args["longitude"]?.jsonPrimitive?.doubleOrNull

        stateStore.update { state ->
            state.copy(
                runnerName = name ?: state.runnerName,
                startLatitude = latitude ?: state.startLatitude,
                startLongitude = longitude ?: state.startLongitude,
                isRunning = true,
            )
        }
        return ToolResult.Success("Run started and start location recorded")
    }
}

/**
 * The tool that survives the app closing. Schedules [RunCheckInWorker] via
 * WorkManager — [BackgroundSecureTool.execute] enqueues it and attaches a
 * [io.github.koogcompose.device.background.WorkManagerObserver] that
 * dispatches [KoogEvent.BackgroundJobStatus] as the job's state changes,
 * independent of whether this Composable (or the app) is still alive.
 */
class StartCheckInTimerTool(
    context: Context,
    eventBus: KoogEventBus,
) : BackgroundSecureTool(
    context = context,
    jobProvider = WorkManagerJobProvider(context, RunCheckInWorker::class.java),
    eventBus = eventBus,
) {
    override val name = "StartCheckInTimer"
    override val description =
        "Schedule a check-in after the run has been going for a while. Runs in the " +
            "background via WorkManager, so it still fires even if the app is closed. " +
            "Args: delaySeconds (defaults to 30 for this demo; a real app would use minutes)."

    override fun buildInputData(args: Map<String, String>): Map<String, String> {
        val delaySeconds = args["delaySeconds"] ?: "30"
        return mapOf(WorkManagerJobProvider.DELAY_SECONDS_KEY to delaySeconds)
    }

    // Stable unique work name (not a random UUID) so the app can re-observe this
    // exact job after being force-closed and reopened — that's what lets the top
    // bar show "completed" for a job that finished while the process was dead.
    override fun resolveJobId(args: Map<String, String>): String = WORK_NAME

    companion object {
        const val WORK_NAME: String = "run_coach_check_in"
    }
}

class FinishRunTool(
    override val stateStore: KoogStateStore<RunState>,
) : StatefulTool<RunState>() {
    override val name = "FinishRun"
    override val description = "Mark the run as finished"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        stateStore.update { it.copy(isRunning = false, isFinished = true) }
        return ToolResult.Success("Run finished")
    }
}

/**
 * The background job WorkManager runs. It executes on WorkManager's own
 * schedule and process — a real app would compute distance/pace from stored
 * GPS waypoints here. This demo just confirms the job ran at all, which is
 * the point: it ran independent of the app's lifecycle.
 */
class RunCheckInWorker(
    appContext: Context,
    params: WorkerParameters,
) : KoogCoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}

// ── Agent Definition ─────────────────────────────────────────────────────────

fun buildRunCoachContext(
    stateStore: KoogStateStore<RunState>,
    context: Context,
    eventBus: KoogEventBus,
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
): KoogComposeContext<RunState> {
    val getLocation = GetCurrentLocationTool(context)
    val recordRunStart = RecordRunStartTool(stateStore)
    val startCheckIn = StartCheckInTimerTool(context, eventBus)
    val finishRun = FinishRunTool(stateStore)

    val result = koogCompose<RunState> {
        provider {
            use(providerConfig)
        }

        initialState { stateStore.current }

        phases {
            phase("ready", initial = true) {
                instructions {
                    """
                    You are a running coach. Ask the runner their name, then use
                    ${getLocation.ref} to read their current GPS location.
                    Once you have both, use ${recordRunStart.ref} to start the run.
                    Then move to the running phase.
                    """.trimIndent()
                }
                tool(getLocation)
                tool(recordRunStart)
            }

            phase("running") {
                instructions {
                    val state = stateStore.current
                    """
                    ${state.runnerName}'s run is in progress, starting near
                    (${state.startLatitude}, ${state.startLongitude}).
                    Use ${startCheckIn.ref} once to schedule a background check-in
                    (this keeps running even if the app is closed).
                    Chat with the runner and encourage them. When they say they're
                    done, use ${finishRun.ref} and move to the finished phase.
                    """.trimIndent()
                }
                tool(startCheckIn)
                tool(finishRun)
            }

            phase("finished") {
                instructions {
                    val state = stateStore.current
                    """
                    Congratulate ${state.runnerName} on finishing their run.
                    Last background check-in status: ${state.checkInStatus ?: "none yet"}.
                    """.trimIndent()
                }
            }
        }
    }

    return result as KoogComposeContext<RunState>
}

// ── UI ───────────────────────────────────────────────────────────────────────

private val locationPermissions = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
fun RunCoachApp(
    activityResults: KoogActivityResultRegistry,
    modifier: Modifier = Modifier,
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
) {
    val context = LocalContext.current
    val eventBus = remember { KoogEventBus() }

    val stateStore = remember { KoogStateStore(RunState()) }

    val eventScope = rememberCoroutineScope()
    DisposableEffect(eventBus, stateStore) {
        val handler = object : KoogEventHandler {
            override fun onEvent(event: KoogEvent) {
                if (event is KoogEvent.BackgroundJobStatus) {
                    // update is suspend; onEvent is not — hop onto a coroutine.
                    eventScope.launch {
                        stateStore.update { it.copy(checkInStatus = event.status) }
                    }
                }
            }
        }
        eventBus.register(handler)
        onDispose { eventBus.unregister(handler) }
    }

    // Re-attach to the check-in job by its stable work name. WorkManager persists
    // finished work, so if the job completed while the app process was dead, this
    // observer replays SUCCEEDED on reopen and the top bar shows "completed" —
    // proving the job ran independent of the app. Must come after the handler above
    // so the dispatched status is caught.
    DisposableEffect(eventBus) {
        val observer = WorkManagerObserver(
            context = context,
            jobId = StartCheckInTimerTool.WORK_NAME,
            eventBus = eventBus,
        )
        observer.attach()
        onDispose { observer.detach() }
    }

    var locationPermissionGranted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val results = activityResults.requestPermissions(locationPermissions)
        locationPermissionGranted = results.values.any { it }
    }

    val session = remember(providerConfig) {
        val context2 = buildRunCoachContext(
            stateStore = stateStore,
            context = context,
            eventBus = eventBus,
            providerConfig = providerConfig,
        )
        PhaseSession(
            context = context2,
            executor = context2.createExecutor(),
            sessionId = "run_coach_${Clock.System.now().toEpochMilliseconds()}",
        )
    }

    val chatState = rememberChatState(handle = session, context = session.context)
    val state by requireNotNull(session.appState).collectAsState()

    val confirmationHandler = rememberDialogConfirmationHandler()
    ConfirmationObserver(chatState = chatState, handler = confirmationHandler)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = { RunCoachTopBar(state, locationPermissionGranted) },
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Say you're ready to run...",
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
private fun RunCoachTopBar(state: RunState, locationPermissionGranted: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when {
                    state.isFinished -> "🏁 Run finished"
                    state.isRunning -> "🏃 Run in progress"
                    else -> "🏃 Run Coach"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!locationPermissionGranted) {
                Text(
                    text = "⚠️ Location permission not granted — the coach can still chat, " +
                        "but can't read your GPS position.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.startLatitude != null) {
                Text(
                    text = "Start: ${state.startLatitude}, ${state.startLongitude}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                Text(
                    text = "Background check-in: ${state.checkInStatus ?: "not scheduled"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
