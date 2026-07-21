package ai.rever.boss.plugin.loader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSignatureEnforcementTest {

    @AfterTest
    fun cleanup() {
        System.clearProperty(PluginSignatureEnforcement.PROPERTY)
    }

    private fun withValue(value: String): Boolean {
        System.setProperty(PluginSignatureEnforcement.PROPERTY, value)
        return PluginSignatureEnforcement.enforceUnsigned
    }

    @Test
    fun `unset falls back to compiled default (warn during rollout)`() {
        System.clearProperty(PluginSignatureEnforcement.PROPERTY)
        assertFalse(PluginSignatureEnforcement.enforceUnsigned)
    }

    @Test
    fun `common truthy spellings all enable enforcement`() {
        for (v in listOf("true", "TRUE", "True", " true ", "1", "yes", "YES", "on", "ON")) {
            assertTrue(withValue(v), "expected '$v' to enable enforcement")
        }
    }

    @Test
    fun `common falsy spellings all disable enforcement`() {
        for (v in listOf("false", "FALSE", "0", "no", "off", " Off ")) {
            assertFalse(withValue(v), "expected '$v' to disable enforcement")
        }
    }

    @Test
    fun `unrecognized value falls back to default without throwing`() {
        // The warning path — an operator typo must not crash, and during
        // rollout it falls back to warn-mode (logged, not silent).
        assertFalse(withValue("enable"))
        assertFalse(withValue(""))
    }
}
