package io.github.koogcompose.tool


import io.github.koogcompose.session.KoogStateStore


/**
 * A [SecureTool] that has access to shared [KoogStateStore].
 *
 * Extend this when your tool needs to read or mutate app state
 * as a side effect of its execution.
 *
 * ```kotlin
 * class FetchBalanceTool(
 *     private val api: WalletApi,
 *     override val stateStore: KoogStateStore<AppState>
 * ) : StatefulTool<AppState>() {
 *
 *     override val name = "GetBalance"
 *     override val description = "Fetch the current wallet balance"
 *     override val permissionLevel = PermissionLevel.SAFE
 *
 *     override suspend fun execute(args: JsonObject): ToolResult {
 *         val balance = api.getBalance()
 *         stateStore.update { it.copy(balance = balance) } // ← mutate state
 *         return ToolResult.Success("Balance is $balance KES")
 *     }
 * }
 * ```
 */
public abstract class StatefulTool<S> : SecureTool {
    public abstract val stateStore: KoogStateStore<S>
}