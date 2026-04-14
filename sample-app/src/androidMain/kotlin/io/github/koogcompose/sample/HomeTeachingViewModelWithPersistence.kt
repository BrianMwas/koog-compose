package io.github.koogcompose.sample

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogDefinition
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.room.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Enhanced HomeTeachingViewModel with session persistence support.
 * Loads previous sessions from Room database and allows resuming them.
 */
class HomeTeachingViewModelWithPersistence(
    agentDef: KoogDefinition<TeachingState>,
    private val persistenceManager: SessionPersistenceManager,
) : ViewModel() {

    private val context = agentDef as KoogComposeContext<TeachingState>
    private val executor = agentDef.createExecutor()
    private val androidContext = context as? Context ?: throw IllegalStateException("Context must be Android Context")

    private val _savedSessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val savedSessions: StateFlow<List<SessionEntity>> = _savedSessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _session = MutableStateFlow<PhaseSession<TeachingState>?>(null)
    val session: StateFlow<PhaseSession<TeachingState>?> = _session.asStateFlow()

    val currentPhase: StateFlow<String>
        get() = _session.value?.currentPhase ?: MutableStateFlow("")

    val isRunning: StateFlow<Boolean>
        get() = _session.value?.isRunning ?: MutableStateFlow(false)

    val appState: StateFlow<TeachingState>
        get() = _session.value?.appState ?: context.stateStore?.stateFlow
            ?: MutableStateFlow(TeachingState())

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            try {
                persistenceManager.dao.observeAllSessions().collect { sessions ->
                    _savedSessions.value = sessions
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _savedSessions.value = emptyList()
            }
        }
    }

    fun startNewSession() {
        val sessionId = "teaching_${System.currentTimeMillis()}"
        _currentSessionId.value = sessionId

        val stateStore = KoogStateStore(TeachingState())
        val agentDef = buildTeachingContextWithPersistence(
            stateStore = stateStore,
            activityResults = KoogActivityResultRegistry(androidContext),
            context = androidContext,
        )

        val ctx = agentDef as KoogComposeContext<TeachingState>
        val exec = agentDef.createExecutor()

        val newSession = PhaseSession(
            context = ctx,
            executor = exec,
            sessionId = sessionId,
            store = persistenceManager.sessionStore,
            scope = viewModelScope,
        )
        _session.value = newSession
    }

    fun resumeSession(sessionId: String) {
        viewModelScope.launch {
            try {
                // Load the persisted session data
                val loadedSession = persistenceManager.sessionStore.load(sessionId)
                if (loadedSession != null) {
                    _currentSessionId.value = sessionId

                    // Reconstruct the state from JSON
                    val restoredState = if (loadedSession.serializedState != null) {
                        try {
                            Json.decodeFromString<TeachingState>(loadedSession.serializedState!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            TeachingState()
                        }
                    } else {
                        TeachingState()
                    }

                    val stateStore = KoogStateStore(restoredState)
                    val agentDef = buildTeachingContextWithPersistence(
                        stateStore = stateStore,
                        activityResults = KoogActivityResultRegistry(androidContext),
                        context = androidContext,
                    )

                    val ctx = agentDef as KoogComposeContext<TeachingState>
                    val exec = agentDef.createExecutor()

                    // Create new session with persisted store
                    // This will allow future saves to go back to the database
                    val resumedSession = PhaseSession(
                        context = ctx,
                        executor = exec,
                        sessionId = sessionId,
                        store = persistenceManager.sessionStore,
                        scope = viewModelScope,
                    )
                    _session.value = resumedSession
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun send(message: String) {
        viewModelScope.launch {
            _session.value?.send(message)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                persistenceManager.deleteSession(sessionId)
                loadSessions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
