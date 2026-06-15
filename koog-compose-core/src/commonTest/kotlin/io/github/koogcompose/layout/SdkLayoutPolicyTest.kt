package io.github.koogcompose.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SdkLayoutPolicyTest {

    // ── ShowComponent ────────────────────────────────────────────────────

    @Test
    fun show_validComponent_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(show(BANNER), LayoutState.Empty, workflowContext())

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun show_unknownSlot_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(show(BANNER, slotId = SlotId("missing")), LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SchemaValidation, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun show_unknownComponent_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(show(ComponentId("missing")), LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SchemaValidation, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun show_tagMismatch_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(show(WRONG_TAG), LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SlotConstraintCheck, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun show_insufficientPermissions_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(
            show(ADMIN_PANEL),
            LayoutState.Empty,
            workflowContext(permissions = setOf(Permissions.Read)),
        )

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.PolicyCheck, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun show_sufficientPermissions_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())

        val decision = policy.evaluate(
            show(ADMIN_PANEL),
            LayoutState.Empty,
            workflowContext(permissions = setOf(Permissions.Admin)),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ── HideComponent ────────────────────────────────────────────────────

    @Test
    fun hide_unknownSlot_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.HideComponent(
            componentId = BANNER,
            slotId = SlotId("missing"),
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SchemaValidation, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun hide_unknownComponent_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.HideComponent(
            componentId = ComponentId("missing"),
            slotId = HERO,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SchemaValidation, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun hide_nullSlotId_knownComponent_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.HideComponent(
            componentId = BANNER,
            slotId = null,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ── ReorderComponents ────────────────────────────────────────────────

    @Test
    fun reorder_unknownSlot_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.ReorderComponents(
            slotId = SlotId("missing"),
            orderedComponentIds = emptyList(),
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SchemaValidation, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun reorder_knownSlot_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.ReorderComponents(
            slotId = HERO,
            orderedComponentIds = listOf(BANNER),
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ── SwapComponent ────────────────────────────────────────────────────

    @Test
    fun swap_validSwap_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val state = stateWith(HERO, BANNER)
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = BANNER,
            insertComponentId = ADMIN_PANEL,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, state, workflowContext(permissions = setOf(Permissions.Admin)))

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun swap_removeComponentNotInSlot_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = BANNER,
            insertComponentId = ADMIN_PANEL,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext(permissions = setOf(Permissions.Admin)))

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.Reduce, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun swap_insertComponentTagMismatch_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val state = stateWith(HERO, BANNER)
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = BANNER,
            insertComponentId = WRONG_TAG,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, state, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.SlotConstraintCheck, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun swap_insertComponentInsufficientPermissions_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val state = stateWith(HERO, BANNER)
        val directive = AgentLayoutDirective.SwapComponent(
            slotId = HERO,
            removeComponentId = BANNER,
            insertComponentId = ADMIN_PANEL,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, state, workflowContext(permissions = setOf(Permissions.Read)))

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.PolicyCheck, (decision as PolicyDecision.Deny).stage)
    }

    // ── LockComponent ────────────────────────────────────────────────────

    @Test
    fun lock_componentNotInSlot_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val directive = AgentLayoutDirective.LockComponent(
            componentId = BANNER,
            slotId = HERO,
            lockMode = LockMode.ReadOnly,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.Reduce, (decision as PolicyDecision.Deny).stage)
    }

    @Test
    fun lock_strengtheningExistingLock_isAllowed() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val state = stateWith(HERO, BANNER, lockMode = LockMode.ReadOnly)
        val directive = AgentLayoutDirective.LockComponent(
            componentId = BANNER,
            slotId = HERO,
            lockMode = LockMode.Hidden,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, state, workflowContext())

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun lock_weakeningExistingLock_isDenied() {
        val policy = SdkLayoutPolicy(slotRegistry(), componentRegistry())
        val state = stateWith(HERO, BANNER, lockMode = LockMode.Hidden)
        val directive = AgentLayoutDirective.LockComponent(
            componentId = BANNER,
            slotId = HERO,
            lockMode = LockMode.ReadOnly,
            correlationId = DirectiveId("d1"),
        )

        val decision = policy.evaluate(directive, state, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals(PipelineStage.PolicyCheck, (decision as PolicyDecision.Deny).stage)
    }
}

class LayoutPolicyChainTest {

    @Test
    fun emptyChain_allowsEverything() {
        val decision = LayoutPolicyChain.Empty.evaluate(show(BANNER), LayoutState.Empty, workflowContext())

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun firstDeny_shortCircuits() {
        val denyPolicy = FakePolicy { PolicyDecision.Deny("nope") }
        val neverCalled = FakePolicy { error("should not be evaluated") }
        val chain = LayoutPolicyChain(listOf(denyPolicy, neverCalled))

        val decision = chain.evaluate(show(BANNER), LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Deny)
        assertEquals("nope", (decision as PolicyDecision.Deny).reason)
    }

    @Test
    fun rewrite_isPassedToSubsequentPolicies() {
        val rewritten = show(ADMIN_PANEL)
        val rewritePolicy = FakePolicy { PolicyDecision.Rewrite(rewritten, "rewrote") }
        var seenBySecondPolicy: AgentLayoutDirective? = null
        val secondPolicy = FakePolicy { directive ->
            seenBySecondPolicy = directive
            PolicyDecision.Allow
        }
        val chain = LayoutPolicyChain(listOf(rewritePolicy, secondPolicy))

        val decision = chain.evaluate(show(BANNER), LayoutState.Empty, workflowContext())

        assertTrue(decision is PolicyDecision.Rewrite)
        assertEquals(rewritten, (decision as PolicyDecision.Rewrite).replacement)
        assertEquals(rewritten, seenBySecondPolicy)
    }
}

class ComponentRegistryValidationTest {

    @Test
    fun validateAgainst_passesWhenFallbackComponentMatchesTags() {
        val registry = ComponentRegistry(listOf(ComponentDefinition(BANNER, setOf(PROMO))))
        val slots = SlotRegistry(
            listOf(LayoutSlot(HERO, SlotCapacity.Single, setOf(PROMO), SlotDefault.FallbackComponent(BANNER)))
        )

        registry.validateAgainst(slots)
    }

    @Test
    fun validateAgainst_throwsWhenFallbackComponentNotRegistered() {
        val registry = ComponentRegistry(emptyList())
        val slots = SlotRegistry(
            listOf(LayoutSlot(HERO, SlotCapacity.Single, setOf(PROMO), SlotDefault.FallbackComponent(BANNER)))
        )

        assertFailsWith<IllegalStateException> { registry.validateAgainst(slots) }
    }

    @Test
    fun validateAgainst_throwsWhenFallbackComponentTagDoesNotMatchSlot() {
        val registry = ComponentRegistry(listOf(ComponentDefinition(BANNER, setOf(OTHER))))
        val slots = SlotRegistry(
            listOf(LayoutSlot(HERO, SlotCapacity.Single, setOf(PROMO), SlotDefault.FallbackComponent(BANNER)))
        )

        assertFailsWith<IllegalArgumentException> { registry.validateAgainst(slots) }
    }
}

// ── Shared fixtures ─────────────────────────────────────────────────────────

private val NOW: Instant = Instant.fromEpochMilliseconds(0)
private val HERO = SlotId("hero")
private val PROMO = Tag("promo")
private val OTHER = Tag("other")

private val BANNER = ComponentId("banner")
private val ADMIN_PANEL = ComponentId("admin_panel")
private val WRONG_TAG = ComponentId("wrong_tag")

private fun slotRegistry(): SlotRegistry = SlotRegistry(
    listOf(LayoutSlot(HERO, SlotCapacity.Multiple(3), setOf(PROMO)))
)

private fun componentRegistry(): ComponentRegistry = ComponentRegistry(
    listOf(
        ComponentDefinition(BANNER, setOf(PROMO)),
        ComponentDefinition(ADMIN_PANEL, setOf(PROMO), setOf(Permissions.Admin)),
        ComponentDefinition(WRONG_TAG, setOf(OTHER)),
    )
)

private class PolicyTestUserRole(permissions: Set<Permission>) : UserRole(
    id = "viewer",
    displayName = "Viewer",
    permissions = permissions,
)

private fun workflowContext(permissions: Set<Permission> = setOf(Permissions.Read)): WorkflowContext = WorkflowContext(
    businessId = BusinessId("biz-1"),
    userRole = PolicyTestUserRole(permissions),
    activeWorkflow = WorkflowId("wf-1"),
    deviceContext = DeviceContext(Platform.Android, FormFactor.Phone, NetworkClass.Online, "en-US"),
)

private fun show(
    componentId: ComponentId,
    slotId: SlotId = HERO,
    position: Position = Position.End,
): AgentLayoutDirective.ShowComponent = AgentLayoutDirective.ShowComponent(
    componentId = componentId,
    slotId = slotId,
    position = position,
    correlationId = DirectiveId("d1"),
)

private fun stateWith(slotId: SlotId, componentId: ComponentId, lockMode: LockMode? = null): LayoutState =
    LayoutState(slots = mapOf(slotId to listOf(SlotEntry(componentId, ComponentProps.Empty, lockMode, addedAt = NOW))))

private class FakePolicy(private val decide: (AgentLayoutDirective) -> PolicyDecision) : LayoutPolicy {
    override fun evaluate(directive: AgentLayoutDirective, state: LayoutState, context: WorkflowContext): PolicyDecision =
        decide(directive)
}
