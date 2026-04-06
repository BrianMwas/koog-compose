# koog-compose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple.svg)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

`koog-compose` is a developer-first Kotlin Multiplatform (KMP) runtime for building AI-driven features that orchestrate app logic, device capabilities, and UI from a single declarative DSL.

Built on top of [JetBrains Koog](https://github.com/JetBrains/koog), it bridges the gap between AI agent graphs and real app surfaces — giving you typed shared state, phase-aware conversations, plug-and-play persistence, and Material 3 UI components that work across Android, iOS, and Desktop.

---

## Why koog-compose?

| Without koog-compose | With koog-compose |
|---|---|
| Wire LLM calls, tool execution, and UI state manually | Single `koogCompose { }` DSL handles the entire runtime |
| Roll your own conversation state machine | Built-in `phases { }` with LLM-driven auto-transitions |
| Pass state between tools and UI via globals or hacks | Typed `KoogStateStore<S>` flows from tools straight to Compose UI |
| Build confirmation dialogs per feature | `AutoConfirmationHandler` with `SAFE` / `SENSITIVE` / `CRITICAL` tiers |
| Reinvent session persistence each project | Drop-in `session-room` module with your own Room DAO |

---

## Modules

```
io.github.brianmwas.koog_compose:koog-compose-core          ← DSL, agent runtime, phase engine   (required)
io.github.brianmwas.koog_compose:koog-compose-ui            ← Material 3 Compose components       (optional)
io.github.brianmwas.koog_compose:koog-compose-device        ← Android/iOS device tools            (optional)
io.github.brianmwas.koog_compose:koog-compose-testing       ← Deterministic fake executor + test DSL
io.github.brianmwas.koog_compose:koog-compose-session-room  ← Room-backed persistent memory       (optional)
```

---

## Installation

```kotlin
dependencies {
    implementation("io.github.brianmwas.koog_compose:koog-compose-core:0.1.0")
    implementation("io.github.brianmwas.koog_compose:koog-compose-ui:0.1.0")            // Compose UI components
    implementation("io.github.brianmwas.koog_compose:koog-compose-device:0.1.0")        // Android/iOS device tools
    implementation("io.github.brianmwas.koog_compose:koog-compose-session-room:0.1.0")  // Persistent memory via Room
}
```

> **Snapshots** — to use the latest unreleased build, add the Sonatype snapshots repository:
> ```kotlin
> maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
> ```
> Then use version `1.1.0-SNAPSHOT`.

---

## Quick start

### 1. Define your app state

koog-compose is generic over your app state type. Tools update it, your Compose UI observes it — no globals, no manual wiring.

```kotlin
data class AppState(
    val userId: String,
    val intent: Intent? = null,
    val location: Coordinates? = null
)
```

### 2. Build the context

```kotlin
val context = koogCompose<AppState> {
    provider {
        anthropic(apiKey = "your-key") {
            model = "claude-3-5-sonnet"
        }
    }

    // Typed initial state — S is inferred from here
    initialState { AppState(userId = currentUserId) }

    phases {
        phase("greeting") {
            instructions { "Greet the user and offer to check their location." }
        }
        phase("location_check") {
            instructions { "You now have access to the user's GPS coordinates." }
        }
        phase("confirm_location") {
            instructions { "Confirm the detected location with the user." }
        }
    }
}
```

Platform-specific tools (e.g. GPS) are injected at construction time via your DI container — not inside the DSL:

```kotlin
// Construct your tool with the typed store
val locationTool = GetCurrentLocationTool(
    stateStore = context.stateStore!!,   // KoogStateStore<AppState>
    locationClient = fusedLocationClient
)
```

### 3. Write a stateful tool

Extend `StatefulTool<S>` to read and mutate app state as a side effect of execution:

```kotlin
class GetCurrentLocationTool(
    override val stateStore: KoogStateStore<AppState>,
    private val locationClient: FusedLocationProviderClient
) : StatefulTool<AppState>() {

    override val name = "GetCurrentLocation"
    override val description = "Fetch the device GPS coordinates"
    override val permissionLevel = PermissionLevel.SENSITIVE  // triggers confirmation UI

    override suspend fun execute(args: JsonObject): ToolResult {
        val coords = locationClient.awaitLastLocation()
        stateStore.update { it.copy(location = coords) }
        return ToolResult.Success("Location acquired: $coords")
    }
}
```

### 4. Run it from a ViewModel

```kotlin
class ChatViewModel(
    context: KoogComposeContext<AppState>,
    executor: PromptExecutor
) : ViewModel() {

    val session = PhaseSession(
        context   = context,
        executor  = executor,
        sessionId = "user_brian",
        scope     = viewModelScope
    )

    // Agent/conversation concerns
    val isRunning    = session.isRunning       // StateFlow<Boolean>
    val lastResponse = session.lastResponse    // StateFlow<String?>
    val currentPhase = session.currentPhase    // StateFlow<String>

    // Domain/app state — updated by tools, observed by UI
    val appState     = session.appState        // StateFlow<AppState>?
}
```

### 5. Add the Compose UI

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val appState  by viewModel.appState.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    // appState drives your UI content (show location, intent resolved, etc.)
    // isRunning drives your loading indicator

    val chatState = rememberChatState(viewModel.session)
    val snackbarHostState = remember { SnackbarHostState() }

    // Handles SAFE / SENSITIVE / CRITICAL confirmation tiers automatically
    ConfirmationObserver(
        chatState = chatState,
        handler = rememberAutoConfirmationHandler(snackbarHostState)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { ChatInputBar(chatState) }
    ) { padding ->
        ChatMessageList(chatState, modifier = Modifier.padding(padding))
    }
}
```

### 6. Add persistent memory (optional)

```kotlin
val session = PhaseSession(
    context   = context,
    executor  = executor,
    sessionId = "user_brian",
    store     = RoomSessionStore(db.sessionDao()),  // swap InMemory for Room
    scope     = viewModelScope
)
```

---

## Core concepts

### Typed shared state

`KoogStateStore<S>` is the single source of truth for your domain state. Tools update it via `stateStore.update { }`, and your Compose UI observes it via `session.appState` — a `StateFlow<S>`.

```
Tool executes
  → stateStore.update { it.copy(location = coords) }
      → StateFlow<AppState> emits
          → Compose UI recomposes
