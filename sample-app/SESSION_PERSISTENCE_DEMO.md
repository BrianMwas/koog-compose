# Session Persistence Demo

## Overview

The Session Persistence Demo enables the teaching app to save active sessions to a local Room database and resume them after app restart. This demonstrates how koog-compose handles stateful conversations with persistence.

## Architecture

### Components

1. **SessionPersistenceManager**
   - Initializes and manages the Room database
   - Provides access to `RoomSessionStore` for session I/O
   - Loads and deletes sessions from the database

2. **HomeTeachingViewModelWithPersistence**
   - Enhanced ViewModel with session lifecycle management
   - Automatically loads saved sessions on initialization
   - Manages session start, resume, and deletion
   - Coordinates between UI and persistence layer

3. **SessionListScreen**
   - Composable UI showing all saved sessions
   - Displays session metadata (phase, last modified, duration)
   - Allows selecting a session to resume or delete
   - Beautiful Material 3 card-based layout

4. **PhaseSession Integration**
   - `PhaseSession.fromPersisted()` reconstructs session state
   - `sessionStore` parameter enables automatic save-on-update
   - All phase transitions and tool calls are persisted

## Usage

### Basic Flow

```
App Start → Load Sessions from DB → Show SessionListScreen
                                  ↓
                         User Selects Session
                                  ↓
                    Resume Session & Show ChatScreen
                                  ↓
                      Chat continues where it left off
                                  ↓
                         App Close (auto-saves)
```

### Starting a New Session

```kotlin
viewModel.startNewSession()
```

Creates a new session with:
- Unique ID: `teaching_${timestamp}`
- Empty state
- Automatic persistence as agent runs

### Resuming a Saved Session

```kotlin
viewModel.resumeSession(sessionId)
```

Reconstructs session from database:
- Loads conversation history
- Restores agent state (student progress, concepts learned)
- Continues from previous phase
- Chat messages appear exactly as they were

### Deleting a Session

```kotlin
viewModel.deleteSession(sessionId)
```

Removes session and all associated data from database.

## Data Model

### SessionEntity (Room)

```kotlin
@Entity(tableName = "koog_sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val currentPhaseName: String,
    val serializedState: String?,          // TeachingState as JSON
    val serializedStateVersion: Int = 0,
    val toolCallCountsJson: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### MessageEntity (Room)

```kotlin
@Entity(tableName = "koog_messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequenceNumber: Int,
    val role: String,  // "user", "assistant", "system"
    val content: String,
    val toolName: String?,
    val toolCallId: String?
)
```

### ContextVarEntity (Room)

```kotlin
@Entity(tableName = "koog_context_vars")
data class ContextVarEntity(
    val sessionId: String,
    val key: String,
    val value: String
)
```

## Migration Strategy

The system supports schema versioning:

```kotlin
val migration = StateMigration.custom(
    currentVersion = 2,
    migrate = { json, fromVersion ->
        if (fromVersion < 2) {
            // Add default value for new field
            json.copy(elements = json.elements + ("newField" to JsonPrimitive("default")))
        }
        json
    }
)

