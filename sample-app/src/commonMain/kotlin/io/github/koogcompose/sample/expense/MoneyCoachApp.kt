package io.github.koogcompose.sample.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.FormFactor
import io.github.koogcompose.layout.Platform
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.ui.ambient.AmbientGradient
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.layout.ComponentContentProvider
import io.github.koogcompose.ui.layout.LayoutSlotHost
import io.github.koogcompose.ui.state.rememberChatState

/**
 * Money Coach — the sample that shows the agent *restructuring the UI*, not just talking.
 *
 * Every other sample streams text into a chat bubble. This one hands the model a single
 * tool (auto-registered by the `layout { }` block) whose only power is to emit layout
 * directives naming components from [expenseComponentRegistry]. Ask it "where did my
 * money go?" and it shows the category breakdown; ask about the shilling and it tries the
 * FX chart — which the reducer rejects outright if the active [MoneyCoachRole] lacks
 * [VIEW_FX]. The prompt never enforces that; the engine does.
 *
 * The ambient gradient on top is driven by the same session's activity, so the screen
 * shows tempo (reasoning / streaming / tool call) without a spinner.
 */

/** Typed session state. Kept trivial — the interesting state here is the layout itself. */
public data class MoneyCoachState(
    val questionsAsked: Int = 0,
)

/**
 * Builds the Money Coach session.
 *
 * @param platform reported to the policy layer so the agent can reason about form factor.
 * @param canViewFx when false, FX directives are rejected by the engine — flip it to see
 *   the permission tier work without touching the prompt.
 */
public suspend fun createMoneyCoachSession(
    stateStore: KoogStateStore<MoneyCoachState>,
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
    platform: Platform = Platform.Desktop,
    formFactor: FormFactor = FormFactor.Desktop,
    canViewFx: Boolean = true,
): PhaseSession<MoneyCoachState> {
    val context = koogCompose<MoneyCoachState> {
        provider { use(providerConfig) }

        initialState { stateStore.current }

        // Configuring layout { } is what auto-registers the directive-emitting tool.
        layout {
            val config = expenseLayoutConfig(
                platform = platform,
                formFactor = formFactor,
                role = MoneyCoachRole(canViewFx = canViewFx),
            )
            workflowContext = config.workflowContext
            slotRegistry = config.slotRegistry
            componentRegistry = config.componentRegistry
        }

        phases {
            phase("coach", initial = true) {
                instructions {
                    """
                    You are a money coach looking at this month's spending.

                    The figures are fixed and already known to the app:
                      total this month: ${formatMinorUnits(MoneyCoachData.monthTotalMinorUnits)}
                      by category: ${
                        MoneyCoachData.categories.joinToString {
                            "${it.category} ${formatMinorUnits(it.minorUnits)}"
                        }
                    }

                    Do NOT recite these numbers as a wall of text. Build the answer as UI:
                    call the layout directive tool to show the component that answers the
                    question, then add one or two sentences of interpretation.

                    Components you may show:
                      - "${MONTHLY_TOTAL.value}"       into slot "${HEADLINE_SLOT.value}"
                      - "${SPEND_BY_CATEGORY.value}"   into slot "${INSIGHTS_SLOT.value}"
                      - "${RECENT_TRANSACTIONS.value}" into slot "${INSIGHTS_SLOT.value}"
                      - "${FX_RATE_CHART.value}"       into slot "${INSIGHTS_SLOT.value}"

                    Show the headline total first. If a directive comes back rejected, tell
                    the user plainly what you could not show and carry on — do not retry it
                    in a loop.
                    """.trimIndent()
                }
            }
        }

        config {
            streamingEnabled = true
            requireConfirmationForSensitive = false
        }
    } as KoogComposeContext<MoneyCoachState>

    return PhaseSession(
        context = context,
        executor = context.createExecutor(),
        sessionId = "money-coach",
    )
}

/**
 * Maps a [ComponentId] the agent named onto a composable.
 *
 * This is the host app's entire responsibility in the layout engine: the agent decides
 * *what* and *where*, the app decides *how it looks*. Unknown ids cannot reach here —
 * they are rejected before reduction — so the fallback is a diagnostic, not a code path
 * the agent can drive.
 */
