# koog-compose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-purple.svg)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

`koog-compose` is a developer-first Kotlin Multiplatform (KMP) runtime for building AI-driven features that orchestrate app logic, device capabilities, and UI from a single declarative DSL.

Built on top of [JetBrains Koog](https://github.com/JetBrains/koog), it bridges the gap between AI agent graphs and real app surfaces — giving you typed shared state, phase-aware conversations, multi-agent handoff, plug-and-play persistence, token-level streaming, and Material 3 UI components that work across Android, iOS, and Desktop.

---

## Why koog-compose?

| Without koog-compose | With koog-compose |
|---|---|
| Wire LLM calls, tool execution, and UI state manually | Single `koogCompose { }` DSL handles the entire runtime |
| Roll your own conversation state machine | Built-in `phases { }` with LLM-driven auto-transitions |
| Pass state between tools and UI via globals or hacks | Typed `KoogStateStore<S>` flows from tools straight to Compose UI |
| Build confirmation dialogs per feature | `AutoConfirmationHandler` with `SAFE` / `SENSITIVE` / `CRITICAL` tiers |
| Reinvent session persistence each project | Drop-in `session-room` module with your own Room DAO |
| Blank UI bubble while LLM thinks | `responseStream: Flow<String>` emits tokens as they arrive |
| Raw exceptions surface to UI on failure | Retry with backoff + stuck detection + graceful fallback messages |
| LLM hallucinated args crash your tool silently | `validateArgs()` blocks bad calls before execution |
| Multi-agent routing is manual plumbing | `handoff(agentRef)` — one line to delegate to a specialist |

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
    implementation("io.github.brianmwas.koog_compose:koog-compose-core:0.3.2")
    implementation("io.github.brianmwas.koog_compose:koog-compose-ui:0.3.2")            // Compose UI components
    implementation("io.github.brianmwas.koog_compose:koog-compose-device:0.3.2")        // Android/iOS device tools
    implementation("io.github.brianmwas.koog_compose:koog-compose-session-room:0.3.2")  // Persistent memory via Room
}
```

> **Snapshots** — to use the latest unreleased build, add the Sonatype snapshots repository:
> ```kotlin
> maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
> ```
> Then use version `0.3.2-SNAPSHOT`.

---

## Quick start

### 1. Define your app state

koog-compose is generic over your app state type. Tools update it, your Compose UI observeses it — no globals, no manual wiring.

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

    config {
        retry {
            maxAttempts = 3
            initialDelayMs = 500L
        }
        stuckDetection {
            threshold = 3
            fallbackMessage = "I'm having trouble with that. Let me connect you to support."
        }
    }

    events {
        onAgentStuck { event ->
            // navigate to support, log analytics, etc.
        }
        onTurnFailed { event ->
            // show error UI
        }
        onToolExecutionCompleted { event ->
            // partial success — event.result has what succeeded before any failure
        }
    }
}
```

### 3. Write a stateful tool

Extend `StatefulTool<S>` to read and mutate app state as a side effect of execution:

```kotlin
class SendMoneyTool(
    override val stateStore: KoogStateStore<AppState>
) : StatefulTool<AppState>() {

    override val name = "SendMoney"
    override val description = "Send money to a recipient"
    override val permissionLevel = PermissionLevel.CRITICAL

    // Optional — validate LLM-supplied args before anything else runs
    override fun validateArgs(args: JsonObject): ValidationResult {
        val amount = args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ValidationResult.Invalid("missing or non-numeric field: amount")
        if (amount <= 0) return ValidationResult.Invalid("amount must be greater than 0")

        args["recipientId"]
            ?: return ValidationResult.Invalid("missing required field: recipientId")

        return ValidationResult.Valid
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        // args are guaranteed valid here
        val amount = args["amount"]!!.toString().toDouble()
        val recipientId = args["recipientId"]!!.toString()
        return ToolResult.Success("Sent $amount to $recipientId")
    }
}
```

