package io.github.koogcompose.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.sample.HomeTeachingApp

class MainActivity : ComponentActivity() {

    // ActivityResult registry — registered once, used by koog-compose tools
    private lateinit var activityResults: KoogActivityResultRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create registry with context, then register launchers
        activityResults = KoogActivityResultRegistry(this)
        activityResults.register(this)

        setContent {
            MaterialTheme {
                AndroidSampleSelectorApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Android's Home Tutor card launches the Android-enhanced version (camera
 * capture + Room persistence, see [HomeTeachingApp]) instead of the plain
 * in-memory [SimpleHomeTeachingApp] used on iOS/desktop. Every other sample
 * is identical to the cross-platform [SampleSelectorApp].
 */
private enum class AndroidSample(val title: String, val description: String, val library: String) {
    GettingStarted(
        title = "🚀 Getting Started",
        description = "The smallest working agent: greet → teach → done. Start here.",
        library = "koogCompose { } basics, phases, tools",
    ),
    HomeTutor(
        title = "🏠 Home Tutor",
        description = "Multi-phase teaching agent with camera homework capture and " +
            "session persistence.",
        library = "6-phase graph, camera tool, Room persistence",
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
}

@Composable
private fun AndroidSampleSelectorApp(modifier: Modifier = Modifier) {
    var providerSelection by remember { mutableStateOf<SampleProviderSelection?>(null) }
    var selected by remember { mutableStateOf<AndroidSample?>(null) }

    val selection = providerSelection
    when {
        selection == null -> ProviderSetupScreen(
            initial = SampleProviderSelection(),
            onContinue = { providerSelection = it },
            modifier = modifier,
        )

        selected == null -> AndroidSampleListScreen(
            selection = selection,
            modifier = modifier,
            onSelect = { selected = it },
            onChangeProvider = { providerSelection = null },
        )

        else -> {
            val providerConfig = selection.toProviderConfig()
            when (selected) {
                AndroidSample.GettingStarted -> SimpleQuickStartApp(modifier = modifier, providerConfig = providerConfig)
                AndroidSample.HomeTutor -> HomeTeachingApp(modifier = modifier, providerConfig = providerConfig)
                AndroidSample.TripPlanner -> SimpleTripPlannerApp(modifier = modifier, providerConfig = providerConfig)
                AndroidSample.MultiAgent -> MultiAgentSample(modifier = modifier, providerConfig = providerConfig)
                AndroidSample.RobustTeaching -> SimpleRobustTeachingApp(modifier = modifier, providerConfig = providerConfig)
                null -> Unit
            }
        }
    }
}

@Composable
private fun AndroidSampleListScreen(
    selection: SampleProviderSelection,
    modifier: Modifier = Modifier,
    onSelect: (AndroidSample) -> Unit,
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
            AndroidSample.entries.forEach { sample ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
