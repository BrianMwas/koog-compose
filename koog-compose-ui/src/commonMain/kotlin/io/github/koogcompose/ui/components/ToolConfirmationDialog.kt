package io.github.koogcompose.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.koogcompose.security.PendingConfirmation
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.ui.state.ChatState

/**
 * Renders the security confirmation dialog when a SENSITIVE or CRITICAL
 * tool requires user approval.
 *
 * Drop this once anywhere in your screen — it self-manages visibility
 * based on [ChatState.pendingConfirmationFlow].
 *
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val chatState = rememberChatState(...)
 *
 *     ToolConfirmationDialog(chatState = chatState)
 *
 *     // rest of your UI
 * }
 * ```
 */
@Composable
fun ToolConfirmationDialog(
    chatState: ChatState,
    modifier: Modifier = Modifier
) {
    val pending by chatState.pendingConfirmationFlow.collectAsState()
    pending ?: return

    ToolConfirmationDialogContent(
        pending = pending!!,
        onConfirm = { chatState.confirmToolExecution() },
        onDeny = { chatState.denyToolExecution() },
        modifier = modifier
    )
}

@Composable
internal fun ToolConfirmationDialogContent(
    pending: PendingConfirmation,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCritical = pending.permissionLevel == PermissionLevel.CRITICAL
    val accentColor = if (isCritical) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = onDeny,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = if (isCritical) "Action Required" else "Confirm Action",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                // Permission level badge
                Surface(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = pending.permissionLevel.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = pending.confirmationMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCritical) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This action cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = accentColor
                )
            ) {
                Text(
                    text = if (isCritical) "Confirm" else "Allow",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Cancel")
            }
        }
    )
}
