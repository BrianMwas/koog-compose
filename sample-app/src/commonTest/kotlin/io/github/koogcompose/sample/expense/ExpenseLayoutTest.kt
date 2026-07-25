package io.github.koogcompose.sample.expense

import io.github.koogcompose.layout.FormFactor
import io.github.koogcompose.layout.Permissions
import io.github.koogcompose.layout.Platform
import io.github.koogcompose.layout.SlotCapacity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The value of the layout engine is that illegal layouts are impossible to express, not
 * merely discouraged by the prompt. These tests pin the parts that guarantee that: every
 * component the agent may name is registered, its tags overlap a real slot, and the FX
 * chart stays permission-gated.
 */
class ExpenseLayoutTest {

    @Test
    fun everyRegisteredComponentFitsAtLeastOneSlot() {
        val slots = expenseSlotRegistry()
        val components = expenseComponentRegistry()

        for (component in components.all) {
            val fits = slots.all.any { slot ->
                slot.allowedComponentTags.any { it in component.tags }
            }
            assertTrue(
                fits,
                "${component.id.value} carries ${component.tags} which matches no slot, " +
                    "so the agent could never legally show it",
            )
        }
    }

    @Test
    fun headlineSlotHoldsExactlyOneCard() {
        val headline = expenseSlotRegistry()[HEADLINE_SLOT]

        assertNotNull(headline)
        assertEquals(
            SlotCapacity.Single,
            headline.capacity,
            "a second headline must evict the first rather than stack",
        )
    }

    @Test
    fun insightsSlotIsCappedSoAChattyModelCannotFloodTheUi() {
        val insights = expenseSlotRegistry()[INSIGHTS_SLOT]

        assertNotNull(insights)
        val capacity = insights.capacity
        assertTrue(capacity is SlotCapacity.Multiple, "expected a bounded slot, got $capacity")
        assertEquals(4, capacity.max)
    }

    @Test
    fun spendCardsCannotLandInTheHeadlineSlot() {
        val slots = expenseSlotRegistry()
        val spendByCategory = expenseComponentRegistry().require(SPEND_BY_CATEGORY)

        val headlineTags = slots.require(HEADLINE_SLOT).allowedComponentTags
        assertTrue(
            headlineTags.none { it in spendByCategory.tags },
            "spend_by_category must not be placeable in the headline slot",
        )
    }

    @Test
    fun fxChartIsPermissionGated() {
        val fx = expenseComponentRegistry().require(FX_RATE_CHART)

        assertTrue(
            VIEW_FX in fx.requiredPermissions,
            "the FX chart must require $VIEW_FX so the reducer, not the prompt, enforces access",
        )
    }

    @Test
    fun basicRoleLacksFxPermissionWhileFullRoleHasIt() {
        val full = MoneyCoachRole(canViewFx = true)
        val basic = MoneyCoachRole(canViewFx = false)

        assertTrue(VIEW_FX in full.permissions)
        assertFalse(VIEW_FX in basic.permissions)

        // Both roles can still read, or they could not use the app at all.
        assertTrue(Permissions.Read in full.permissions)
        assertTrue(Permissions.Read in basic.permissions)

        // UserRole equality is by id, so the two roles must not collapse into one.
        assertTrue(full != basic, "the two roles must be distinguishable")
    }

    @Test
    fun layoutConfigCarriesTheRoleAndPlatformItWasBuiltFor() {
        val config = expenseLayoutConfig(
            platform = Platform.Desktop,
            formFactor = FormFactor.Desktop,
            role = MoneyCoachRole(canViewFx = false),
        )

        assertEquals(Platform.Desktop, config.workflowContext.deviceContext.platform)
        assertEquals(FormFactor.Desktop, config.workflowContext.deviceContext.formFactor)
        assertFalse(VIEW_FX in config.workflowContext.userRole.permissions)
    }

    @Test
    fun categoryPropsCoverEveryDemoCategory() {
        val props = MoneyCoachData.categoryProps()

        assertEquals(MoneyCoachData.categories.size, props.values.size)
        for (category in MoneyCoachData.categories) {
            assertEquals(
                category.minorUnits.toString(),
                props.get(category.category),
                "props lost the figure for ${category.category}",
            )
        }
    }

    @Test
    fun categoryFiguresSumToTheHeadlineTotal() {
        val summed = MoneyCoachData.categories.sumOf { it.minorUnits }

        assertEquals(
            MoneyCoachData.monthTotalMinorUnits,
            summed,
            "the headline total must match the category breakdown, or the demo contradicts itself",
        )
    }

    @Test
    fun minorUnitsFormatWithTwoDecimalPlaces() {
        assertEquals("1842.36", formatMinorUnits(184_236))
        assertEquals("0.05", formatMinorUnits(5))
        assertEquals("0.50", formatMinorUnits(50))
        assertEquals("10.00", formatMinorUnits(1_000))
    }
}
