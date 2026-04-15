# Error Handling Best Practices — Robust Teaching App

**File**: [RobustTeachingApp.kt](src/commonMain/kotlin/io/github/koogcompose/sample/RobustTeachingApp.kt)  
**Related Issue**: #42 — Error Handling & Recovery Patterns  
**Test Coverage**: [RobustHandlingE2ETest.kt](../../koog-compose-testing/src/commonTest/kotlin/io/github/koogcompose/test/RobustHandlingE2ETest.kt)

---

## Overview

The `RobustTeachingApp` demonstrates production-grade error handling for AI agent systems. Rather than assuming the "happy path," this sample shows how to:

1. **Classify errors** by type for metrics and recovery decisions
2. **Use circuit breakers** to prevent cascading failures
3. **Provide recovery hints** to guide agent and user behavior
4. **Detect session corruption** and recover gracefully
5. **Fall back to degraded mode** when all providers fail
6. **Observable all error paths** for debugging and monitoring

---

## Architecture: Four Layers of Error Recovery

```
┌─────────────────────────────────────────────────────────┐
│ USER EXPERIENCE LAYER                                   │
│ (Friendly messages, no stack traces, recovery hints)    │
├─────────────────────────────────────────────────────────┤
│ TOOL LAYER                                              │
│ (SaveHomeworkPhotoTool, TrackProgressTool, etc.)        │
│ Each tool classifies errors & returns ToolResult        │
├─────────────────────────────────────────────────────────┤
│ CIRCUIT BREAKER LAYER                                   │
│ (Prevents cascading failures after N failures)          │
├─────────────────────────────────────────────────────────┤
│ PROVIDER LAYER                                          │
│ (Fallback: on-device → cloud → local → degraded)       │
└─────────────────────────────────────────────────────────┘
```

---

## Scenario: Network Timeout

### What Happens

1. **Tool attempts file operation** (save photo)
2. **Network is down** → IOException("Connection timeout")
3. **Tool catches exception** → classifies as `NetworkError`
4. **Returns `ToolResult.Failure`** with `retryable = true`
5. **Session sees retryable flag** → waits, then retries (3 attempts default)
6. **User sees non-technical message**: "Network hiccup while saving. Retrying shortly..."

### Code Pattern

```kotlin
class SaveHomeworkPhotoTool(...) : StatefulTool<RobustAppState>() {
    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val savedPath = saveToDisk(photoUri)
            ToolResult.Success("Photo saved successfully.")
        } catch (e: Exception) {
            when {
                isNetworkRelated(e.message) -> ToolResult.Failure(
                    message = "Network hiccup while saving. Retrying shortly...",
                    retryable = true,
                    recoveryHint = RecoveryHint.RetryAfterDelay,
                )
                // ... other cases
            }
        }
    }
}
```

### Recovery

- **Automatic**: Session retries 3 times with exponential backoff (1s, 2s, 4s)
- **User-initiated**: Agent can ask user to try again once network returns
- **Graceful degradation**: If retries exhausted, suggests trying later

---

## Scenario: Permission Denied

### What Happens

1. **Tool attempts file write** (save photo)
2. **SecurityException("Permission denied")** thrown
3. **Tool catches exception** → classifies as `PermissionError`
4. **Returns `ToolResult.Denied`** (not transient)
5. **User sees actionable message**: "Please grant permission in Settings"

### Code Pattern

```kotlin
} catch (e: Exception) {
    when {
        e is SecurityException -> ToolResult.Denied(
            reason = "Storage permission was denied.",
            recoveryHint = RecoveryHint.RequiresUserAction(
                "Go to Settings → Permissions → Storage and grant access"
            )
        )
    }
}
```

### Recovery