`validateArgs()` is optional — the default accepts all args, so existing tools need no
changes. When validation fails, `GuardedTool` returns a `ToolResult.Failure` with your
reason before guardrails, confirmation, or `execute()` are ever reached. The LLM sees
the failure and can correct its args on the next iteration.

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

    val isRunning      = session.isRunning       // StateFlow<Boolean>
    val lastResponse   = session.lastResponse    // StateFlow<String?>
    val currentPhase   = session.currentPhase    // StateFlow<String>
    val responseStream = session.responseStream  // Flow<String> — token by token
    val turnId         = session.turnId          // StateFlow<Int> — increments per send()
    val appState       = session.appState        // StateFlow<AppState>?
    val error          = session.error           // StateFlow<Throwable?>
    val toolCallCounts = session.toolCallCounts  // StateFlow<Map<String, Int>>
}
```

### 5. Streaming tokens in Compose

`responseStream` emits tokens as they arrive from the LLM — wired directly into
Koog's pipeline feature system via `StreamingFeature`. Use `turnId` to reset
accumulation on each new `send()` call:

```kotlin
@Composable
fun StreamingMessage(viewModel: ChatViewModel) {
    val displayText by remember {
        viewModel.turnId.flatMapLatest { _ ->
            viewModel.responseStream
                .runningFold("") { acc, token -> acc + token }
        }
    }.collectAsState(initial = "")

    Text(text = displayText)
}
```

If you only need the final assembled response:

```kotlin
val fullResponse by viewModel.lastResponse.collectAsState()
```

### 6. Add the Compose UI

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val appState  by viewModel.appState.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

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

### 7. Add persistent memory (optional)

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

## How it works

koog-compose is built as a thin, opinionated layer over [JetBrains Koog](https://github.com/JetBrains/koog). Every `koogCompose { }` block produces a live `AIAgent` with features installed:

| Feature | Purpose |
|---|---|
| **ChatMemory** | Owns LLM conversation history. Loads from your `SessionStore` before each turn, saves after. Replaces manual `messageHistory` lists. |
| **StreamingFeature** | Intercepts `TextDelta` frames from Koog's LLM pipeline and emits them as `Flow<String>` for real-time UI updates. |
| **EventHandler** | Maps Koog's native lifecycle callbacks (`onToolCallStarting`, `onAgentCompleted`, etc.) into koog-compose's typed `KoogEvent` sealed hierarchy. |
| **Persistence** (opt-in) | Captures complete agent state (history, current node, input data, timestamps) at each execution point. Supports rollback. Disabled by default — pass `persistenceStorage` to enable. |

Phase subgraphs are built from your `phases { }` DSL into Koog's `AIAgentGraphStrategy`. Each phase becomes a subgraph with `nodeLLMRequest` → `nodeExecuteTool` → `nodeLLMSendToolResult` nodes, with optional `nodeLLMCompressHistory` between them. Phase transitions are captured as edges — the LLM triggers them by calling generated transition tools.

```
koogCompose { } DSL
       │
       ▼
┌─────────────────────────┐
│  KoogComposeContext     │  ← provider, phases, tools, config, events
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  PhaseAwareAgent        │  ← builds AIAgent with 3 features installed
│  • ChatMemory           │     (ChatHistoryProvider → your SessionStore)
│  • StreamingFeature     │     (TextDelta → responseStream Flow)
│  • EventHandler         │     (Koog callbacks → KoogEvent dispatch)
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  PhaseStrategyBuilder   │  ← phases → subgraphs with compression nodes
└────────┬────────────────┘
         │
         ▼
   Koog AIAgent runs ──► LLM calls, tool execution, phase transitions
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

### Tool references in instructions

Use `[ToolName]` syntax in phase instructions to inject full tool schemas into the system prompt. The LLM gets precise knowledge of what tools it has and how to call them, without you repeating tool docs in every instruction block.

```kotlin
phase("payment") {
    instructions {
        """
        Help the user send money.
        Use [GetBalance] to check funds before sending.
        Use [SendMoney] to execute the transfer.
        Always confirm before calling [SendMoney].
        """.trimIndent()
    }
}
```

`[GetBalance]` is expanded to:
```
`get_balance` [SAFE]: Retrieves the current account balance for the authenticated user.
  Parameters:
    - account_id (String, required): The account to query.
    - currency (String, optional): ISO currency code. Defaults to KES.
```

