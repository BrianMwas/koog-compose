package io.github.koogcompose.provider

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
