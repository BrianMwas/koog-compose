package io.github.koogcompose.provider.ondevice

import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS stub for OnDeviceProvider.
 *
 * Apple Foundation Models integration is not implemented in this library yet.
 * Even on supported devices, this implementation remains unavailable until a
 * Swift bridge is added.
 */
public actual class OnDeviceProvider public actual constructor(
    modelPath: String,
    private val tools: List<SecureTool>,
    maxToolRounds: Int,
) {
    private val bridge: AppleFoundationModelsBridge?
        get() = AppleFoundationModelsBridgeRegistry.bridge

    public actual fun isAvailable(): Boolean = bridge?.isAvailable() == true

    public actual fun supportsToolCalls(): Boolean = bridge?.supportsToolCalls() == true

    public actual suspend fun execute(prompt: OnDevicePrompt): String {
        val runtimeBridge = requireBridge()
        if (tools.isNotEmpty() && !runtimeBridge.supportsToolCalls()) {
            error(
                "The installed Apple Foundation Models bridge does not support tool calling yet. " +
                    "Configure onUnavailable { ... } for tool-using phases."
            )
        }
        return suspendCancellableCoroutine { continuation ->
            runtimeBridge.execute(
                systemPrompt = prompt.system,
                userPrompt = prompt.user,
            ) { result, error ->
                when {
                    error != null -> continuation.resumeWithException(IllegalStateException(error))
                    result != null -> continuation.resume(result)
                    else -> continuation.resume("")
                }
            }
        }
    }

    public actual fun executeStreaming(prompt: OnDevicePrompt): Flow<String> {
        val runtimeBridge = requireBridge()
        return callbackFlow {
            if (tools.isNotEmpty() && !runtimeBridge.supportsToolCalls()) {
                close(
                    IllegalStateException(
                        "The installed Apple Foundation Models bridge does not support tool calling yet. " +
                            "Configure onUnavailable { ... } for tool-using phases."
                    )
                )
                return@callbackFlow
            }

            runtimeBridge.stream(
                systemPrompt = prompt.system,
                userPrompt = prompt.user,
                onToken = { token -> trySend(token) },
                onComplete = { error ->
                    if (error != null) {
                        close(IllegalStateException(error))
                    } else {
                        close()
                    }
                },
            )

            awaitClose {}
        }
    }

    public actual fun close() {}

    private fun requireBridge(): AppleFoundationModelsBridge =
        bridge ?: error(
            "No Apple Foundation Models bridge is installed. " +
                "Call installOnDeviceBridges(...) from Swift app startup."
        )
}
