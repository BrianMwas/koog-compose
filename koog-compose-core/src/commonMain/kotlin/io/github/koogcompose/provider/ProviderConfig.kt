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
     * LiteRT-LM — on-device Gemma inference via Google's LiteRT-LM engine.
     * No API key, no network. Runs quantized Gemma models directly on the device.
     *
     * Requires the `:koog-compose-mediapipe` module and a `.litertlm` model file
     * (downloaded from HuggingFace LiteRT Community).
     *
     * ```kotlin
     * provider {
     *     litertlm(
     *         modelPath = "/path/to/gemma3-1b-it.litertlm",
     *         maxTokens = 1024,
     *         temperature = 0.7,
     *     )
     * }
     * ```
     *
     * @param modelPath Absolute path to the `.litertlm` model file.
     * @param maxTokens Maximum tokens per generation.
     * @param temperature Sampling temperature (0.0–1.0).
     */
    public data class LiteRtLm(
        val modelPath: String = "gemma3-1b-it.litertlm",
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

    /**
     * On-device provider — runs the model locally on the device.
     *
     * Android: LiteRT-LM with Gemma 4 (E2B/E4B .litertlm model).
     * iOS: planned Apple Foundation Models integration. Not implemented yet.
     *
     * Tools go through koog's SecureTool pipeline (validation + guardrails).
     * Automatic tool calling is disabled so koog remains the orchestrator.
     *
     * ```kotlin
     * provider {
     *     onDevice(modelPath = "/data/models/gemma-4-E2B.litertlm") {
     *         onUnavailable { anthropic(apiKey = BuildConfig.KEY) }
     *     }
     * }
     * ```
     */
    public data class OnDevice(
        val modelPath: String = "",
        val maxToolRounds: Int = 5,
        val fallback: ProviderConfig? = null,
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

    public fun litertlm(
        modelPath: String = "gemma3-1b-it.litertlm",
        block: LiteRtLmBuilder.() -> Unit = {}
    ) {
        config = LiteRtLmBuilder(modelPath).apply(block).build()
    }

    /**
     * Configures an on-device provider.
     *
     * Simple usage:
     * ```kotlin
     * provider {
     *     onDevice(modelPath = "/data/models/gemma-4-E2B.litertlm")
     * }
     * ```
     *
     * With tools and fallback:
     * ```kotlin
     * provider {
     *     onDevice(modelPath = "/data/models/gemma-4-E4B.litertlm") {
     *         maxToolRounds(8)
     *         onUnavailable {
     *             anthropic(apiKey = BuildConfig.KEY)
     *         }
     *     }
     * }
     * ```
     */
    public fun onDevice(
        modelPath: String = "",
        block: OnDeviceProviderBuilder.() -> Unit = {},
    ) {
        val builder = OnDeviceProviderBuilder(modelPath).apply(block)
        config = builder.build()
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

public class LiteRtLmBuilder(private val modelPath: String) {
    public var maxTokens: Int? = null
    public var temperature: Double? = null
    public fun build(): ProviderConfig = ProviderConfig.LiteRtLm(modelPath, maxTokens, temperature)
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

    public fun litertlm(
        modelPath: String = "gemma3-1b-it.litertlm",
        block: LiteRtLmBuilder.() -> Unit = {}
    ): Unit {
        providers.add(LiteRtLmBuilder(modelPath).apply(block).build())
    }

    public fun build(): ProviderConfig = ProviderConfig.Router(providers.toList(), strategy)
}

/**
 * Builder for [ProviderConfig.OnDevice] inside the provider DSL.
 */
public class OnDeviceProviderBuilder internal constructor(
    private val modelPath: String,
) {
    private var maxToolRounds: Int = 5
    internal var unavailableHandler: (() -> ProviderConfig)? = null

    /** Maximum agentic loop iterations before capping and returning. */
    public fun maxToolRounds(max: Int) {
        require(max > 0) { "maxToolRounds must be > 0" }
        this.maxToolRounds = max
    }

    /**
     * Provides a fallback provider when the on-device model is unavailable.
     *
     * ```kotlin
     * onUnavailable { anthropic(apiKey = BuildConfig.KEY) }
     * // or
     * onUnavailable { ollama(model = "llama3.2") }
     * ```
     */
    public fun onUnavailable(fallback: ProviderConfigBuilder.() -> Unit) {
        this.unavailableHandler = { ProviderConfigBuilder().apply(fallback).build() }
    }

    internal fun build(): ProviderConfig {
        return ProviderConfig.OnDevice(
            modelPath = modelPath,
            maxToolRounds = maxToolRounds,
            fallback = unavailableHandler?.invoke(),
        )
    }
}
