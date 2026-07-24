package ai.rever.boss.keymap

import ai.rever.boss.keymap.lifecycle.AlwaysEnabledCondition
import ai.rever.boss.keymap.lifecycle.AndCondition
import ai.rever.boss.keymap.lifecycle.NeverEnabledCondition
import ai.rever.boss.keymap.lifecycle.OrCondition
import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleCondition
import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ShortcutLifecycleManager component.
 *
 * Tests cover:
 * - Condition registration and unregistration
 * - Enable/disable state checking
 * - Reevaluation of conditions
 * - Built-in condition classes
 * - State management
 */
class ShortcutLifecycleManagerTest {
    @BeforeEach
    fun setUp() {
        ShortcutLifecycleManager.clear()
        ShortcutLifecycleManager.debugMode = false
    }

    @AfterEach
    fun tearDown() {
        ShortcutLifecycleManager.clear()
    }

    // ==================== REGISTRATION TESTS ====================

    @Test
    fun `registerCondition adds condition`() {
        val condition = AlwaysEnabledCondition

        ShortcutLifecycleManager.registerCondition("test.action", condition)

        assertTrue(ShortcutLifecycleManager.getRegisteredActions().contains("test.action"))
    }

    @Test
    fun `unregisterCondition removes condition`() {
        val condition = AlwaysEnabledCondition
        ShortcutLifecycleManager.registerCondition("test.action", condition)

        ShortcutLifecycleManager.unregisterCondition("test.action")

        assertFalse(ShortcutLifecycleManager.getRegisteredActions().contains("test.action"))
    }

    @Test
    fun `getRegisteredActions returns all registered action IDs`() {
        ShortcutLifecycleManager.registerCondition("action1", AlwaysEnabledCondition)
        ShortcutLifecycleManager.registerCondition("action2", AlwaysEnabledCondition)
        ShortcutLifecycleManager.registerCondition("action3", AlwaysEnabledCondition)

        val actions = ShortcutLifecycleManager.getRegisteredActions()

        assertEquals(3, actions.size)
        assertTrue(actions.containsAll(setOf("action1", "action2", "action3")))
    }

    @Test
    fun `clear removes all conditions and states`() {
        ShortcutLifecycleManager.registerCondition("action1", AlwaysEnabledCondition)
        ShortcutLifecycleManager.registerCondition("action2", AlwaysEnabledCondition)

        ShortcutLifecycleManager.clear()

        assertTrue(ShortcutLifecycleManager.getRegisteredActions().isEmpty())
    }

    // ==================== IS ENABLED TESTS ====================

    @Test
    fun `isEnabled returns true for unregistered action`() =
        runBlocking {
            val enabled = ShortcutLifecycleManager.isEnabled("unregistered.action")
            assertTrue(enabled, "Unregistered actions should be enabled by default")
        }

    @Test
    fun `isEnabled returns true for AlwaysEnabledCondition`() =
        runBlocking {
            ShortcutLifecycleManager.registerCondition("test.action", AlwaysEnabledCondition)

            val enabled = ShortcutLifecycleManager.isEnabled("test.action")

            assertTrue(enabled)
        }

    @Test
    fun `isEnabled returns false for NeverEnabledCondition`() =
        runBlocking {
            ShortcutLifecycleManager.registerCondition("test.action", NeverEnabledCondition())

            val enabled = ShortcutLifecycleManager.isEnabled("test.action")

            assertFalse(enabled)
        }

    @Test
    fun `isEnabled handles condition exception`() =
        runBlocking {
            val failingCondition =
                object : ShortcutLifecycleCondition {
                    override suspend fun isEnabled(): Boolean = throw RuntimeException("Test error")

                    override val disabledReason: String = "Error"
                }

            ShortcutLifecycleManager.registerCondition("test.action", failingCondition)

            val enabled = ShortcutLifecycleManager.isEnabled("test.action")

            assertFalse(enabled, "Should return false on error (fail-safe)")
        }

    // ==================== STATE TESTS ====================

    @Test
    fun `getState returns null for unregistered action`() {
        val state = ShortcutLifecycleManager.getState("unregistered.action")
        assertNull(state)
    }

    @Test
    fun `getState returns state after registration`() =
        runBlocking {
            ShortcutLifecycleManager.registerCondition("test.action", AlwaysEnabledCondition)

            // Wait for async evaluation
            delay(100)

            val state = ShortcutLifecycleManager.getState("test.action")

            assertNotNull(state)
            assertEquals("test.action", state.actionId)
            assertTrue(state.enabled)
        }

