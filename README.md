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

### 5. Wire it to a ViewModel (or use Compose directly)

**Option A: ViewModel + Compose** (traditional)
```kotlin
class RunViewModel(context: KoogComposeContext<RunState>, executor: PromptExecutor) : ViewModel() {
    val session = phaseSession(context, executor) {
        sessionId = "run_brian"
        scope = viewModelScope
    }

    val responseStream = session.responseStream  // Flow<String> — tokens as they arrive
    val runState       = session.appState        // StateFlow<RunState>
}
```

**Option B: Pure Compose** (recommended for new code)
```kotlin
@Composable
fun RunScreen(definition: KoogDefinition<RunState> = koogCompose { ... }) {
    val session = rememberPhaseSession(definition) {
        sessionId = "run_brian"
    }

    val responseStream = session.responseStream
    val runState by session.appState.collectAsState()
}
```

### 6. Render it

**With ViewModel:**
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

**Pure Compose (no ViewModel):**
```kotlin
@Composable
fun RunScreen(definition: KoogDefinition<RunState> = koogCompose { ... }) {
    val session = rememberPhaseSession(definition) {
        sessionId = "run_brian"
    }
    val runState by session.appState.collectAsState()

    if (runState.isRunning) {
        Text("Running — ${runState.distanceKm} km")
    }

    Scaffold(bottomBar = { ChatInputBar(rememberChatState(session)) }) { padding ->
        ChatMessageList(rememberChatState(session), modifier = Modifier.padding(padding))
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
LLM args → validateArgs() → GuardrailEnforcer → [SENSITIVE/CRITICAL: confirmation UI] → execute()
                                                   ↓
                                         SAFE: skipped, runs silently
```

- **`validateArgs()`** — block malformed or unexpected args before they cause runtime errors
- **`GuardrailEnforcer`** — rate limits and action allowlists per tool
- **Confirmation UI** — conditional based on permission level:
  - `SAFE` runs silently (no UI)
  - `SENSITIVE` shows a bottom sheet (requires user review)
  - `CRITICAL` shows a full-screen dialog (high-friction confirmation)

### Streaming

`responseStream` emits tokens as they arrive from the model. Reset accumulation on each new turn using `turnId`:

```kotlin
val displayText by remember {
    viewModel.turnId.flatMapLatest { _ ->
        viewModel.responseStream.runningFold("") { acc, token -> acc + token }
    }
}.collectAsState(initial = "")
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

**Test assertions:**

| Assertion | Purpose |
|-----------|---------|
| `assertPhase(session, "phase_name")` | Verify current phase |
| `assertToolCalled(session, "ToolName")` | Verify tool was invoked |
| `assertToolNotCalled(session, "ToolName")` | Verify tool was NOT invoked |
| `assertState(session) { block }` | Assert app state with lambda |
| `assertResponse(session, "text")` | Verify agent response contains text |

This DSL approach makes tests **deterministic** and **fast** — no network calls, no model inference, no flakiness.

---

### On-device models & Privacy

koog-compose runs inference locally on the device by default — **no API key, no network call, all data stays on-device**.

```kotlin
provider {
    onDevice(modelPath = "/data/models/gemma-4-E2B.litertlm") {
        maxToolRounds(8)
        onUnavailable {
            // Fallback only if model is unavailable:
            // - File missing or corrupted
            // - Device hardware incompatible
            // ⚠️ This fallback sends data to Anthropic's servers
            anthropic(apiKey = BuildConfig.KEY)
        }
    }
}
```

**Data flow & privacy:**

| Scenario | What happens | Privacy |
|----------|---|---|
| On-device model available | All inference runs locally | ✅ 100% on-device, no internet |
| Model file missing | Falls back to `onUnavailable` block | ⚠️ Data sent to fallback provider (Anthropic, OpenAI, etc.) |
| User revokes permissions | Tool execution denied, conversation continues | ✅ On-device, no network |
| Tool calls device APIs (GPS, camera) | Local, permission-gated | ✅ On-device, gated by OS permissions |

**Important:** If you use `onUnavailable { anthropic(...) }` as a fallback, that provider will see:
- Full conversation history (messages + tool results)
- Tool names and arguments
- Application context (phase name, session ID)

If this is unacceptable, use `onUnavailable { throw UnsupportedOperationException(...) }` instead — users will see the error, but no data leaves the device.

| Platform | Backend | Scope |
|---|---|---|
| Android | LiteRT-LM with Gemma 4 (E2B / E4B) | ✅ On-device |
| iOS | Apple Foundation Models (iOS 26+) | ✅ On-device |
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

### Observability & event tracking

Route structured lifecycle events to Firebase, Datadog, a local database, or any custom backend. Events capture every significant moment: session starts, phase transitions, tool calls, guardrails denying access, stuck detection, and failures.

```kotlin
config {
    eventSink = PrintlnEventSink          // dev: logs to console
    // or
    eventSink = FirebaseEventSink()       // prod: Firebase Analytics
    // or
    eventSink = NoOpEventSink             // tests: silent
}
```

Events emitted at runtime:

| Event | When | Use case |
|---|---|---|
| `SessionStarted` | First user message | Session analytics, trace IDs |
| `PhaseTransitioned` | LLM routes to a new phase | Funnel analysis, flow tracing |
| `ToolCalled` | Tool executes successfully | Usage metrics, feature adoption |
| `GuardrailDenied` | Tool blocked by rate limit, allowlist, or user refusal | Security/compliance audit, UX friction |
| `AgentStuck` | LLM repeats the same phase N times | Loop detection, fallback messaging |
| `TurnFailed` | Retry exhausted after N attempts | Error rates, provider reliability |
| `LLMRequested` | (Reserved for future use) | — |

Implement a custom sink by extending `EventSink`:

```kotlin
class FirebaseEventSink(private val analytics: FirebaseAnalytics) : EventSink {
    override suspend fun emit(event: AgentEvent) {
        val bundle = when (event) {
            is AgentEvent.SessionStarted -> Bundle().apply {
                putString("initialPhase", event.initialPhase)
            }
            is AgentEvent.ToolCalled -> Bundle().apply {
                putString("toolName", event.toolName)
                putString("result", event.result.toString())
            }
            is AgentEvent.PhaseTransitioned -> Bundle().apply {
                putString("from", event.from)
                putString("to", event.to)
            }
            is AgentEvent.GuardrailDenied -> Bundle().apply {
                putString("reason", event.reason)
                putString("toolName", event.toolName)
            }
            is AgentEvent.AgentStuck -> Bundle().apply {
                putInt("consecutiveCount", event.consecutiveCount)
                putString("fallback", event.fallbackMessage)
            }
            is AgentEvent.TurnFailed -> Bundle().apply {
                putString("errorMessage", event.message)
                putString("phase", event.phase)
            }
            else -> Bundle()
        }
        analytics.logEvent(event::class.simpleName ?: "AgentEvent", bundle)
    }
}
```

Wire in development, production, and test builds separately:

```kotlin
// In your DI container or ViewModel factory
val eventSink = when {
    BuildConfig.DEBUG    -> PrintlnEventSink
    BuildConfig.FIREBASE -> FirebaseEventSink(analytics)
    else                 -> NoOpEventSink
}

