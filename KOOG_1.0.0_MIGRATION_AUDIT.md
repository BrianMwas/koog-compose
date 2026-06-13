# Koog 1.0.0 Migration Audit — koog-compose

**Status:** Audit / report only. **No source code was changed by this document.**
**Current koog version:** `0.8.0` (`gradle/libs.versions.toml` → `koog = "0.8.0"`)
**Target koog version:** `1.0.0` (verified present on Maven Central)
**Date:** 2026-06-13

---

## 0. How this audit was produced (and its limits)

This audit was written in a sandbox where the project **cannot be compiled**:
the network policy blocks `dl.google.com` (`host_not_allowed`), so the Android
Gradle Plugin, AndroidX, and Compose-for-Android artifacts cannot be downloaded.
Every module declares `androidTarget()`, so Gradle cannot even *configure* the
build here, let alone run `:koog-compose-core:desktopTest`.

Consequently, the findings below are derived from:

1. The koog **1.0.0 migration notes / release notes** (JetBrains blog + GitHub
   release page).
2. A line-by-line read of every file in this repo that imports `ai.koog.*`.

Each finding carries a **confidence** rating:

- **Certain** — the migration notes name this exact change and the code uses it.
- **Likely** — the notes describe a category that this code clearly falls into.
- **Verify** — plausible breakage; confirm against the compiler once you can build.

> **Recommended next step:** apply the fixes on a machine where
> `./gradlew :koog-compose-core:desktopTest` runs, and let the Kotlin compiler
> drive the "Verify" items to ground truth. Work through Section 9's checklist
> module-by-module so each compile error is resolved against the real 1.0.0 API.

---

## 1. Breaking changes in koog 1.0.0 (upstream summary)

From the 1.0.0 release notes, the changes relevant to this codebase are:

| # | Change | Hits this repo? |
|---|--------|-----------------|
| 1 | Minimum **JDK raised to 17** | ✅ build config |
| 2 | `ai.koog.prompt.dsl.Prompt` → **`ai.koog.prompt.Prompt`** (type import only; `prompt { }` DSL stays in `dsl`) | ✅ 3 files |
| 3 | `kotlin.time.Clock` params → **`KoogClock`** abstraction | ✅ 3 files |
| 4 | LLM clients no longer accept Ktor `HttpClient`; **`OllamaClient.baseUrl` removed** | ✅ provider |
| 5 | Factory functions replace `invoke` constructors for `AIAgent`, `ToolRegistry`, `AIAgentService`, `RollbackToolRegistry`, `AIAgentPlannerStrategy` (caller syntax usually unchanged) | ✅ verify |
| 6 | Graph-DSL node renames + **auto-writeback removed**; `nodeExecuteToolsAndGetResults` → `nodeExecuteTools` (returns `ReceivedToolResults` directly) | ✅ strategy builder |
| 7 | Memory module restructure: `AgentMemory` → `LongTermMemory`; **`RetrieveFactsFromHistory` relocated** out of `agents-features-memory`; `QueryExtractor`→`SearchQueryProvider`; `ExtractionStrategy`→`DocumentExtractor` | ✅ strategy builder, memory deps |
| 8 | Pipeline classes: `AIAgentPipeline`/`AIAgentPipelineImpl` replaced by `AIAgentPipelineAPI`, `AIAgentGraphPipeline`, `AIAgentPlannerPipeline`; **event contexts expose `AIAgent` instead of `agentId`/`config`** | ✅ streaming feature, event bridge |
| 9 | Snapshot/persistence: `AgentCheckpointData` fields (`nodePath`, `lastInput`, `lastOutput`) moved to `properties`; `runFromCheckpoint(agentInput=)` → `input=` | ✅ persistence |
| 10 | `prompt-executor-llms-all` no longer leaks Ktor transitively; consumers add `oshai.kotlin-logging` if needed | ✅ deps hygiene |
| 11 | All previously `@Deprecated` members removed (e.g. `AnthropicModels.Haiku_3`) | ✅ verify model refs |
| 12 | `AIAgentStorage(serializer)` constructor; equality now name-based | ⚠️ verify (not directly constructed here) |