    @Test
    fun `getDisabledReason returns null for enabled action`() =
        runBlocking {
            ShortcutLifecycleManager.registerCondition("test.action", AlwaysEnabledCondition)
            delay(100)

            val reason = ShortcutLifecycleManager.getDisabledReason("test.action")

            assertNull(reason)
        }

    @Test
    fun `getDisabledReason returns reason for disabled action`() =
        runBlocking {
            val condition = NeverEnabledCondition("No tabs open")
            ShortcutLifecycleManager.registerCondition("test.action", condition)
            delay(100)

            val reason = ShortcutLifecycleManager.getDisabledReason("test.action")

            assertEquals("No tabs open", reason)
        }

    // ==================== REEVALUATION TESTS ====================

    @Test
    fun `reevaluate updates all states`() =
        runBlocking {
            var value = false

            val dynamicCondition =
                object : ShortcutLifecycleCondition {
                    override suspend fun isEnabled(): Boolean = value

                    override val disabledReason: String = "Value is false"
                }

            ShortcutLifecycleManager.registerCondition("test.action", dynamicCondition)
            delay(100)

            assertFalse(ShortcutLifecycleManager.isEnabled("test.action"))

            // Change value and reevaluate
            value = true
            ShortcutLifecycleManager.reevaluate()
            delay(100)

            assertTrue(ShortcutLifecycleManager.isEnabled("test.action"))
        }

    @Test
    fun `reevaluateSingle updates single state`() =
        runBlocking {
            var value1 = false
            var value2 = false

            val condition1 =
                object : ShortcutLifecycleCondition {
                    override suspend fun isEnabled(): Boolean = value1

                    override val disabledReason: String = "Value 1 is false"
                }
            val condition2 =
                object : ShortcutLifecycleCondition {
                    override suspend fun isEnabled(): Boolean = value2

                    override val disabledReason: String = "Value 2 is false"
                }

            ShortcutLifecycleManager.registerCondition("action1", condition1)
            ShortcutLifecycleManager.registerCondition("action2", condition2)
            delay(100)

            // Change only value1 and reevaluate only action1
            value1 = true
            ShortcutLifecycleManager.reevaluateSingle("action1")
            delay(100)

            assertTrue(ShortcutLifecycleManager.isEnabled("action1"))
            assertFalse(ShortcutLifecycleManager.isEnabled("action2"))
        }

    // ==================== ALWAYS ENABLED CONDITION TESTS ====================

    @Test
    fun `AlwaysEnabledCondition isEnabled returns true`() =
        runBlocking {
            assertTrue(AlwaysEnabledCondition.isEnabled())
        }

    @Test
    fun `AlwaysEnabledCondition disabledReason is descriptive`() {
        assertEquals("Always enabled", AlwaysEnabledCondition.disabledReason)
    }

    @Test
    fun `AlwaysEnabledCondition enabledDescription is descriptive`() {
        assertEquals("Always enabled", AlwaysEnabledCondition.enabledDescription)
    }

    // ==================== NEVER ENABLED CONDITION TESTS ====================

    @Test
    fun `NeverEnabledCondition isEnabled returns false`() =
        runBlocking {
            val condition = NeverEnabledCondition()
            assertFalse(condition.isEnabled())
        }

    @Test
    fun `NeverEnabledCondition uses custom disabled reason`() {
        val condition = NeverEnabledCondition("Feature not implemented")
        assertEquals("Feature not implemented", condition.disabledReason)
    }

    @Test
    fun `NeverEnabledCondition uses default disabled reason`() {
        val condition = NeverEnabledCondition()
        assertEquals("Shortcut disabled", condition.disabledReason)
    }

    @Test
    fun `NeverEnabledCondition enabledDescription is Never`() {
        val condition = NeverEnabledCondition()
        assertEquals("Never", condition.enabledDescription)
    }

    // ==================== AND CONDITION TESTS ====================

    @Test
    fun `AndCondition returns true when all conditions true`() =
        runBlocking {
            val condition =
                AndCondition(
                    listOf(
                        AlwaysEnabledCondition,
                        AlwaysEnabledCondition,
                        AlwaysEnabledCondition,
                    ),
                )

            assertTrue(condition.isEnabled())
        }

    @Test
    fun `AndCondition returns false when any condition false`() =
        runBlocking {
            val condition =
                AndCondition(
                    listOf(
                        AlwaysEnabledCondition,
                        NeverEnabledCondition(),
                        AlwaysEnabledCondition,
                    ),
                )

            assertFalse(condition.isEnabled())
        }

    @Test
    fun `AndCondition returns true for empty list`() =
        runBlocking {
            val condition = AndCondition(emptyList())
            assertTrue(condition.isEnabled())
        }

