# koog-compose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brianmwas.koog_compose/koog-compose-core?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.brianmwas.koog_compose)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple.svg)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen.svg)](https://www.jetbrains.com/kotlin-multiplatform/)

`koog-compose` is a developer-first Kotlin Multiplatform (KMP) runtime for building AI-driven features that orchestrate app logic, device capabilities, and UI from a single declarative DSL.

Built on top of [JetBrains Koog](https://github.com/JetBrains/koog), it bridges the gap between AI agent graphs and real app surfaces — giving you phase-aware conversations, plug-and-play persistence, and Material 3 UI components that work across Android, iOS, and Desktop.

---

## Why koog-compose?

| Without koog-compose | With koog-compose |
|---|---|
| Wire LLM calls, tool execution, and UI state manually | Single `koogCompose { }` DSL handles the entire runtime |
| Roll your own conversation state machine | Built-in `phases { }` with LLM-driven auto-transitions |
| Build confirmation dialogs per feature | `AutoConfirmationHandler` with SAFE / SENSITIVE / CRITICAL tiers |
| Reinvent session persistence each project | Drop-in `session-room` battery with your own DAO |

---

## Modules

```
io.github.brianmwas.koog_compose:koog-compose-core          ← DSL, agent runtime, phase engine   (required)
io.github.brianmwas.koog_compose:koog-compose-ui            ← Material 3 Compose components       (optional)
io.github.brianmwas.koog_compose:koog-compose-device        ← Android/iOS device tools            (optional)
io.github.brianmwas.koog_compose:koog-compose-session-room  ← Room-backed persistent memory       (optional)
```

---

## Installation

Add to your `build.gradle.kts`:

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

### 1. Define your context

```kotlin
val context = koogCompose {
    // Choose your LLM provider
    provider {
        anthropic(apiKey = "your-key") {
            model = "claude-3-5-sonnet"
        }
    }

    // Build a phase-aware conversation graph
    phases {
        phase("greeting") {
            instructions { "Greet the user and offer to check their location." }
            onCondition("user asks for location", targetPhase = "location_check")
        }

        phase("location_check") {
            instructions { "You now have access to the user's GPS coordinates." }
            tool(GetCurrentLocationTool(androidContext)) // :device module
        }
    }
}
```

### 2. Add the Compose UI

```kotlin
@Composable
fun ChatScreen() {
    val chatState = rememberChatState(context)
    val snackbarHostState = remember { SnackbarHostState() }

    // Handles SAFE/SENSITIVE/CRITICAL confirmation tiers automatically
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

### 3. Add persistent memory (optional)

```kotlin
// Plug your own Room DAO — koog-compose handles the session lifecycle
val context = koogCompose {
    provider { /* ... */ }
    session {
        store(RoomSessionStore(db.sessionDao()))
    }
}
```

---

## Core concepts

### Phases

A `Phase` is a named state in your conversation graph. Each phase carries its own system instructions and tool access. The LLM transitions between phases automatically using generated transition tools — no manual routing code required.

```
greeting ──[user asks for location]──► location_check ──[done]──► summary
```

### Security tiers

Every tool action is assigned a risk tier. `AutoConfirmationHandler` maps tiers to the right UI friction:

| Tier | UI treatment | Example |
|---|---|---|
| `SAFE` | Silent / Snackbar | Reading calendar events |
| `SENSITIVE` | Bottom sheet confirmation | Sending a message |
| `CRITICAL` | Full-screen dialog | Deleting data, making a purchase |

### Session store

Implement `SessionStore` to plug in any persistence backend:

```kotlin
interface SessionStore {
    suspend fun save(session: ChatSession)
    suspend fun load(sessionId: String): ChatSession?
    suspend fun delete(sessionId: String)
}
```

The `:session-room` module provides a ready-made Room implementation.

---

## Platform support

| Feature | Android | iOS | Desktop (JVM) |
|---|---|---|---|
| Core DSL & phases | ✅ | ✅ | ✅ |
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

- Bug reports and feature requests → [GitHub Issues](https://github.com/YOUR_USERNAME/koog-compose/issues)
- Questions → [GitHub Discussions](https://github.com/YOUR_USERNAME/koog-compose/discussions)

---

## License

```
Copyright 2025 Brian Mwangi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```