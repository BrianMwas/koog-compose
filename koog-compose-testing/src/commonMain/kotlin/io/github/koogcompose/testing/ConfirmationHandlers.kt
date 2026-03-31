package io.github.koogcompose.testing

import io.github.koogcompose.security.PendingConfirmation
import io.github.koogcompose.ui.confirmation.ConfirmationHandler

class AutoApproveConfirmationHandler : ConfirmationHandler {
    override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean = true
}

class AutoDenyConfirmationHandler : ConfirmationHandler {
    override suspend fun requestConfirmation(pending: PendingConfirmation): Boolean = false
}
