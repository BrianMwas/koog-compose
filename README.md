# koog-compose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-purple.svg)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

A declarative Kotlin Multiplatform (KMP) runtime for building AI agents that orchestrate app logic, device capabilities, and UI — all from a single DSL.

Built on [JetBrains Koog](https://github.com/JetBrains/koog), it bridges the gap between Koog's custom strategy graphs and real app surfaces — giving you typed shared state, phase-aware conversations with subphases and parallel branches, multi-agent handoff, plug-and-play persistence, token-level streaming, and Material 3 UI components across Android, iOS, and Desktop.

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
| Flat phases pollute the graph with internal steps | `subphase { }` encapsulates sequential steps; the graph sees one phase |
| Independent reads run sequentially | `parallel { branch { } }` fans out concurrent tool calls via Koog's `nodeExecuteMultipleTools(parallelTools = true)` |
| External triggers (push, deep links) need custom routing | `session.resumeAt("phaseName")` — one call from any platform trigger |
| Duplicate phase configs across agents | `include(phaseTemplate)` and `include(subphaseTemplate)` — define once, reuse anywhere |

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
        phase("greeting", initial = true) {
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
        onAgentStuck { event -> /* navigate to support, log analytics, etc. */ }
        onTurnFailed { event -> /* show error UI */ }
    }
}
```

### 3. Write a stateful tool

```kotlin
class SendMoneyTool(
    override val stateStore: KoogStateStore<AppState>
) : StatefulTool<AppState>() {

    override val name = "SendMoney"
    override val description = "Send money to a recipient"
    override val permissionLevel = PermissionLevel.CRITICAL

    override fun validateArgs(args: JsonObject): ValidationResult {
        val amount = args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ValidationResult.Invalid("missing or non-numeric field: amount")
        if (amount <= 0) return ValidationResult.Invalid("amount must be greater than 0")
        args["recipientId"]
            ?: return ValidationResult.Invalid("missing required field: recipientId")
        return ValidationResult.Valid
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val amount = args["amount"]!!.toString().toDouble()
        val recipientId = args["recipientId"]!!.toString()
        return ToolResult.Success("Sent $amount to $recipientId")
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

    val isRunning      = session.isRunning       // StateFlow<Boolean>
    val lastResponse   = session.lastResponse    // StateFlow<String?>
    val currentPhase   = session.currentPhase    // StateFlow<String>
    val responseStream = session.responseStream  // Flow<String> — token by token
    val appState       = session.appState        // StateFlow<AppState>?
    val error          = session.error           // StateFlow<Throwable?>
}
```

### 5. Add the Compose UI

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val chatState = rememberChatState(viewModel.session)
    val snackbarHostState = remember { SnackbarHostState() }

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
    store     = RoomSessionStore(db.sessionDao()),
    scope     = viewModelScope
)
```

---

## Core concepts

### Typed shared state

`KoogStateStore<S>` is the single source of truth. Tools update it via `stateStore.update { }`, and your Compose UI observes it via `session.appState` — a `StateFlow<S>`.

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

### Subphases — sequential steps inside one phase

When a phase encapsulates multiple sequential steps, use `subphase { }` instead of polluting the top-level graph with internal implementation details. Subphases run in declaration order. Each has its own tool scope and optional typed output. The parent phase's `onCondition` transitions only fire after ALL subphases complete.

```kotlin
phase("checkout", initial = true) {
    subphase("validate_cart") {
        instructions { "Verify all items are in stock. Respond ONLY with JSON." }
        tool(CheckInventoryTool())
        typedOutput<CartValidation>()
    }
    subphase("process_payment") {
        instructions { "Charge the card. Respond ONLY with JSON." }
        tool(ChargeCardTool())   // only reachable in this subphase
        typedOutput<PaymentResult>()
    }
    subphase("confirm_order") {
        instructions { "Send order confirmation email." }
        tool(SendEmailTool())
    }
    onCondition("order complete", "post_purchase")
}
phase("post_purchase") {
    instructions { "Thank the user and offer tracking." }
}
```

The phase graph only sees **checkout** → **post_purchase**. The three internal steps are invisible to the router.

### Parallel branches — concurrent tool execution

When a phase needs multiple independent operations, use `parallel { branch { } }`. Tools from all branches are collected into a single subgraph that uses Koog's `nodeExecuteMultipleTools(parallelTools = true)` for true concurrent execution. Many LLM providers execute independent tool calls natively in parallel.

```kotlin
phase("gather_context", initial = true) {
    parallel {
        branch("location") {
            tool(GeocoderTool())
            typedOutput<LocationContext>()
        }
        branch("device") {
            tool(LocaleTool())
            tool(TimezoneTool())
            typedOutput<DeviceContext>()
        }
        branch("permissions") {
            tool(PermissionCheckTool())
            typedOutput<PermissionContext>()
        }
    }
    onCondition("context ready", "main")
}
```

Branch results flow through `stateStore` — design branch tools to write their output to `stateStore.update { }` directly. Multiple `parallel` blocks in one phase run sequentially (group 1 → group 2 → ...).

### Reusable templates — define once, include anywhere

Common patterns like "research → summarise" appear in multiple agents. Define them once with `phaseTemplate` or `subphaseTemplate` and `include()` them anywhere.

```kotlin
// SharedTemplates.kt
val researchSubphase = subphaseTemplate("research") {
    instructions { "Search and summarise relevant information. Respond ONLY with JSON." }
    tool(WebSearchTool())
    typedOutput<ResearchSummary>()
}

val safetyCheckTemplate = phaseTemplate {
    instructions { "Check content against safety guidelines." }
    tool(ContentModerationTool())
    typedOutput<SafetyResult>()
}

// Agent 1 — uses research as a subphase
koogAgent("answer") {
    phases {
        phase("respond", initial = true) {
            include(researchSubphase)           // adds "research" subphase
            subphase("compose_answer") {
                instructions { "Write the answer based on research." }
            }
        }
    }
}

// Agent 2 — same templates, different flow
koogAgent("email_drafter") {
    phases {
        phase("safety_check", initial = true) {
            include(safetyCheckTemplate)        // flat phase template
            onCondition("safe", "draft")
        }
        phase("draft") {
            include(researchSubphase)           // same template, different agent
            subphase("write_email") {
                tool(EmailDraftTool())
                typedOutput<EmailDraft>()
            }
        }
    }
}
```

Templates are plain Kotlin values — no new DSL machinery. Anything declared after `include()` overrides template values.

### Resume at a phase from any external trigger

`resumeAt("phaseName")` lets you jump into a specific phase from push notifications, deep links, background tasks, or any other external trigger. All platform triggers converge to this one call.

```kotlin
// From a push notification handler:
session.resumeAt("notify_user", userMessage = "Your order has shipped!")

// From a deep link — resume without a user message (sentinel, no history pollution):
session.resumeAt("onboarding_flow")

// From a broadcast receiver:
session.resumeAt("location_update", userMessage = "You're near a saved place")
```

When called without a `userMessage`, an internal sentinel is used so nothing pollutes conversation history. If the phase doesn't exist in any registered agent, the error is surfaced on `session.error` as `UnknownPhaseException`. Works on both single-agent (`PhaseSession`) and multi-agent (`SessionRunner`) runtimes.

### Tool references in instructions

Use `[ToolName]` syntax in phase instructions to inject full tool schemas into the system prompt.

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

`[GetBalance]` is expanded to the tool's full schema at build time. Matching is flexible — `[GetBalance]`, `[get_balance]`, and `[GetBalanceTool]` all resolve to the same tool.

### Structured outputs

Phases can declare a typed output schema. The LLM is primed with JSON Schema + examples, and the response is parsed into your Kotlin data class automatically.

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
        examples = listOf(ExtractedIntent("check_balance", 0.92)),
        validate = { result ->
            if (result.confidence < 0.5)
                ValidationResult.Invalid("confidence too low: ${result.confidence}")
            else ValidationResult.Valid
        }
    )
}
```

Features: auto schema generation, retry with self-correction, schema versioning, markdown fence stripping.

### Arg validation

`SecureTool` exposes an optional `validateArgs` hook that runs before guardrails, confirmation, or `execute()`.

```
LLM delivers args
  → validateArgs()          ← your field/type checks        (step 0)
      → GuardrailEnforcer   ← rate limits, allowlists       (step 1)
          → confirmation UI ← SENSITIVE / CRITICAL          (step 2)
              → execute()   ← guaranteed valid args         (step 3)
