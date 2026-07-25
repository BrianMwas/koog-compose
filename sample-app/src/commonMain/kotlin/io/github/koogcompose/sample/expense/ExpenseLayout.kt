package io.github.koogcompose.sample.expense

import io.github.koogcompose.layout.BusinessId
import io.github.koogcompose.layout.ComponentDefinition
import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.ComponentRegistry
import io.github.koogcompose.layout.DeviceContext
import io.github.koogcompose.layout.FormFactor
import io.github.koogcompose.layout.LayoutEngineConfig
import io.github.koogcompose.layout.LayoutSlot
import io.github.koogcompose.layout.Permission
import io.github.koogcompose.layout.Permissions
import io.github.koogcompose.layout.Platform
import io.github.koogcompose.layout.NetworkClass
import io.github.koogcompose.layout.SlotCapacity
import io.github.koogcompose.layout.SlotId
import io.github.koogcompose.layout.SlotRegistry
import io.github.koogcompose.layout.Tag
import io.github.koogcompose.layout.UserRole
import io.github.koogcompose.layout.WorkflowContext
import io.github.koogcompose.layout.WorkflowId

/**
 * Slot and component vocabulary for the Money Coach sample.
 *
 * This file is the interesting half of the sample and is deliberately free of Compose
 * types so it can be unit-tested: it declares *what the agent is allowed to build*.
 * The agent never constructs UI — it emits [io.github.koogcompose.layout.AgentLayoutDirective]s
 * naming components from [expenseComponentRegistry], and the reducer decides whether each
 * one is legal for the current slot, role, and phase.
 *
 * The Compose side (`MoneyCoachApp`) only maps a [ComponentId] to a composable.
 */

// ── Tags ──────────────────────────────────────────────────────────────────────
// A slot accepts tags, a component carries tags, and the engine rejects any pairing
// that does not overlap. This is what stops the model inventing illegal layouts.

/** Anything that summarises money already spent. */
public val SPEND_TAG: Tag = Tag("spend")

/** Anything plotting a rate or series over time. */
public val CHART_TAG: Tag = Tag("chart")

/** The single headline figure. */
public val HEADLINE_TAG: Tag = Tag("headline")

// ── Slots ─────────────────────────────────────────────────────────────────────

/**
 * The one headline card at the top. [SlotCapacity.Single] means a second `ShowComponent`
 * evicts the first rather than stacking, so the agent cannot fill the screen with
 * competing headlines.
 */
public val HEADLINE_SLOT: SlotId = SlotId("headline")

/** The scrolling body. Capped at four cards so a chatty model cannot flood the UI. */
public val INSIGHTS_SLOT: SlotId = SlotId("insights")

// ── Components ────────────────────────────────────────────────────────────────

public val MONTHLY_TOTAL: ComponentId = ComponentId("monthly_total")
public val SPEND_BY_CATEGORY: ComponentId = ComponentId("spend_by_category")
public val RECENT_TRANSACTIONS: ComponentId = ComponentId("recent_transactions")
public val FX_RATE_CHART: ComponentId = ComponentId("fx_rate_chart")

/** Permission gating the FX chart — see [MoneyCoachRole]. */
public val VIEW_FX: Permission = Permission("sample.fx.view")

/**
 * The sample's single role. Real apps subclass [UserRole] once per role at startup.
 *
 * @param canViewFx when false the role lacks [VIEW_FX], and the engine rejects any
 *   directive trying to show [FX_RATE_CHART] — which is the point of the permission
 *   demo: the *agent* does not enforce this, the reducer does.
 */
public class MoneyCoachRole(canViewFx: Boolean = true) : UserRole(
    id = if (canViewFx) "coach-full" else "coach-basic",
    displayName = if (canViewFx) "Money Coach" else "Money Coach (no FX)",
    permissions = buildSet {
        add(Permissions.Read)
        if (canViewFx) add(VIEW_FX)
    },
)

