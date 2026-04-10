package io.github.koogcompose.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.session.KoogSession
import io.github.koogcompose.session.SessionRunnerHandle
import io.github.koogcompose.session.koogAgent
import io.github.koogcompose.session.koogSession
import io.github.koogcompose.session.multiAgentHandle
import io.github.koogcompose.phase.handoff

@Composable
fun MultiAgentSample(
    modifier: Modifier = Modifier,
) {
    val focusAgent = remember {
        koogAgent("focus") {
            instructions {
                """
                You are a focus session specialist. Help the user set up focus sessions,
                suggest pomodoro techniques, and encourage deep work.
                Keep responses concise and actionable.
                """.trimIndent()
            }
        }
    }

    val weatherAgent = remember {
        koogAgent("weather") {
            instructions {
                """
                You are a weather specialist. Provide friendly, brief weather forecasts.
                If the user doesn't specify a location, ask for one.
                Keep responses to 1-2 sentences.
                """.trimIndent()
            }
        }
    }

    val session: KoogSession<Unit> = remember(focusAgent, weatherAgent) {
        koogSession<Unit> {
            provider {
                ollama(model = "llama3.2")
            }

            main {
                instructions {
                    """
                    You are a general assistant. Answer most questions directly.
                    Route to specialists when:
                    - Focus, productivity, or pomodoro → handoff to focus agent
                    - Weather or forecasts → handoff to weather agent
                    """.trimIndent()
                }
                phases {
                    phase("root", initial = true) {
                        // Register handoff tools so the LLM can call them.
                        // handoff() returns a HandoffTool (a SecureTool).
                        // PhaseBuilder.tool(SecureTool) registers it in the phase's tool registry.
                        //
                        // The PhaseBuilder.handoff() extension is used here — it creates
                        // the HandoffTool and registers it via tool() internally.
                        handoff(target = focusAgent, description = {
                            "User asks about focus, productivity, pomodoro, or concentration"
                        })
                        handoff(target = weatherAgent, description =  {
                            "User asks about weather or wants a forecast"
                        })
                    }
                }
            }

            agents(focusAgent, weatherAgent)

            events {
                onPhaseTransitioned { /* log analytics */ }
                onToolCallRequested { /* show toast */ }
            }
        }
    }

    val handle: SessionRunnerHandle<Unit> = remember(session) {
        multiAgentHandle(
            session   = session,
            sessionId = "multi-agent-demo",
        )
    }

    val chatState = rememberChatState(
        handle  = handle,
        context = handle.context,
    )

    val currentAgent by handle.activeAgentName.collectAsState()

    val agentChips = listOf(
        "main"    to "General",
        "focus"   to "Focus",
        "weather" to "Weather",
    )

    EventObserver(chatState = chatState) { event ->
        when (event) {
            is KoogEvent.TurnFailed -> { /* log */ }
            else -> {}
        }
    }

    MaterialTheme {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text     = "Multi-Agent Assistant",
                        style    = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        items(agentChips) { (name, label) ->
                            AgentChip(
                                label    = label,
                                active   = currentAgent == name,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            },
            bottomBar = {
                ChatInputBar(
                    chatState   = chatState,
                    placeholder = "Ask about focus, weather, or anything...",
                )
            },
        ) { innerPadding ->
            ChatMessageList(
                chatState            = chatState,
                modifier             = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                showSystemMessages   = false,
                showToolCallMessages = true,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text     = "Active agent: $currentAgent",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp),
            )
        }
    }
}

@Composable
private fun AgentChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = if (active)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Text(
                text  = "●",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text  = label,
            color = if (active)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}