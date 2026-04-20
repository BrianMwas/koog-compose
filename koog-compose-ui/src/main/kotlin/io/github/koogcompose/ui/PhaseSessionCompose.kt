@file:Suppress("NOTHING_TO_INLINE")

package io.github.koogcompose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.PhaseSessionBuilder
import io.github.koogcompose.session.phaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope

/**
 * Compose helper for creating and remembering a [PhaseSession].
 *
 * Automatically binds to the Compose lifecycle and recomposes only when
 * the [context] or [executor] references change.
 *
 * ```kotlin
 * @Composable
 * fun RunScreen(
 *     definition: KoogDefinition<RunState> = koogCompose { ... }
 * ) {
 *     val executor = definition.createExecutor()
 *     val context = definition as KoogComposeContext<RunState>
 *
 *     val session = rememberPhaseSession(context, executor) {
 *         sessionId = "run_${System.currentTimeMillis()}"
 *     }
 *
 *     // Use session.responseStream, session.appState, etc.
 * }
 * ```
 *
 * For ViewModels (non-Compose), use [phaseSession] with explicit `scope = viewModelScope`.
 */
@Composable
public inline fun <S> rememberPhaseSession(
    context: KoogComposeContext<S>,
    executor: PromptExecutor,
    crossinline block: PhaseSessionBuilder<S>.() -> Unit = {}
): PhaseSession<S> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(context, executor) {
        phaseSession(context, executor) {
            scope = lifecycleOwner.lifecycleScope
            block()
        }
    }
}

/**
 * Alternative: if you have a [KoogDefinition], use this for maximum consistency.
 *
 * ```kotlin
 * val definition = koogCompose<AppState> { ... }
 * val session = rememberPhaseSession(definition) {
 *     sessionId = "my_session"
 * }
 * ```
 *
 * This version casts the definition internally and throws if it's not a
 * single-agent [KoogComposeContext].
 */
@Composable
public inline fun <S> rememberPhaseSession(
    definition: io.github.koogcompose.session.KoogDefinition<S>,
    crossinline block: PhaseSessionBuilder<S>.() -> Unit = {}
): PhaseSession<S> {
    val context = definition as? KoogComposeContext<S>
        ?: throw IllegalArgumentException(
            "rememberPhaseSession requires a single-agent definition. " +
            "For multi-agent KoogSession, use SessionRunner instead."
        )
    val executor = remember(definition) { definition.createExecutor() }
    return rememberPhaseSession(context, executor, block)
}