Matching is flexible — `[GetBalance]`, `[get_balance]`, and `[GetBalanceTool]` all resolve to the same tool. Unresolved references are left in-place with a `⚠ not registered` warning so you catch mismatches at dev time.

### Arg validation

`SecureTool` exposes an optional `validateArgs(args: JsonObject): ValidationResult` hook.
`GuardedTool` runs it as step 0 — before rate limit checks, before confirmation dialogs,
before `execute()`. A `ValidationResult.Invalid` response short-circuits the entire call
and returns a `ToolResult.Failure` with your reason. The LLM sees the failure and can
correct its args on the next iteration.

```
LLM delivers args
  → validateArgs()          ← your field/type checks        (step 0)
      → GuardrailEnforcer   ← rate limits, allowlists       (step 1)
          → confirmation UI ← SENSITIVE / CRITICAL          (step 2)
              → execute()   ← guaranteed valid args         (step 3)
```

Existing tools that don't override `validateArgs()` are unaffected — the default returns
`ValidationResult.Valid`.

### Structured outputs

Phases can declare a typed output schema. The LLM is primed with the JSON schema + examples, and the response is parsed into your Kotlin data class automatically.

```kotlin
@Serializable
data class ExtractedIntent(
    @LLMDescription("The user's primary intent")
    val intent: String,

    @LLMDescription("Confidence score between 0.0 and 1.0")
    val confidence: Double,
)

phase("intent_extraction") {
    instructions { "Analyze the user's message and extract intent." }
    typedOutput<ExtractedIntent>(
        retries = 3,
        version = 1,
        examples = listOf(
            ExtractedIntent(intent = "check_balance", confidence = 0.92)
        ),
        validate = { result ->
            if (result.confidence < 0.5) {
                ValidationResult.Invalid("confidence too low: ${result.confidence}")
            } else {
                ValidationResult.Valid
            }
        }
    )
}
```

Features:
- **Auto schema generation** — JSON Schema generated from `@LLMDescription` annotations
- **Retry with self-correction** — validation errors fed back to LLM so it can fix its output
- **Schema versioning** — `version` parameter for evolving output types and analytics tracking
- **Markdown fence stripping** — handles ```json fences gracefully

One-shot extraction outside phases is also available via `runner.extract<T>()`.

### Streaming

`PhaseSession` exposes `responseStream: Flow<String>` that emits tokens as they arrive
from the LLM. The tap is implemented as a Koog pipeline feature (`StreamingFeature`) that
intercepts `TextDelta` frames from `ContextualPromptExecutor` — no polling, no executor
wrapping. `lastResponse` always holds the full assembled string on completion. `turnId`
increments on every `send()` so collectors know when to reset accumulation.

### Resilience

`PhaseSession.send()` has two layers of protection against failures:

**Retry with backoff** — driven by `RetryPolicy` in `KoogConfig`. On each failed attempt
the agent is rebuilt to avoid reusing a corrupted session. After all attempts are exhausted,
`KoogEvent.TurnFailed` is dispatched and `error` is set.

**Stuck detection** — tracks consecutive identical phase + input pairs. When the threshold
is hit, `KoogEvent.AgentStuck` is dispatched, the fallback message is surfaced as
`lastResponse`, and the stuck state resets — no raw error, no halt.

```kotlin
config {
    retry {
        maxAttempts = 3
        initialDelayMs = 500L  // doubles on each attempt
        useStructureFixingParser = true
        structureFixingRetries = 3
    }
    stuckDetection {
        threshold = 3
        fallbackMessage = "I'm having trouble with that. Let me connect you to support."
    }
}
```

### Security tiers

Every tool declares a `PermissionLevel`. `GuardrailEnforcer` intercepts all tool calls
before execution and `AutoConfirmationHandler` maps tiers to the appropriate UI friction:

| Tier | UI treatment | Example |
|---|---|---|
| `SAFE` | Silent / Snackbar | Reading a preference |
| `SENSITIVE` | Bottom sheet confirmation | Sending a message, reading location |
| `CRITICAL` | Full-screen dialog | Deleting data, making a purchase |

Guardrails also enforce rate limits and action allowlists at the config level:

```kotlin
config {
    guardrails {
        rateLimit("GetCurrentLocation", max = 3, per = 1.minutes)
        allowIntent("android.intent.action.SEND")
        maxScheduledJobs(2)
    }
}
```

### Multi-agent handoff

Define specialist agents and register handoff tools in your main agent's phase. The LLM reads the handoff descriptions and calls them when appropriate — `SessionRunner` intercepts the call and swaps the active agent.

```kotlin
val focusAgent = koogAgent("focus") {
    instructions { "You are a focus session specialist. Suggest pomodoro techniques and encourage deep work." }
    phases {
        phase("active") {
            instructions { "Help the user set up a focus session." }
        }
    }
}

