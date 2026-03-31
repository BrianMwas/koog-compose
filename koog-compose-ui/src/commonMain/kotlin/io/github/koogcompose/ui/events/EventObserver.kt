package io.github.koogcompose.ui.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.ui.state.ChatState
import kotlinx.coroutines.flow.collect

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
