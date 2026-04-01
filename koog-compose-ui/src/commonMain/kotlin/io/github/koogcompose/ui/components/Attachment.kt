package io.github.koogcompose.ui.components

import io.github.koogcompose.session.Attachment as CoreAttachment

/**
 * UI-specific models for attachments, used within the Compose layer.
 * These provide extra metadata like display names and sizes for the UI.
 */
public sealed class ChatAttachment {
    public abstract val uri: String
    public abstract val displayName: String

    public data class Image(
        override val uri: String,
        override val displayName: String = "Image",
    ) : ChatAttachment()

    public data class File(
        override val uri: String,
        override val displayName: String,
        val mimeType: String,
        val sizeBytes: Long? = null,
    ) : ChatAttachment()

    public data class Audio(
        override val uri: String,
        override val displayName: String = "Voice message",
        val durationMs: Long? = null,
    ) : ChatAttachment()
}

/** Maps UI [ChatAttachment] to core [CoreAttachment]. */
public fun ChatAttachment.toCore(): CoreAttachment = when (this) {
    is ChatAttachment.Image -> CoreAttachment.Image(uri)
    is ChatAttachment.File -> CoreAttachment.Document(uri, displayName, mimeType)
    is ChatAttachment.Audio -> CoreAttachment.Audio(uri)
}

/** Maps core [CoreAttachment] to UI [ChatAttachment]. */
public fun CoreAttachment.toUI(): ChatAttachment = when (this) {
    is CoreAttachment.Image -> ChatAttachment.Image(uri)
    is CoreAttachment.Document -> ChatAttachment.File(uri, uri.substringAfterLast('/'), mimeType)
    is CoreAttachment.Audio -> ChatAttachment.Audio(uri)
}