- **Not automatic** (retry won't help, permission must be granted)
- **Recovery hint guides user** to exact setting location
- **Session passes hint to UI** for in-app permission dialog

---

## Scenario: Storage Full

### What Happens

1. **Tool attempts file write** (save photo)
2. **IOException("No space left on device")** thrown
3. **Tool catches exception** → classifies as `StorageError`
4. **Returns `ToolResult.Denied`** with user action hint
5. **User sees guidance**: "Please free up space and say 'try again'"

### Code Pattern

```kotlin
} catch (e: Exception) {
    when {
        isStorageFull(e.message) -> ToolResult.Denied(
            reason = "Your storage is full.",
            recoveryHint = RecoveryHint.RequiresUserAction(
                "Please delete some files and say 'try again'."
            )
        )
    }
}
```

### Recovery

- **User action required** (delete files)
- **Agent can monitor storage** and retry after user frees space
- **Circuit breaker doesn't open** (not a service issue, user issue)

---

## Scenario: Circuit Breaker Prevents Cascade

### What Happens

1. **File operation fails 5 times in a row** (e.g., API rate limited)
2. **Circuit breaker opens** (state = OPEN)
3. **6th attempt is rejected immediately** without even trying
4. **Tool returns user-friendly failure**: "This feature is temporarily unavailable"
5. **All subsequent calls skip the failing resource** (60s cooldown)
6. **After 60s, circuit enters HALF_OPEN** (trial phase)
7. **Next attempt tries again** (2 successes required to fully close)

### Code Pattern

```kotlin
val fileOperationBreaker = CircuitBreaker(
    failureThreshold = 5,      // Open after 5 failures
    cooldownMs = 60_000,       // 60s cooldown before trying again
    successThreshold = 2       // Need 2 successes to close
)

tool(
    CircuitBreakerGuard(
        delegate = SaveHomeworkPhotoTool(...),
        circuitBreaker = fileOperationBreaker
    )
)
```

### Recovery States

```
CLOSED (normal)
    ↓ (5 failures)
OPEN (rejecting all calls)
    ↓ (60s pass)
HALF_OPEN (trial mode)
    ↓ (2 successes)
CLOSED (recovered!)
    OR
    ↓ (1 failure)
OPEN (back to rejecting)
```

---

## Scenario: Model File Missing

### What Happens

1. **Session starts**
2. **On-device provider tries to load** `/data/models/gemma-4.litertlm`
3. **FileNotFoundException** thrown
4. **Provider falls through to next** (Anthropic Cloud)
5. **If cloud API key exists**, uses Anthropic
6. **If no cloud API**, tries Ollama (local instance)
7. **If all fail**, enters degraded mode

### Code Pattern

```kotlin
provider {
    router(strategy = RouterStrategy.Fallback) {
        // 1. Try on-device first
        onDevice(modelPath = "/data/models/gemma-4.litertlm") {
            maxToolRounds(5)
            timeout = 30_000
        }
        
        // 2. Fall back to cloud
        anthropic(apiKey = BuildConfig.ANTHROPIC_KEY ?: "") {
            model = "claude-sonnet-4-5"
            timeout = 60_000
        }
        
        // 3. Fall back to local Ollama
        ollama(model = "llama3.2") {
            baseUrl = "http://localhost:11434"
            timeout = 20_000
        }
    }
}
```

### Recovery

- **Automatic**: Fallback chain handles transparently
- **User never sees error** if any provider available
- **Session tracks which provider** succeeded (telemetry)

---

## Scenario: Session Corruption

### What Happens

1. **App crashes mid-session**
2. **State file corrupted** during write
3. **On restart, session loads state**
4. **Checksum doesn't match** (or values impossible: retryCount=500)
5. **Corruption detected** → state reset to clean RobustAppState()
6. **User sees**: "We had an issue, starting fresh"

### Code Pattern

```kotlin
val currentState = stateStore.current

val isCorrupted = 
    currentState.retryCount > 100 ||  // Sanity check
    (currentState.lastSuccessfulToolRunMs > System.currentTimeMillis()) ||
    (currentState.stateChecksum != null && 
     currentState.stateChecksum != computeStateChecksum(currentState))

if (isCorrupted) {
    println("[ERROR] Session corrupted — resetting to clean state")
    stateStore.reset(RobustAppState())
}
```

### Recovery

- **Automatic detection** on session load
- **Safe reset** to known-good state
- **User informs agent** they're starting fresh (natural language)

---

## Scenario: Degraded Mode (All Providers Fail)

### What Happens

1. **On-device model file missing**
2. **Anthropic API key not set**
3. **Local Ollama not running**
4. **Session initializes in degraded mode**
5. **Agent uses cached knowledge only** (no LLM reasoning)
6. **Offers simple exercises** from local cache
7. **Informs user**: "I'm having trouble connecting, but we can still practice"

### Code Pattern

```kotlin
class DegradedModeTeachingStrategy {
    fun getFallbackMessage(): String = 
        "I'm having trouble connecting to my knowledge system right now, " +
        "but we can still practice! Let me give you a math problem..."
    
    fun getCachedExercise(): String = 
        "Let's try this: What is 25% of 80?"
}
```

### Recovery

- **User can retry** after fixing one of:
  - Download on-device model
  - Set Anthropic API key
  - Start local Ollama instance
- **Session detects recovery** when next provider available

---

## Error Classification System

All errors are classified into one of these categories:

| Category | Examples | Recovery |
|----------|----------|----------|
| `NetworkError` | timeout, connection refused, DNS | Retry after delay |
| `PermissionError` | permission denied, unauthorized | User action: grant setting |
| `StorageError` | disk full, write permission | User action: free space |
| `ResourceNotFound` | model missing, 404, file not found | Fall back to next provider |
| `AuthenticationError` | invalid API key, expired token, 401 | Fix configuration |
| `ServiceUnavailable` | 503, service down, provider offline | Retry after delay + fallback |
| `RateLimitError` | 429, rate limit exceeded | Exponential backoff |
| `DataValidationError` | malformed response, corrupt state | Log and skip / reset state |
| `ConfigurationError` | missing key, invalid setup | Fix configuration |
| `UnknownError` | anything else | Log and fail gracefully |

---

## Observable Events for Monitoring

The session emits events for all error paths:

```kotlin
events {
    onTurnFailed { event ->
        // Classify error, update state, log for metrics
        val errorType = classifyError(event.message)
        stateStore.update { it.copy(lastError = event.message) }
    }
    
    onToolFailed { event ->
        // Track tool failures for circuit breaker
        stateStore.update { state ->
            state.copy(toolFailureCount = state.toolFailureCount + 1)
        }
    }
    
    onAgentStuck { event ->
        // Detect if agent loops, may trigger fallback
    }
    
    onTurnSucceeded { event ->
        // Reset failure counters on success
        stateStore.update { state ->
            state.copy(
                lastError = null,
                toolFailureCount = 0,
                lastSuccessfulToolRunMs = System.currentTimeMillis()
            )
        }
    }
}
```

**Metrics captured**:
- `error_type` (NetworkError, PermissionError, etc.)
- `tool_name` (which tool failed)
- `failure_count` (cumulative)
- `recovery_hint` (what was suggested)
- `recovery_outcome` (success, user_action_needed, etc.)

---

## Best Practices Summary

### 1. Classify Errors Explicitly

✅ **Do**:
```kotlin
when {
    message.contains("timeout") -> return ToolResult.Failure(..., retryable = true)
    message.contains("permission") -> return ToolResult.Denied(...)
    else -> return ToolResult.Failure(..., retryable = false)
}
```

❌ **Avoid**:
```kotlin
throw RuntimeException("Something went wrong")
```

### 2. Provide Recovery Hints

✅ **Do**:
```kotlin
ToolResult.Denied(
    reason = "Permission denied",
    recoveryHint = RecoveryHint.RequiresUserAction(
        "Please go to Settings → Permissions and grant access"
    )
)
```

❌ **Avoid**:
```kotlin
ToolResult.Denied("Permission denied")
```

### 3. Use Circuit Breakers for External Calls

✅ **Do**:
```kotlin
val breaker = CircuitBreaker(failureThreshold = 5, cooldownMs = 60_000)
tool(CircuitBreakerGuard(delegate = myTool, circuitBreaker = breaker))
```

❌ **Avoid**:
```kotlin
// Unbounded retries → can cascade failures
repeat(1000) { myTool.execute(args) }
```

### 4. Emit Observable Events

✅ **Do**:
```kotlin
eventSink.emit(
    AgentEvent.ToolCalled(
        sessionId = id,
        toolName = "SavePhoto",
        result = ToolResult.Failure(...),
    )
)
```

❌ **Avoid**:
```kotlin
// Silent failures → no visibility
try { ... } catch (e: Exception) { }
```

### 5. Validate State on Load

✅ **Do**:
```kotlin
val isCorrupted = state.retryCount > 100 || 
                  state.checksum != computeChecksum(state)
if (isCorrupted) stateStore.reset(RobustAppState())
```

❌ **Avoid**:
```kotlin
// Just assume state is valid
val state = loadState()
```

### 6. Show User-Friendly Messages

✅ **Do**:
```kotlin
"Network hiccup while saving. Retrying shortly..."
```

❌ **Avoid**:
```kotlin
"java.net.SocketTimeoutException: Connection timeout after 5s"
```

---

## Testing Your Error Handlers

The test suite [RobustHandlingE2ETest.kt](../../koog-compose-testing/src/commonTest/kotlin/io/github/koogcompose/test/RobustHandlingE2ETest.kt) covers all scenarios:

```kotlin
@Test
fun `circuit breaker opens after failureThreshold failures`() { ... }

@Test
fun `circuit breaker recovers after cooldown`() { ... }

@Test
fun `permission denied tool returns Denied result`() { ... }

@Test
fun `corrupted session can be detected and cleared`() { ... }

@Test
fun `recovery hint chains guide agent decisions`() { ... }
```

Run tests:
```sh
./gradlew :koog-compose-testing:commonTest
```

---

## References

- **Related GitHub Issue**: #42 — Error Handling & Recovery Patterns
- **Circuit Breaker Pattern**: [Resilience4j Docs](https://resilience4j.readme.io/docs/circuitbreaker)
- **Fallback Patterns**: [Netflix Hystrix](https://github.com/Netflix/Hystrix)
- **Error Handling Philosophy**: [Go's `error` interface](https://golang.org/doc/effective_go#errors)

---

**Last Updated**: April 2026  
**Framework Version**: koog-compose v1.5.0+  
**Status**: Production-ready sample
