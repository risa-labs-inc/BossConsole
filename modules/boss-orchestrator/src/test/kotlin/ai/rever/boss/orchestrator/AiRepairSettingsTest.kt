package ai.rever.boss.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the switch that decides whether crash source files leave the machine.
 *
 * This used to turn itself on because `OPENAI_API_KEY` happened to be set — a key that is present
 * on developer machines for entirely unrelated reasons. Enabling source upload is a decision
 * someone has to make on purpose, so it now takes an explicit opt-in *and* a named root, and each
 * of those is asserted separately here.
 */
class AiRepairSettingsTest {
    @Test
    fun `a key on its own does not enable source upload`() {
        val settings = aiRepairSettings(optIn = null, projectRoot = "/work/project", apiKey = "sk-live")

        assertFalse(settings.enabled)
        assertNull(settings.projectRoot)
    }

    @Test
    fun `an opt-in without a named root does not enable it`() {
        // "Yes, use AI repair" without saying what it may read is not an answer.
        val settings = aiRepairSettings(optIn = "true", projectRoot = null, apiKey = "sk-live")

        assertFalse(settings.enabled)
        assertNull(settings.projectRoot)
    }

    @Test
    fun `a blank root counts as no root`() {
        val settings = aiRepairSettings(optIn = "true", projectRoot = "   ", apiKey = "sk-live")

        assertFalse(settings.enabled)
    }

    @Test
    fun `an opt-in without a key does not enable it`() {
        val settings = aiRepairSettings(optIn = "true", projectRoot = "/work/project", apiKey = null)

        assertFalse(settings.enabled)
        assertNull(settings.projectRoot)
    }

    @Test
    fun `all three together enable it, and name the root`() {
        val settings = aiRepairSettings(optIn = "true", projectRoot = "/work/project", apiKey = "sk-live")

        assertTrue(settings.enabled)
        assertEquals("/work/project", settings.projectRoot)
    }

    @Test
    fun `the opt-in is not case sensitive`() {
        assertTrue(aiRepairSettings("TRUE", "/work/project", "sk-live").enabled)
    }

    @Test
    fun `anything other than true is off`() {
        // Including the shapes a config file might plausibly produce.
        listOf("false", "1", "yes", "", "  ").forEach { value ->
            assertFalse(
                aiRepairSettings(value, "/work/project", "sk-live").enabled,
                "opt-in \"$value\" must not enable source upload",
            )
        }
    }

    @Test
    fun `the root is withheld when the feature is off`() {
        // RepairEngine reads nothing without a root, so an off switch must not leak one through.
        val settings = aiRepairSettings(optIn = "false", projectRoot = "/work/project", apiKey = "sk-live")

        assertNull(settings.projectRoot)
    }
}
