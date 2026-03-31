# koog-compose

`koog-compose` is an Android-first Kotlin Multiplatform runtime for building AI features that can orchestrate app logic, device capabilities, and UI from one DSL.

The current v1 shape is intentionally split:
- `koog-compose-core`: headless runtime, provider bridge, prompt stack, secure tool registry, session/tool loop
- `koog-compose-ui`: Compose state adapters plus small Material 3 primitives
- `koog-compose-device`: Android-only integrations for device APIs
- `koog-compose-testing`: scripted providers, fake tools, and confirmation doubles
- `sample-app`: the official Android example app

`composeApp` and `iosApp` remain in the repo as starter scaffolds, but they are not part of the supported library surface in this pass.

## Design Direction

This library is not meant to stop at "chat UI around an LLM".

The runtime is headless on purpose so developers can layer familiar, declarative syntax on top of it in the same spirit that made libraries like Koin and Haze easy to read:

```kotlin
val context = koogCompose {
    provider {
        openAI(apiKey = BuildConfig.API_KEY) {
            model = "gpt-4o"
        }
    }

    prompt {
        default { "You are an Android finance copilot." }
        enforce { "Never perform sensitive actions without confirmation." }
    }

    tools {
        register(GetCurrentLocationTool(context))
        register(UploadReceiptScreenshotTool(api))
        register(UpdateExpenseSummaryTool(firebase))
    }

    config {
        requireConfirmationForSensitive = true
        responseCache = true
    }
}
```

Today that DSL powers chat sessions and tool execution. The longer-term direction is broader orchestration:
- routines or phases layered on top of the same runtime
- multi-step permission flows for sensitive capabilities
- Android intents, screenshots, device APIs, and backend mutations coordinated in one tool graph
- intelligent execution paths that do not force the developer into a single chat-screen UX

That direction matters for the architecture in this repo: `core` stays headless, `ui` stays optional, and Android integrations live behind explicit tools instead of hard-coded widgets.

## Current Capabilities

- Koog `0.7.2` bridge adapted in `core`
- string-based provider config for OpenAI, Anthropic, Google, Ollama, and router modes
- secure tool execution with audit logging and confirmation for sensitive tools
- tool-call loop that appends call/result messages and resumes the assistant turn
- Compose `rememberChatState(...)` overloads that work with either an explicit provider or a `KoogComposeContext`
- Material 3 `ChatMessageList` and `ChatInputBar` primitives
- Android location tool in `koog-compose-device`
- scripted provider and fake tools in `koog-compose-testing`
- Android sample app that runs in demo mode without API keys

## Module Guide

### `koog-compose-core`

Owns the runtime:
- `KoogComposeContext`
- provider creation and Koog adapter
- prompt stack and session context
- secure tool registry
- `ChatSession` with tool confirmation and result replay

This is the layer to build future routine/phase orchestration on top of.

### `koog-compose-ui`

Owns Compose adapters, not the runtime itself:
- `rememberChatState(provider, context, userId)`
- `rememberChatState(context, userId)`
- `rememberChatState(userId) { ... }`
- `ConfirmationObserver`
- `ChatMessageList`
- `ChatInputBar`

The UI module is intentionally light. It exposes primitives and state holders instead of a single opinionated full-screen scaffold.

### `koog-compose-device`

Android-only device integrations.

This pass ships one real tool:
- `GetCurrentLocationTool`

Camera, WorkManager, intents, and multi-permission orchestration are intentionally deferred until they have concrete APIs and tests.

### `koog-compose-testing`

Deterministic helpers for tests and demos:
- `ScriptedAIProvider`
- `FakeSecureTool`
- auto-approve / auto-deny confirmation handlers

### `sample-app`

The official example app.

It demonstrates:
- basic send/response flow
- sensitive tool confirmation
- Android current-location tool
- optional live-provider mode through local env vars or Gradle properties

Supported sample configuration:
- `KOOG_SAMPLE_PROVIDER` / `koog.sample.provider`
- `KOOG_SAMPLE_API_KEY` / `koog.sample.apiKey`
- `KOOG_SAMPLE_MODEL` / `koog.sample.model`
- `KOOG_SAMPLE_BASE_URL` / `koog.sample.baseUrl`

## Build

Android-facing verification:

```shell
./gradlew :koog-compose-ui:compileDebugKotlinAndroid
./gradlew :koog-compose-device:compileDebugKotlinAndroid
./gradlew :koog-compose-testing:compileDebugKotlinAndroid
./gradlew :sample-app:assembleDebug
```

Core tests:

```shell
./gradlew :koog-compose-core:desktopTest
```

## Status

This repo is now aligned around an Android-first KMP library shape, not a generic Compose Multiplatform starter.

What is stable in this pass:
- the runtime/tool loop
- public Compose entry points
- Android location integration
- scripted demo/sample flow

What is intentionally next rather than done:
- routines/phases as first-class orchestration primitives
- richer Android integrations such as screenshots, intents, and WorkManager-based execution
- backend orchestration helpers for Firebase and app-specific APIs
- broader non-Android parity