/** Slots this sample supports. Immutable for the life of the app. */
public fun expenseSlotRegistry(): SlotRegistry = SlotRegistry(
    listOf(
        LayoutSlot(
            id = HEADLINE_SLOT,
            capacity = SlotCapacity.Single,
            allowedComponentTags = setOf(HEADLINE_TAG),
        ),
        LayoutSlot(
            id = INSIGHTS_SLOT,
            capacity = SlotCapacity.Multiple(max = 4),
            allowedComponentTags = setOf(SPEND_TAG, CHART_TAG),
        ),
    )
)

/** Components the agent may name. Anything else is rejected at schema validation. */
public fun expenseComponentRegistry(): ComponentRegistry = ComponentRegistry(
    listOf(
        ComponentDefinition(id = MONTHLY_TOTAL, tags = setOf(HEADLINE_TAG)),
        ComponentDefinition(id = SPEND_BY_CATEGORY, tags = setOf(SPEND_TAG)),
        ComponentDefinition(id = RECENT_TRANSACTIONS, tags = setOf(SPEND_TAG)),
        ComponentDefinition(
            id = FX_RATE_CHART,
            tags = setOf(CHART_TAG),
            requiredPermissions = setOf(VIEW_FX),
        ),
    )
)

/** Describes who is running the session and on what, for the policy layer. */
public fun expenseWorkflowContext(
    platform: Platform,
    formFactor: FormFactor = FormFactor.Phone,
    role: UserRole = MoneyCoachRole(),
    networkClass: NetworkClass = NetworkClass.Online,
): WorkflowContext = WorkflowContext(
    businessId = BusinessId("koog-compose-sample"),
    userRole = role,
    activeWorkflow = WorkflowId("money-coach"),
    deviceContext = DeviceContext(
        platform = platform,
        formFactor = formFactor,
        networkClass = networkClass,
        locale = "en-US",
    ),
)

/** Assembles the full engine config passed to `koogCompose { layout { } }`. */
public fun expenseLayoutConfig(
    platform: Platform,
    formFactor: FormFactor = FormFactor.Phone,
    role: UserRole = MoneyCoachRole(),
): LayoutEngineConfig = LayoutEngineConfig(
    workflowContext = expenseWorkflowContext(platform, formFactor, role),
    slotRegistry = expenseSlotRegistry(),
    componentRegistry = expenseComponentRegistry(),
)

// ── Demo data ─────────────────────────────────────────────────────────────────
// The sample ships fixed figures so it is reproducible offline and so the interesting
// part stays the agent's layout choices rather than a data pipeline.

/** One spending category, as rendered by [SPEND_BY_CATEGORY]. */
public data class CategorySpend(
    public val category: String,
    public val minorUnits: Long,
)

/** One point on the [FX_RATE_CHART] series. */
public data class FxPoint(
    public val label: String,
    public val rate: Double,
)

/** Fixed demo figures, in minor units to avoid floating-point money. */
public object MoneyCoachData {
    public val monthTotalMinorUnits: Long = 184_236

    public val categories: List<CategorySpend> = listOf(
        CategorySpend("Rent", 90_000),
        CategorySpend("Groceries", 42_150),
        CategorySpend("Transport", 21_400),
        CategorySpend("Eating out", 18_686),
        CategorySpend("Everything else", 12_000),
    )

    public val usdKes: List<FxPoint> = listOf(
        FxPoint("Mar", 129.4),
        FxPoint("Apr", 130.1),
        FxPoint("May", 129.0),
        FxPoint("Jun", 127.8),
        FxPoint("Jul", 128.6),
    )

    /** Props for [SPEND_BY_CATEGORY]: the engine only carries strings. */
    public fun categoryProps(): ComponentProps = ComponentProps(
        values = categories.associate { it.category to it.minorUnits.toString() },
    )
}

/** Formats minor units as a plain decimal string without platform locale APIs. */
public fun formatMinorUnits(minorUnits: Long): String {
    val whole = minorUnits / 100
    val cents = (minorUnits % 100).toInt()
    val padded = if (cents < 10) "0$cents" else "$cents"
    return "$whole.$padded"
}
