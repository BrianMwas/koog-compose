package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogStateStore
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.koogCompose
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.StatefulTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.confirmation.ConfirmationObserver
import io.github.koogcompose.ui.confirmation.rememberDialogConfirmationHandler
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

// ── Trip Planner State ─────────────────────────────────────────────────────────

@Serializable
data class ItineraryItem(
    val day: Int,
    val title: String,
    val category: String = "activity",
    val cost: Double = 0.0,
)

@Serializable
data class TripState(
    val destination: String = "",
    val travelDates: String = "",
    val budgetTotal: Double = 0.0,
    val itinerary: List<ItineraryItem> = emptyList(),
    val isBooked: Boolean = false,
) {
    val budgetSpent: Double get() = itinerary.sumOf { it.cost }
}

// ── Trip Planner Tools (shared across platforms) ────────────────────────────────

class SetTripDetailsTool(
    override val stateStore: KoogStateStore<TripState>
) : StatefulTool<TripState>() {
    override val name = "SetTripDetails"
    override val description = "Set the destination, travel dates, and total budget for the trip"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val destination = args["destination"]?.jsonPrimitive?.contentOrNull
        val dates = args["dates"]?.jsonPrimitive?.contentOrNull
        val budget = args["budget"]?.jsonPrimitive?.doubleOrNull

        stateStore.update { state ->
            state.copy(
                destination = destination ?: state.destination,
                travelDates = dates ?: state.travelDates,
                budgetTotal = budget ?: state.budgetTotal,
            )
        }
        return ToolResult.Success("Trip details updated")
    }
}

