@file:Suppress("NOTHING_TO_INLINE")

package io.github.koogcompose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.KoogDefinition
import io.github.koogcompose.session.PhaseSession
import io.github.koogcompose.session.PhaseSessionBuilder
import io.github.koogcompose.session.phaseSession

/**
 * Creates and remembers a [PhaseSession] in Compose.
 *
 * This is the Compose entry point for the Koog AIAgent runtime. It is common
 * code so Android and iOS samples use the same session path.
 */
@Composable
public inline fun <S> rememberPhaseSession(
    context: KoogComposeContext<S>,
    executor: PromptExecutor,
    crossinline block: PhaseSessionBuilder<S>.() -> Unit = {},
): PhaseSession<S> {
    val scope = rememberCoroutineScope()
    return remember(context, executor) {
        phaseSession(context, executor) {
            this.scope = scope
            block()
        }
    }
}

/**
 * Convenience overload for a single-agent [KoogDefinition].
 */
@Composable
public inline fun <S> rememberPhaseSession(
    definition: KoogDefinition<S>,
    crossinline block: PhaseSessionBuilder<S>.() -> Unit = {},
): PhaseSession<S> {
    val context = definition as? KoogComposeContext<S>
        ?: throw IllegalArgumentException(
            "rememberPhaseSession requires a single-agent definition. " +
                "For multi-agent KoogSession, use multiAgentHandle()."
        )
    val executor = remember(definition) { definition.createExecutor() }
    return rememberPhaseSession(context, executor, block)
}
