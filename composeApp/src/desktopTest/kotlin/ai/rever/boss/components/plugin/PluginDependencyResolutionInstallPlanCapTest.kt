package ai.rever.boss.components.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the installPlan size cap.
 *
 * `PluginDependencyResolution.installPlan` checks `visited.size >= MAX_PLAN_SIZE`
 * inside the children loop AFTER `visited.add` has already added the parent,
 * so the parent itself is not counted against the cap. With `MAX_PLAN_SIZE = 32`
 * the walk can plan up to 33 plugins before `truncated` flips, and the offender
 * above the cap is still planned (`order += pluginId` runs after the loop).
 * The cap exists to keep consent dialogs from listing unbounded plans.
 *
 * The fix moves the cap check to the TOP of `visit`, before `visited.add`, so
 * exactly MAX_PLAN_SIZE entries are planned and the offender is dropped
 * rather than added.
 */
class PluginDependencyResolutionInstallPlanCapTest {
    @Test
    fun `installPlan caps the order at MAX_PLAN_SIZE and drops the offender`() =
        runTest {
            // Build a chain of plugins where every link is declared and none are present,
            // so the walk visits every one of them. We aim for a chain slightly longer
            // than the cap to exercise the bound.
            val total = PluginDependencyResolution.MAX_PLAN_SIZE + 1
            val chain = (0 until total).map { "com.example.p$it" }
            // rootId is the last entry; dependencies walk back from there.
            val deps =
                chain.dropLast(1).withIndex().associate { (i, id) ->
                    chain[i + 1] to listOf(id)
                }

            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = chain.last(),
                    isPresent = { false },
                    dependenciesOf = { id -> deps[id] ?: emptyList() },
                )

            assertEquals(
                PluginDependencyResolution.MAX_PLAN_SIZE,
                plan.order.size,
                "order must be capped at exactly MAX_PLAN_SIZE, got ${plan.order.size}",
            )
            // The walk visits the root first, then its children in order. The first
            // MAX_PLAN_SIZE ids visited are kept; the rest are dropped because the cap
            // trips after `visited.add`, not after the parent is added. We assert that
            // one specific id (the deepest one walked, dropped by the cap) is NOT in the plan.
            assertTrue(
                chain.first() !in plan.order,
                "the offender above the cap must be dropped, found ${chain.first()} in ${plan.order}",
            )
            assertTrue(plan.truncated, "truncated flag must be set when the cap fires")
        }
}