    @Test
    fun `AndCondition enabledDescription joins with and`() {
        val condition =
            AndCondition(
                listOf(
                    AlwaysEnabledCondition,
                    AlwaysEnabledCondition,
                ),
            )

        assertTrue(condition.enabledDescription.contains(" and "))
    }

    // ==================== OR CONDITION TESTS ====================

    @Test
    fun `OrCondition returns true when any condition true`() =
        runBlocking {
            val condition =
                OrCondition(
                    listOf(
                        NeverEnabledCondition(),
                        AlwaysEnabledCondition,
                        NeverEnabledCondition(),
                    ),
                )

            assertTrue(condition.isEnabled())
        }

    @Test
    fun `OrCondition returns false when all conditions false`() =
        runBlocking {
            val condition =
                OrCondition(
                    listOf(
                        NeverEnabledCondition(),
                        NeverEnabledCondition(),
                        NeverEnabledCondition(),
                    ),
                )

            assertFalse(condition.isEnabled())
        }

    @Test
    fun `OrCondition returns false for empty list`() =
        runBlocking {
            val condition = OrCondition(emptyList())
            assertFalse(condition.isEnabled())
        }

    @Test
    fun `OrCondition enabledDescription joins with or`() {
        val condition =
            OrCondition(
                listOf(
                    AlwaysEnabledCondition,
                    AlwaysEnabledCondition,
                ),
            )

        assertTrue(condition.enabledDescription.contains(" or "))
    }

    @Test
    fun `OrCondition disabledReason lists all conditions`() {
        val condition =
            OrCondition(
                listOf(
                    NeverEnabledCondition("Reason 1"),
                    NeverEnabledCondition("Reason 2"),
                ),
            )

        val reason = condition.disabledReason

        assertTrue(reason.contains("Reason 1"))
        assertTrue(reason.contains("Reason 2"))
    }

    // ==================== DEBUG MODE TESTS ====================

    @Test
    fun `debug mode can be toggled`() {
        ShortcutLifecycleManager.debugMode = true
        assertTrue(ShortcutLifecycleManager.debugMode)

        ShortcutLifecycleManager.debugMode = false
        assertFalse(ShortcutLifecycleManager.debugMode)
    }

    // ==================== STATES FLOW TESTS ====================

    @Test
    fun `states flow emits initial empty map`() =
        runBlocking {
            ShortcutLifecycleManager.clear()

            val currentStates = ShortcutLifecycleManager.states.value

            assertTrue(currentStates.isEmpty())
        }

    @Test
    fun `states flow updates when condition registered`() =
        runBlocking {
            ShortcutLifecycleManager.registerCondition("test.action", AlwaysEnabledCondition)
            delay(100)

            val currentStates = ShortcutLifecycleManager.states.value

            assertTrue(currentStates.containsKey("test.action"))
        }

    // ==================== AUTO REEVALUATION TESTS ====================

    @Test
    fun `startAutoReevaluation returns cancellable job`() =
        runBlocking {
            val job = ShortcutLifecycleManager.startAutoReevaluation(intervalMs = 1000)

            assertTrue(job.isActive)

            job.cancel()
            delay(50)

            assertFalse(job.isActive)
        }

    // ==================== CUSTOM CONDITION TESTS ====================

    @Test
    fun `custom condition with dynamic state`() =
        runBlocking {
            var tabCount = 0

            val condition =
                object : ShortcutLifecycleCondition {
                    override suspend fun isEnabled(): Boolean = tabCount > 0

                    override val disabledReason: String = "No tabs open"
                    override val enabledDescription: String = "When tabs are open"
                }

            ShortcutLifecycleManager.registerCondition("tab.close", condition)
            delay(100)

            assertFalse(ShortcutLifecycleManager.isEnabled("tab.close"))

            tabCount = 5
            ShortcutLifecycleManager.reevaluate()
            delay(100)

            assertTrue(ShortcutLifecycleManager.isEnabled("tab.close"))
        }

    @Test
    fun `nested And and Or conditions work correctly`() =
        runBlocking {
            // (true AND true) OR false = true
            val condition =
                OrCondition(
                    listOf(
                        AndCondition(
                            listOf(
                                AlwaysEnabledCondition,
                                AlwaysEnabledCondition,
                            ),
                        ),
                        NeverEnabledCondition(),
                    ),
                )

            assertTrue(condition.isEnabled())
        }

    @Test
    fun `default enabledDescription is Always`() {
        val condition =
            object : ShortcutLifecycleCondition {
                override suspend fun isEnabled(): Boolean = true

                override val disabledReason: String = "N/A"
                // Don't override enabledDescription
            }

        assertEquals("Always", condition.enabledDescription)
    }
}