val weatherAgent = koogAgent("weather") {
    instructions { "You are a weather specialist. Provide friendly, brief forecasts." }
    phases {
        phase("active") {
            instructions { "Give the user a brief weather update." }
        }
    }
}

val session = koogSession<Unit> {
    provider { ollama(model = "llama3.2") }

    main {
        instructions {
            """
            You are a general assistant. Route to specialists when:
            - Focus or productivity → handoff to focus agent
            - Weather or forecasts → handoff to weather agent
            """.trimIndent()
        }
        phases {
            phase("root", initial = true) {
                handoff(focusAgent) {
                    "User asks about focus, productivity, pomodoro, or concentration"
                }
                handoff(weatherAgent) {
                    "User asks about weather or wants a forecast"
                }
            }
        }
    }

    agents(focusAgent, weatherAgent)
}
```

Handoff options:
- **`description`** — natural language condition the LLM reads to decide when to call
- **`continueHistory`** — if `true` (default), the specialist sees the full conversation; if `false`, it starts fresh
- **`onHandoff`** — callback to mutate shared state before the swap

### Tool call tracking

Every tool call is tracked per session. Expose `toolCallCounts: StateFlow<Map<String, Int>>` from your session handle for analytics, usage quotas, and loop detection.

```kotlin
val counts by handle.toolCallCounts.collectAsState()
println("get_balance called: ${counts["get_balance"] ?: 0} times")
```

Counts persist in `AgentSession` and reset on `session.reset()`.

### Privacy & data ownership

koog-compose is designed so that **all data stays on the user's device by default**. Nothing is transmitted to external servers unless you explicitly wire it up.

**What gets stored and where:**

| Data | Where it goes | User control |
|---|---|---|
| **Conversation history** | `SessionStore` — user picks `InMemory`, `Room`, `Redis`, or custom | Full ownership. Can `delete(sessionId)` or `reset()` anytime |
| **Tool audit log** | In-memory `SharedFlow` only — never leaves device unless you subscribe and forward externally | `AuditLogger(redactArgs = true)` replaces raw args with `[REDACTED]` for PII-sensitive apps |
| **Tool call counts** | In-memory `StateFlow` — per-session only | Resets on `reset()`. Not persisted to disk |
| **Agent checkpoints (persistence)** | File-based, on user's device | **Opt-in** — disabled by default. Pass `persistenceStorage` to enable |
| **Events** | Dispatched via `EventHandlers` — you decide what to wire | You own the handler registration |

**What koog-compose does NOT do:**

- ❌ No network telemetry
- ❌ No analytics sent to external servers
- ❌ No prompts, responses, or tool args transmitted
- ❌ No crash reporting

**Audit log redaction for PII:**

If your app handles sensitive data (phone numbers, addresses, auth tokens), enable args redaction:

```kotlin
val auditLogger = AuditLogger(redactArgs = true)
```

Tool names, outcomes, and timestamps are still logged so production monitoring works — just not the raw arguments. `AuditEntry.isRedacted` lets consumers detect redacted entries.

### Persistence (opt-in)

Agent checkpoints capture the complete state — message history, current graph node, input data, and timestamps — at each execution point. This is **disabled by default**. Enable it by passing a `FilePersistenceStorageProvider`:

```kotlin
val agent = PhaseAwareAgent.create(
    context = ctx,
    promptExecutor = executor,
    sessionId = "session-1",
    store = InMemorySessionStore(),
    persistenceStorage = myFilePersistenceProvider,  // opt-in
)
```

When enabled, all checkpoint data stays on the user's device. Nothing is transmitted externally. To disable persistence entirely, simply omit `persistenceStorage`.

### Session store

Implement `SessionStore` to plug in any persistence backend:

```kotlin
interface SessionStore {
    suspend fun load(sessionId: String): AgentSession?
    suspend fun save(sessionId: String, session: AgentSession)
    suspend fun delete(sessionId: String)
    suspend fun exists(sessionId: String): Boolean
}

