# koog-compose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-purple.svg)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

**A Kotlin Multiplatform runtime for building on-device AI agents.**

koog-compose lets you write a single `koogCompose { }` block that manages your LLM conversation, runs device tools (GPS, alarms, screen blocking), handles multi-step flows, and streams tokens to your Compose UI — with no server required.

Built on [JetBrains Koog](https://github.com/JetBrains/koog).

---




## The core idea

Most AI integrations treat the LLM as a text box. koog-compose treats it as an orchestrator.

The user says *"I'm going for a run."* The agent starts a background GPS tracker, checks the weather, estimates duration from their history, and schedules a WorkManager task that survives the app being closed. When they say *"I'm back,"* it stops everything, calculates pace, and responds conversationally. No buttons. No forms. No app-switching. **The conversation is the UI.**

This works because koog-compose bridges two things that usually live in separate worlds: the LLM conversation loop and the device's native APIs. `KoogStateStore<S>` is the shared state that flows from a tool result straight into your Compose UI via `StateFlow`. Device tools (location, alarms, screen time, camera) are first-class citizens of the agent graph, not afterthoughts bolted onto a chat widget.

---

## Quick start

### 1. Install

```kotlin
dependencies {
    implementation("io.github.brianmwas.koog_compose:koog-compose-core:1.4.2")

    // Optional modules — add what you need
    implementation("io.github.brianmwas.koog_compose:koog-compose-ui:1.4.2")           // Material 3 chat components
    implementation("io.github.brianmwas.koog_compose:koog-compose-device:1.4.2")       // GPS, alarms, WorkManager (Android)
    implementation("io.github.brianmwas.koog_compose:koog-compose-mediapipe:1.4.2")    // On-device models (Gemma 4, Apple FMs)
    implementation("io.github.brianmwas.koog_compose:koog-compose-session-room:1.4.2") // Room-backed session persistence
    implementation("io.github.brianmwas.koog_compose:koog-compose-testing:1.4.2")      // Test utilities
}
```

### 2. Install the on-device bridge (Android only)

If you're using `provider { onDevice(...) }`, register the runtime bridge once at startup:

```kotlin
import io.github.koogcompose.provider.ondevice.installOnDeviceProviderSupport

fun initAi() {
    installOnDeviceProviderSupport()  // Application.onCreate() or main()
}
```

On iOS this happens automatically — `iOSApp.init()` installs the Apple Foundation Models bridge on launch.

### 3. Define your state

Everything flows through a single typed state object. Device tools write to it; your Compose UI reads from it.

```kotlin
@Serializable
data class RunState(
    val userName: String,
    val isRunning: Boolean = false,
    val distanceKm: Double = 0.0,
    val durationMs: Long = 0,
    val pace: String? = null,
)
```

### 4. Build the agent

```kotlin
val runCoach = koogCompose<RunState> {
    provider {
        onDevice(modelPath = "/data/models/gemma-4-E2B.litertlm") {
            onUnavailable { anthropic(apiKey = BuildConfig.KEY) }
        }
    }

    initialState { RunState(userName = "brian") }

    phases {
        phase("ready", initial = true) {
            instructions { "Ask the user if they're ready for their run." }
            tool(StartRunTimerTool(stateStore))
        }

        phase("running") {
            instructions { "The run is active. Check in if they go quiet for 15 minutes." }
            tool(BackgroundTimerTool())         // WorkManager — survives the app closing
            tool(LocationTrackerTool(stateStore))
        }

        phase("finished") {
            instructions { "Summarise their run: duration, distance, pace." }
            tool(StopTimerTool(stateStore))
            tool(CalculatePaceTool(stateStore))
        }
    }

    config {
        retry { maxAttempts = 3; initialDelayMs = 500L }
        stuckDetection { threshold = 3; fallbackMessage = "Let me try a different approach." }
    }
}
```

### 5. Wire it to a ViewModel

```kotlin
class RunViewModel(context: KoogComposeContext<RunState>, executor: PromptExecutor) : ViewModel() {
    val session = PhaseSession(
        context   = context,
        executor  = executor,
        sessionId = "run_brian",
        scope     = viewModelScope,
    )

    val responseStream = session.responseStream  // Flow<String> — tokens as they arrive
    val runState       = session.appState        // StateFlow<RunState>
}
```

### 6. Render it

```kotlin
@Composable
fun RunScreen(viewModel: RunViewModel = viewModel()) {
    val chatState = rememberChatState(viewModel.session)
    val runState  by viewModel.runState.collectAsState()

    if (runState.isRunning) {
        Text("Running — ${runState.distanceKm} km")
    }

    Scaffold(bottomBar = { ChatInputBar(chatState) }) { padding ->
        ChatMessageList(chatState, modifier = Modifier.padding(padding))
    }
}
```

---

## How it works

### Phases

A phase is a named stage in your conversation flow. Each one has its own system instructions and tool access. The LLM transitions between phases automatically — no manual routing code.

```
ready ──► running ──► finished ──► END
```

For more complex flows, phases can contain ordered **subphases** (sequential steps invisible to the router) and **parallel branches** (concurrent tool execution).

```kotlin
phase("finish_run") {
    subphase("stop_timer") {
        instructions { "Stop the run timer and record final duration." }
        tool(StopTimerTool(stateStore))
    }
    subphase("calculate_stats") {
        instructions { "Calculate distance and pace from the GPS trace." }
        tool(CalculatePaceTool(stateStore))
    }
    subphase("save_run") {
        instructions { "Save the run to storage." }
        tool(SaveRunTool(stateStore))
    }
    onCondition("run saved", "summary")
}
```

Branches inside `parallel { }` run concurrently using Koog's `nodeExecuteMultipleTools(parallelTools = true)`:

```kotlin
phase("gather_context", initial = true) {
    parallel {
        branch("weather")  { tool(WeatherTool(stateStore)) }
        branch("location") { tool(GeocoderTool(stateStore)) }
        branch("history")  { tool(RunHistoryTool(stateStore)) }
    }
    onCondition("context ready", "plan")
}
```

### Shared state

`KoogStateStore<S>` connects tools to your UI without globals or manual wiring:

```
Tool executes
  → stateStore.update { it.copy(distanceKm = 3.2) }
      → StateFlow<RunState> emits
          → Compose UI recomposes automatically
```

### Writing a tool

```kotlin
class LocationTrackerTool(
    override val stateStore: KoogStateStore<RunState>
) : StatefulTool<RunState>() {
    override val name            = "TrackLocation"
    override val description     = "Record GPS coordinates during the run"
    override val permissionLevel = PermissionLevel.SENSITIVE

    override suspend fun execute(args: JsonObject): ToolResult {
        val location = getCurrentLocation()
        stateStore.update {
            it.copy(gpsTrace = it.gpsTrace + location)
        }
        return ToolResult.Success("Recorded ${location.latitude}, ${location.longitude}")
    }
}
```

Every tool call goes through a pipeline before `execute()` is reached:

```
LLM args → validateArgs() → GuardrailEnforcer → confirmation UI → execute()
```

- **`validateArgs()`** — block malformed or unexpected args before they cause runtime errors
- **`GuardrailEnforcer`** — rate limits and action allowlists per tool
- **Confirmation UI** — `SAFE` runs silently; `SENSITIVE` shows a bottom sheet; `CRITICAL` shows a full-screen dialog

### Streaming

`responseStream` emits tokens as they arrive from the model. Reset accumulation on each new turn using `turnId`:

```kotlin
val displayText by remember {
    viewModel.turnId.flatMapLatest { _ ->
        viewModel.responseStream.runningFold("") { acc, token -> acc + token }
    }
}.collectAsState(initial = "")
```

### On-device models

koog-compose runs inference locally on the device — no API key, no network call.

```kotlin
provider {
    onDevice(modelPath = "/data/models/gemma-4-E2B.litertlm") {
        maxToolRounds(8)
        onUnavailable {
            // Falls back automatically if the model file is missing or device isn't eligible
            anthropic(apiKey = BuildConfig.KEY)
        }
    }
}
```

| Platform | Backend | Status |
|---|---|---|
| Android | LiteRT-LM with Gemma 4 (E2B / E4B) | ✅ |
| iOS | Apple Foundation Models (iOS 26+) | ✅ |
| Desktop | — | Planned |

On Android, koog-compose disables LiteRT-LM's automatic tool calling loop so Gemma 4's `<tool_call>` responses are routed through koog's own `SecureTool` pipeline — validation and guardrails stay active regardless of the model backend.

### Multi-agent handoff

Define specialist agents and the orchestrator delegates to them automatically:

```kotlin
val focusAgent = koogAgent("focus") {
    instructions { "You are a focus session specialist." }
    phases { phase("active") { /* ... */ } }
}

val session = koogSession<Unit> {
    provider { ollama(model = "llama3.2") }
    main {
        phases {
            phase("root", initial = true) {
                handoff(focusAgent) {
                    "User asks about focus, productivity, or pomodoro"
                }
            }
        }
    }
    agents(focusAgent)
}
```

### Resume from any external trigger

Jump to a specific phase from a push notification, deep link, or WorkManager callback:

```kotlin
// From a notification
session.resumeAt("notify_user", userMessage = "Your run is ready to view!")

// From a deep link — no user message, no history pollution
session.resumeAt("onboarding_flow")
```

### Reusable templates

Define common phase patterns once and include them anywhere:

```kotlin
val researchSubphase = subphaseTemplate("research") {
    instructions { "Search and summarise relevant information." }
    tool(WebSearchTool(stateStore))
}

phase("respond") {
    include(researchSubphase)    // adds the "research" subphase
    subphase("compose_answer") { /* ... */ }
}
```

---

## Testing

`koog-compose-testing` swaps the live provider for a scripted `FakePromptExecutor`. You test real phase transitions and tool dispatch without hitting a model.

```kotlin
@Test
fun `"I'm back" transitions from running to finished`() {
    val session = testPhaseSession(context) {
        on("I'm back", phase = "running") {
            transitionTo("finished")
            callTool("StopTimer")
            callTool("CalculateStats")
            respondWith("Great run! 3.2 km in 18 minutes — 5:38 pace.")
        }
    }

    session.send("I'm back")

    assertPhase(session, "finished")
    assertToolCalled(session, "StopTimer")
    assertState(session) { assertFalse(it.isRunning) }
}
```

Run tests without an emulator:

```bash
./gradlew :koog-compose-core:desktopTest
```

---

## Persistence

### Session memory

Drop in Room-backed persistence with one line:

```kotlin
val session = PhaseSession(
    context   = context,
    executor  = executor,
    sessionId = "run_brian",
    store     = RoomSessionStore(db.sessionDao()),
    scope     = viewModelScope,
)
```

Or implement `SessionStore` directly to use any backend (Redis, SQLite, custom).

### State migration

When your app state evolves, increment the schema version and define an upgrade path:

```kotlin
val migration = object : StateMigration<AppState> {
    override val schemaVersion = 2
    override suspend fun migrate(json: JsonObject, fromVersion: Int): JsonObject {
        return when (fromVersion) {
            1    -> json + ("themeMode" to JsonPrimitive("System"))
            else -> json
        }
    }
}
```

Added fields with defaults and removed fields are handled automatically by default (`ignoreUnknownKeys` + `coerceInputValues`). Only renamed or retyped fields need an explicit migration.

---

## Platform support

| Feature | Android | iOS | Desktop |
|---|---|---|---|
| Core DSL & phases | ✅ | ✅ | ✅ |
| Subphases & parallel branches | ✅ | ✅ | ✅ |
| Token streaming | ✅ | ✅ | ✅ |
| Multi-agent handoff | ✅ | ✅ | ✅ |
| On-device model (LiteRT-LM) | ✅ | — | — |
| On-device model (Apple FMs) | — | ✅ | — |
| Provider fallback routing | ✅ | ✅ | ✅ |
| Compose UI components | ✅ | ✅ | — |
| Room session store | ✅ | ✅ | — |
| Device tools & WorkManager | ✅ | — | — |

---

## Modules

| Module | What it contains |
|---|---|
| `koog-compose-core` | DSL, agent runtime, phase engine — **required** |
| `koog-compose-ui` | Material 3 chat UI components |
| `koog-compose-device` | Android device tools (GPS, alarms, WorkManager) |
| `koog-compose-mediapipe` | On-device model providers (LiteRT-LM, Apple FMs) |
| `koog-compose-testing` | Deterministic fake executor + test assertions |
| `koog-compose-session-room` | Room-backed session persistence |

---

## Privacy

All data stays on the device by default. koog-compose does not transmit prompts, responses, tool args, or telemetry anywhere. You own the `SessionStore`. Audit logs stay in-memory only, with optional PII redaction:

```kotlin
config {
    auditLog { redactArgs = true }
}
```

---

## Contributing

Bug reports and feature requests → [GitHub Issues](https://github.com/brianmwas/koog-compose/issues)
Questions → [GitHub Discussions](https://github.com/brianmwas/koog-compose/discussions)

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

---

## License

```
Copyright 2025-2026 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
