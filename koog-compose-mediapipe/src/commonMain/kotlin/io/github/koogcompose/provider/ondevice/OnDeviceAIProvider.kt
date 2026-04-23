package io.github.koogcompose.provider.ondevice

import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.provider.ProviderConfig
import io.github.koogcompose.provider.ProviderRuntimeRegistry
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Reason the on-device provider fell back to a cloud provider. */
public enum class OnDeviceFallbackReason {
    /** [OnDeviceProvider.isAvailable] returned false (model missing, OS too old, etc.). */
    MODEL_UNAVAILABLE,
    /** Device has on-device inference but the active bridge does not support tool calls. */
    TOOL_CALLS_UNSUPPORTED,
    /** The request included attachments, which the on-device model cannot handle. */
    ATTACHMENTS_NOT_SUPPORTED,
}

/**
 * Bridges [ProviderConfig.OnDevice] into the standard [AIProvider] contract.
 *
 * Install once at app startup via [installOnDeviceProviderSupport] so
 * `rememberChatState(context)` can resolve `provider { onDevice(...) }`.
 *
 * @param onFallbackUsed Optional callback invoked whenever inference is silently
 *   routed to the configured cloud fallback. Use this to surface a privacy/cost
 *   notice in your UI or to record a metric. Called on the coroutine dispatcher
 *   of the calling [PhaseSession].
 */
public class OnDeviceAIProvider(
    private val onFallbackUsed: ((reason: OnDeviceFallbackReason) -> Unit)? = null,
) : AIProvider {
    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>,
    ): Flow<AIResponseChunk> = flow {
        val config = context.providerConfig as? ProviderConfig.OnDevice
            ?: error("OnDeviceAIProvider requires ProviderConfig.OnDevice")

        if (attachments.isNotEmpty()) {
            val fallback = fallbackProvider(context, config)
            if (fallback != null) {
                onFallbackUsed?.invoke(OnDeviceFallbackReason.ATTACHMENTS_NOT_SUPPORTED)
                emitAll(fallback.stream(context, history, systemPrompt, attachments))
                return@flow
            }
            emit(
                AIResponseChunk.Error(
                    "On-device provider does not support attachments yet. " +
                        "Configure onUnavailable { ... } for a multimodal fallback."
                )
            )
            return@flow
        }

        val provider = OnDeviceProvider(
            modelPath = config.modelPath,
            tools = context.resolveEffectiveTools(),
            maxToolRounds = config.maxToolRounds,
        )
        val effectiveTools = context.resolveEffectiveTools()

        try {
            if (!provider.isAvailable()) {
                val fallback = fallbackProvider(context, config)
                if (fallback != null) {
                    onFallbackUsed?.invoke(OnDeviceFallbackReason.MODEL_UNAVAILABLE)
                    emitAll(fallback.stream(context, history, systemPrompt, attachments))
                } else {
                    emit(
                        AIResponseChunk.Error(
                            "On-device model is unavailable on this platform/device and no fallback provider is configured."
                        )
                    )
                }
                return@flow
            }

            if (effectiveTools.isNotEmpty() && !provider.supportsToolCalls()) {
                val fallback = fallbackProvider(context, config)
                if (fallback != null) {
                    onFallbackUsed?.invoke(OnDeviceFallbackReason.TOOL_CALLS_UNSUPPORTED)
                    emitAll(fallback.stream(context, history, systemPrompt, attachments))
                } else {
                    emit(
                        AIResponseChunk.Error(
                            "On-device provider is available, but this platform bridge does not support tool calling yet."
                        )
                    )
                }
                return@flow
            }

            val prompt = buildOnDevicePrompt(systemPrompt, history)
            emitAll(provider.executeStreaming(prompt).asResponseChunks())
        } finally {
            provider.close()
        }
    }

    private fun <S> fallbackProvider(
        context: KoogComposeContext<S>,
        config: ProviderConfig.OnDevice,
    ): AIProvider? = config.fallback?.let { fallback ->
        context.copy(providerConfig = fallback).createProvider()
    }
}

public fun installOnDeviceProviderSupport() {
    OnDeviceProviderRuntimeSupport.install()
}

private object OnDeviceProviderRuntimeSupport {
    private var installed: Boolean = false

    fun install() {
        if (installed) return
        installed = true
        ProviderRuntimeRegistry.register { context ->
            if (context.providerConfig is ProviderConfig.OnDevice) {
                OnDeviceAIProvider()
            } else {
                null
            }
        }
    }
}

private fun buildOnDevicePrompt(
    systemPrompt: String,
    history: List<ChatMessage>,
): OnDevicePrompt {
    val transcript = history.joinToString("\n\n") { message ->
        val role = when (message.role) {
            MessageRole.USER -> "User"
            MessageRole.ASSISTANT -> "Assistant"
            MessageRole.SYSTEM -> "System"
            MessageRole.TOOL -> {
                val toolName = message.toolName ?: "tool"
                val toolKind = message.toolKind?.name?.lowercase() ?: "message"
                "Tool[$toolName/$toolKind]"
            }
        }
        "$role: ${message.content}"
    }
    return OnDevicePrompt(
        system = systemPrompt,
        user = transcript,
    )
}

private fun Flow<String>.asResponseChunks(): Flow<AIResponseChunk> = flow {
    collect { chunk ->
        emit(AIResponseChunk.TextDelta(chunk))
    }
    emit(AIResponseChunk.End)
}
