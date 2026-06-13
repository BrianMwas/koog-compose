package io.github.koogcompose.provider

import ai.koog.prompt.executor.model.PromptExecutor
import io.github.koogcompose.session.KoogComposeContext

/**
 * Optional runtime hook for platform/provider modules that need to supply a
 * custom [AIProvider] for specific [ProviderConfig] values.
 *
 * Core falls back to [KoogAIProvider] when no registered factory claims the
 * context.
 */
public object ProviderRuntimeRegistry {
    private val factories = mutableListOf<(KoogComposeContext<*>) -> AIProvider?>()

    public fun register(factory: (KoogComposeContext<*>) -> AIProvider?) {
        factories += factory
    }

    internal fun create(context: KoogComposeContext<*>): AIProvider? =
        factories.asReversed().firstNotNullOfOrNull { it(context) }
}

/**
 * Optional runtime hook for platform/provider modules that need to supply a
 * custom Koog [PromptExecutor] for specific [ProviderConfig] values.
 *
 * This is what lets non-standard providers, such as on-device LiteRT-LM,
 * participate in the AIAgent graph without routing through legacy
 * [AIProvider]-only chat sessions.
 */
public object PromptExecutorRuntimeRegistry {
    private val factories = mutableListOf<(KoogComposeContext<*>) -> PromptExecutor?>()

    public fun register(factory: (KoogComposeContext<*>) -> PromptExecutor?) {
        factories += factory
    }

    internal fun create(context: KoogComposeContext<*>): PromptExecutor? =
        factories.asReversed().firstNotNullOfOrNull { it(context) }
}
