package io.github.koogcompose.litertlm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.koogcompose.provider.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import io.github.koogcompose.session.MessageRole as KoogMessageRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * On-device [AIProvider] backed by Google's LiteRT-LM engine.
 *
 * Runs quantized Gemma models (e.g. Gemma3-1B-IT, Gemma-3n-E2B) directly on
 * the device — no network, no API key, no server. Works completely offline
 * after the one-time model download.
 *
 * ## Model preparation
 * Download a `.litertlm` model from the
 * [HuggingFace LiteRT Community](https://huggingface.co/litert-community)
 * and place it in your app's `assets/` directory or download on first launch.
 *
 * ## Usage
 * ```kotlin
 * val provider = LiteRtLmProvider(
 *     context = context,
 *     modelPath = "/path/to/gemma3-1b-it.litertlm",
 * )
 *
 * val chatState = rememberChatState(
 *     provider = provider,
 *     context = koogCompose { phases { ... } },
 * )
 * ```
 *
 * @param context Android context.
 * @param modelPath Absolute path to the `.litertlm` model file.
 * @param maxTokens Maximum tokens to generate per turn.
 * @param temperature Sampling temperature (0.0–1.0). Lower = more deterministic.
 */
public class LiteRtLmProvider(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = 1024,
    private val temperature: Double = 0.7,
) : AIProvider {

    private val engine: Engine
    private var _isInitialized = false

    /** True once the engine is initialized and ready. */
    public val isReady: Boolean
        get() = _isInitialized

    init {
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            cacheDir = context.cacheDir.path,
        )
        engine = Engine(engineConfig)
        engine.initialize()
        _isInitialized = true
    }

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>,
    ): Flow<AIResponseChunk> = callbackFlow {
        // Build conversation contents
        val parts = buildContents(systemPrompt, history, attachments)

        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    temperature = temperature,
                    topK = 40,
                    topP = 0.95,
                ),
            )
        )

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                // LiteRT-LM Message.toString() returns the generated text
                val text = message.toString()
                if (text.isNotEmpty()) {
                    trySend(AIResponseChunk.TextDelta(text))
                }
            }

            override fun onDone() {
                trySend(AIResponseChunk.End)
                close()
            }

            override fun onError(throwable: Throwable) {
                trySend(AIResponseChunk.Error(throwable.message ?: throwable.toString()))
                close(throwable)
            }
        }

        conversation.sendMessageAsync(parts, callback)

        awaitClose {
            conversation.close()
        }
    }

    /**
     * Builds multimodal [Contents] from system prompt, history, and attachments.
     * LiteRT-LM handles the conversation formatting internally via ConversationConfig.
     */
    private fun buildContents(
        systemPrompt: String,
        history: List<ChatMessage>,
        attachments: List<Attachment>,
    ): Contents {
        val parts = mutableListOf<Content>()

        // Add image attachments
        attachments.filterIsInstance<Attachment.Image>().forEach { att ->
            val file = resolveFile(att.uri)
            if (file != null && file.exists()) {
                parts.add(Content.ImageFile(file.absolutePath))
            } else {
                // Try loading as bytes from content URI
                loadBytesFromUri(att.uri)?.let { bytes ->
                    parts.add(Content.ImageBytes(bytes))
                }
            }
        }

        // Add audio attachments
        attachments.filterIsInstance<Attachment.Audio>().forEach { att ->
            loadBytesFromUri(att.uri)?.let { bytes ->
                parts.add(Content.AudioBytes(bytes))
            }
        }

        // Add conversation text
        val userText = history.joinToString("\n") { msg ->
            val role = when (msg.role) {
                KoogMessageRole.USER -> "User"
                KoogMessageRole.ASSISTANT -> "Assistant"
                else -> "System"
            }
            "$role: ${msg.content}"
        }

        parts.add(Content.Text(userText))
        return Contents.of(parts)
    }

    /**
     * Resolves a URI string to a [File]. Handles `file://` URIs directly,
     * and returns null for `content://` URIs (handled by [loadBytesFromUri]).
     */
    private fun resolveFile(uriString: String): File? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.scheme == "file") File(uri.path!!) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads raw bytes from a content URI.
     */
    private fun loadBytesFromUri(uriString: String): ByteArray? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** Releases native resources. */
    public fun close() {
        engine.close()
    }
}