```

### Streaming

`responseStream: Flow<String>` emits tokens as they arrive. Use `turnId` to reset accumulation on each new `send()`:

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

### Resilience

**Retry with backoff** — on each failed attempt the agent is rebuilt. After exhaustion, `KoogEvent.TurnFailed` is dispatched.

**Stuck detection** — tracks consecutive identical phase + input pairs. When the threshold is hit, a fallback message is surfaced and the stuck state resets.

```kotlin
config {
    retry {
        maxAttempts = 3
        initialDelayMs = 500L  // doubles on each attempt
    }
    stuckDetection {
        threshold = 3
        fallbackMessage = "I'm having trouble with that. Let me connect you to support."
    }
}
```

### Security tiers

Every tool declares a `PermissionLevel`. The confirmation handler maps tiers to UI friction:

| Tier | UI treatment | Example |
|---|---|---|
| `SAFE` | Silent / Snackbar | Reading a preference |
| `SENSITIVE` | Bottom sheet confirmation | Sending a message, reading location |
| `CRITICAL` | Full-screen dialog | Deleting data, making a purchase |

Guardrails also enforce rate limits and action allowlists:

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

Define specialist agents and register handoff tools. The LLM reads handoff descriptions and calls them when appropriate.

```kotlin
val focusAgent = koogAgent("focus") {
    instructions { "You are a focus session specialist." }
    phases {
        phase("active") {
            instructions { "Help the user set up a focus session." }
        }
    }
}

