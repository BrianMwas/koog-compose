package io.github.koogcompose.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatAttachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.MessageRole
import io.github.koogcompose.session.ToolMessageKind
import io.github.koogcompose.ui.LocalChatColors
import io.github.koogcompose.ui.LocalChatShapes
import io.github.koogcompose.ui.state.ChatState

// ─── Public overloads ─────────────────────────────────────────────────────────

@Composable
fun ChatMessageList(
    chatState: ChatState,
    modifier: Modifier = Modifier,
    showSystemMessages: Boolean = false,
    showToolCallMessages: Boolean = false,
    messageContent: @Composable ((ChatMessage) -> Unit)? = null,
) {
    val sessionState by chatState.sessionStateFlow.collectAsState()
    ChatMessageList(
        messages = sessionState.messages,
        modifier = modifier,
        showSystemMessages = showSystemMessages,
        showToolCallMessages = showToolCallMessages,
        messageContent = messageContent,
    )
}

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    showSystemMessages: Boolean = false,
    showToolCallMessages: Boolean = false,
    messageContent: @Composable ((ChatMessage) -> Unit)? = null,
) {
    val visibleMessages = messages.filter { message ->
        when (message.role) {
            MessageRole.SYSTEM -> showSystemMessages
            MessageRole.TOOL -> when (message.toolKind) {
                ToolMessageKind.CALL -> showToolCallMessages
                ToolMessageKind.RESULT -> true
                null -> true
            }
            MessageRole.USER,
            MessageRole.ASSISTANT -> true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(visibleMessages, key = ChatMessage::id) { message ->
            if (messageContent != null) {
                messageContent(message)
            } else {
                DefaultChatMessage(message)
            }
        }
    }
}

// ─── Default message bubble ───────────────────────────────────────────────────

@Composable
private fun DefaultChatMessage(message: ChatMessage) {
    val colors = LocalChatColors.current
    val shapes = LocalChatShapes.current

    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start

    val bubbleBackground = when {
        isUser -> colors.userBubble
        message.role == MessageRole.TOOL -> MaterialTheme.colorScheme.secondaryContainer
        else -> colors.assistantBubble
    }
    val contentColor = when {
        isUser -> colors.userText
        message.role == MessageRole.TOOL -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> colors.assistantText
    }
    val bubbleShape = if (isUser) shapes.userBubble else shapes.assistantBubble

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        val toolName = message.toolName
        if (message.role == MessageRole.TOOL && !toolName.isNullOrBlank()) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.timestamp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleBackground, bubbleShape)
                .clip(bubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.attachments.isNotEmpty()) {
                // Map core Attachment → UI ChatAttachment at the rendering boundary.
                // This keeps the session model clean and the UI enrichment in one place.
                AttachmentList(
                    attachments = message.attachments.map { it.toUI() },
                    isUser = isUser,
                    bubbleShape = bubbleShape,
                    contentColor = contentColor,
                )
            }

            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
}

// ─── Attachment rendering ─────────────────────────────────────────────────────

@Composable
private fun AttachmentList(
    attachments: List<ChatAttachment>,
    isUser: Boolean,
    bubbleShape: Shape,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            when (attachment) {
                is ChatAttachment.Image -> ImageAttachment(attachment, bubbleShape)
                is ChatAttachment.File  -> FileAttachment(attachment, contentColor)
                is ChatAttachment.Audio -> AudioAttachment(attachment, contentColor, isUser)
            }
        }
    }
}

@Composable
private fun ImageAttachment(
    attachment: ChatAttachment.Image,
    bubbleShape: Shape,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(attachment.uri)
            .crossfade(true)
            .build(),
        contentDescription = attachment.displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(bubbleShape),
    )
}

@Composable
private fun FileAttachment(
    attachment: ChatAttachment.File,
    contentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            attachment.sizeBytes?.let { bytes ->
                Text(
                    text = formatFileSize(bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun AudioAttachment(
    attachment: ChatAttachment.Audio,
    contentColor: Color,
    isUser: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice message",
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isUser) "Voice message" else attachment.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
            attachment.durationMs?.let { ms ->
                Text(
                    text = formatDuration(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ─── Formatters ───────────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024L     -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024} KB"
    else               -> "%.1f MB".format(bytes / 1_048_576.0)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}