# Structured Observability with Pluggable Agent Event Sinks

## Overview

This release introduces a comprehensive observability layer to koog-compose, enabling production-grade monitoring, analytics, and audit trails. Developers can now route structured agent lifecycle events to any backend—Firebase, Datadog, a local database, or custom implementations—without modifying core runtime code.

## What's New

### 1. **Structured Agent Events** (`AgentEvent`)

Seven new sealed class event types capture every significant lifecycle moment:

- **`SessionStarted`** — fired on first user message; includes session ID and initial phase
- **`PhaseTransitioned`** — emitted whenever the LLM routes to a new phase; tracks `from`/`to` phases
- **`ToolCalled`** — recorded after any tool execution; includes tool name, arguments, and result
- **`GuardrailDenied`** — triggers when a tool is blocked by rate limits, allowlists, or user denial; includes reason
- **`AgentStuck`** — detected when the LLM repeats the same phase N times; includes fallback message
- **`TurnFailed`** — emitted when a turn exceeds retry attempts; includes error message and phase
- **`LLMRequested`** — (reserved for future use; enables request/response timing)

All events carry `timestampMs` with platform-specific implementations (JVM: `System.currentTimeMillis()`, iOS: `NSDate.timeIntervalSince1970`).

### 2. **Pluggable Event Sinks** (`EventSink`)

Simple suspension-safe interface for consuming events:

```kotlin
interface EventSink {
    suspend fun emit(event: AgentEvent)
}
```

**Built-in implementations:**

- **`PrintlnEventSink`** — structured console logging for development
- **`NoOpEventSink`** — silent sink for tests and production when no observability backend is configured

Users can implement custom sinks for:
- **Firebase Analytics** — event routing with custom payloads
- **Datadog APM** — trace correlation and performance monitoring
- **Local databases** — audit logs and compliance records
- **Custom backends** — HTTP APIs, message queues, streaming ingestion

### 3. **Configuration Integration** (`KoogConfig`)

New `eventSink` field added to `KoogConfig` with DSL support:

```kotlin
koogCompose<AppState> {
    config {
        eventSink = PrintlnEventSink          // dev
        eventSink = FirebaseEventSink()       // prod
        eventSink = NoOpEventSink             // tests
    }
}
```

Defaults to `NoOpEventSink` — zero overhead if observability is not configured.

### 4. **Emission Points** 

Events are wired at strategic runtime checkpoints:

| Component | Events Emitted | Rationale |
|-----------|---|---|
| **`PhaseSession`** | `SessionStarted`, `PhaseTransitioned`, `AgentStuck`, `TurnFailed` | Orchestration layer — captures session lifecycle |
| **`GuardedTool`** | `GuardrailDenied`, `ToolCalled` | Security boundary — records authorization and execution |
| **Future** | `LLMRequested` (from provider layer) | Provider layer — latency and token metrics |

### 5. **Async-Safe & Suspension** 

All `emit()` calls are non-blocking:
- Wrapped in `scope.launch { }` where appropriate
- Safe for database writes, API calls, or batching
- No impact on main agent turn if sink is slow

## Platform Support

| Platform | Status | Details |
|----------|--------|---------|
| **Android** | ✅ Full | `System.currentTimeMillis()` timestamp |
| **iOS** | ✅ Full | `NSDate.timeIntervalSince1970` timestamp |
| **Desktop** | ✅ Full | `System.currentTimeMillis()` timestamp |

## Migration Guide

### For existing users (non-breaking)

Zero changes required. Observability is opt-in:

```kotlin
// Existing code — works unchanged
koogCompose<AppState> {
    config { /* ... */ }
}
```

No events are emitted until an `eventSink` is explicitly configured.

### To enable observability

1. **Development** — use built-in console logging:
   ```kotlin
   config { eventSink = PrintlnEventSink }
   ```

