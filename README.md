# koog-compose

`koog-compose` is a developer-first Kotlin Multiplatform (KMP) runtime for building AI-driven features that orchestrate app logic, device capabilities, and UI from a single, declarative DSL.

While Android-first in its integration depth, the library is architected for **Compose Multiplatform (CMP)** to ensure cross-platform stability and portability.

## 🚀 Recent v1 Milestone Updates

We've recently shifted from a basic tool loop to a **Structured Orchestration Framework**. Key highlights:

- **Phases & Conversation Graphs**: The conversation is now a state machine. The LLM can self-transition between "Phases" (Discovery, Payment, Support) using auto-generated transition tools.
- **5-Star Security & UX**: Added an `AutoConfirmationHandler` that automatically selects the right UI friction (Snackbar for SENSITIVE tools, Dialog for CRITICAL tools) based on `PermissionLevel`.
- **Plug-and-Play Persistence**: Refactored Room storage into an "Autonomous Battery" mode. Users can plug their own DAO into the session without being forced into a specific database file or configuration.
- **Enhanced Tracing & Telemetry**: Added a native `KoogEventBus` with support for `TracingSink`s. Ready-to-use `ConsoleTracingSink` included for Logcat/Console debugging.
- **Scripted Determinism**: The testing provider now supports `PhaseValidation`, allowing you to assert that the AI is in the correct state before returning scripted responses.

## 🏗 Design Direction

The runtime is intentionally headless. Developers layer familiar syntax on top of it:

```kotlin
val context = koogCompose {
    provider {
        anthropic(apiKey = BuildConfig.ANTHROPIC_KEY) { model = "claude-3-5-sonnet" }
    }

    phases {
        phase("discovery") {
            instructions { "Help the user find products." }
            tool(SearchTool())
            onCondition("user wants to checkout", targetPhase = "checkout")
        }

        phase("checkout") {
            instructions { "Collect shipping and payment info." }
            tool(PaymentTool()) // CRITICAL level -> triggers Dialog
            onCondition("payment complete", targetPhase = "discovery")
        }
    }
}
```

## ✨ Current Capabilities

- **Core Engine**: Fully aligned with Koog `0.7.2` using modern `TypeToken` and `AIAgent` graph APIs.
- **Multi-Phase Transitions**: Automatic tool-based state management driven by the LLM.
- **Theme-Agnostic UI**: `KoogChatTheme` with `LocalChatColors` and `LocalChatShapes` for 100% manual UI customization.
- **Modern Chat Primitives**: `ChatInputBar` with paper-envelope send icons and `leading/trailing` action slots for voice/media.
- **Security**: Robust `PermissionManager` coordinating framework permissions (SAFE/CRITICAL) and system-level OS permissions.
- **Testing**: `ScriptedAIProvider` for deterministic UI testing in CMP environments.

## 📦 Module Guide

### `koog-compose-core`
The "Brain". Owns the `KoogComposeContext`, Phase-aware `ChatSession`, and the Koog graph bridge. Shared logic for all platforms.

### `koog-compose-ui`
The "Face". Material 3 adapters, slots for custom media/voice, and the `KoogChatTheme` provider.

### `koog-compose-device`
The "Body". Android-specific tools (Location, soon: Screenshots, Intents). Designed as a "Battery" module that you plug into Core.

### `koog-compose-testing`
The "Lab". Scripted providers and fake tools for unit/UI testing.

## 🛠 Build & Test

Core logic (commonMain):
```shell
./gradlew :koog-compose-core:desktopTest
```

Android verification:
```shell
./gradlew :sample-app:assembleDebug
```

## 🎯 What's Next
- **Richer Android Hooks**: Built-in orchestration for `ActivityResult`, `WorkManager` background sync, and Screenshot context.
- **iOS Parity**: Bringing the Device module tools to iOS (CLLocation, PHPicker).
- **Backend Sinks**: Tracing exporters for Firebase and remote telemetry.
