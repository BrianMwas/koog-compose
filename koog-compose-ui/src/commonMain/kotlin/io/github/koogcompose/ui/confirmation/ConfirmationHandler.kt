package io.github.koogcompose.ui.confirmation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.koogcompose.security.PendingConfirmation
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.ui.state.ChatState
import kotlinx.coroutines.launch

/**
 * Contract for handling tool confirmation requests.
 *
 * The library enforces NOTHING about the UI — developers implement
 * this interface with whatever confirmation experience fits their app:
 * a dialog, a snackbar, a bottom sheet, a pill notification,
 * biometric authentication, or a completely custom flow.
 *
 * The only contract: return true if the user approved, false if denied.
 *
 * ```kotlin
 * // Snackbar implementation
 * class SnackbarConfirmation(
 *     val snackbarHostState: SnackbarHostState
 * ) : ConfirmationHandler {
 *     override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean {
 *         val result = snackbarHostState.showSnackbar(
 *             message = pending.confirmationMessage,
 *             actionLabel = "Allow",
 *             duration = SnackbarDuration.Long,
 *             withDismissAction = true
 *         )
 *         return result == SnackbarResult.ActionPerformed
 *     }
 * }
 *
 * // Bottom sheet implementation
 * class BottomSheetConfirmation(
 *     val sheetState: SheetState
 * ) : ConfirmationHandler {
 *     override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean {
 *         // show your bottom sheet, await user response
 *         return userConfirmed
 *     }
 * }
 *
 * // Biometric implementation (banking, health)
 * class BiometricConfirmation(
 *     val context: Context
 * ) : ConfirmationHandler {
 *     override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean {
 *         return if (pending.permissionLevel == PermissionLevel.CRITICAL) {
 *             authenticateWithBiometric(context) // your biometric flow
 *         } else {
 *             true // SENSITIVE tools don't require biometric
 *         }
 *     }
 * }
 * ```
 */
interface ConfirmationHandler {
    /**
     * Called when a SENSITIVE or CRITICAL tool requires user approval.
     *
     * @param pending Details about the tool call requesting confirmation.
     * @return true if the user approved execution, false if denied.
     */
    suspend fun requestConfirmation(pending: PendingConfirmation): Boolean
}

/**
 * Wires a [ConfirmationHandler] into a [ChatState].
 *
 * Observes pending confirmations and routes them through your handler.
 * Place this once in your screen — it has no visual output of its own.
 *
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val chatState = rememberChatState(...)
 *     val snackbarHostState = remember { SnackbarHostState() }
 *
 *     // Wire your custom handler — no dialog enforced
 *     ConfirmationObserver(
 *         chatState = chatState,
 *         handler = SnackbarConfirmation(snackbarHostState)
 *     )
 *
 *     Scaffold(
 *         snackbarHost = { SnackbarHost(snackbarHostState) }
 *     ) { ... }
 * }
 * ```
 */
@Composable
fun ConfirmationObserver(
    chatState: ChatState,
    handler: ConfirmationHandler
) {
    val scope = rememberCoroutineScope()
    val pending by chatState.pendingConfirmationFlow.collectAsState()

    LaunchedEffect(pending) {
        val current = pending ?: return@LaunchedEffect
        scope.launch {
            val approved = handler.requestConfirmation(current)
            if (approved) chatState.confirmToolExecution()
            else chatState.denyToolExecution()
        }
    }
}

// ── Built-in handlers — reference implementations, not enforced ───────────────

/**
 * Snackbar-based confirmation handler.
 * Requires a [SnackbarHostState] from your Scaffold.
 *
 * ```kotlin
 * val snackbarHostState = remember { SnackbarHostState() }
 * ConfirmationObserver(
 *     chatState = chatState,
 *     handler = rememberSnackbarConfirmationHandler(snackbarHostState)
 * )
 * ```
 */
@Composable
fun rememberSnackbarConfirmationHandler(
    snackbarHostState: SnackbarHostState,
    actionLabel: String = "Allow",
    criticalActionLabel: String = "Confirm"
): ConfirmationHandler = remember(snackbarHostState) {
    object : ConfirmationHandler {
        override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean {
            val label = if (pending.permissionLevel == PermissionLevel.CRITICAL)
                criticalActionLabel else actionLabel
            val result = snackbarHostState.showSnackbar(
                message = pending.confirmationMessage,
                actionLabel = label,
                duration = SnackbarDuration.Long,
                withDismissAction = true
            )
            return result == SnackbarResult.ActionPerformed
        }
    }
}

/**
 * Dialog-based confirmation handler.
 * A reference implementation — copy and customise to match your theme.
 *
 * ```kotlin
 * ConfirmationObserver(
 *     chatState = chatState,
 *     handler = rememberDialogConfirmationHandler()
 * )
 * ```
 */
@Composable
fun rememberDialogConfirmationHandler(): ConfirmationHandler {
    var pendingState by remember { mutableStateOf<PendingConfirmation?>(null) }
    var resolveCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // Render the dialog when there's a pending confirmation
    pendingState?.let { pending ->
        val isCritical = pending.permissionLevel == PermissionLevel.CRITICAL
        val accentColor = if (isCritical) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary

        AlertDialog(
            onDismissRequest = {
                resolveCallback?.invoke(false)
                pendingState = null
            },
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
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = pending.confirmationMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isCritical) {
                        Spacer(Modifier.height(12.dp))
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
                    onClick = {
                        resolveCallback?.invoke(true)
                        pendingState = null
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                ) {
                    Text(
                        text = if (isCritical) "Confirm" else "Allow",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    resolveCallback?.invoke(false)
                    pendingState = null
                }) { Text("Cancel") }
            }
        )
    }

    return remember {
        object : ConfirmationHandler {
            override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean {
                return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    pendingState = pending
                    resolveCallback = { approved -> cont.resume(approved) {} }
                }
            }
        }
    }
}