```

### Phases

A `Phase` is a named state in your conversation graph. Each phase carries its own system instructions and tool access. The LLM transitions between phases automatically using generated transition tools — no manual routing code required.

```
greeting ──► location_check ──► confirm_location ──► END
```

Transitions are driven by the LLM reading the current `AppState` — you define the conditions, the agent decides when they're met.

### Security tiers

Every tool declares a `PermissionLevel`. `GuardrailEnforcer` intercepts all tool calls before execution and `AutoConfirmationHandler` maps tiers to the appropriate UI friction:

| Tier | UI treatment | Example |
|---|---|---|
| `SAFE` | Silent / Snackbar | Reading a preference |
| `SENSITIVE` | Bottom sheet confirmation | Sending a message, reading location |
| `CRITICAL` | Full-screen dialog | Deleting data, making a purchase |

Guardrails also enforce rate limits and action allowlists at the config level:

```kotlin
koogCompose<AppState> {
    // ...
    config {
        guardrails {
            rateLimit("GetCurrentLocation", max = 3, per = 1.minutes)
            allowIntent("android.intent.action.SEND")
            maxScheduledJobs(2)
        }
    }
}
```

### Session store

Implement `SessionStore` to plug in any persistence backend:

```kotlin
interface SessionStore {
    suspend fun load(sessionId: String): AgentSession?
    suspend fun save(sessionId: String, session: AgentSession)
    suspend fun delete(sessionId: String)
    suspend fun exists(sessionId: String): Boolean
}
```

The `:session-room` module provides a ready-made Room implementation.

### Testing

`koog-compose-testing` gives you a deterministic harness for chat and phase flows.
It keeps the real `ChatSession` tool/phase loop, but swaps the live provider for a scripted
`FakePromptExecutor`, so you can prove transitions, tool calls, confirmation behavior, and
shared-state mutation in unit tests without hitting a real model.

```kotlin
val session = testPhaseSession(context) {
    on("I need help with my location", phase = "greeting") {
        transitionTo("location_check")
        callTool("RecordLocationIntent")
        respondWith("Sure, fetching location now.")
    }
}

session.send("I need help with my location")

assertPhase(session, "location_check")
assertToolCalled(session, "RecordLocationIntent")
assertState(session) { state ->
    assertEquals(Intent.LOCATION_REQUEST, state.intent)
}
```

Use the simple form when a turn only needs text:

```kotlin
val session = testPhaseSession(context) {
    on("Hello") respondWith "Hi there."
}
```

You can also make confirmation behavior deterministic:

```kotlin
val session = testPhaseSession(
    context = context,
    confirmationHandler = AutoDenyConfirmationHandler,
) {
    on("Share my location", phase = "greeting") {
        transitionTo("location_check")
        callTool("RecordLocationIntent")
        respondWith("I could not access location.")
    }
}
```

### Stateless sessions

If you don't need shared state, omit `initialState { }` and use the `Unit` overload:

```kotlin
val context = koogCompose {
    provider { anthropic(apiKey = "...") { model = "claude-3-5-sonnet" } }
    phases { /* ... */ }
}
// context is KoogComposeContext<Unit>
```

---

## Platform support

| Feature | Android | iOS | Desktop (JVM) |
|---|---|---|---|
| Core DSL & phases | ✅ | ✅ | ✅ |
| Typed shared state | ✅ | ✅ | ✅ |
| Compose UI | ✅ | ✅ | ✅ |
| Room session store | ✅ | ✅ | — |
| Device tools (location) | ✅ | 🔜 v1.1 | — |
| WorkManager proactive agents | ✅ | — | — |

---

## Build & test

```bash
# Run common (KMP) tests
./gradlew :koog-compose-core:desktopTest

# Run Android instrumented tests
./gradlew :koog-compose-core:connectedAndroidTest

# Build the sample app
./gradlew :sample-app:assembleDebug

# Generate KDoc
./gradlew dokkaHtml
```

---

## Roadmap

### v1.1
- **iOS device parity** — `CLLocation` and `PHPicker` tool support
- **ActivityResult integration** — camera, file picker, permissions as agent tools
- **WorkManager proactive agents** — background context gathering

### v1.2
- **Backend telemetry sinks** — Firebase, remote tracing exporters
- **Screenshot context tool** — give the agent a view of the current screen
- **Voice slot** — LiveKit-compatible audio input/output in the UI module

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

- Bug reports and feature requests → [GitHub Issues](https://github.com/brianmwas/koog-compose/issues)
- Questions → [GitHub Discussions](https://github.com/brianmwas/koog-compose/discussions)

---

## License

```
Copyright 2025 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
