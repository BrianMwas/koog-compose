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
 *
 * Defines all colors used by chat components including message bubbles,
 * text, timestamps, and input fields. These colors are applied via
 * [KoogChatTheme] and can be distributed throughout your UI via Compose's
 * CompositionLocal mechanism.
 */
@Immutable
public data class ChatColors(
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
 *
 * Controls the visual shape of message bubbles and input fields.
 * Message bubbles can have different shapes for the user (right-aligned)
 * and assistant (left-aligned) for asymmetrical, modern chat designs.
 */
@Immutable
public data class ChatShapes(
    val userBubble: Shape,
    val assistantBubble: Shape,
    val inputField: Shape
)

/**
 * Entry point for customizing the Koog Chat UI theme.
 *
 * Wrap your chat screen in this composable to override the default colors and shapes.
 * The theme is distributed to child composables via [LocalChatColors] and [LocalChatShapes]
 * CompositionLocals.
 *
 * Example:
 * ```kotlin
 * KoogChatTheme(
 *     colors = ChatColors(
 *         userBubble = Color(0xFF0084FF),
 *         assistantBubble = Color(0xFFE5E5EA),
 *         // ... other colors
 *     ),
 *     shapes = ChatShapes(
 *         userBubble = RoundedCornerShape(16.dp),
 *         assistantBubble = RoundedCornerShape(16.dp),
 *         inputField = RoundedCornerShape(24.dp)
 *     ),
 *     content = {
 *         ChatScreen()
 *     }
 * )
 * ```
 *
 * @param colors The [ChatColors] to use (default: Material 3 adapted defaults).
 * @param shapes The [ChatShapes] to use (default: Material 3 rounded corners).
 * @param content The composable content that will receive the theme.
 */
@Composable
public fun KoogChatTheme(
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
public fun defaultChatColors(): ChatColors = ChatColors(
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

public fun defaultChatShapes(): ChatShapes = ChatShapes(
    userBubble = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
    assistantBubble = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
    inputField = RoundedCornerShape(24.dp)
)

internal val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified,
        Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified
    )
}

internal val LocalChatShapes = staticCompositionLocalOf {
    ChatShapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp))
}