class AddItineraryItemTool(
    override val stateStore: KoogStateStore<TripState>
) : StatefulTool<TripState>() {
    override val name = "AddItineraryItem"
    override val description =
        "Add an item (flight, hotel, activity, food, or transport) to the trip itinerary " +
        "for a given day, with an estimated cost"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val day = args["day"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return ToolResult.Failure("Missing or invalid 'day' parameter.", retryable = false)
        val title = args["title"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Failure("Missing 'title' parameter.", retryable = false)
        val category = args["category"]?.jsonPrimitive?.contentOrNull ?: "activity"
        val cost = args["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        val current = stateStore.current
        val newSpent = current.budgetSpent + cost
        if (current.budgetTotal > 0 && newSpent > current.budgetTotal) {
            return ToolResult.Failure(
                "Adding '$title' (\$$cost) would exceed the budget of \$${current.budgetTotal}. " +
                    "Current spend is \$${current.budgetSpent}. Suggest a cheaper alternative or " +
                    "ask the user to raise the budget.",
                retryable = false,
            )
        }

        stateStore.update { state ->
            state.copy(itinerary = state.itinerary + ItineraryItem(day, title, category, cost))
        }
        return ToolResult.Success("Added '$title' on day $day (\$$cost)")
    }
}

class RemoveItineraryItemTool(
    override val stateStore: KoogStateStore<TripState>
) : StatefulTool<TripState>() {
    override val name = "RemoveItineraryItem"
    override val description = "Remove an itinerary item by its title"
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val title = args["title"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Failure("Missing 'title' parameter.", retryable = false)

        val current = stateStore.current
        if (current.itinerary.none { it.title == title }) {
            return ToolResult.Failure("No itinerary item named '$title' was found.", retryable = false)
        }

        stateStore.update { state ->
            state.copy(itinerary = state.itinerary.filterNot { it.title == title })
        }
        return ToolResult.Success("Removed '$title' from the itinerary")
    }
}

class BookTripTool(
    override val stateStore: KoogStateStore<TripState>
) : StatefulTool<TripState>() {
    override val name = "BookTrip"
    override val description =
        "Finalize and book the trip. This commits the itinerary and charges the budget — " +
        "only call this once the user has confirmed they're happy with the plan."
    override val permissionLevel = PermissionLevel.SENSITIVE

    override suspend fun executeInternal(args: JsonObject): ToolResult {
        val current = stateStore.current
        if (current.itinerary.isEmpty()) {
            return ToolResult.Failure("Cannot book an empty itinerary.", retryable = false)
        }

        stateStore.update { it.copy(isBooked = true) }
        return ToolResult.Success(
            "Trip to ${current.destination} booked! Total cost: \$${current.budgetSpent}"
        )
    }
}

// ── Agent Definition (shared) ───────────────────────────────────────────────────

/**
 * Builds the multi-phase trip-planner agent shared by every platform.
 *
 * Phases: discover -> itinerary -> booking. Itinerary edits and a final
 * [BookTripTool] (SENSITIVE -> confirmation dialog) demonstrate live
 * stateful UI updates alongside human-in-the-loop confirmation.
 */
fun buildTripPlannerContext(
    stateStore: KoogStateStore<TripState>,
): KoogComposeContext<TripState> {
    val setTripDetails = SetTripDetailsTool(stateStore)
    val addItem = AddItineraryItemTool(stateStore)
    val removeItem = RemoveItineraryItemTool(stateStore)
    val bookTrip = BookTripTool(stateStore)

    return koogCompose<TripState> {
        provider {
            ollama(model = "llama3.2")
        }

        config {
            guardrails {
                rateLimit("AddItineraryItem", max = 30, per = 1.minutes)
            }
            stuckDetection {
                threshold = 4
                fallbackMessage = "Let's take a step back — what would you like to change about the trip?"
            }
        }

        phases {
            // Phase 1: Discover the traveler's preferences
            phase("discover", initial = true) {
                instructions {
                    val state = stateStore.current
                    """
                    You are an enthusiastic travel planning assistant.
                    ${if (state.destination.isBlank()) "" else "Destination so far: ${state.destination}."}

                    Ask the traveler where they want to go, their travel dates, and their
                    total budget. Once you know the destination, dates, and budget,
                    use [SetTripDetails] to record them, then move to the itinerary phase.
                    """.trimIndent()
                }
                tool(setTripDetails)
            }

            // Phase 2: Build the itinerary
            phase("itinerary") {
                instructions {
                    val state = stateStore.current
                    """
                    Help the traveler build a day-by-day itinerary for ${state.destination}
                    (${state.travelDates}), with a total budget of \$${state.budgetTotal}.

                    Suggest flights, hotels, activities, food, and transport. For each item
                    the user likes, use [AddItineraryItem] with a day number, title,
                    category, and estimated cost in USD.

                    Current spend: \$${state.budgetSpent} / \$${state.budgetTotal}.
                    If an item would exceed the budget, suggest a cheaper alternative.
                    Use [RemoveItineraryItem] if the user wants to drop something.

                    When the user is happy with the plan, move to the booking phase.
                    """.trimIndent()
                }
                tool(addItem)
                tool(removeItem)
            }

            // Phase 3: Confirm and book
            phase("booking") {
                instructions {
                    val state = stateStore.current
                    """
                    Summarize the final itinerary for ${state.destination}
                    (${state.travelDates}): ${state.itinerary.size} items,
                    total cost \$${state.budgetSpent} of \$${state.budgetTotal} budget.

                    Ask the traveler to confirm. When they confirm, call [BookTrip]
                    to finalize the booking. After booking, congratulate them and
                    offer to help plan another trip.
                    """.trimIndent()
                }
                tool(addItem)
                tool(removeItem)
                tool(bookTrip)
            }
        }
    } as KoogComposeContext<TripState>
}

/**
 * Cross-platform entry point for the trip-planner sample: a multi-phase agent
 * that builds a live itinerary with budget tracking, and demonstrates the
 * human-in-the-loop confirmation flow via the SENSITIVE [BookTripTool].
 */
@Composable
fun SimpleTripPlannerApp(modifier: Modifier = Modifier) {
    val snackbarHostState = remember { SnackbarHostState() }

    val session = remember {
        val stateStore = KoogStateStore(TripState())
        val context = buildTripPlannerContext(stateStore)
        PhaseSession(
            context = context,
            executor = context.createExecutor(),
            sessionId = "trip_planner_${Clock.System.now().toEpochMilliseconds()}",
        )
    }

    val chatState = rememberChatState(handle = session, context = session.context)
    val currentPhase by session.currentPhase.collectAsState(initial = "")
    val state by requireNotNull(session.appState).collectAsState()

    val confirmationHandler = rememberDialogConfirmationHandler()
    ConfirmationObserver(chatState = chatState, handler = confirmationHandler)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TripPlannerTopBar(state, currentPhase)
        },
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Where do you want to go?",
            )
        },
    ) { innerPadding ->
        ChatMessageList(
            chatState = chatState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            showSystemMessages = false,
            showToolCallMessages = true,
        )
    }
}

// ── Shared UI ────────────────────────────────────────────────────────────────────

@Composable
fun TripPlannerTopBar(
    state: TripState,
    currentPhase: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.isBooked) Icons.Default.CheckCircle else Icons.Default.FlightTakeoff,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = when {
                        state.isBooked -> "🎉 Trip booked!"
                        currentPhase == "discover" -> "🌍 Where to?"
                        currentPhase == "itinerary" -> "🗺️ Building your itinerary"
                        currentPhase == "booking" -> "✅ Ready to book"
                        else -> "✈️ Trip Planner"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.destination.isNotBlank()) {
                Text(
                    text = "${state.destination} • ${state.travelDates}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.budgetTotal > 0) {
                val spentFraction = (state.budgetSpent / state.budgetTotal).toFloat().coerceIn(0f, 1f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { spentFraction },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "\$${state.budgetSpent} / \$${state.budgetTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (state.itinerary.isNotEmpty()) {
                Text(
                    text = "Itinerary: " + state.itinerary.joinToString(", ") { "Day ${it.day}: ${it.title}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                )
            }
        }
    }
}