data class AgentSession(
    val sessionId: String,
    val currentPhaseName: String,
    val messageHistory: List<SessionMessage>,
    val serializedState: String? = null,
    val contextVars: Map<String, String> = emptyMap(),
    val toolCallCounts: Map<String, Int> = emptyMap(),  // ← tracked per session
    val createdAt: Long,
    val updatedAt: Long
)
```

The `:session-room` module provides a ready-made Room implementation.

### Events

Observe runtime events via the `events { }` DSL block:

```kotlin
events {
    onTurnStarted { event -> }
    onTurnCompleted { event -> }
    onTurnFailed { event -> }
    onPhaseTransitioned { event -> }
    onToolCallRequested { event -> }
    onToolExecutionCompleted { event -> }  // partial success visibility
    onAgentStuck { event -> }              // stuck detection fired
    onProviderChunkReceived { event -> }
}
```

### Testing

`koog-compose-testing` gives you a deterministic harness for chat and phase flows.
It keeps the real `PhaseSession` tool/phase loop but swaps the live provider for a
scripted `FakePromptExecutor`, so you can prove transitions, tool calls, confirmation
behavior, and shared-state mutation in unit tests without hitting a real model.

#### Add the test dependency

```kotlin
// In your module's build.gradle.kts
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":koog-compose-testing"))
}
```

#### Write your first test

```kotlin
import io.github.koogcompose.testing.testPhaseSession
import io.github.koogcompose.testing.assertPhase
import io.github.koogcompose.testing.assertToolCalled
import io.github.koogcompose.testing.assertState
import kotlin.test.Test
import kotlin.test.assertEquals

class MyFeatureTest {

    @Test
    fun `location request transitions to location_check phase`() {
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
    }
}
```

#### Simple text-only turns

When a turn doesn't involve tools or transitions:

```kotlin
val session = testPhaseSession(context) {
    on("Hello") respondWith "Hi there."
}

session.send("Hello")
```

#### Test denial flows

Make confirmation behavior deterministic with `AutoDenyConfirmationHandler`:

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

session.send("Share my location")
assertGuardrailDenied(session, "RecordLocationIntent")
```

#### Test structured output

Verify that typed phase outputs parse correctly:

```kotlin
@Serializable
data class ExtractedIntent(val name: String, val confidence: Double)

@Test
fun `extracts intent from user message`() {
    val context = testContext {
        phases {
            phase("extract") {
                instructions { "Extract the user's intent as JSON." }
                typedOutput<ExtractedIntent>(
                    validate = {
                        if (it.confidence < 0.5)
                            ValidationResult.Invalid("confidence too low")
                        else ValidationResult.Valid
                    }
                )
            }
        }
    }

    val session = testPhaseSession(context) {
        on("I want to check my balance") respondWith
            """{"name": "check_balance", "confidence": 0.92}"""
    }

    session.send("I want to check my balance")
    // Validation passes — no exception thrown
}
```

#### Test audit logging and tool counts

```kotlin
@Test
fun `audit log tracks approved and denied calls`() {
    val session = testPhaseSession(context) {
        on("Send money", phase = "root") {
            callTool("SendMoney")
            respondWith("Done.")
        }
    }

    session.send("Send money")

    assertEquals(1, session.auditLogger.approvedCount)
    assertEquals(0, session.auditLogger.deniedCount)
}
```

#### Available test assertions