2. **Production** — implement a custom sink:
   ```kotlin
   class FirebaseEventSink(private val analytics: FirebaseAnalytics) : EventSink {
       override suspend fun emit(event: AgentEvent) {
           analytics.logEvent(event::class.simpleName ?: "AgentEvent", when (event) {
               is AgentEvent.SessionStarted -> { /* map to Firebase bundle */ }
               is AgentEvent.ToolCalled -> { /* map to Firebase bundle */ }
               // ...
           })
       }
   }
   ```

3. **Tests** — silence all events:
   ```kotlin
   config { eventSink = NoOpEventSink }
   ```

## Use Cases

### Session Analytics
Track user funnel (SessionStarted → PhaseTransitioned events) to identify drop-off points.

### Audit Compliance
Log every tool call and guardrail denial for regulatory requirements (HIPAA, SOC 2, GDPR).

### Error Monitoring
Route `TurnFailed` events to Sentry/Datadog to correlate agent failures with model latency or input patterns.

### Feature Adoption
Count `ToolCalled` events by tool name to measure which device capabilities users actually invoke.

### Loop Detection
Alert when `AgentStuck` events exceed thresholds (using Datadog monitors, CloudWatch alarms, etc.).

### Performance Optimization
Measure agent efficiency by analyzing `PhaseTransitioned` patterns and phase duration.

## Implementation Details

### Files Added

- `io.github.koogcompose.observability.AgentEvent` — sealed class hierarchy
- `io.github.koogcompose.observability.EventSink` — interface + built-in implementations
- `io.github.koogcompose.observability.CurrentTimeMs` — expect/actual platform timestamps
  - `androidMain/CurrentTimeMs.android.kt`
  - `iosMain/CurrentTimeMs.ios.kt`
  - `desktopMain/CurrentTimeMs.desktop.kt`
- `io.github.koogcompose.observability.Observability` — public API typealiases

### Files Modified

- `KoogConfig` — added `eventSink` field and Builder integration
- `GuardedTool` — emits `GuardrailDenied` and `ToolCalled` events
- `PhaseSession` — emits `SessionStarted`, `PhaseTransitioned`, `AgentStuck`, `TurnFailed`
- `README.md` — comprehensive observability guide with examples

### Backward Compatibility

✅ **Fully backward compatible**
- No breaking changes to existing APIs
- All new fields have sensible defaults
- Observability is 100% opt-in
- Existing code continues to work unchanged

## Documentation

See [README.md](README.md#observability--event-tracking) for:
- Full event reference table
- Firebase implementation example
- DI container patterns for dev/prod/test separation
- Best practices for production deployments

## Testing

All observability code is tested via:
- Unit tests for event emission sequences
- Integration tests with fake sinks
- E2E tests with phase transitions

Example test:
```kotlin
@Test
fun `AgentStuck event emitted when stuck threshold reached`() {
    val events = mutableListOf<AgentEvent>()
    val session = testPhaseSession(
        config = { eventSink = object : EventSink {
            override suspend fun emit(event: AgentEvent) { events.add(event) }
        }}
    )
    
    // Trigger stuck condition...
    assertContains(events.map { it::class }, AgentEvent.AgentStuck::class)
}
```

## Breaking Changes

**None.** This is a pure additive release.

## Deprecations

**None.**

## Future Work

1. **`LLMRequested` event** (placeholder for future) — enable request/response timing and token metrics at the provider layer
2. **Event batching** — optional built-in batching sink to reduce API calls in high-frequency scenarios
3. **Structured logging** — JSON-formatted sink for cloud logging platforms (Cloud Logging, ELK)
4. **Correlation IDs** — tracing support for distributed session replay tools

## Patch Version Justification

This release increments the **patch version** because:
- ✅ Zero breaking changes
- ✅ Fully backward compatible
- ✅ Pure additive feature (new optional field, new sealed class)
- ✅ No changes to existing public method signatures
- ✅ No changes to configuration DSL (only additions)

---

**Related Issues**: [Link to tracking issue if applicable]
**Tested on**: Android 26→34, iOS 16→17, Desktop (JVM 21)
