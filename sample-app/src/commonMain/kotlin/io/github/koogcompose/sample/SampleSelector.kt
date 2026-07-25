package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cross-platform picker for every koog-compose sample. Shows a one-time
 * [ProviderSetupScreen] first (change it later via "Change provider" on the
 * list screen), then lets the user launch any sample against that provider.
 *
 * Used directly by the iOS entry point; Android's [MainActivity] uses its
 * own list so it can additionally offer the Android-enhanced Home Tutor
 * (camera capture + Room persistence), but shares this same provider setup.
 */
@Composable
fun SampleSelectorApp(modifier: Modifier = Modifier) {
    var providerSelection by remember { mutableStateOf<SampleProviderSelection?>(null) }
    var selected by remember { mutableStateOf<Sample?>(null) }

    val selection = providerSelection
    when {
        selection == null -> ProviderSetupScreen(
            initial = SampleProviderSelection(),
            onContinue = { providerSelection = it },
            modifier = modifier,
        )

        selected == null -> SampleListScreen(
            selection = selection,
            modifier = modifier,
            onSelect = { selected = it },
            onChangeProvider = { providerSelection = null },
        )

        else -> {
            val providerConfig = selection.toProviderConfig()
            when (selected) {
                Sample.GettingStarted -> SimpleQuickStartApp(modifier = modifier, providerConfig = providerConfig)
                Sample.HomeTutor -> SimpleHomeTeachingApp(modifier = modifier, providerConfig = providerConfig)
                Sample.TripPlanner -> SimpleTripPlannerApp(modifier = modifier, providerConfig = providerConfig)
                Sample.MultiAgent -> MultiAgentSample(modifier = modifier, providerConfig = providerConfig)
                Sample.RobustTeaching -> SimpleRobustTeachingApp(modifier = modifier, providerConfig = providerConfig)
                Sample.MoneyCoach -> MoneyCoachApp(modifier = modifier, providerConfig = providerConfig)
                null -> Unit
            }
        }
    }
}

enum class Sample(val title: String, val description: String, val library: String) {
    GettingStarted(
        title = "🚀 Getting Started",
        description = "The smallest working agent: greet → teach → done. Start here.",
        library = "koogCompose { } basics, phases, tools",
    ),
    HomeTutor(
        title = "🏠 Home Tutor",
        description = "Multi-phase teaching agent that assesses, teaches, and tracks progress.",
        library = "6-phase graph, stateful tools, guardrails, stuck detection",
    ),
    TripPlanner(
        title = "✈️ Trip Planner",
        description = "Plans a trip across phases with a live itinerary, budget tracking, " +
            "and a confirmation-gated booking tool.",
        library = "Live state UI, SENSITIVE tool + confirmation dialog",
    ),
    MultiAgent(
        title = "🧑‍🤝‍🧑 Multi-Agent",
        description = "A general assistant that hands off to focus and weather specialists.",
        library = "koogAgent, handoff, multi-agent sessions",
    ),
    RobustTeaching(
        title = "🛡️ Robust Teaching",
        description = "Same teaching agent wrapped in circuit breakers and degraded-mode handling.",
        library = "CircuitBreaker, retry/backoff, stuck detection, observability",
    ),
    MoneyCoach(
        title = "💸 Money Coach",
        description = "The agent answers by building the screen — it emits layout directives " +
            "to show spend breakdowns and an FX chart, and the engine rejects any it is not " +
            "permitted to show. Ambient gradient reflects the agent's tempo.",
        library = "Layout directives, slots + tag/permission policy, AmbientGradient",
    ),
}

@Composable
private fun SampleListScreen(
    selection: SampleProviderSelection,
    modifier: Modifier = Modifier,
    onSelect: (Sample) -> Unit,
    onChangeProvider: () -> Unit,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "koog-compose Samples",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Running on ${selection.summary()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onChangeProvider) {
                    Text("Change provider")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Sample.entries.forEach { sample ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    onClick = { onSelect(sample) },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = sample.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = sample.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "Demonstrates: ${sample.library}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