val session = koogSession<Unit> {
    provider { ollama(model = "llama3.2") }

    main {
        instructions { "You are a general assistant." }
        phases {
            phase("root", initial = true) {
                handoff(focusAgent) {
                    "User asks about focus, productivity, pomodoro, or concentration"
                }
            }
        }
    }

    agents(focusAgent)
}
```

Handoff options: `description`, `continueHistory` (shared or fresh history), `onHandoff` callback.

### Privacy & data ownership

All data stays on the user's device by default. Nothing is transmitted externally unless you explicitly wire it up.

| Data | Where it goes | User control |
|---|---|---|
| Conversation history | `SessionStore` — InMemory, Room, Redis, or custom | Full ownership. `delete(sessionId)` or `reset()` anytime |
| Tool audit log | In-memory `SharedFlow` only | `AuditLogger(redactArgs = true)` replaces raw args with `[REDACTED]` |
| Tool call counts | In-memory `StateFlow` — per-session only | Resets on `reset()` |
| Agent checkpoints (opt-in) | File-based, on user's device | Disabled by default. Pass `persistenceStorage` to enable |
| Events | Dispatched via `EventHandlers` — you decide what to wire | You own the handler registration |

**What koog-compose does NOT do:**
- ❌ No network telemetry
- ❌ No analytics sent to external servers
- ❌ No prompts, responses, or tool args transmitted
- ❌ No crash reporting

### Events

```kotlin
events {
    onTurnStarted { event -> }
    onTurnCompleted { event -> }
    onTurnFailed { event -> }
    onPhaseTransitioned { event -> }
    onToolCallRequested { event -> }
    onToolExecutionCompleted { event -> }
    onAgentStuck { event -> }
}
```

### Session store

Implement `SessionStore` to plug in any persistence backend:

```kotlin
interface SessionStore {
    suspend fun load(sessionId: String): AgentSession?
    suspend fun save(sessionId: String, session: AgentSession)
    suspend fun delete(sessionId: String)
}

data class AgentSession(
    val sessionId: String,
    val currentPhaseName: String,
    val messageHistory: List<SessionMessage>,
    val serializedState: String? = null,
    val toolCallCounts: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long
)
```

The `:session-room` module provides a ready-made Room implementation.

---

## Testing

`koog-compose-testing` gives you a deterministic harness for chat and phase flows. It keeps the real `PhaseSession` tool/phase loop but swaps the live provider for a scripted `FakePromptExecutor`, so you can prove transitions, tool calls, and shared-state mutation without hitting a real model.

```kotlin
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
```

Simple text-only turns:
```kotlin
val session = testPhaseSession(context) {
    on("Hello") respondWith "Hi there."
}
session.send("Hello")
```

Test denial flows:
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

### Available test assertions

| Assertion | Purpose |
|---|---|
| `assertPhase(session, "phaseName")` | Verify the session is in the expected phase |
| `assertToolCalled(session, "toolName")` | Verify a specific tool was called |
| `assertGuardrailDenied(session, "toolName")` | Verify a tool call was denied by guardrails |
| `assertState(session) { state -> ... }` | Assert against the typed app state |
| `assertEventDispatched(session) { event -> ... }` | Verify a specific KoogEvent was fired |

### Running tests

```bash
# Run all desktop tests (fastest — no Android emulator needed)
./gradlew :koog-compose-core:desktopTest

