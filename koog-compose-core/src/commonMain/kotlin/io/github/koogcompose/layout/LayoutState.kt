package io.github.koogcompose.layout

import kotlinx.datetime.Instant

/**
 * Immutable snapshot of a single component's occupancy in a slot.
 *
 * [addedAt] records when the component was placed; used for LRU eviction in
 * [SlotCapacity.Multiple] slots. [lockMode] is non-null only once a
 * [AgentLayoutDirective.LockComponent] has been applied.
 */
public data class SlotEntry(
    val componentId: ComponentId,
    val props: ComponentProps,
    val lockMode: LockMode? = null,
    val addedAt: Instant,
)

/**
 * Immutable snapshot of the entire agent-driven layout.
 *
 * Each value in [slots] is the ordered list of [SlotEntry] items currently
 * occupying that slot. Slots not yet touched by any directive are absent from
 * the map (check [entriesFor] for a safe accessor).
 *
 * [version] is a monotonically-increasing counter that increments on every
 * successful directive application. The Compose UI can use it as a cheap
 * change signal without deep structural comparison.
 */
public data class LayoutState(
    val slots: Map<SlotId, List<SlotEntry>> = emptyMap(),
    val version: Long = 0L,
) {
    public fun entriesFor(slotId: SlotId): List<SlotEntry> = slots[slotId] ?: emptyList()
    public fun isEmpty(): Boolean = slots.all { it.value.isEmpty() }

    public companion object {
        public val Empty: LayoutState = LayoutState()
    }
}
