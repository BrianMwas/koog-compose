# koog-compose

`koog-compose` is a developer-first Kotlin Multiplatform (KMP) runtime for building AI-driven features that orchestrate app logic, device capabilities, and UI from a single, declarative DSL.

While Android-first in its integration depth, the library is architected for **Compose Multiplatform (CMP)** to ensure cross-platform stability and portability.

## 🏁 Getting Started

To get started with `koog-compose`, follow these steps to set up your runtime and UI.

### 1. Installation

Add the following to your `build.gradle.kts` (available on Maven Central soon):

```kotlin
dependencies {
    implementation("io.github.koogcompose:core:1.0.0")
    implementation("io.github.koogcompose:ui:1.0.0") // Optional: for Compose UI
    implementation("io.github.koogcompose:device:1.0.0") // Optional: for Android/iOS tools
}
```

### 2. Configure your Context

Use the `koogCompose` DSL to define your AI's provider, rules, and tools.

```kotlin
val context = koogCompose {
    // 1. Choose your LLM
    provider {
        anthropic(apiKey = "your-key") { model = "claude-3-5-sonnet" }
    }

    // 2. Define conversation phases
    phases {
        phase("greeting") {
            instructions { "Greet the user and offer to check their location." }
            onCondition("user asks for location", targetPhase = "location_check")
        }

        phase("location_check") {
            instructions { "You now have access to the user's GPS." }
            tool(GetCurrentLocationTool(androidContext)) // From :device module
        }
    }
}
```

### 3. Integrate with Compose UI

`koog-compose-ui` provides high-level state holders and Material 3 components.

```kotlin
@Composable
fun MyChatScreen() {
    // Create stable chat state
    val chatState = rememberChatState(context)

    // Wire automatic confirmation (SAFE/SENSITIVE/CRITICAL logic)
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

## 🚀 Recent v1 Milestone Updates

- **Phases & Conversation Graphs**: The conversation is now a state machine. The LLM can self-transition between "Phases" using auto-generated transition tools.
- **5-Star Security & UX**: Added an `AutoConfirmationHandler` that automatically selects the right UI friction (Snackbar vs. Dialog).
- **Plug-and-Play Persistence**: Refactored Room storage into an "Autonomous Battery" mode. Plug your own DAO into the session.
- **Enhanced Tracing & Telemetry**: Added a native `KoogEventBus` with support for `TracingSink`s.

## 📦 Module Guide

### `koog-compose-core`
The "Brain". Owns the `KoogComposeContext`, Phase-aware `ChatSession`, and the Koog graph bridge.

### `koog-compose-ui`
The "Face". Material 3 adapters, slots for custom media/voice, and the `KoogChatTheme`.

### `koog-compose-device`
The "Body". Android-specific tools (Location, soon: Screenshots, Intents).

### `koog-compose-session-room`
The "Memory". A pluggable KMP battery for persistent AI memory using Room.

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
- **Richer Android Hooks**: ActivityResult, WorkManager, and Screenshot context.
- **iOS Parity**: Bringing the Device module tools to iOS (CLLocation, PHPicker).
- **Backend Sinks**: Tracing exporters for Firebase and remote telemetry.