config { eventSink = eventSink }
```

Events are emitted from within coroutines and the sink is safe to suspend — use `emit(event)` to write to databases, call remote APIs, or batch events without blocking the agent.

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

## Persistence

### Session creation (DSL-consistent)

Create sessions using the `phaseSession()` DSL builder for consistency with `koogCompose { }`:

```kotlin
// In ViewModel
class MyViewModel(context: KoogComposeContext<MyState>, executor: PromptExecutor) : ViewModel() {
    val session = phaseSession(context, executor) {
        sessionId = "my_session"
        scope = viewModelScope
        store = RoomSessionStore(db.sessionDao())
    }
}
```

Or in Compose:
```kotlin
@Composable
fun MyScreen(definition: KoogDefinition<MyState>) {
    val session = rememberPhaseSession(definition) {
        sessionId = "my_screen_session"
        store = RedisSessionStore()
    }
}
```

All parameters are optional; defaults are sensible:
- `sessionId` — defaults to `"default"`
- `scope` — defaults to `Dispatchers.Default`
- `store` — defaults to `InMemorySessionStore()`
- `strategyName` — defaults to `"koog-compose-phases"`
- `eventHandlers` — defaults to `EventHandlers.Empty`

### Session storage

Drop in Room-backed persistence by passing a custom `store`:

```kotlin
val session = phaseSession(context, executor) {
    sessionId = "run_brian"
    scope = viewModelScope
    store = RoomSessionStore(db.sessionDao())  // ← Room backend
}
```

Or implement `SessionStore` directly to use any backend (Redis, SQLite, custom).

### State migration

When your app state evolves, increment the schema version and define upgrade paths. Migrations are **chained** — if a user skips versions, all intermediate steps run automatically:

```kotlin
val migration = object : StateMigration<AppState> {
    override val schemaVersion = 3
    override suspend fun migrate(json: JsonObject, fromVersion: Int): JsonObject {
        return when (fromVersion) {
            // v1 → v2: add themeMode field
            1    -> json + ("themeMode" to JsonPrimitive("System"))
            // v2 → v3: rename "userName" → "userDisplayName"
            2    -> (json.toMutableMap() as MutableMap<String, JsonElement>).apply {
                val userName = remove("userName")
                if (userName != null) put("userDisplayName", userName)
            }.let { JsonObject(it) }
            else -> json
        }
    }
}
```

**How chaining works:**
- If stored version is 1 and current is 3: `v1 → v2` runs, then `v2 → v3` runs
- Each step transforms the JSON once
- All steps happen before the app sees the state

**Quick migrations** (no explicit handler needed):
- Added fields with defaults — handled automatically
- Removed fields — ignored automatically
- Use `ignoreUnknownKeys` + `coerceInputValues` in serializer

**Explicit migrations** only for:
- Renamed fields
- Retyped fields (e.g., `String` → `Int`)
- Complex transformations (e.g., splitting one field into many)

---

## Session Creation Patterns

koog-compose is DSL-first. All three ways to create a session follow the same builder pattern for consistency:

### 1. **Non-Compose (ViewModel, Services)**

```kotlin
class MyViewModel(context: KoogComposeContext<MyState>, executor: PromptExecutor) : ViewModel() {
    val session = phaseSession(context, executor) {
        sessionId = "my_session"
        scope = viewModelScope
        store = RoomSessionStore(db.sessionDao())  // optional
    }
}
```

All parameters except `context` and `executor` are optional.

### 2. **Compose (recommended for new code)**

```kotlin
@Composable
fun MyScreen(definition: KoogDefinition<MyState> = koogCompose { ... }) {
    val session = rememberPhaseSession(definition) {
        sessionId = "my_screen_session"
    }
}
```

`rememberPhaseSession()` automatically:
- Binds to the Compose lifecycle
- Uses `lifecycleScope` (no need to pass `scope`)
- Memoizes across recompositions

### 3. **Bridge Pattern (when you already have a definition)**

```kotlin
val definition = koogCompose<MyState> { ... }
val session = definition.createPhaseSession(executor, viewModelScope) {
    sessionId = "my_session"
}
```

All three patterns are equivalent; choose based on your UI framework.

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

## Error Handling & Resilience

koog-compose provides production-grade error recovery patterns to keep your agent running even when dependencies fail.

### Recovery Hints

Tool failures now carry metadata to guide the agent's recovery strategy:

```kotlin
class SavePhotoTool : StatefulTool<AppState>() {
    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            saveFile(args["path"]?.content ?: "")
            ToolResult.Success("Saved")
        } catch (e: IOException) when {
            e.isNetworkRelated() -> ToolResult.Failure(
                message = "Network hiccup. Retrying shortly...",
                retryable = true,                           // Agent can retry automatically
                recoveryHint = RecoveryHint.RetryAfterDelay // With backoff
            )
            e.isStorageFull() -> ToolResult.Denied(
                reason = "Storage full",
                recoveryHint = RecoveryHint.RequiresUserAction(
                    "Please free up space and say 'try again'"
                )
            )
            else -> ToolResult.Failure("Couldn't save", retryable = false)
        }
    }
}
```

Recovery hint types:

| Hint | Use Case |
|------|----------|
| `RetryAfterDelay` | Transient failures (network timeout, rate limit) |
| `RequiresUserAction` | User action needed (permission, confirmation) |
| `DegradedFallback` | Fall back to limited functionality instead of crashing |
| `None` | Permanent failure, don't retry |

### Circuit Breaker

Prevent cascading failures when an external service keeps failing:

```kotlin
val breaker = CircuitBreaker(failureThreshold = 5, cooldownMs = 60_000)
val tool = CircuitBreakerGuard(
    delegate = SavePhotoTool(stateStore),
    circuitBreaker = breaker
)