# Run a single test class
./gradlew :koog-compose-core:desktopTest \
    --tests "io.github.koogcompose.phase.PhaseOutputTest"
```

---

## Architecture

koog-compose is a thin, opinionated layer over [JetBrains Koog](https://github.com/JetBrains/koog). Every `koogCompose { }` block produces a live `AIAgent` with features installed:

| Feature | Purpose |
|---|---|
| **ChatMemory** | Owns LLM conversation history. Loads from your `SessionStore` before each turn, saves after. |
| **StreamingFeature** | Intercepts `TextDelta` frames and emits them as `Flow<String>` for real-time UI updates. |
| **EventHandler** | Maps Koog's native lifecycle callbacks into koog-compose's typed `KoogEvent` sealed hierarchy. |
| **Persistence** (opt-in) | Captures complete agent state at each execution point. Supports rollback. Disabled by default. |

Each phase (or subphase, or parallel branch group) becomes a Koog subgraph with `nodeLLMRequest` → `nodeExecuteTool` → `nodeLLMSendToolResult` nodes, with optional `nodeLLMCompressHistory` between them. Phase transitions are captured as edges — the LLM triggers them by calling generated transition tools.

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
│  PhaseAwareAgent        │  ← builds AIAgent with 4 features installed
│  • ChatMemory           │     (ChatHistoryProvider → your SessionStore)
│  • StreamingFeature     │     (TextDelta → responseStream Flow)
│  • EventHandler         │     (Koog callbacks → KoogEvent dispatch)
│  • Persistence (opt-in) │     (file-based checkpoints on device)
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  PhaseStrategyBuilder   │  ← phases / subphases / parallel → subgraphs
│  • buildFlatSubgraph    │     with optional compression nodes
│  • buildMultiStep       │
│  • buildParallel        │     uses Koog's nodeExecuteMultipleTools(parallelTools = true)
└────────┬────────────────┘
         │
         ▼
   Koog AIAgent runs ──► LLM calls, tool execution, phase transitions
```

### Parallel tool execution

When a phase has parallel branches, the strategy uses Koog's `nodeExecuteMultipleTools(parallelTools = true)` for true concurrent tool execution:

```
parallel branches subgraph:
  nodeStart → nodeLLMRequestMultiple
    → onAssistantMessage → nodeFinish (text response)
    → onMultipleToolCalls → nodeExecuteMultipleTools(parallelTools = true)
      → ALL tool calls execute concurrently
      → nodeLLMSendMultipleToolResults → back to LLM
```

Regular phases and subphases use sequential single-tool execution — correct for step-by-step LLM orchestration.

### Nesting guardrails