| Assertion | Purpose |
|---|---|
| `assertPhase(session, "phaseName")` | Verify the session is in the expected phase |
| `assertToolCalled(session, "toolName")` | Verify a specific tool was called |
| `assertGuardrailDenied(session, "toolName")` | Verify a tool call was denied by guardrails |
| `assertState(session) { state -> ... }` | Assert against the typed app state |
| `assertEventDispatched(session) { event -> ... }` | Verify a specific KoogEvent was fired |

#### Run tests

```bash
# Run all desktop tests (fastest — no Android emulator needed)
./gradlew :koog-compose-core:desktopTest

# Run a single test class
./gradlew :koog-compose-core:desktopTest \
    --tests "io.github.koogcompose.phase.PhaseOutputTest"

# Run tests matching a pattern
./gradlew :koog-compose-core:desktopTest \
    --tests "*AuditLoggerTest" \
    --tests "*HandoffToolTest"

# Run all project tests
./gradlew test

# Run Android instrumented tests (requires connected device/emulator)
./gradlew :koog-compose-core:connectedAndroidTest
```

#### Test the sample app

```bash
# Build the sample app for Android
./gradlew :sample-app:assembleDebug

# Build for iOS Simulator
./gradlew :sample-app:assembleDebugIosSim
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
| Token streaming | ✅ | ✅ | ✅ |
| Retry & stuck detection | ✅ | ✅ | ✅ |
| Arg validation | ✅ | ✅ | ✅ |
| Structured outputs | ✅ | ✅ | ✅ |
| Multi-agent handoff | ✅ | ✅ | ✅ |
| Tool call tracking | ✅ | ✅ | ✅ |
| Audit log redaction | ✅ | ✅ | ✅ |
| Agent checkpoints (opt-in) | ✅ | ✅ | ✅ |
| Compose UI | ✅ | ✅ | ✅ |
| Room session store | ✅ | ✅ | — |
| Device tools (location) | ✅ | 🔜 v0.4 | — |
| WorkManager background | ✅ | — | — |

---

## Build & test

```bash
# Run common (KMP) tests
./gradlew :koog-compose-core:desktopTest

# Run Android instrumented tests
./gradlew :koog-compose-core:connectedAndroidTest

# Run the CMP sample app (Android or iOS)
./gradlew :sample-app:assembleDebug        # Android
./gradlew :sample-app:assembleDebugIosSim  # iOS Simulator

# Generate KDoc
./gradlew dokkaHtml
```

---

## Roadmap

### v0.3 (current)
- ✅ Structured outputs with validation & self-correction
- ✅ Schema versioning for evolving output types
- ✅ Tool call frequency tracking per session
- ✅ Multi-agent handoff via `handoff(agentRef)`
- ✅ `[ToolName]` reference resolution in phase instructions
- ✅ Rich JSON Schema tool parameter types (String, Integer, Boolean, Enum, Array, Object)
- ✅ Privacy & data ownership — all data stays on device by default
- ✅ Audit log args redaction for PII-sensitive apps
- ✅ Agent checkpoints (opt-in via `Persistence` feature)
- ✅ Background task tools via WorkManager (Android)
- ✅ RoomSessionStore persists `serializedState` and `toolCallCounts`
- ✅ `KoogRoutine` registers phase tools and transitions

### v0.4
- **iOS device parity** — `CLLocation` and `PHPicker` tool support
- **ActivityResult integration** — camera, file picker, permissions as agent tools
- **Structured observability** — pluggable `EventSink` for Firebase, Datadog, custom backends
- **Custom PersistenceStorageProvider** — SQLite, Keychain, cloud sync

### v0.5
- **Screenshot context tool** — give the agent a view of the current screen
- **Voice slot** — LiveKit-compatible audio input/output in the UI module
- **Schema migration utilities** — migrate persisted structured outputs across versions

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

- Bug reports and feature requests → [GitHub Issues](https://github.com/brianmwas/koog-compose/issues)
- Questions → [GitHub Discussions](https://github.com/brianmwas/koog-compose/discussions)

---

## License

```
Copyright 2025-2026 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```