// After 5 failures: circuit opens, returns user-friendly message
// After 60s cooldown: circuit enters half-open (trial mode)
// On success: circuit closes, normal operation resumed
```

States:
- **CLOSED** (normal) → failures counted
- **OPEN** (broken) → calls rejected immediately
- **HALF_OPEN** (trial) → one success closes it, one failure reopens

### Session Corruption Recovery

Sessions corrupted by storage errors are detected and recovered:

```kotlin
val store = RoomSessionStore(dao, serializer)

// Load with automatic recovery
val result = store.loadOrRecover(sessionId)
when (result) {
    is SessionLoadResult.Success -> {
        session = resumeSession(result.session)
    }
    is SessionLoadResult.Recovered -> {
        showMessage(result.reason)  // "Session corrupted, starting fresh"
        session = startNewSession()
    }
    is SessionLoadResult.NotFound -> { }
}
```

### Retry with Backoff

Configure automatic retries in your session config:

```kotlin
config {
    retry {
        maxAttempts = 3
        initialDelayMs = 1_000
        backoffMultiplier = 2.0   // 1s → 2s → 4s
    }
}
```

### Error Mapping for Users

Never show raw exceptions to users. Map internal errors to friendly messages:

```kotlin
// Inside your tool
catch (e: IOException) {
    val userMessage = when {
        e.isNetworkRelated() -> "Internet connection problem — trying again..."
        e.isStorageFull() -> "Your device is full — please free up space"
        else -> "Something went wrong — our team is aware"
    }
    ToolResult.Failure(userMessage, retryable = false)
}
```

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
