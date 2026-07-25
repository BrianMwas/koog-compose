# koog-compose — AI Agents for Compose Multiplatform

**Build on-device AI agents in Kotlin Multiplatform. One DSL for LLM conversations, device tools, multi-step flows, and streaming Compose UI — no server required.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-purple.svg)](https://kotlinlang.org)
[![Built on Koog](https://img.shields.io/badge/built%20on-JetBrains%20Koog-orange.svg)](https://github.com/JetBrains/koog)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

<!-- TODO: Record a 15s demo of the sample app (streaming tokens + confirmation sheet) and drop it here. This is the single highest-converting element of the README. -->
<!-- ![koog-compose demo](docs/assets/demo.gif) -->

koog-compose lets you write a single `koogCompose { }` block that manages your LLM conversation, runs device tools (GPS, alarms, screen blocking), handles multi-step phase flows, and streams tokens straight into your Jetpack Compose / Compose Multiplatform UI.

Built on [JetBrains Koog](https://github.com/JetBrains/koog), the official Kotlin framework for AI agents.

---

## Why koog-compose?

Most AI integrations treat the LLM as a text box. **koog-compose treats it as an orchestrator.**

The user says *"I'm going for a run."* The agent starts a background GPS tracker, checks the weather, estimates duration from their history, and schedules a WorkManager task that survives the app being closed. When they say *"I'm back,"* it stops everything, calculates pace, and responds conversationally.

No buttons. No forms. No app-switching. **The conversation is the UI.**

This works because koog-compose bridges two things that usually live in separate worlds: the LLM conversation loop and the device's native APIs. `KoogStateStore<S>` is shared state that flows from a tool result straight into your Compose UI via `StateFlow`. Device tools (location, alarms, screen time, camera) are first-class citizens of the agent graph — not afterthoughts bolted onto a chat widget.

|  | Raw Koog | Chat SDK widgets | **koog-compose** |
|---|---|---|---|
| Compose-native state (`StateFlow` → recomposition) | manual wiring | ❌ | ✅ built in |
| Device tools (GPS, alarms, WorkManager) | build yourself | ❌ | ✅ first-class |
| On-device inference (Gemma via LiteRT-LM, Apple FMs) | build yourself | ❌ | ✅ built in |
| Tool guardrails + confirmation UI | build yourself | ❌ | ✅ built in |
| Multi-step phase flows with auto-routing | graph DSL | ❌ | ✅ declarative DSL |
| Deterministic agent testing (no model calls) | partial | ❌ | ✅ testing DSL |

**Production-ready security:** every tool call enforces guardrails (rate limits, allowlists, confirmations) with full audit logging. Circuit breakers prevent cascading failures. Thread-safe parallel tool execution.

---

## Compatibility

| koog-compose | Koog | Kotlin | Compose Multiplatform |
|---|---|---|---|
| 2.1.0 (current) | <!-- TODO: verify --> 1.0.0 | 2.3.20 | <!-- TODO: fill --> |
| 0.1.x (legacy) | 0.x | 2.2.x | <!-- TODO: fill --> |

> Koog 1.0 removed all previously deprecated APIs. If you're upgrading from koog-compose 0.1.x, see the [migration notes](#) <!-- TODO: link CHANGELOG/migration guide -->.

---

## Table of contents

- [Run the samples first](#run-the-samples-first)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
  - [Phases](#phases)
  - [Shared state](#shared-state)
  - [Writing a tool](#writing-a-tool)
  - [Streaming](#streaming)
  - [Generative UI layout](#generative-ui-layout)
- [On-device models & privacy](#on-device-models--privacy)
- [Multi-agent handoff](#multi-agent-handoff)
- [Observability & event tracking](#observability--event-tracking)
- [Persistence](#persistence)
- [Session creation patterns](#session-creation-patterns)
- [Error handling & resilience](#error-handling--resilience)
- [Testing](#testing)
- [Platform support](#platform-support)
- [Modules](#modules)
- [FAQ](#faq)
- [Contributing](#contributing)

---

## Run the samples first

Two apps in this repo exercise the library. If you're cloning this to see what it does, start with **`sample-app`** — it's the real showcase and the fastest way to see the library working with your own credentials:

| Module | What it is | When to open it |
|---|---|---|
| **`sample-app`** | 6 samples (Getting Started, Home Tutor, Trip Planner, Multi-Agent, Robust Teaching, and — Android only — Run Coach) behind a provider-setup screen where you paste an OpenAI key, point at a local Ollama, or pick on-device Gemma 4 (Android). | **Start here.** This is what to run first. |
| **`composeApp`** | A ~100-line "hello world" — the smallest `koogCompose { }` block that runs. Also OpenAI, paste your key when it starts. | Once you understand the shape and want to see the minimal version stripped down. |

Run `sample-app` on Android, iOS, or desktop; on first launch it shows a provider screen — pick **OpenAI** and paste a key to try it immediately, no local setup required. Switch to **Ollama** later once you have it running locally (`ollama serve`), or **On-device Gemma 4** on Android once you've pushed a `.litertlm` file to the device (see [On-device models & privacy](#on-device-models--privacy)). The provider choice is remembered for the rest of that run and applies to whichever sample you open next — no per-sample setup.

**🏃 Run Coach (Android only)** is the sample that backs this README's opening pitch: the agent reads the device's GPS location (`GetCurrentLocationTool`, a confirmation-gated SENSITIVE tool) and schedules a background check-in via WorkManager (`StartCheckInTimerTool`) that keeps running even if you close the app. Grant location permission when prompted, say you're ready to run, confirm the GPS read, then ask the coach to schedule a check-in — close the app, wait ~30s, reopen it, and the "Background check-in" status in the top bar will already show `completed`, dispatched through `KoogEventBus` while the app wasn't running. Every other sample is a chat-plus-state app; this is the one that actually exercises `koog-compose-device`.

> **Gemma without any local setup:** if you have Ollama running, `ollama pull gemma3:4b` and pick Ollama with model `gemma3:4b` in the setup screen — no LiteRT-LM or `.litertlm` file needed. On-device Gemma 4 via LiteRT-LM (fully offline, Android only) is the deeper integration described below.

---

## Quick start

### 1. Install

```kotlin
dependencies {
    implementation("io.github.brianmwas.koog_compose:koog-compose-core:2.1.0")

    // Optional modules — add what you need
    implementation("io.github.brianmwas.koog_compose:koog-compose-ui:2.1.0")           // Material 3 chat components
    implementation("io.github.brianmwas.koog_compose:koog-compose-device:2.1.0")       // GPS, alarms, WorkManager (Android)
    implementation("io.github.brianmwas.koog_compose:koog-compose-mediapipe:2.1.0")    // On-device models (Gemma, Apple FMs)
    implementation("io.github.brianmwas.koog_compose:koog-compose-session-room:2.1.0") // Room-backed session persistence
    implementation("io.github.brianmwas.koog_compose:koog-compose-testing:2.1.0")      // Test utilities
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

### 5. Wire it up

**Pure Compose** (recommended for new code):

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

**ViewModel + Compose** (traditional):

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

That's it — a phase-driven AI agent with device tools, streaming into Compose.

---

## Core concepts

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

### Generative UI layout

The layout engine lets the agent drive *what UI is shown* through a small, declarative vocabulary instead of free-form code generation. The agent emits `AgentLayoutDirective`s — `ShowComponent`, `HideComponent`, `ReorderComponents`, `SwapComponent`, `LockComponent` — into named, host-declared **slots**. Each directive runs through a validation pipeline (`SchemaValidation → PolicyCheck → SlotConstraintCheck → Reduce`) before it touches the live `LayoutState` your Compose UI renders.

Every directive carries a `correlationId`, and the engine publishes a `DirectiveOutcome` the agent reads back on its next turn:

| Outcome | Meaning |
|---------|---------|
| `Accepted` | Applied as-is. |
| `Rewritten` | Modified by policy before applying (e.g. evict-then-show on a Single slot). |
| `Rejected` | Dropped; `rejectedAt` names the pipeline stage that refused it. |
| `Coalesced` | Deduplicated against an in-flight directive with the same `correlationId`. |

**`positionFallback`** — `ShowComponent` can request a relative `Position.Before(ref)` or `Position.After(ref)`. If the referenced component isn't in the slot, the engine silently appends to the end instead of rejecting the directive. When that happens, the `Accepted` (or `Rewritten`) outcome carries `positionFallback = true`, so the agent can detect that its requested ordering wasn't honored and correct course on the next turn:

```kotlin
processor.outcomes.collect { outcome ->
    if (outcome is DirectiveOutcome.Accepted && outcome.positionFallback) {
        // The Before/After reference was missing — component went to the end.
        // The agent can re-issue a ReorderComponents directive if ordering matters.
    }
}
```

---

## On-device models & privacy

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

> Want to try Gemma without downloading a `.litertlm` file? `ollama pull gemma3:4b` and use `provider { ollama(model = "gemma3:4b") }` instead — same model family, runs through Ollama's server rather than fully offline. The `onDevice(...)` path above is for the zero-network, fully-offline case.

**Data flow & privacy:**

| Scenario | What happens | Privacy |
|----------|---|---|
| On-device model available | All inference runs locally | ✅ 100% on-device, no internet |
| Model file missing | Falls back to `onUnavailable` block | ⚠️ Data sent to fallback provider (Anthropic, OpenAI, etc.) |
| User revokes permissions | Tool execution denied, conversation continues | ✅ On-device, no network |
| Tool calls device APIs (GPS, camera) | Local, permission-gated | ✅ On-device, gated by OS permissions |

**Important:** if you use `onUnavailable { anthropic(...) }` as a fallback, that provider will see the full conversation history (messages + tool results), tool names and arguments, and application context (phase name, session ID). If this is unacceptable, use `onUnavailable { throw UnsupportedOperationException(...) }` instead — users will see the error, but no data leaves the device.

| Platform | Backend | Scope |
|---|---|---|
| Android | LiteRT-LM with Gemma 4 (E2B / E4B) | ✅ On-device |
| iOS | Apple Foundation Models (iOS 26+) | ✅ On-device |
| Desktop | — | Planned |

On Android, koog-compose disables LiteRT-LM's automatic tool calling loop so Gemma's `<tool_call>` responses are routed through koog-compose's own `SecureTool` pipeline — validation and guardrails stay active regardless of the model backend.

koog-compose does not transmit prompts, responses, tool args, or telemetry anywhere. You own the `SessionStore`. Audit logs stay in-memory only, with optional PII redaction:

```kotlin
config {
    auditLog { redactArgs = true }
}
```

---

## Multi-agent handoff

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

---

## Observability & event tracking

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
| `CircuitBreakerOpened` | A `CircuitBreakerGuard` trips OPEN after repeated failures | Degraded-mode banners, dependency alerting |
| `CircuitBreakerClosed` | A tripped breaker recovers to CLOSED | Recovery tracking, clearing degraded UI |

Implement a custom sink by extending `EventSink`:

```kotlin
class FirebaseEventSink(private val analytics: FirebaseAnalytics) : EventSink {
    override suspend fun emit(event: AgentEvent) {
        val bundle = when (event) {
            is AgentEvent.ToolCalled -> Bundle().apply {
                putString("toolName", event.toolName)
            }
            is AgentEvent.PhaseTransitioned -> Bundle().apply {
                putString("from", event.from)
                putString("to", event.to)
            }
            else -> Bundle()
        }
        analytics.logEvent(event::class.simpleName ?: "AgentEvent", bundle)
    }
}
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

**Quick migrations** (no explicit handler needed): added fields with defaults and removed fields are handled automatically — use `ignoreUnknownKeys` + `coerceInputValues` in your serializer. **Explicit migrations** are only needed for renamed fields, retyped fields, or complex transformations.

---

## Session creation patterns

koog-compose is DSL-first. All three ways to create a session follow the same builder pattern:

**1. Compose (recommended for new code)** — `rememberPhaseSession()` binds to the Compose lifecycle, uses `lifecycleScope`, and memoizes across recompositions:

```kotlin
@Composable
fun MyScreen(definition: KoogDefinition<MyState> = koogCompose { ... }) {
    val session = rememberPhaseSession(definition) {
        sessionId = "my_screen_session"
    }
}
```

**2. Non-Compose (ViewModel, Services):**

```kotlin
class MyViewModel(context: KoogComposeContext<MyState>, executor: PromptExecutor) : ViewModel() {
    val session = phaseSession(context, executor) {
        sessionId = "my_session"
        scope = viewModelScope
        store = RoomSessionStore(db.sessionDao())  // optional
    }
}
```

**3. Bridge pattern (when you already have a definition):**

```kotlin
val definition = koogCompose<MyState> { ... }
val session = definition.createPhaseSession(executor, viewModelScope) {
    sessionId = "my_session"
}
```

All parameters are optional with sensible defaults: `sessionId = "default"`, `scope = Dispatchers.Default`, `store = InMemorySessionStore()`, `strategyName = "koog-compose-phases"`, `eventHandlers = EventHandlers.Empty`.

---

## Error handling & resilience

### Recovery hints

Tool failures carry metadata to guide the agent's recovery strategy:

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

| Hint | Use case |
|------|----------|
| `RetryAfterDelay` | Transient failures (network timeout, rate limit) |
| `RequiresUserAction` | User action needed (permission, confirmation) |
| `DegradedFallback` | Fall back to limited functionality instead of crashing |
| `None` | Permanent failure, don't retry |

### Circuit breaker

Prevent cascading failures when an external service keeps failing:

```kotlin
val breaker = CircuitBreaker(failureThreshold = 5, cooldownMs = 60_000)
val tool = CircuitBreakerGuard(
    delegate = SavePhotoTool(stateStore),
    circuitBreaker = breaker,
    sessionId = session.id,        // optional
    eventSink = myEventSink,       // optional — emits CircuitBreakerOpened/Closed
)
```

States: **CLOSED** (normal) → failures counted; **OPEN** (broken) → calls rejected immediately; **HALF_OPEN** (trial) → one success closes it, one failure reopens. The breaker counts thrown exceptions **and** `ToolResult.Failure` as failures; `ToolResult.Denied` (policy/user denials) are not counted.

### Session corruption recovery

```kotlin
val result = store.loadOrRecover(sessionId)
when (result) {
    is SessionLoadResult.Success   -> session = resumeSession(result.session)
    is SessionLoadResult.Recovered -> {
        showMessage(result.reason)  // "Session corrupted, starting fresh"
        session = startNewSession()
    }
    is SessionLoadResult.NotFound  -> { }
}
```

### Retry with backoff

```kotlin
config {
    retry {
        maxAttempts = 3
        initialDelayMs = 1_000
        backoffMultiplier = 2.0   // 1s → 2s → 4s
    }
}
```

### Error mapping for users

Never show raw exceptions to users — map internal errors to friendly messages inside your tools:

```kotlin
catch (e: IOException) {
    val userMessage = when {
        e.isNetworkRelated() -> "Internet connection problem — trying again..."
        e.isStorageFull()    -> "Your device is full — please free up space"
        else                 -> "Something went wrong — our team is aware"
    }
    ToolResult.Failure(userMessage, retryable = false)
}
```

---

## Testing

`koog-compose-testing` swaps the live provider for a scripted `FakePromptExecutor`. You test real phase transitions and tool dispatch without hitting a model — **deterministic, fast, no network, no flakiness**.

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

| Assertion | Purpose |
|-----------|---------|
| `assertPhase(session, "phase_name")` | Verify current phase |
| `assertToolCalled(session, "ToolName")` | Verify tool was invoked |
| `assertToolNotCalled(session, "ToolName")` | Verify tool was NOT invoked |
| `assertState(session) { block }` | Assert app state with lambda |
| `assertResponse(session, "text")` | Verify agent response contains text |

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

## FAQ

**How is this different from JetBrains Koog?**
Koog is the agent framework — the graph engine, LLM clients, and tool protocol. koog-compose is the mobile runtime on top: Compose-native state, device tools, on-device inference, guardrail/confirmation UI, phase DSL, and testing utilities. If you're building an agent into a Kotlin Multiplatform or Android app, you use both — koog-compose pulls Koog in for you.

**Does it work offline?**
Yes. With an on-device provider (Gemma via LiteRT-LM on Android, Apple Foundation Models on iOS), the entire agent — inference, tools, state — runs with no network at all.

**Can I use it with OpenAI / Anthropic / Ollama instead of on-device models?**
Yes. Any provider Koog supports works via the `provider { }` block, and you can chain fallbacks (`onDevice → cloud`).

**Do I have to use the chat UI components?**
No. `koog-compose-ui` is optional — the core exposes `StateFlow`/`Flow` primitives you can render with any Compose UI you like.

**Is it production-ready?**
The security pipeline (guardrails, confirmations, audit logging, circuit breakers) is designed for production use. The library is pre-1.0 in spirit — APIs may still evolve between minor versions; pin your version and read release notes.

---

## Contributing

Bug reports and feature requests → [GitHub Issues](https://github.com/BrianMwas/koog-compose/issues)
Questions → [GitHub Discussions](https://github.com/BrianMwas/koog-compose/discussions)

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR. Issues labeled `good first issue` are a great place to start.

If koog-compose is useful to you, **a ⭐ helps other Kotlin developers find it.**

---

## License

```
Copyright 2025-2026 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```