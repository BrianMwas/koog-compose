package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lightweight picker between the cross-platform koog-compose samples.
 *
 * Used by the iOS entry point (and available to other platforms) so the
 * library's different showcase scenarios — a multi-phase home tutor and a
 * stateful trip planner with confirmation flows — are both reachable.
 */
@Composable
fun SampleSelectorApp(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Sample?>(null) }

    when (selected) {
        null -> SampleListScreen(modifier = modifier, onSelect = { selected = it })
        Sample.HomeTutor -> SimpleHomeTeachingApp(modifier = modifier)
        Sample.TripPlanner -> SimpleTripPlannerApp(modifier = modifier)
    }
}

private enum class Sample(val title: String, val description: String) {
    HomeTutor(
        title = "🏠 Home Tutor",
        description = "Multi-phase teaching agent that assesses, teaches, and tracks progress.",
    ),
    TripPlanner(
        title = "✈️ Trip Planner",
        description = "Plans a trip across phases with a live itinerary, budget tracking, " +
            "and a confirmation-gated booking tool.",
    ),
}

@Composable
private fun SampleListScreen(
    modifier: Modifier = Modifier,
    onSelect: (Sample) -> Unit,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            Text(
                text = "koog-compose Samples",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
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
                    }
                }
            }
        }
    }
}