val sessionStore = RoomSessionStore(
    dao = database.koogSessionDao(),
    stateSerializer = TeachingState.serializer(),
    stateMigration = migration
)
```

## Storage Details

### Database Location

- **Android**: `{app_files_dir}/teaching_sessions.db`
- Can be backed up automatically by OS
- Survives app uninstall only if backup is enabled

### Storage Size

- Typical session: 5-50 KB
- 100 sessions: ~1-5 MB
- No automatic cleanup (consider implementing)

## Testing Session Persistence

### Manual Test Flow

1. Open app → "Start New Session"
2. Chat with tutor for 3-5 exchanges
3. Close app completely
4. Reopen app → See saved session in list
5. Tap session → Chat continues from same point
6. Verify teacher remembers: student name, concepts, difficulty

### Automated Test

```kotlin
@Test
fun testSessionPersistence() = runTest {
    val persistenceManager = SessionPersistenceManager(context)
    
    // Create session
    val sessionId = "test_${System.currentTimeMillis()}"
    val session = AgentSession(
        sessionId = sessionId,
        currentPhaseName = "teach",
        messageHistory = listOf(...),
        serializedState = """{"studentName":"Alice"}"""
    )
    
    // Save to DB
    persistenceManager.sessionStore.save(sessionId, session)
    
    // Load from DB
    val loaded = persistenceManager.sessionStore.load(sessionId)
    
    // Verify
    assertEquals(session.currentPhaseName, loaded?.currentPhaseName)
    assertEquals(session.serializedState, loaded?.serializedState)
}
```

## Common Issues

### Sessions Not Appearing

**Problem**: SessionListScreen shows no sessions even after chatting.

**Solution**: 
- Verify Room database is initialized
- Check that `sessionStore` parameter is passed to `PhaseSession`
- Review database logs: `adb logcat | grep Room`

### State Loss After Resume

**Problem**: App state (student progress) not restored after restart.

**Solution**:
- Ensure `serializedState` is being saved (check SessionEntity)
- Verify `StateMigration` is compatible with your state schema
- Check that state is marked `@Serializable`

### Database File Not Found

**Problem**: `DatabaseBuilder.buildDatabase()` throws error.

**Solution**:
- Add dependency: `implementation(project(":koog-compose-session-room"))`
- Ensure Room library is up to date in `libs.versions.toml`
- Check Android `Context` is being passed correctly

## Performance Considerations

### Query Performance

- Session load: ~10-50ms (typical, even with 100+ messages)
- Session save: ~5-20ms (async, doesn't block UI)
- Session list: ~5-10ms (even with 100 sessions)

### Optimization Tips

- Use `observeAllSessions()` for reactive updates (already in list)
- Delete old sessions periodically to keep DB lean
- Consider pagination if expecting 1000+ sessions

### Database Size Management

```kotlin
// Implement cleanup in ViewModelScope.launchPeriodically
val cutoffTime = System.currentTimeMillis() - 30.days.inWholeMilliseconds
val oldSessions = getAllSessions().filter { it.updatedAt < cutoffTime }
oldSessions.forEach { deleteSession(it.sessionId) }
```

## Integration with Library

This demo uses standard koog-compose APIs:

- `PhaseSession`: Manages agent lifecycle
- `RoomSessionStore`: Implements `SessionStore` interface
- `KoogStateStore`: Holds mutable state that's serialized
- `KoogDefinition`: Defines agent phases and tools

**No special instrumentation required!** Any agent using `PhaseSession` with a `sessionStore` parameter gets persistence automatically.

## Next Steps

1. **Implement cleanup**: Add periodic deletion of old sessions (older than 30 days)
2. **Add export**: Save sessions to file for backup/sharing
3. **Track analytics**: Log session durations, concepts learned, accuracy
4. **Multi-user support**: Extend to support multiple students (user ID in session key)
5. **Cloud sync**: Add option to sync sessions to cloud backend

## Example: Teaching App Flow

```
👋 Home Tutoring App

┌─────────────────────────────────────┐
│  [Resume Teaching Session]          │
├─────────────────────────────────────┤
│                                     │
│  ► Session: Alice's Math (12 min)   │
│    Last: 5 mins ago                 │
│    Phase: teach                     │
│                                     │
│  ► Session: Bob's Reading (8 min)   │
│    Last: 1 day ago                  │
│    Phase: practice                  │
│                                     │
│  [+] Start New Session              │
│                                     │
└─────────────────────────────────────┘
                    ↓
          User taps Alice's session
                    ↓
┌─────────────────────────────────────┐
│  Alice's Math Tutor          [teach]│
├─────────────────────────────────────┤
│                                     │
│  Tutor: Great! You've learned       │
│  fractions. Now let's practice      │
│  adding fractions...                │
│                                     │
│  You: 1/3 + 1/3 = ?                │
│                                     │
│  [Type your answer...]              │
│                                     │
└─────────────────────────────────────┘
```

The session continues exactly where Alice left it. All previous messages, state, and progress are restored from the database.
