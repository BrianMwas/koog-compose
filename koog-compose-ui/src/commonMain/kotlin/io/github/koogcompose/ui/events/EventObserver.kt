package io.github.koogcompose.ui.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.ui.state.ChatState
import kotlinx.coroutines.flow.collect

/**
 * Collects and observes events from the active chat session.
 *
 * This composable subscriulates lifecycle-aware collection of [KoogEvent]s (e.g., tool calls,
 * confirmations, tracing events) from [chatState.eventFlow] and invokes the [onEvent] lambda
 * for each event. Useful for logging, analytics, or triggering side effects outside the normal UI flow.
 *
 * @param chatState The [ChatState] holding the session event flow.
 * @param onEvent Suspend lambda invoked for each emitted event. Can perform network calls,
 *        logging, analytics tracking, etc.
 *
 * Example:
 * ```kotlin
 * EventObserver(
 *     chatState = chatState,
 *     onEvent = { event ->
 *         when (event) {
 *             is ToolCallEvent -> {
 *                 println("Tool called: ${event.toolName}")
 *             }
 *             is ConfirmationEvent -> {
 *                 analytics.logConfirmationRequested(event.level)
 *             }
 *             else -> {}
 *         }
 *     }
 * )
 * ```
 */
@Composable
fun EventObserver(
    chatState: ChatState,
    onEvent: suspend (KoogEvent) -> Unit
) {
    LaunchedEffect(chatState) {
        chatState.eventFlow.collect { event ->
            onEvent(event)
        }
    }
}
