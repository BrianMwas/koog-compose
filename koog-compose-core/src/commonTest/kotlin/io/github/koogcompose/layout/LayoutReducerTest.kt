package io.github.koogcompose.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class LayoutReducerTest {

    // ── ShowComponent: positions ───────────────────────────────────────────

    @Test
    fun showComponent_addsEntryAtEnd() {
        val result = LayoutReducer.apply(LayoutState.Empty, show("a", HERO, Position.End))

        assertEquals(listOf("a"), result.entriesFor(HERO).map { it.componentId.value })
        assertEquals(1L, result.version)
    }

    @Test
    fun showComponent_positionStart_insertsAtFront() {
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.Start))

        assertEquals(listOf("c", "a", "b"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_positionIndex_clampedToSize() {
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.Index(10)))

        assertEquals(listOf("a", "b", "c"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_positionBefore_insertsBeforeReference() {
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.Before(ComponentId("b"))))

        assertEquals(listOf("a", "c", "b"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_positionAfter_insertsAfterReference() {
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.After(ComponentId("a"))))

        assertEquals(listOf("a", "c", "b"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_positionBefore_missingReference_fallsBackToEnd() {
        val state = stateWith(HERO, entry("a"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.Before(ComponentId("missing"))))

        assertEquals(listOf("a", "c"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_positionAfter_missingReference_fallsBackToEnd() {
        val state = stateWith(HERO, entry("a"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.After(ComponentId("missing"))))

        assertEquals(listOf("a", "c"), result.entriesFor(HERO).map { it.componentId.value })
    }

    // ── ShowComponent: re-show ─────────────────────────────────────────────

    @Test
    fun showComponent_reShow_preservesAddedAtAndUpdatesPropsAndPosition() {
        val originalTime = Instant.fromEpochMilliseconds(1_000)
        val newTime = Instant.fromEpochMilliseconds(2_000)
        val state = LayoutState(
            slots = mapOf(
                HERO to listOf(
                    SlotEntry(ComponentId("a"), ComponentProps(mapOf("k" to "v1")), addedAt = originalTime),
                    SlotEntry(ComponentId("b"), ComponentProps.Empty, addedAt = originalTime),
                )
            )
        )
        val directive = AgentLayoutDirective.ShowComponent(
            componentId = ComponentId("a"),
            slotId = HERO,
            position = Position.Start,
            props = ComponentProps(mapOf("k" to "v2")),
            correlationId = DirectiveId("d1"),
            issuedAt = newTime,
        )

        val result = LayoutReducer.apply(state, directive, now = newTime)

        val entries = result.entriesFor(HERO)
        assertEquals(listOf("a", "b"), entries.map { it.componentId.value })
        val a = entries.first { it.componentId.value == "a" }
        assertEquals(originalTime, a.addedAt)
        assertEquals("v2", a.props.get("k"))
    }

    // ── ShowComponent: slot capacity ───────────────────────────────────────

    @Test
    fun showComponent_singleCapacity_evictsExisting() {
        val slotRegistry = SlotRegistry(listOf(LayoutSlot(HERO, SlotCapacity.Single, setOf(Tag("promo")))))
        val state = stateWith(HERO, entry("a"))

        val result = LayoutReducer.apply(state, show("b", HERO, Position.End), slotRegistry)

        assertEquals(listOf("b"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_multipleCapacity_evictsOldestWhenFull() {
        val slotRegistry = SlotRegistry(listOf(LayoutSlot(HERO, SlotCapacity.Multiple(2), setOf(Tag("promo")))))
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.End), slotRegistry)

        assertEquals(listOf("b", "c"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun showComponent_unboundedCapacity_doesNotEvict() {
        val slotRegistry = SlotRegistry(listOf(LayoutSlot(HERO, SlotCapacity.Unbounded, setOf(Tag("promo")))))
        val state = stateWith(HERO, entry("a"), entry("b"))

        val result = LayoutReducer.apply(state, show("c", HERO, Position.End), slotRegistry)

        assertEquals(listOf("a", "b", "c"), result.entriesFor(HERO).map { it.componentId.value })
    }

    // ── HideComponent ───────────────────────────────────────────────────────

    @Test
    fun hideComponent_withSlotId_removesFromThatSlotOnly() {
        val state = LayoutState(
            slots = mapOf(
                HERO to listOf(entry("a")),
                FOOTER to listOf(entry("a")),
            )
        )
        val directive = AgentLayoutDirective.HideComponent(
            componentId = ComponentId("a"),
            slotId = HERO,
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(emptyList(), result.entriesFor(HERO))
        assertEquals(listOf("a"), result.entriesFor(FOOTER).map { it.componentId.value })
        assertEquals(1L, result.version)
    }

    @Test
    fun hideComponent_withNullSlotId_removesFromAllSlots() {
        val state = LayoutState(
            slots = mapOf(
                HERO to listOf(entry("a")),
                FOOTER to listOf(entry("a"), entry("b")),
            )
        )
        val directive = AgentLayoutDirective.HideComponent(
            componentId = ComponentId("a"),
            slotId = null,
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(emptyList(), result.entriesFor(HERO))
        assertEquals(listOf("b"), result.entriesFor(FOOTER).map { it.componentId.value })
    }

    @Test
    fun hideComponent_absentComponent_leavesSlotUnchanged() {
        val state = stateWith(HERO, entry("a"))
        val directive = AgentLayoutDirective.HideComponent(
            componentId = ComponentId("missing"),
            slotId = HERO,
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(listOf("a"), result.entriesFor(HERO).map { it.componentId.value })
        assertEquals(1L, result.version)
    }

    // ── ReorderComponents ───────────────────────────────────────────────────

    @Test
    fun reorderComponents_explicitOrderThenRemainder() {
        val state = stateWith(HERO, entry("a"), entry("b"), entry("c"))
        val directive = AgentLayoutDirective.ReorderComponents(
            slotId = HERO,
            orderedComponentIds = listOf(ComponentId("c"), ComponentId("a")),
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(listOf("c", "a", "b"), result.entriesFor(HERO).map { it.componentId.value })
        assertEquals(1L, result.version)
    }

    @Test
    fun reorderComponents_unknownIdsAreIgnored() {
        val state = stateWith(HERO, entry("a"), entry("b"))
        val directive = AgentLayoutDirective.ReorderComponents(
            slotId = HERO,
            orderedComponentIds = listOf(ComponentId("missing"), ComponentId("b")),
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(listOf("b", "a"), result.entriesFor(HERO).map { it.componentId.value })
    }

    @Test
    fun reorderComponents_missingSlot_isNoOp() {
        val state = LayoutState.Empty
        val directive = AgentLayoutDirective.ReorderComponents(
            slotId = HERO,
            orderedComponentIds = listOf(ComponentId("a")),
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(state, result)
    }

    // ── SwapComponent ────────────────────────────────────────────────────────

    @Test
    fun swapComponent_replacesTargetWithNewEntry() {
        val state = stateWith(HERO, entry("a"), entry("b"))
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = ComponentId("a"),
            insertComponentId = ComponentId("c"),
            props = ComponentProps(mapOf("k" to "v")),
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive, now = NOW)

        val entries = result.entriesFor(HERO)
        assertEquals(listOf("c", "b"), entries.map { it.componentId.value })
        assertEquals("v", entries.first().props.get("k"))
        assertEquals(NOW, entries.first().addedAt)
        assertEquals(1L, result.version)
    }

    @Test
    fun swapComponent_missingSlot_isNoOp() {
        val state = LayoutState.Empty
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = ComponentId("a"),
            insertComponentId = ComponentId("b"),
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(state, result)
    }

    // ── LockComponent ────────────────────────────────────────────────────────

    @Test
    fun lockComponent_appliesLockModeToTargetOnly() {
        val state = stateWith(HERO, entry("a"), entry("b"))
        val directive = AgentLayoutDirective.LockComponent(
            componentId = ComponentId("a"),
            slotId = HERO,
            lockMode = LockMode.ReadOnly,
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        val entries = result.entriesFor(HERO)
        assertEquals(LockMode.ReadOnly, entries.first { it.componentId.value == "a" }.lockMode)
        assertEquals(null, entries.first { it.componentId.value == "b" }.lockMode)
        assertEquals(1L, result.version)
    }

    @Test
    fun lockComponent_missingSlot_isNoOp() {
        val state = LayoutState.Empty
        val directive = AgentLayoutDirective.LockComponent(
            componentId = ComponentId("a"),
            slotId = HERO,
            lockMode = LockMode.Hidden,
            correlationId = DirectiveId("d1"),
        )

        val result = LayoutReducer.apply(state, directive)

        assertEquals(state, result)
    }

    // ── positionFallsBackToEnd ──────────────────────────────────────────────

    @Test
    fun positionFallsBackToEnd_falseForNonShowDirective() {
        val state = stateWith(HERO, entry("a"))
        val directive = AgentLayoutDirective.HideComponent(
            componentId = ComponentId("a"),
            slotId = HERO,
            correlationId = DirectiveId("d1"),
        )

        assertFalse(LayoutReducer.positionFallsBackToEnd(state, directive))
    }

    @Test
    fun positionFallsBackToEnd_falseForStartEndIndexPositions() {
        val state = stateWith(HERO, entry("a"))

        assertFalse(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.Start)))
        assertFalse(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.End)))
        assertFalse(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.Index(0))))
    }

    @Test
    fun positionFallsBackToEnd_trueWhenReferenceMissing() {
        val state = stateWith(HERO, entry("a"))

        assertTrue(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.Before(ComponentId("missing")))))
        assertTrue(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.After(ComponentId("missing")))))
    }

    @Test
    fun positionFallsBackToEnd_falseWhenReferencePresent() {
        val state = stateWith(HERO, entry("a"))

        assertFalse(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.Before(ComponentId("a")))))
        assertFalse(LayoutReducer.positionFallsBackToEnd(state, show("b", HERO, Position.After(ComponentId("a")))))
    }
}

private val NOW: Instant = Instant.fromEpochMilliseconds(0)
private val HERO = SlotId("hero")
private val FOOTER = SlotId("footer")

private fun entry(id: String, addedAt: Instant = NOW): SlotEntry =
    SlotEntry(componentId = ComponentId(id), props = ComponentProps.Empty, addedAt = addedAt)

private fun stateWith(slotId: SlotId, vararg entries: SlotEntry): LayoutState =
    LayoutState(slots = mapOf(slotId to entries.toList()))

private fun show(componentId: String, slotId: SlotId, position: Position): AgentLayoutDirective.ShowComponent =
    AgentLayoutDirective.ShowComponent(
        componentId = ComponentId(componentId),
        slotId = slotId,
        position = position,
        correlationId = DirectiveId("d-$componentId"),
        issuedAt = NOW,
    )
