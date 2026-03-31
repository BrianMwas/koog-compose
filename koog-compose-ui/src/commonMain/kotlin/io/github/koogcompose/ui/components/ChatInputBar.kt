package io.github.koogcompose.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp // ← correct package, matches ChatMessage
import io.github.koogcompose.session.ChatAttachment
import io.github.koogcompose.ui.LocalChatColors
import io.github.koogcompose.ui.LocalChatShapes
import io.github.koogcompose.ui.state.ChatState

// ─── Theming contract ─────────────────────────────────────────────────────────

@Immutable
data class ChatInputBarColors(
    val inputField: TextFieldColors,
    val sendActive: Color,
    val sendInactive: Color,
    val actionIcon: Color,
    val attachmentChipBackground: Color,
    val attachmentChipContent: Color,
)

@Immutable
data class ChatInputBarDimensions(
    val outerPadding: PaddingValues,
    val actionIconSize: Dp,
    val actionSpacing: Dp,
    val maxInputLines: Int,
)

// ─── Defaults ─────────────────────────────────────────────────────────────────

object ChatInputBarDefaults {

    @Composable
    fun colors(
        inputField: TextFieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        sendActive: Color = LocalChatColors.current.userBubble,
        sendInactive: Color = LocalChatColors.current.inputPlaceholder,
        actionIcon: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        attachmentChipBackground: Color = MaterialTheme.colorScheme.secondaryContainer,
        attachmentChipContent: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    ) = ChatInputBarColors(
        inputField = inputField,
        sendActive = sendActive,
        sendInactive = sendInactive,
        actionIcon = actionIcon,
        attachmentChipBackground = attachmentChipBackground,
        attachmentChipContent = attachmentChipContent,
    )

    fun dimensions(
        outerPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        actionIconSize: Dp = 24.dp,
        actionSpacing: Dp = 8.dp,
        maxInputLines: Int = 4,
    ) = ChatInputBarDimensions(
        outerPadding = outerPadding,
        actionIconSize = actionIconSize,
        actionSpacing = actionSpacing,
        maxInputLines = maxInputLines,
    )

    // Note: AttachFile and Mic require material-icons-extended in build.gradle:
    // implementation("androidx.compose.material:material-icons-extended")

    @Composable
    fun AttachmentAction(
        onClick: () -> Unit,
        colors: ChatInputBarColors,
        dimensions: ChatInputBarDimensions,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Add attachment",
                tint = colors.actionIcon,
                modifier = Modifier.size(dimensions.actionIconSize),
            )
        }
    }

    @Composable
    fun RecordAction(
        onClick: () -> Unit,
        colors: ChatInputBarColors,
        dimensions: ChatInputBarDimensions,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Record voice message",
                tint = colors.actionIcon,
                modifier = Modifier.size(dimensions.actionIconSize),
            )
        }
    }

    @Composable
    fun SendAction(
        canSend: Boolean,
        onClick: () -> Unit,
        colors: ChatInputBarColors,
        dimensions: ChatInputBarDimensions,
        enabled: Boolean = true,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled && canSend,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send message",
                tint = if (canSend) colors.sendActive else colors.sendInactive,
                modifier = Modifier.size(dimensions.actionIconSize),
            )
        }
    }
}

// ─── Attachment chip ──────────────────────────────────────────────────────────

@Composable
fun AttachmentChip(
    attachment: ChatAttachment,
    onRemove: (ChatAttachment) -> Unit,
    colors: ChatInputBarColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.attachmentChipBackground)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Icons.Default.Add is in material-icons-core — no extended dependency needed
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = colors.attachmentChipContent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = colors.attachmentChipContent,
            maxLines = 1,
        )
        IconButton(
            onClick = { onRemove(attachment) },
            modifier = Modifier.size(18.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.attachmentChipContent),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${attachment.displayName}",
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// ─── Main composable ──────────────────────────────────────────────────────────

@Composable
fun ChatInputBar(
    chatState: ChatState,
    modifier: Modifier = Modifier,
    colors: ChatInputBarColors = ChatInputBarDefaults.colors(),
    dimensions: ChatInputBarDimensions = ChatInputBarDefaults.dimensions(),
    placeholder: String = "Message",
    enabled: Boolean = true,
    inputShape: Shape = LocalChatShapes.current.inputField,
    leadingActions: @Composable (() -> Unit)? = null,
    trailingActions: @Composable (() -> Unit)? = null,
    attachmentPreview: @Composable ((
        attachments: List<ChatAttachment>,
        onRemove: (ChatAttachment) -> Unit,
    ) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dimensions.outerPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (chatState.attachments.isNotEmpty()) {
            if (attachmentPreview != null) {
                attachmentPreview(chatState.attachments, chatState::removeAttachment)
            } else {
                DefaultAttachmentPreview(
                    attachments = chatState.attachments,
                    onRemove = chatState::removeAttachment,
                    colors = colors,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensions.actionSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingActions?.invoke()

            OutlinedTextField(
                value = chatState.inputText,
                onValueChange = chatState::onInputChanged,
                placeholder = {
                    Text(
                        text = placeholder,
                        color = colors.inputField.unfocusedPlaceholderColor,
                    )
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = inputShape,
                colors = colors.inputField,
                singleLine = false,
                maxLines = dimensions.maxInputLines,
            )

            val canSend = chatState.inputText.isNotBlank() || chatState.attachments.isNotEmpty()

            if (trailingActions != null) {
                trailingActions()
            } else {
                ChatInputBarDefaults.SendAction(
                    canSend = canSend,
                    onClick = chatState::send,
                    colors = colors,
                    dimensions = dimensions,
                    enabled = enabled,
                )
            }
        }
    }
}

// ─── Default attachment preview ───────────────────────────────────────────────

@Composable
private fun DefaultAttachmentPreview(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    colors: ChatInputBarColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            AttachmentChip(
                attachment = attachment,
                onRemove = onRemove,
                colors = colors,
            )
        }
    }
}