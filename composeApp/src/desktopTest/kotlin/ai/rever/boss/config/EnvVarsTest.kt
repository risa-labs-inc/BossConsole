package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvVarsTest {
    @Test
    fun `menu disable comments stop enabling kernel mode`() {
        assertEquals("KERNEL", parseEnvVars(listOf("BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
        assertEquals(null, parseEnvVars(listOf("# BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
    }

    @Test
    fun `export-prefixed lines are read as their bare key`() {
        // Shell convention, allowed because env_vars doubles as the secret-manager plugin's
        // key file. The settings reader recognised this line long before the ConfigLoader
        // reader did - that split is what BossConsole#450's review asked to close.
        assertEquals("KERNEL", parseEnvVars(listOf("export BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
    }

    @Test
    fun `indented comment lines are comments in the ConfigLoader reader too`() {
        assertEquals(null, parseEnvVars(listOf("  # BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
    }

    @Test
    fun `parser ignores malformed and blank entries and preserves embedded equals`() {
        val values = parseEnvVars(listOf("bad", "=bad", "BOSS_MODE= ", " A = first ", "A=value=tail"))
        assertEquals(1, values.size)
        assertEquals("value=tail", values.getProperty("A"))
    }

    @Test
    fun `saved mode uses the last active assignment`() {
        val values = parseEnvVars(listOf("BOSS_MODE=KERNEL", "  # BOSS_MODE=KERNEL", "BOSS_MODE=MONOLITH"))
        assertEquals("MONOLITH", values.getProperty("BOSS_MODE"))
    }
}
