package io.github.koogcompose.ui.ambient

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.koogcompose.session.AgentActivity
import io.github.koogcompose.session.KoogSessionHandle

/**
 * Colour stops for each [AmbientRole].
 *
 * Every entry is a gradient band: fully transparent at both ends so the band reads as a
 * moving glow rather than a hard-edged fill. Defaults come from [ambientColors].
 */
@Immutable
public data class AmbientColors(
    public val reasoning: List<Color>,
    public val speaking: List<Color>,
    public val working: List<Color>,
    public val awaiting: List<Color>,
    public val stalled: List<Color>,
    public val settled: List<Color>,
    public val error: List<Color>,
) {
    internal fun forRole(role: AmbientRole): List<Color> = when (role) {
        AmbientRole.Reasoning -> reasoning
        AmbientRole.Speaking -> speaking
        AmbientRole.Working -> working
        AmbientRole.Awaiting -> awaiting
        AmbientRole.Stalled -> stalled
        AmbientRole.Settled -> settled
        AmbientRole.Error -> error
        // Dormant never reaches the draw path — AmbientGradient returns before drawing
        // once intensity fades to zero — but the mapping must stay exhaustive.
        AmbientRole.Dormant -> settled
    }
}

/** Builds a soft band that fades to nothing at both ends. */
private fun glow(from: Color, to: Color): List<Color> = listOf(
    from.copy(alpha = 0f),
    from,
    to,
    to.copy(alpha = 0f),
)

/** Default [AmbientColors] derived from the active `MaterialTheme.colorScheme`. */
@Composable
public fun ambientColors(): AmbientColors {
    val scheme = MaterialTheme.colorScheme
    return AmbientColors(
        reasoning = glow(scheme.tertiary, scheme.secondary),
        speaking = glow(scheme.primary, scheme.tertiary),
        working = glow(scheme.secondary, scheme.primary),
        awaiting = glow(scheme.primary, scheme.secondary),
        stalled = glow(scheme.outline, scheme.surfaceVariant),
        settled = glow(scheme.primary, scheme.primary),
        error = glow(scheme.error, scheme.errorContainer),
    )
}

/**
 * An animated gradient that reflects what the agent is doing right now.
 *
 * The motion is derived entirely from [AgentActivity] via [ambientTempoFor]: reasoning
 * breathes slowly, streaming sweeps briskly, tool calls pulse, and finished turns stop
 * moving so a completed response never looks like one still in flight.
 *
 * Place it behind or above your content in a `Box`. It draws nothing at all while the
 * agent is [AgentActivity.Idle], so an idle screen costs no animation frames.
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     ChatMessageList(chatState = chatState)
 *     AmbientGradient(handle = session, modifier = Modifier.matchParentSize())
 * }
 * ```
 */
@Composable
public fun AmbientGradient(
    activity: AgentActivity,
    modifier: Modifier = Modifier,
    colors: AmbientColors = ambientColors(),
) {
    val tempo = remember(activity) { ambientTempoFor(activity) }

    // Cross-fade between activities so transitions read as a shift in mood rather than
    // a pop. This is also what lets Idle wind down smoothly instead of cutting out.
    val intensity by animateFloatAsState(
        targetValue = tempo.intensity,
        animationSpec = tween(durationMillis = 450, easing = LinearEasing),
        label = "ambientIntensity",
    )

    if (intensity <= 0.001f) return

    val sweep = if (tempo.isAnimated) {
        val transition = rememberInfiniteTransition(label = "ambientSweep")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = tempo.cycleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambientSweepFraction",
        )
        animated
    } else {
        0.5f
    }

    val pulse = if (tempo.pulse) {
        val transition = rememberInfiniteTransition(label = "ambientPulse")
        val animated by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambientPulseAlpha",
        )
        animated
    } else {
        1f
    }

    val bandColors = colors.forRole(tempo.role)

    Box(
        modifier = modifier.drawBehind {
            val span = size.maxDimension
            // Travel the band a full span either side of centre so the glow enters and
            // leaves the viewport instead of stalling at the edges.
            val travel = (sweep - 0.5f) * 2f * span

            drawRect(
                brush = Brush.linearGradient(
                    colors = bandColors,
                    start = Offset(travel, 0f),
                    end = Offset(travel + span, size.height),
                ),
                alpha = (intensity * pulse).coerceIn(0f, 1f),
            )
        }
    )
}

/**
 * Convenience overload that follows a session's [KoogSessionHandle.activity] directly.
 */
@Composable
public fun AmbientGradient(
    handle: KoogSessionHandle,
    modifier: Modifier = Modifier,
    colors: AmbientColors = ambientColors(),
) {
    val activity by handle.activity.collectAsState()
    AmbientGradient(activity = activity, modifier = modifier, colors = colors)
}
