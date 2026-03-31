package io.github.koogcompose.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Colors for the Koog chat UI.
 */
@Immutable
data class ChatColors(
    val userBubble: Color,
    val assistantBubble: Color,
    val userText: Color,
    val assistantText: Color,
    val timestamp: Color,
    val background: Color,
    val inputBackground: Color,
    val inputPlaceholder: Color,
    val border: Color
)

/**
 * Shapes for the Koog chat UI.
 */
@Immutable
data class ChatShapes(
    val userBubble: Shape,
    val assistantBubble: Shape,
    val inputField: Shape
)

/**
 * Entry point for customizing the Koog Chat UI theme.
 * Wrap your chat screen in this to override the default styles.
 */
@Composable
fun KoogChatTheme(
    colors: ChatColors = defaultChatColors(),
    shapes: ChatShapes = defaultChatShapes(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalChatColors provides colors,
        LocalChatShapes provides shapes,
        content = content
    )
}

@Composable
fun defaultChatColors() = ChatColors(
    userBubble = MaterialTheme.colorScheme.primaryContainer,
    assistantBubble = MaterialTheme.colorScheme.surfaceVariant,
    userText = MaterialTheme.colorScheme.onPrimaryContainer,
    assistantText = MaterialTheme.colorScheme.onSurfaceVariant,
    timestamp = MaterialTheme.colorScheme.outline,
    background = MaterialTheme.colorScheme.background,
    inputBackground = MaterialTheme.colorScheme.surface,
    inputPlaceholder = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    border = MaterialTheme.colorScheme.outlineVariant
)

fun defaultChatShapes() = ChatShapes(
    userBubble = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
    assistantBubble = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
    inputField = RoundedCornerShape(24.dp)
)

internal val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        Color.Unspecified, Color.Unused, Color.Unspecified, Color.Unspecified,
        Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified
    )
}

internal val LocalChatShapes = staticCompositionLocalOf {
    ChatShapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp))
}
