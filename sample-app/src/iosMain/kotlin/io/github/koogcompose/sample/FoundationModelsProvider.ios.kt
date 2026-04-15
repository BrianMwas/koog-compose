package io.github.koogcompose.sample

import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * iOS-specific LLM provider using Apple FoundationModels (iOS 18+).
 *
 * Bridges Kotlin/Native to Swift's LanguageModelSession for on-device inference.
 *
 * ## Usage
 * ```kotlin
 * val provider = FoundationModelsProvider()
 * if (provider.isAvailable()) {
 *     val session = PhaseSession(context, executor = ..., provider)
 * } else {
 *     // Fallback to cloud provider (Anthropic, OpenAI)
 * }
 * ```
 *
 * ## System Requirements
 * - iOS 18.0 or later
 * - Minimum 4GB RAM for optimal performance
 * - ~2GB free disk space for model caching
 *
 * ## Architecture
 * The Swift bridge in `iOSApp.swift` + `FoundationModelsSwiftBridge.swift`:
 * 1. Checks if FoundationModels framework is available (iOS 18+)
 * 2. Creates a LanguageModelSession with optional system prompt
 * 3. Streams responses token-by-token via AsyncSequence
 * 4. Converts Swift errors to Kotlin exceptions
 * 5. Handles memory automatically (no manual management)
 */
public class FoundationModelsProvider : AIProvider {

    /**
     * Check if FoundationModels is available on this device.
     * Returns true only on iOS 18+ with the framework accessible.
     */
    public fun isAvailable(): Boolean = checkFoundationModelsAvailable()

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        if (!isAvailable()) {
            emit(AIResponseChunk.Failed("FoundationModels not available"))
            return@flow
        }

        try {
            // Build conversation history for the model
            val messages = buildConversationHistory(history, systemPrompt)
            
            // Call Swift bridge to get streaming response
            streamFromFoundationModels(messages) { token ->
                // Emit each token as it arrives
                if (token.isNotEmpty()) {
                    emit(AIResponseChunk.TextChunk(token))
                }
            }
            
            // Signal completion
            emit(AIResponseChunk.End)
        } catch (e: Exception) {
            emit(AIResponseChunk.Failed("FoundationModels error: ${e.message}"))
        }
    }

    private fun buildConversationHistory(
        history: List<ChatMessage>,
        systemPrompt: String
    ): String {
        val sb = StringBuilder()
        
        // Add system prompt if provided
        if (systemPrompt.isNotEmpty()) {
            sb.append("System:\n")
            sb.append(systemPrompt)
            sb.append("\n\n")
        }
        
        // Add message history
        history.forEachIndexed { _, message ->
            val role = when (message.role) {
                io.github.koogcompose.session.MessageRole.USER -> "User"
                io.github.koogcompose.session.MessageRole.ASSISTANT -> "Assistant"
                else -> "System"
            }
            sb.append("$role:\n")
            sb.append(message.content)
            sb.append("\n\n")
        }
        
        sb.append("Assistant:\n")
        
        return sb.toString()
    }

    /**
     * Bridge to Swift implementation.
     * Calls the native FoundationModels API.
     *
     * @param messages Formatted conversation history
     * @param onToken Callback for each streamed token
     */
    private external fun streamFromFoundationModels(
        messages: String,
        onToken: (String) -> Unit
    )

    companion object {
        private external fun checkFoundationModelsAvailable(): Boolean
        
        init {
            // Load Kotlin/Native interop bridge
            System.loadLibrary("konanInterop")
        }
    }
}

/**
 * Extension to check iOS version before using FoundationModels
 */
internal fun systemVersionAtLeast(major: Int, minor: Int = 0): Boolean {
    val version = Platform.osVersion
    val parts = version.split(".")
    return if (parts.size >= 2) {
        val osMajor = parts[0].toIntOrNull() ?: 0
        val osMinor = parts[1].toIntOrNull() ?: 0
        osMajor > major || (osMajor == major && osMinor >= minor)
    } else {
        false
    }
}

/** iOS platform detection */
internal expect object Platform {
    val osVersion: String
}
