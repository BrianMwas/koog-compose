package io.github.koogcompose.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.DefaultLayoutDirectiveProcessor
import io.github.koogcompose.layout.LayoutState
import io.github.koogcompose.layout.LockMode
import io.github.koogcompose.layout.SlotEntry
import io.github.koogcompose.layout.SlotId

/**
 * Provides the composable content for a component given its [id] and current [props].
 *
 * Register one of these at startup in your ViewModel or DI graph and pass it down
 * to every [LayoutSlotHost]. This keeps [koog-compose-core] free of Compose dependencies.
 */
public fun interface ComponentContentProvider {
    @Composable
    public fun Content(id: ComponentId, props: ComponentProps)
}

/**
 * Renders the contents of [slotId] as reported by [processor].
 *
 * Each [SlotEntry] in the slot is passed to [contentProvider] in order.
 * Components whose [SlotEntry.lockMode] is [LockMode.Hidden] are skipped entirely.
 * Components with [LockMode.Disabled] or [LockMode.ReadOnly] are rendered — the
 * host app's composable is responsible for applying the visual treatment (greyed-out,
 * non-interactive) since koog-compose-ui has no knowledge of your design system.
 *
 * When [processor] is null (layout engine not configured) or the slot is empty,
 * [emptyContent] is rendered instead. Defaults to rendering nothing.
 *
 * ### Example
 * ```kotlin
 * val provider = ComponentContentProvider { id, props ->
 *     when (id.value) {
 *         "promo_banner" -> PromoBanner(props)
 *         "loyalty_card" -> LoyaltyCard(props)
 *         else           -> Box(Modifier.fillMaxWidth().height(0.dp))
 *     }
 * }
 *
 * LayoutSlotHost(
 *     slotId         = SlotId("hero"),
 *     processor      = handle.layoutProcessor,
 *     contentProvider = provider,
 * )
 * ```
 */
@Composable
public fun LayoutSlotHost(
    slotId: SlotId,
    processor: DefaultLayoutDirectiveProcessor?,
    contentProvider: ComponentContentProvider,
    emptyContent: @Composable () -> Unit = {},
) {
    if (processor == null) {
        emptyContent()
        return
    }

    val layoutState by processor.layoutState.collectAsState()
    LayoutSlotContent(
        slotId = slotId,
        layoutState = layoutState,
        contentProvider = contentProvider,
        emptyContent = emptyContent,
    )
}

/**
 * Stateless overload that accepts a pre-collected [LayoutState] snapshot.
 * Useful when you want to hoist state collection out of this composable —
 * e.g., to share it with sibling slots without extra subscriptions.
 */
@Composable
public fun LayoutSlotContent(
    slotId: SlotId,
    layoutState: LayoutState,
    contentProvider: ComponentContentProvider,
    emptyContent: @Composable () -> Unit = {},
) {
    val entries = layoutState.entriesFor(slotId).filter { it.lockMode != LockMode.Hidden }
    if (entries.isEmpty()) {
        emptyContent()
    } else {
        for (entry in entries) {
            contentProvider.Content(id = entry.componentId, props = entry.props)
        }
    }
}