The DSL prevents accidental nesting abuse:
- `subphase { }` can only be declared on a top-level phase — not inside another subphase
- `parallel { }` can only be declared on a top-level phase — not inside a subphase or branch
- Branches inside `parallel { }` cannot declare further nesting
- `onCondition { }` is only available on top-level phases (subphases don't have transitions — the parent handles them)

Each violation produces a clear error message at DSL build time, not at runtime.

---

## Stateless sessions

If you don't need shared state, omit `initialState { }` and use the `Unit` overload:

```kotlin
val context = koogCompose {
    provider { anthropic(apiKey = "...") { model = "claude-3-5-sonnet" } }
    phases { /* ... */ }
}
// context is KoogComposeContext<Unit>
```

---

## Schema migration for persisted state

When your app state data class evolves (new fields, renamed properties, removed fields), **old persisted sessions will fail to deserialize** unless you handle migration. koog-compose v0.3.2 includes built-in migration hooks to prevent data loss.

### How it works

`AgentSession` tracks a `serializedStateVersion` alongside the raw JSON `serializedState`. When you upgrade your app state, increment the version and define an upgrade path:

```kotlin
@Serializable
data class AppState(
    val userId: String,
    val intent: Intent? = null,
    val location: Coordinates? = null,
    val themeMode: ThemeMode = ThemeMode.System // ← new field in v2
)

val stateMigration = object : StateMigration<AppState> {
    override val schemaVersion: Int = 2

    override suspend fun migrate(
        json: JsonObject,
        fromVersion: Int
    ): JsonObject {
        return when (fromVersion) {
            0, 1 -> json + ("themeMode" to JsonPrimitive("System"))
            else -> json
        }
    }

    override fun decodeMigrated(json: JsonObject): AppState {
        return Json.decodeFromJsonElement(serializer(), json)
    }
}
```

### Using with RoomSessionStore

Pass your serializer and migration to the constructor:

```kotlin
val sessionStore = RoomSessionStore<AppState>(
    dao = db.koogSessionDao(),
    stateSerializer = AppState.serializer(),
    stateMigration = stateMigration // optional — defaults to lenient parsing
)
```

If you omit `stateMigration`, the store falls back to `StateMigration.lenient()` which uses `ignoreUnknownKeys = true` and `coerceInputValues = true` — this survives **added fields with defaults** and **removed fields** but NOT **renamed fields**.

### Using with RedisSessionStore

`RedisSessionStore` serializes the full `AgentSession` as JSON. It already uses `ignoreUnknownKeys = true` and `coerceInputValues = true`, so new fields with defaults and removed fields are handled automatically. For renamed fields, you'll need to migrate the full `AgentSession` JSON before deserialization — implement a custom store or pre-migrate at the Redis level.

### Room database migration

The `:session-room` module includes a Room migration (`v1 → v2`) that adds the `serializedStateVersion` column automatically.

### Quick checklist

| Change | Breaking? | Handled by default? | Needs migration? |
|---|---|---|---|
| Add field with default value | No | ✅ Yes (`coerceInputValues`) | No |
| Add field with nullable type | No | ✅ Yes (`coerceInputValues`) | No |
| Remove a field | No | ✅ Yes (`ignoreUnknownKeys`) | No |
| Rename a field | **Yes** | ❌ No | ✅ Yes |
| Change a field type | **Yes** | ❌ No | ✅ Yes |

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
| Subphases (sequential) | ✅ | ✅ | ✅ |
| Parallel branches (concurrent) | ✅ | ✅ | ✅ |
| Resume at phase | ✅ | ✅ | ✅ |
| Reusable templates | ✅ | ✅ | ✅ |
| Multi-agent handoff | ✅ | ✅ | ✅ |
| Tool call tracking | ✅ | ✅ | ✅ |
| Audit log redaction | ✅ | ✅ | ✅ |
| Agent checkpoints (opt-in) | ✅ | ✅ | ✅ |
| Compose UI | ✅ | ✅ | ✅ |
| Room session store | ✅ | ✅ | — |
| Device tools (location) | ✅ | 🔜 v0.4 | — |
| WorkManager background | ✅ | — | — |

---

## Roadmap

### v0.3 (current)
- ✅ Structured outputs with validation & self-correction
- ✅ Schema versioning for evolving output types
- ✅ Tool call frequency tracking per session
- ✅ Multi-agent handoff via `handoff(agentRef)`
- ✅ `[ToolName]` reference resolution in phase instructions
- ✅ Subphases — sequential steps inside a single phase
- ✅ Parallel branches — concurrent tool execution via `nodeExecuteMultipleTools(parallelTools = true)`
- ✅ `resumeAt()` — jump to any phase from external triggers
- ✅ Reusable templates — `phaseTemplate` and `subphaseTemplate` with `include()`
- ✅ Nesting guardrails — clear errors for accidental deep nesting
- ✅ Rich JSON Schema tool parameter types (String, Integer, Boolean, Enum, Array, Object)
- ✅ Privacy & data ownership — all data stays on device by default
- ✅ Audit log args redaction for PII-sensitive apps
- ✅ Agent checkpoints (opt-in via `Persistence` feature)
- ✅ Background task tools via WorkManager (Android)
- ✅ RoomSessionStore persists `serializedState` and `toolCallCounts`
- ✅ `KoogRoutine` registers phase tools and transitions
- ✅ Schema migration utilities — `StateMigration` interface for evolving app state
- ✅ Lenient deserialization by default (`ignoreUnknownKeys` + `coerceInputValues`)
- ✅ Room database auto-migration (v1 → v2) for `serializedStateVersion` column

### v0.4
- **iOS device parity** — `CLLocation` and `PHPicker` tool support
- **ActivityResult integration** — camera, file picker, permissions as agent tools
- **Structured observability** — pluggable `EventSink` for Firebase, Datadog, custom backends
- **Custom PersistenceStorageProvider** — SQLite, Keychain, cloud sync

### v0.5
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
Copyright 2025-2026 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
