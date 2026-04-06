package io.github.koogcompose.provider


/**
 * Supported LLM providers.
 * Each maps to a Koog LLM client under the hood.
 *
 * Usage in DSL:
 * ```kotlin
 * koogCompose {
 *     provider {
 *         anthropic(apiKey = BuildConfig.ANTHROPIC_KEY) {
 *             model = "claude-sonnet-4-5"
 *             maxTokens = 4096
 *             temperature = 0.7
 *         }
 *     }
 * }
 * ```
 */
public sealed class ProviderConfig {

    public data class Anthropic(
        val apiKey: String,
        val model: String = "claude-sonnet-4-5",
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    ) : ProviderConfig()

    public data class OpenAI(
        val apiKey: String,
        val model: String = "gpt-4o",
        val baseUrl: String = "https://api.openai.com/v1",
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    ) : ProviderConfig()

    public data class Google(
        val apiKey: String,
        val model: String = "gemini-2.0-flash",
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    ) : ProviderConfig()

    /**
     * Ollama — local on-device inference.
     * No API key needed. Requires Ollama running locally or on a reachable host.
     *
     * ```kotlin
     * provider {
     *     ollama(model = "llama3.2") {
     *         baseUrl = "http://10.0.2.2:11434" // Android emulator → host machine
     *     }
     * }
     * ```
     */
    public data class Ollama(
        val model: String,
        val baseUrl: String = "http://localhost:11434",
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    ) : ProviderConfig()

    /**
     * Router — distributes requests across multiple providers.
     * Backed by Koog's built-in LLMClientRouter.
     *
     * ```kotlin
     * provider {
     *     router(strategy = RouterStrategy.Fallback) {
     *         anthropic(apiKey = BuildConfig.ANTHROPIC_KEY)
     *         openAI(apiKey = BuildConfig.OPENAI_KEY)
     *         ollama(model = "llama3.2") // on-device fallback
     *     }
     * }
     * ```
     */
    public data class Router(
        val providers: List<ProviderConfig>,
        val strategy: RouterStrategy = RouterStrategy.RoundRobin,
    ) : ProviderConfig()
}

public enum class RouterStrategy { RoundRobin, Fallback }

/**
 * Builder for [ProviderConfig].
 * Used inside the `provider { }` DSL block.
 */
public class ProviderConfigBuilder {
    private var config: ProviderConfig? = null

    public fun anthropic(
        apiKey: String,
        block: AnthropicBuilder.() -> Unit = {}
    ) {
        config = AnthropicBuilder(apiKey).apply(block).build()
    }

    public fun openAI(
        apiKey: String,
        block: OpenAIBuilder.() -> Unit = {}
    ) {
        config = OpenAIBuilder(apiKey).apply(block).build()
    }

    public fun google(
        apiKey: String,
        block: GoogleBuilder.() -> Unit = {}
    ) {
        config = GoogleBuilder(apiKey).apply(block).build()
    }

    public fun ollama(
        model: String,
        block: OllamaBuilder.() -> Unit = {}
    ) {
        config = OllamaBuilder(model).apply(block).build()
    }

    public fun router(
        strategy: RouterStrategy = RouterStrategy.RoundRobin,
        block: RouterBuilder.() -> Unit
    ) {
        config = RouterBuilder(strategy).apply(block).build()
    }

    public fun build(): ProviderConfig = config
        ?: error("koog-compose: No provider configured. Call anthropic(), openAI(), google(), or ollama() inside provider { }")
}

public class AnthropicBuilder(private val apiKey: String) {
    public var model: String = "claude-sonnet-4-5"
    public var maxTokens: Int? = null
    public var temperature: Double? = null
    public fun build(): ProviderConfig = ProviderConfig.Anthropic(apiKey, model, maxTokens, temperature)
}

public class OpenAIBuilder(private val apiKey: String) {
    public var model: String = "gpt-4o"
    public var baseUrl: String = "https://api.openai.com/v1"
    public var maxTokens: Int? = null
    public var temperature: Double? = null
    public fun build(): ProviderConfig = ProviderConfig.OpenAI(apiKey, model, baseUrl, maxTokens, temperature)
}

public class GoogleBuilder(private val apiKey: String) {
    public var model: String = "gemini-2.0-flash"
    public var maxTokens: Int? = null
    public var temperature: Double? = null
    public fun build(): ProviderConfig = ProviderConfig.Google(apiKey, model, maxTokens, temperature)
}

public class OllamaBuilder(private val model: String) {
    public var baseUrl: String = "http://localhost:11434"
    public var maxTokens: Int? = null
    public var temperature: Double? = null
    public fun build(): ProviderConfig = ProviderConfig.Ollama(model, baseUrl, maxTokens, temperature)
}

public class RouterBuilder(private val strategy: RouterStrategy) {
    private val providers = mutableListOf<ProviderConfig>()

    public fun anthropic(apiKey: String, block: AnthropicBuilder.() -> Unit = {}): Unit {
        providers.add(AnthropicBuilder(apiKey).apply(block).build())
    }

    public fun openAI(apiKey: String, block: OpenAIBuilder.() -> Unit = {}): Unit {
        providers.add(OpenAIBuilder(apiKey).apply(block).build())
    }

    public fun google(apiKey: String, block: GoogleBuilder.() -> Unit = {}): Unit {
        providers.add(GoogleBuilder(apiKey).apply(block).build())
    }

    public fun ollama(model: String, block: OllamaBuilder.() -> Unit = {}): Unit {
        providers.add(OllamaBuilder(model).apply(block).build())
    }

    public fun build(): ProviderConfig = ProviderConfig.Router(providers.toList(), strategy)
}