Sources:
- [Koog 1.0 Is Out — JetBrains AI Blog](https://blog.jetbrains.com/ai/2026/05/koog-1-0-is-out-stable-core-better-interop-and-multiplatform-observability/)
- [JetBrains/koog Releases](https://github.com/JetBrains/koog/releases)
- [Remove "AI" prefix discussion #329](https://github.com/JetBrains/koog/discussions/329)

---

## 2. Build & dependency changes

### 2.1 Bump the version — `gradle/libs.versions.toml`
```toml
# koog — the AI engine
koog = "1.0.0"   # was 0.8.0
```
**Confidence: Certain.**

### 2.2 JDK 17 minimum — affects every module (#1)
`koog-compose-core/build.gradle.kts` currently targets JVM 11 in three places:
```kotlin
androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }   // line 14-17
jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }  // line 19-22
compileOptions {                                                        // line 60-63
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
```
koog 1.0.0 requires **JDK 17**. Raise `JVM_11` → `JVM_17` and
`VERSION_11` → `VERSION_17` here **and in every other module's build file**
(`koog-compose-ui`, `-device`, `-mediapipe`, `-session-room`, `-testing`,
`sample-app`, `composeApp`). Also confirm `build-logic` convention plugins don't
re-pin Java 11.
**Confidence: Certain.** (koog 1.0.0 is compiled against JVM 17 bytecode; JVM 11
consumers fail at link time.)

> Android note: `minSdk = 26` already maps to a Java-17-capable desugaring path,
> and `android.useAndroidX=true` is already set in `gradle.properties` (✓ required
> by #10).

### 2.3 Explicit memory dependency — `koog-compose-core/build.gradle.kts` (#7)
`commonMain` declares only:
```kotlin
implementation(libs.koog.agents)
implementation(libs.koog.agents.core)
implementation(libs.koog.prompt.executor)
```
But the code imports `ai.koog.agents.memory.*` and
`ai.koog.agents.chatMemory.feature.*` (currently resolved **transitively** via
`koog-agents`). In 1.0.0 the memory/planner modules were split out. Add the
explicit dependency the catalog already defines but the module never wired:
```kotlin
implementation(libs.koog.agents.memory)  // ai.koog:agents-features-memory:1.0.0
```
and confirm `RetrieveFactsFromHistory`'s new home (see 3.4). If planners are ever
used, `agents:agents-planners` must be added too.
**Confidence: Likely.**

### 2.4 Logging — `oshai.kotlin-logging` (#10)
1.0.0 may no longer pull `io.github.oshai:kotlin-logging` transitively. If any
module references a logger at runtime and you hit `NoClassDefFoundError`, add it
explicitly. **Confidence: Verify.**

---

## 3. Source-level breaking changes (per file)

### 3.1 `prompt.dsl.Prompt` → `prompt.Prompt` (#2) — **Certain**
The **type** moved package; the `prompt { }` builder did **not**. Change only the
type import, keep `import ai.koog.prompt.dsl.prompt`.

| File | Line | Fix |
|------|------|-----|
| `core/.../provider/KoogaiProvider.kt` | 5 | `import ai.koog.prompt.dsl.Prompt` → `import ai.koog.prompt.Prompt` |
| `core/.../phase/StructuredPhaseExecutor.kt` | 5 | same |
| `testing/.../FakePromptExecutor.kt` | 5 | same |

`Prompt` is used as a parameter/return type (`buildPrompt(): Prompt`,
`executeStructured(basePrompt: Prompt)`) — no behavioral change, import only.

### 3.2 `KoogClock` replaces `kotlin.time.Clock` (#3) — **Likely**
`RequestMetaInfo.create(...)` and `ResponseMetaInfo.create(...)` now take a
`KoogClock` rather than `kotlin.time.Clock`.

- **`core/.../provider/KoogaiProvider.kt`**
  - line 53: `import kotlin.time.Clock` → import koog's `KoogClock`.
  - lines 302, 306, 309, 316, 323, 330: `RequestMetaInfo.create(Clock.System)` /
    `ResponseMetaInfo.create(Clock.System)` → pass `KoogClock.System` (confirm the
    exact symbol/companion name).
- **`core/.../session/SessionStoreChatHistoryProvider.kt`**
  - line 5: `import kotlin.time.Clock as KoogClock` is **misleading** — it aliases
    the *kotlin* clock, not koog's. Replace with the real koog `KoogClock` import.
  - lines 73-74: `RequestMetaInfo.create(KoogClock.System)` /
    `ResponseMetaInfo.create(KoogClock.System)` resolve against the real type.
- **`core/.../event/KoogEventHandlerBridge.kt`**
  - lines 5, 27, 45, 60, 74 use `kotlin.time.Clock.System.now().toEpochMilliseconds()`
    purely as a *timestamp source* — it is **not** passed into any koog API. This
    can stay on `kotlin.time.Clock` (stable since Kotlin 2.1). **No change required**,
    but consider routing through koog's clock for testability.

> Action: locate the exact 1.0.0 symbol (`ai.koog...KoogClock` + its `System`
> instance, or whether `RequestMetaInfo.create()` gained a no-arg/default overload)
> and apply uniformly. If a no-arg `create()` exists, prefer it.

### 3.3 LLM client construction / Ollama (#4) — **Mixed**
`core/.../provider/KoogaiProvider.kt`, `buildClientForProvider()` (lines 206-234):

- `OpenAILLMClient(apiKey, settings = OpenAIClientSettings(baseUrl=...))` (207-209),
  `AnthropicLLMClient(apiKey, settings = …)` (212-214),
  `GoogleLLMClient(apiKey, settings = …)` (217-219): only the **`HttpClient`
  overload** was removed. These three pass `apiKey` + `settings` and should still
  compile — **Verify** the `settings` types (`*ClientSettings`) kept their public
  ctors and that `baseUrl` is still an `OpenAIClientSettings` field.
- `OllamaClient(config.baseUrl)` (222): **`OllamaClient.baseUrl` was removed** and
  base-URL configuration moved to the supplied HTTP client. The `String`-arg
  constructor is the most likely casualty. **Confidence: Likely breakage.** Fix by
  configuring the base URL via a `KoogHttpClient.Factory` (or the new `ollama()`
  builder) instead of a positional `baseUrl` string.

Also in this file:
- `resolveModel()` references `AnthropicModels.Sonnet_4_5`, `OpenAIModels.Chat.GPT4o`,
  `GoogleModels.Gemini2_0Flash` (lines 246, 244, 250). Deprecated model constants
  were purged (#11) — **Verify** these three still exist (Sonnet_4_5 almost
  certainly does; double-check the Google/OpenAI ids).
- `modelsById()` extension (`ai.koog.prompt.executor.clients.modelsById`, used 241/245/249)
  — **Verify** still present.
- `executor.executeStreaming(prompt, model, toolDescriptors)` (line 105) — **Verify**
  the 3-arg signature is unchanged.

### 3.4 Graph DSL strategy builder (#6, #7) — **HIGHEST RISK / Likely**
`core/.../phase/PhasestrategyBuilder.kt` is the most exposed file.

- **Node functions** (imports 9-15, used in `buildFlatSubgraph`/`buildParallelSubgraph`):
  - `nodeLLMRequest`, `nodeLLMRequestMultiple` — String-input nodes; likely retained
    under the `nodeLLMRequest*` family. **Verify.**
  - `nodeExecuteTool` (303-304), `nodeExecuteMultipleTools` (168) — the notes rename
    `nodeExecuteToolsAndGetResults` → `nodeExecuteTools` and state it now returns
    `ReceivedToolResults` **directly** (auto-writeback removed). Confirm whether the
    singular/`Multiple` variants survived or collapsed into `nodeExecuteTools`.
    **Likely rename.**
  - `nodeLLMSendToolResult` (305), `nodeLLMSendMultipleToolResults` (169) — the new
    family is `nodeLLMSendToolResults*` / `nodeLLMSendMessage*`. The **singular**
    `nodeLLMSendToolResult` is the likely casualty. **Verify/rename.**
- **`ReceivedToolResult`** (import 19, generic arg in `nodeLLMCompressHistory<…>` at
  171 & 309, and in branch lambdas) — there may now be a plural `ReceivedToolResults`
  type returned by `nodeExecuteTools`. The `nodeLLMCompressHistory<ReceivedToolResult>`
  / `<List<ReceivedToolResult>>` generic parameters must match the new node output
  types. **Verify** carefully — this is where the auto-writeback removal will bite.
- **Memory imports** (20-22):
  - `ai.koog.agents.memory.feature.history.RetrieveFactsFromHistory` — **relocated**
    out of `agents-features-memory` (#7). Find its new package and update import.
    Used at line 390. **Likely breakage.**
  - `ai.koog.agents.memory.model.Concept`, `FactType` (21-22, used 392-403) — confirm
    still in `memory.model`; ensure the explicit memory dependency from 2.3 is present.
- **`HistoryCompressionStrategy`** family (`WholeHistory`, `FromLastNMessages(n)`,
  `Chunked`, `NoCompression`) at 173/311/380-387 — **Verify** these names/shape.
- Edge DSL (`onAssistantMessage`, `onToolCall`, `onMultipleToolCalls`, `onCondition`,
  `forwardTo`, `nodeStart`, `nodeFinish`) — **Verify**; these are core and likely
  stable, but `onToolCall`/`onMultipleToolCalls` lambda input types may shift if the
  execute-node output type changed.

> This file should be migrated **first under a live compiler** — its errors will
> reveal the exact 1.0.0 node API faster than any doc.

### 3.5 Streaming feature / pipeline classes (#8) — **HIGH RISK / Likely**
`core/.../session/StreamingFeatureConfig.kt`:

- Imports `AIAgentPipeline` (line 12) and uses it as the common supertype param in
  `installCommon(pipeline: AIAgentPipeline, …)` (line 81). The notes say the old
  `AIAgentPipeline`/`AIAgentPipelineImpl` were **replaced** by `AIAgentPipelineAPI`
  (+ the graph/functional/planner pipelines). If `AIAgentPipeline` is gone, change
  the shared param type to **`AIAgentPipelineAPI`** (the common interface the three
  concrete pipelines implement). **Likely breakage.**
- `AIAgentGraphFeature` / `AIAgentFunctionalFeature` / `AIAgentPlannerFeature`
  (imports 5-7) and their `install(config, pipeline)` overrides (51-78) — **Verify**
  the feature interface method shapes (`createInitialConfig(agentConfig)`, `key`,
  `install`) are unchanged.
- `interceptLLMStreamingFrameReceived(this) { eventContext -> eventContext.streamFrame }`
  (83-84) — **Verify** the interceptor name and that the context still exposes
  `streamFrame`. Per #8, event contexts were harmonized (now expose the `AIAgent`),
  which may also touch this interceptor's context shape.
- `AIAgentStorageKey` (`ai.koog.agents.core.agent.entity`, line 4 / used 46) — **Verify**
  package + generic usage.

### 3.6 Koog EventHandler bridge (#8) — **Likely**
`core/.../event/KoogEventHandlerBridge.kt` registers
`onToolCallStarting`, `onToolCallCompleted`, `onAgentCompleted`,
`onAgentExecutionFailed` on `EventHandlerConfig` and reads fields off each
`eventContext`:

- `eventContext.toolName`, `.toolArgs` (22-35), `.toolResult`, `.toolCallId` (40-52),
  `.result` (56-67), `.throwable` (70-81).
- Per #8, **event contexts were reshaped** (expose `AIAgent` instead of
  `agentId`/`config`; parameter order harmonized). The four callback names and these
  property names must be re-checked against `EventHandlerConfig` in 1.0.0.
  **Confidence: Likely** at least one renamed (these handler hooks churned across
  0.7→0.8→1.0).
- This file already wraps each handler in `runCatching { }`, so a *runtime* mismatch
  would be swallowed silently — meaning a wrong-but-compiling field access could
  make events silently stop firing. **Add a test** (see Section 6) that asserts a
  `ToolCalled`/`TurnCompleted` event actually reaches the sink after migration.

### 3.7 Persistence / snapshot (#9) — **Verify**
`core/.../phase/PhaseAwareAgent.kt`:

- `import ai.koog.agents.snapshot.feature.Persistence`,
  `…providers.file.FilePersistenceStorageProvider`,
  `…providers.filters.AgentCheckpointPredicateFilter` (9-11).
- `install(Persistence) { storage = persistenceStorage; enableAutomaticPersistence = true }`
  (188-191) — **Verify** the config field names survived.
- `FilePersistenceStorageProvider<Path>` (param, line 73) and
  `FilePersistenceStorageProvider<AgentCheckpointPredicateFilter>` (213-224) — note
  these two use **different generic arguments** for the *same* class, which is
  already suspicious in 0.8.0 and likely wrong in 1.0.0. Reconcile the type
  parameter. `AgentCheckpointData` field moves (#9) only matter if you read those
  fields directly (you don't here), but `runFromCheckpoint(input=)` rename applies
  if/when you call it.
- `install(ChatMemory) { chatHistoryProvider = …; windowSize(n) }` (152-157) — **Verify**
  the `ChatMemory` feature config DSL (`chatHistoryProvider`, `windowSize`) is intact
  and that `ChatMemory` itself wasn't folded into the memory restructure.
- `EventHandler` install + `installKoogEventHandlers` (171-181): depends on 3.6.

### 3.8 AIAgent / AIAgentConfig / ToolRegistry factories (#5) — **Verify**
Used in `PhaseAwareAgent.kt` (98-127, 144-194), `Routine.kt` (73-104),
`KoogaiProvider.kt` (286-292):

- `AIAgent(promptExecutor=, agentConfig=, strategy=, toolRegistry=, installFeatures={})`
  — factory replaces the constructor but caller syntax is "unchanged for normal
  callers." **Verify** the `installFeatures` parameter name/shape and that
  `AIAgentConfig(prompt=, model=, maxAgentIterations=, missingToolsConversionStrategy=,
  serializer=)` kept its named params.
- `MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON)` — **Verify**.
- `KotlinxSerializer()` (`ai.koog.serialization.kotlinx.KotlinxSerializer`) — **Verify**
  (note #12: `AIAgentStorage(serializer)` now takes a serializer — adjacent area).
- `ToolRegistry { … }` builder and `ToolRegistry.EMPTY` (KoogaiProvider 286-292) —
  factory form; **Verify** `EMPTY` still exists.

### 3.9 Tool bridge (#5-adjacent) — **Verify**
`core/.../tool/KoogtoolBridge.kt`:

- Subclasses `Tool<JsonObject, String>(argsType = KSerializerTypeToken(...),
  resultType = KSerializerTypeToken(...), descriptor = …)` (33-48) under
  `@OptIn(InternalKoogSerializationApi::class)`. Because it relies on an **internal**
  serialization API, it is at elevated risk of churn. **Verify**
  `KSerializerTypeToken`, the `Tool` base-class constructor params, and that
  `execute(args): String` is still the abstract method shape.
- `ToolDescriptor`, `ToolParameterDescriptor`, `ToolParameterType.{String,Integer,
  Float,Boolean,Enum(Array<String>),List,Object,Null}` (80-209) — **Verify** the
  `Enum(vararg/Array<String>)` and `Object(properties, requiredProperties,
  additionalProperties, additionalPropertiesType)` constructor shapes.

### 3.10 Structured output (#—) — **Verify**
`core/.../phase/PhaseOutput.kt` + `StructuredPhaseExecutor.kt`:

- `ai.koog.prompt.structure.json.JsonStructure<O>` (PhaseOutput line 3, field 18) —
  structured-output APIs were actively reworked toward 1.0; **Verify** the
  `JsonStructure` package/generic and the parse entry point.
- `ai.koog.prompt.dsl.ModerationResult` (StructuredPhaseExecutor 4, FakePromptExecutor 4)
  — **Verify** package; the new `nodeLLMModerate*` family suggests moderation moved.
- `StructuredPhaseExecutor : PromptExecutor()` and `FakePromptExecutor : PromptExecutor()`
  — **see 3.11.**

### 3.11 `PromptExecutor` subclasses (#—) — **HIGH RISK / Verify**
`PromptExecutor` is subclassed in two places:
- `core/.../phase/StructuredPhaseExecutor.kt` (`: PromptExecutor()`)
- `testing/.../FakePromptExecutor.kt` (`: PromptExecutor()`)

If `PromptExecutor`'s abstract surface changed in 1.0.0 (e.g. `execute`,
`executeStreaming`, `moderate` signatures, or new abstract members), **both
subclasses fail to compile** until they implement the new contract. The
`koog-compose-testing` module's entire value (deterministic tests) depends on this,
so treat it as a first-class migration target, not an afterthought.

### 3.12 Pre-existing bug spotted while auditing (not a 1.0.0 change)
`core/.../phase/StructuredPhaseExecutor.kt` imports:
```kotlin
import io.modelcontextprotocol.kotlin.sdk.Role.assistant
import io.modelcontextprotocol.kotlin.sdk.Role.user
```
These are **MCP SDK** symbols that look accidental (the file otherwise uses
`ai.koog.prompt.message.Message`). Likely unused leftovers that pull an unintended
dependency. Recommend removing them while you're in the file. **Confidence: Certain
(stray import).**

---

## 4. Risk-ranked migration order

1. **Build config** (2.1, 2.2, 2.3) — get the project configuring on JDK 17 + koog 1.0.0.
2. **`PhasestrategyBuilder.kt`** (3.4) — node DSL is the deepest change; let the
   compiler enumerate the new node API.
3. **`StreamingFeatureConfig.kt`** (3.5) — pipeline class rename.
4. **`PromptExecutor` subclasses** (3.11) — unblocks core + testing.
5. **`KoogEventHandlerBridge.kt`** (3.6) + **`PhaseAwareAgent.kt`** (3.7).
6. **`KoogaiProvider.kt`** (3.1-3.3) — imports, Clock, Ollama.
7. **`KoogtoolBridge.kt`** (3.9), **structured output** (3.10).
8. **Trivia**: stray MCP imports (3.12), model-constant verification (#11).

---

## 5. Documentation drift (worth fixing alongside the upgrade)

- `README.md` install snippet pins **koog-compose** `1.4.2` (lines 37-44) while
  `gradle.properties` is `VERSION_NAME=1.5.0`. Bump the README to the release you cut.
- `README.md` references `ollama(model = "llama3.2")` (line 387) and
  `provider { onDevice(...) }` flows — re-verify these against the post-migration
  provider API (3.3) so the docs don't ship stale.
- The "Observability" table lists `LLMRequested` as "reserved for future use"; if
  1.0.0's harmonized pipeline events make this cheap to emit, consider wiring it.

---

## 6. Showcase samples to add (the "showcase to devs" ask)

Today the runnable samples are `composeApp` (a 2-phase greeting/chat demo) and
`sample-app` (a homework-photo teaching app + multi-agent + persistence pieces,
largely **androidMain**, so not runnable on desktop/iOS). The README advertises a
much larger feature set that has **no runnable showcase**. Recommended additions,
each as a small `commonMain` scenario so it runs on all three targets and doubles
as a smoke test of the migrated API:

| Sample | Feature it showcases (from README) | Notes |
|--------|-----------------------------------|-------|
| `ParallelContextSample` | `parallel { branch(...) }` concurrent tools | exercises `nodeExecuteMultipleTools(parallelTools=true)` (3.4) |
| `SubphaseSample` | ordered `subphase { }` chaining | exercises `buildMultiStepSubgraph` |
| `HandoffSample` | multi-agent `handoff(agent) { }` | exercises `koogSession`/specialist routing |
| `StreamingSample` | `responseStream` token-by-token UI | exercises `StreamingFeature` (3.5) |
| `ObservabilitySample` | `eventSink` → `PrintlnEventSink` for every `AgentEvent` | guards 3.6 from silently breaking |
| `PersistenceSample` | `RoomSessionStore` / `loadOrRecover` / `SessionLoadResult` | desktop variant with an in-memory store |
| `ResumeAtSample` | `session.resumeAt(phase, userMessage)` | deep-link / notification entry |
| `StructuredOutputSample` | `PhaseOutput` + `JsonStructure` typed phase output | exercises 3.10 |
| `ResilienceSample` | `CircuitBreaker` + `RecoveryHint` + `retry { }` | pure-Kotlin, no model needed |
| `MigrationSample` | `StateMigration` chained schema upgrades | pure-Kotlin |
| `TestingShowcase` (in `commonTest`) | `testPhaseSession { on(...) { transitionTo / callTool / respondWith } }` + `assertPhase/assertToolCalled/assertState` | also re-validates `FakePromptExecutor` (3.11) post-migration |

Because these can live in `commonMain`/`commonTest` and use the
`koog-compose-testing` `FakePromptExecutor`, **most need no API key and no network**,
so they run under `:koog-compose-core:desktopTest` — which is exactly the gate that
will confirm the 1.0.0 migration on a machine that can build.

---

## 7. Suggested verification checklist (run where the build works)

```bash
# 1. After build-config + per-file fixes:
./gradlew :koog-compose-core:compileKotlinDesktop
./gradlew :koog-compose-testing:compileKotlinDesktop
./gradlew :koog-compose-core:desktopTest

# 2. Then the Android/iOS targets on a full toolchain:
./gradlew :koog-compose-core:compileDebugKotlinAndroid
./gradlew :koog-compose-core:compileKotlinIosSimulatorArm64

# 3. Each new showcase sample as a desktopTest smoke test.
```

Treat every **Verify** item above as "let the compiler confirm." The migration
notes told us *what categories* changed; only the koog 1.0.0 compiler can confirm
the *exact* symbol names — which this sandbox cannot run (Section 0).

---

## 8. One-line summary of "loopholes"

> Bump `koog` to `1.0.0` and JDK to `17`; the real work is the **graph-DSL node
> rename + auto-writeback removal** (`PhasestrategyBuilder.kt`), the **pipeline
> class rename** (`StreamingFeatureConfig.kt`), the **`PromptExecutor` subclass
> contract** (core + testing), the **EventHandler context reshape**
> (`KoogEventHandlerBridge.kt`), the **`Prompt`/`KoogClock` import moves**, and the
> **`OllamaClient.baseUrl` removal** — plus adding `commonMain` showcase samples
> that double as the desktop-test gate proving the migration.