@Composable
private fun moneyCoachComponents(): ComponentContentProvider = ComponentContentProvider { id, props ->
    when (id) {
        MONTHLY_TOTAL -> MonthlyTotalCard()
        SPEND_BY_CATEGORY -> SpendByCategoryCard(props)
        RECENT_TRANSACTIONS -> RecentTransactionsCard()
        FX_RATE_CHART -> FxRateChartCard()
        else -> Text(
            text = "Unregistered component: ${id.value}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun MonthlyTotalCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Spent this month", style = MaterialTheme.typography.labelMedium)
            Text(
                text = formatMinorUnits(MoneyCoachData.monthTotalMinorUnits),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
private fun SpendByCategoryCard(props: ComponentProps, modifier: Modifier = Modifier) {
    // Prefer the figures the agent passed as props; fall back to the demo data so the card
    // still renders if the model omitted them.
    val rows = MoneyCoachData.categories.map { category ->
        category.category to (props.get(category.category)?.toLongOrNull() ?: category.minorUnits)
    }
    val largest = rows.maxOfOrNull { it.second }?.takeIf { it > 0L } ?: 1L
    val barColor = MaterialTheme.colorScheme.primary

    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Where it went", style = MaterialTheme.typography.titleSmall)
            for ((label, amount) in rows) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = formatMinorUnits(amount),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                        )
                    }
                    // A bar chart cheap enough to need no charting dependency.
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth((amount.toFloat() / largest.toFloat()).coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTransactionsCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            for (category in MoneyCoachData.categories.take(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(category.category, style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatMinorUnits(category.minorUnits),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FxRateChartCard(modifier: Modifier = Modifier) {
    val points = MoneyCoachData.usdKes
    val min = points.minOf { it.rate }
    val max = points.maxOf { it.rate }
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    val barColor = MaterialTheme.colorScheme.tertiary

    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("USD / KES", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                for (point in points) {
                    val fraction = ((point.rate - min) / span).toFloat().coerceIn(0.08f, 1f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height((12 + 44 * fraction).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor)
                        )
                        Text(point.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Cross-platform entry point for the Money Coach sample. */
@Composable
public fun MoneyCoachApp(
    modifier: Modifier = Modifier,
    providerConfig: ProviderConfig = ProviderConfig.Ollama(model = "llama3.2"),
    platform: Platform = Platform.Desktop,
    formFactor: FormFactor = FormFactor.Desktop,
    canViewFx: Boolean = true,
) {
    var session by remember(providerConfig, canViewFx) {
        mutableStateOf<PhaseSession<MoneyCoachState>?>(null)
    }

    LaunchedEffect(providerConfig, canViewFx) {
        session = createMoneyCoachSession(
            stateStore = KoogStateStore(MoneyCoachState()),
            providerConfig = providerConfig,
            platform = platform,
            formFactor = formFactor,
            canViewFx = canViewFx,
        )
    }

    val current = session
    if (current == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    MoneyCoachUI(session = current, modifier = modifier)
}

@Composable
private fun MoneyCoachUI(
    session: PhaseSession<MoneyCoachState>,
    modifier: Modifier = Modifier,
) {
    val chatState = rememberChatState(handle = session, context = session.context)
    val components = moneyCoachComponents()

    Scaffold(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Ask where your money went…",
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                // Agent-driven region. Both hosts render whatever the agent has legally
                // placed; neither knows what will appear.
                LayoutSlotHost(
                    slotId = HEADLINE_SLOT,
                    processor = session.layoutProcessor,
                    contentProvider = components,
                )
                LayoutSlotHost(
                    slotId = INSIGHTS_SLOT,
                    processor = session.layoutProcessor,
                    contentProvider = components,
                    emptyContent = {
                        Text(
                            text = "Ask a question and the agent will build the answer here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    },
                )

                ChatMessageList(
                    chatState = chatState,
                    modifier = Modifier.fillMaxSize(),
                    showToolCallMessages = true,
                )
            }

            // Tempo, not a spinner. Drawn above content but is pure colour, no hit testing.
            AmbientGradient(
                handle = session,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
