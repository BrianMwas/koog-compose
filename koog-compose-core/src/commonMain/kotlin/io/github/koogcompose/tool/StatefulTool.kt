package io.github.koogcompose.tool

import io.github.koogcompose.session.KoogStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * A [SecureTool] that has access to shared [KoogStateStore] with explicit dispatcher control.
 *
 * For predictable coroutine execution, the dispatcher is set once at construction time,
 * never lazily evaluated or changed. This ensures:
 * - Thread-safety guarantees
 * - Explicit resource allocation
 * - Testability (easy to inject test dispatchers)
 * - Clear I/O vs CPU-bound semantics
 *
 * Extend this when your tool needs to read or mutate app state as a side effect of its execution.
 *
 * ```kotlin
 * // I/O-bound tool (default)
 * class FetchBalanceTool(
 *     private val api: WalletApi,
 *     override val stateStore: KoogStateStore<AppState>
 * ) : StatefulTool<AppState>() {
 *
 *     override val name = "GetBalance"
 *     override val description = "Fetch the current wallet balance"
 *     override val permissionLevel = PermissionLevel.SAFE
 *
 *     override suspend fun executeInternal(args: JsonObject): ToolResult {
 *         val balance = api.getBalance() // Runs on Dispatchers.IO
 *         stateStore.update { it.copy(balance = balance) }
 *         return ToolResult.Success("Balance is $balance KES")
 *     }
 * }
 *
 * // CPU-bound tool
 * class AnalyzeTool(
 *     override val stateStore: KoogStateStore<AppState>
 * ) : StatefulTool<AppState>(dispatcher = Dispatchers.Default) {
 *
 *     override val name = "Analyze"
 *     override val description = "Analyze data"
 *     override val permissionLevel = PermissionLevel.SAFE
 *
 *     override suspend fun executeInternal(args: JsonObject): ToolResult {
 *         val result = expensiveComputation() // Runs on Dispatchers.Default
 *         stateStore.update { it.copy(result = result) }
 *         return ToolResult.Success(result)
 *     }
 * }
 * ```
 */
public abstract class StatefulTool<S>(
    public val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SecureTool {
    public abstract val stateStore: KoogStateStore<S>

    protected abstract suspend fun executeInternal(args: JsonObject): ToolResult

    override suspend fun execute(args: JsonObject): ToolResult =
        withContext(dispatcher) {
            executeInternal(args)
        }
}