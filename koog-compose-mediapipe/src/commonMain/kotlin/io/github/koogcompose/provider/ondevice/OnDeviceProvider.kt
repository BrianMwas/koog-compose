package io.github.koogcompose.provider.ondevice

import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic on-device LLM provider.
 *
 * ## Android (LiteRT-LM / Gemma 4)
 * Requires a local `.litertlm` model file. The model must support function calling
 * (e.g. `gemma-4-E2B-it` or `gemma-4-E4B-it`). Tool dispatch is handled by
 * koog-compose — LiteRT-LM's automatic tool calling is disabled so that
 * [SecureTool] validation, guardrails, and retry logic remain active.
 *
 * **Known model limitations on Gemma 4 edge variants:**
 * - Complex multi-step reasoning may degrade on E2B (2B parameters).
 * - Math and code generation are not primary use cases.
 * - World knowledge has a training cutoff; do not use for real-time facts.
 * - Ideal for: device orchestration, natural language → device API, coaching flows.
 *
 * ## iOS (Apple Foundation Models) — upcoming
 * No model path needed. The ~3B parameter model is built into iOS 26+.
 * Apple Intelligence must be enabled on the device.
 *
 * ## Desktop
 * Not supported by this module. Use Ollama or a cloud provider instead.
 *
 * @param modelPath Path to the `.litertlm` model file (Android only; ignored on iOS).
 * @param tools List of [SecureTool] instances to register with the model.
 *              Each tool goes through koog's validation + guardrail pipeline.
 * @param maxToolRounds Maximum agentic loop iterations before returning a
 *                      partial result. Prevents infinite loops on misbehaving models.
 *                      Defaults to 5, matching LiteRT-LM's RECURRING_TOOL_CALL_LIMIT.
 */
public expect class OnDeviceProvider(
    modelPath: String = "",
    tools: List<SecureTool> = emptyList(),
    maxToolRounds: Int = 5,
) {
    /**
     * Returns true if the on-device model is available and ready to use on this device.
     *
     * On Android: checks that [modelPath] exists and the engine can be initialised.
     * On iOS: currently returns false until the Foundation Models bridge is implemented.
     * On Desktop: returns false.
     */
    public fun isAvailable(): Boolean

    /** Returns true when this platform implementation can execute tool calls inline. */
    public fun supportsToolCalls(): Boolean

    /**
     * Runs a full prompt → response cycle, executing any tool calls the model
     * requests along the way. Suspends until a final non-tool-call response is
     * returned or [maxToolRounds] is exhausted.
     */
    public suspend fun execute(prompt: OnDevicePrompt): String

    /**
     * Streams tokens as they are decoded. Tool call mid-stream tokens are
     * consumed internally; only final response tokens are emitted to the caller.
     */
    public fun executeStreaming(prompt: OnDevicePrompt): Flow<String>

    /** Releases the underlying engine. Call when the provider is no longer needed. */
    public fun close()
}

/**
 * Prompt container for on-device inference.
 *
 * @param system Optional system instruction injected before the conversation.
 * @param user The user turn text.
 */
public data class OnDevicePrompt(
    val system: String? = null,
    val user: String,
)
