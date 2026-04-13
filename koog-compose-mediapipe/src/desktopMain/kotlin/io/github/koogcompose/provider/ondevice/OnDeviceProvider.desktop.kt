package io.github.koogcompose.provider.ondevice

import io.github.koogcompose.tool.SecureTool
import kotlinx.coroutines.flow.Flow

/**
 * Desktop stub for OnDeviceProvider.
 *
 * LiteRT-LM integration in this module is Android-only, and Apple Foundation
 * Models are iOS-only. Desktop consumers should use a cloud provider or a
 * desktop-local provider such as Ollama instead.
 */
public actual class OnDeviceProvider public actual constructor(
    modelPath: String,
    tools: List<SecureTool>,
    maxToolRounds: Int,
) {
    public actual fun isAvailable(): Boolean = false
    public actual fun supportsToolCalls(): Boolean = false

    public actual suspend fun execute(prompt: OnDevicePrompt): String {
        error(
            "OnDeviceProvider is not supported on desktop in :koog-compose-mediapipe. " +
                "Use ollama(...) or a cloud provider instead."
        )
    }

    public actual fun executeStreaming(prompt: OnDevicePrompt): Flow<String> {
        error(
            "OnDeviceProvider is not supported on desktop in :koog-compose-mediapipe. " +
                "Use ollama(...) or a cloud provider instead."
        )
    }

    public actual fun close() {}
}
