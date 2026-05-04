package io.github.koogcompose.layout

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Pure function: applies [directive] to [state] and returns the new [LayoutState].
 *
 * No coroutines, no Compose — designed to be fully unit-testable in isolation.
 * [slotRegistry] is optional; when provided, [SlotCapacity] constraints are
 * enforced (Single → replace existing; Multiple(max) → LRU eviction). When
 * null (useful in tests), capacity is treated as [SlotCapacity.Unbounded].
 *
 * Called by [DefaultLayoutDirectiveProcessor] only after all policies pass.
 * Replay is safe — all subtypes are idempotent.
 */
public object LayoutReducer {

    public fun apply(
        state: LayoutState,
        directive: AgentLayoutDirective,
        slotRegistry: SlotRegistry? = null,
        now: Instant = Clock.System.now(),
    ): LayoutState = when (directive) {
        is AgentLayoutDirective.ShowComponent     -> applyShow(state, directive, slotRegistry, now)
        is AgentLayoutDirective.HideComponent     -> applyHide(state, directive)
        is AgentLayoutDirective.ReorderComponents -> applyReorder(state, directive)
        is AgentLayoutDirective.SwapComponent     -> applySwap(state, directive, now)
        is AgentLayoutDirective.LockComponent     -> applyLock(state, directive)
    }

    // ── ShowComponent ──────────────────────────────────────────────────────

    private fun applyShow(
        state: LayoutState,
        d: AgentLayoutDirective.ShowComponent,
        slotRegistry: SlotRegistry?,
        now: Instant,
    ): LayoutState {
        val existing = state.slots[d.slotId] ?: emptyList()

        // Reuse the existing entry (preserving addedAt) if the component is already present.
        val entry = existing.firstOrNull { it.componentId == d.componentId }
            ?.copy(props = d.props)
            ?: SlotEntry(componentId = d.componentId, props = d.props, addedAt = now)

        // Remove the target component before re-inserting it at the new position.
        val withoutTarget = existing.filter { it.componentId != d.componentId }

        // Apply slot-capacity constraints.
        val capacity = slotRegistry?.get(d.slotId)?.capacity
        val cappedBase: List<SlotEntry> = when (capacity) {
            is SlotCapacity.Single    -> emptyList()  // evict any existing occupant
            is SlotCapacity.Multiple  -> {
                if (withoutTarget.size >= capacity.max) {
                    // LRU eviction: the list is insertion-ordered; oldest is first.
                    withoutTarget.drop(1)
                } else {
                    withoutTarget
                }
            }
            is SlotCapacity.Unbounded, null -> withoutTarget
        }

        val inserted = insertAt(cappedBase, d.position, entry)
        return state.copy(
            slots = state.slots + (d.slotId to inserted),
            version = state.version + 1,
        )
    }

    // ── HideComponent ──────────────────────────────────────────────────────

    private fun applyHide(
        state: LayoutState,
        d: AgentLayoutDirective.HideComponent,
    ): LayoutState {
        val targetSlots: List<SlotId> = if (d.slotId != null) {
            listOf(d.slotId)
        } else {
            state.slots.keys.toList()
        }
        var newSlots = state.slots
        for (slotId in targetSlots) {
            newSlots = newSlots + (slotId to (newSlots[slotId] ?: emptyList())
                .filter { it.componentId != d.componentId })
        }
        return state.copy(slots = newSlots, version = state.version + 1)
    }

    // ── ReorderComponents ──────────────────────────────────────────────────

    private fun applyReorder(
        state: LayoutState,
        d: AgentLayoutDirective.ReorderComponents,
    ): LayoutState {
        val existing = state.slots[d.slotId] ?: return state
        val byId = existing.associateBy { it.componentId }
        val orderedSet = d.orderedComponentIds.toSet()
        val explicit = d.orderedComponentIds.mapNotNull { byId[it] }
        val rest = existing.filter { it.componentId !in orderedSet }
        return state.copy(
            slots = state.slots + (d.slotId to explicit + rest),
            version = state.version + 1,
        )
    }

    // ── SwapComponent ──────────────────────────────────────────────────────

    private fun applySwap(
        state: LayoutState,
        d: AgentLayoutDirective.SwapComponent,
        now: Instant,
    ): LayoutState {
        val existing = state.slots[d.slotId] ?: return state
        val newEntry = SlotEntry(componentId = d.insertComponentId, props = d.props, addedAt = now)
        val replaced = existing.map { entry ->
            if (entry.componentId == d.removeComponentId) newEntry else entry
        }
        return state.copy(
            slots = state.slots + (d.slotId to replaced),
            version = state.version + 1,
        )
    }

    // ── LockComponent ──────────────────────────────────────────────────────

    private fun applyLock(
        state: LayoutState,
        d: AgentLayoutDirective.LockComponent,
    ): LayoutState {
        val existing = state.slots[d.slotId] ?: return state
        val updated = existing.map { entry ->
            if (entry.componentId == d.componentId) entry.copy(lockMode = d.lockMode) else entry
        }
        return state.copy(
            slots = state.slots + (d.slotId to updated),
            version = state.version + 1,
        )
    }

    // ── Position helpers ───────────────────────────────────────────────────

    private fun insertAt(
        base: List<SlotEntry>,
        position: Position,
        entry: SlotEntry,
    ): List<SlotEntry> = when (position) {
        is Position.Start -> listOf(entry) + base
        is Position.End   -> base + entry
        is Position.Index -> {
            val clamped = position.index.coerceIn(0, base.size)
            base.toMutableList().apply { add(clamped, entry) }
        }
        is Position.Before -> {
            val idx = base.indexOfFirst { it.componentId == position.reference }
            if (idx < 0) base + entry
            else base.toMutableList().apply { add(idx, entry) }
        }
        is Position.After -> {
            val idx = base.indexOfFirst { it.componentId == position.reference }
            if (idx < 0) base + entry
            else base.toMutableList().apply { add(idx + 1, entry) }
        }
    }
}
