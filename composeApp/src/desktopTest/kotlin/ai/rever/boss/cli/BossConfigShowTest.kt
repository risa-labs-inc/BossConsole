package ai.rever.boss.cli

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossConfigShowTest {
    private val show = ConfigShow()

    private fun emptyProps(): Properties = Properties()

    private fun propsWith(vararg pairs: Pair<String, String>): Properties {
        val p = Properties()
        for ((k, v) in pairs) p[k] = v
        return p
    }

    @Test
    fun `every tracked key resolves through the configured precedence`() {
        // env wins over sysprop, sysprop over env_vars, env_vars over local, local over embedded.
        val env = { key: String -> if (key == "BOSS_MODE") "KERNEL" else null }
        val sys = { key: String -> if (key == "SUPABASE_URL") "https://from-sysprop" else null }
        val envVars = propsWith("BOSS_MODE" to "should-be-shadowed")
        val local = propsWith("SUPABASE_URL" to "https://from-local", "GITHUB_TOKEN" to "ghp_secret_value")
        val embedded = propsWith("JXBROWSER_LICENSE_KEY" to "embedded-license")

        val report =
            show.collect(
                keys = listOf("BOSS_MODE", "SUPABASE_URL", "GITHUB_TOKEN", "JXBROWSER_LICENSE_KEY"),
                includeAll = false,
                envProvider = env,
                syspropProvider = sys,
                envVarsProps = envVars,
                localProps = local,
                embeddedProps = embedded,
            )

        assertEquals(emptyList<String>(), report.missing, "all four keys should resolve")
        val bossMode = report.rows.single { it.key == "BOSS_MODE" }
        assertEquals("KERNEL", bossMode.value)
        assertEquals(ConfigSource.ENV_VAR, bossMode.source)
        val supabase = report.rows.single { it.key == "SUPABASE_URL" }
        assertEquals("https://from-sysprop", supabase.value)
        assertEquals(ConfigSource.SYSTEM_PROPERTY, supabase.source)
        val token = report.rows.single { it.key == "GITHUB_TOKEN" }
        assertEquals(ConfigSource.LOCAL_PROPERTIES, token.source)
        val license = report.rows.single { it.key == "JXBROWSER_LICENSE_KEY" }
        assertEquals(ConfigSource.EMBEDDED, license.source)
    }

    @Test
    fun `env_vars is consulted only for BOSS_MODE`() {
        // Anything else written there must NOT win; only the BOSS_MODE key gets a pass-through.
        val envVars =
            propsWith(
                "BOSS_MODE" to "KERNEL",
                "SUPABASE_URL" to "should-be-ignored",
            )
        val report =
            show.collect(
                keys = listOf("BOSS_MODE", "SUPABASE_URL"),
                includeAll = false,
                envProvider = { _ -> null },
                syspropProvider = { _ -> null },
                envVarsProps = envVars,
                localProps = emptyProps(),
                embeddedProps = null,
            )
        val bossMode = report.rows.single { it.key == "BOSS_MODE" }
        assertEquals(ConfigSource.ENV_VARS_FILE, bossMode.source)
        assertEquals(listOf("SUPABASE_URL"), report.missing)
    }

    @Test
    fun `a blank value at a higher tier falls through to the next`() {
        // `KEY=` (exported blank) must not shadow a real value below it.
        val env = { key: String -> if (key == "BOSS_MODE") "" else null }
        val local = propsWith("BOSS_MODE" to "KERNEL")
        val report =
            show.collect(
                keys = listOf("BOSS_MODE"),
                includeAll = false,
                envProvider = env,
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = local,
                embeddedProps = null,
            )
        val bossMode = report.rows.single { it.key == "BOSS_MODE" }
        assertEquals("KERNEL", bossMode.value)
        assertEquals(ConfigSource.LOCAL_PROPERTIES, bossMode.source)
    }

    @Test
    fun `sensitive keys are masked but their source is still reported`() {
        // Operator can confirm a value is set without the secret being in the report.
        val local =
            propsWith(
                "SUPABASE_ANON_KEY" to "eyJhbGciOiJIUzI1NiJ9.long-anon-key",
                "GITHUB_TOKEN" to "ghp_abcdefghij1234567890",
                "jxbrowser.license.key" to "license-1.2.3-very-long-string",
            )
        val report =
            show.collect(
                keys = listOf("SUPABASE_ANON_KEY", "GITHUB_TOKEN", "jxbrowser.license.key"),
                includeAll = false,
                envProvider = { _ -> null },
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = local,
                embeddedProps = null,
            )
        // None of the unique TAIL of each secret should appear in the masked value.
        // The 4-char head is allowed to appear (e.g. "eyJh..."); the tail would let
        // a reader recover the original.
        for (row in report.rows) {
            assertTrue(row.masked, "$row.key should be masked")
            assertFalse(row.value.contains("long-anon-key"), "$row.key leaked anon key tail")
            assertFalse(row.value.contains("abcdefghij"), "$row.key leaked token tail")
            assertFalse(row.value.contains("very-long-string"), "$row.key leaked license tail")
            assertTrue(row.value.contains("(len="), "$row.key should show length")
        }
    }

    @Test
    fun `--all surfaces keys that did not resolve as a missing bucket`() {
        val report =
            show.collect(
                keys = listOf("BOSS_MODE", "SUPABASE_URL"),
                includeAll = false,
                envProvider = { _ -> null },
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = emptyProps(),
                embeddedProps = null,
            )
        assertEquals(emptyList<String>(), report.rows.map { it.key })
        assertEquals(listOf("BOSS_MODE", "SUPABASE_URL"), report.missing)

        val withAll =
            show.collect(
                keys = listOf("BOSS_MODE", "SUPABASE_URL"),
                includeAll = true,
                envProvider = { _ -> null },
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = emptyProps(),
                embeddedProps = null,
            )
        assertEquals(listOf("BOSS_MODE", "SUPABASE_URL"), withAll.rows.map { it.key })
        assertEquals(ConfigSource.NONE, withAll.rows.single { it.key == "BOSS_MODE" }.source)
    }

    @Test
    fun `unknown keys passed by the operator still resolve from any tier`() {
        // The list of tracked keys is what `boss config show` defaults to,
        // but a custom --key may pass a name the host never reads. It
        // still resolves if a tier has it; it just isn't a tracked key.
        val local = propsWith("BOSS_NEW_KNOB" to "new-value")
        val report =
            show.collect(
                keys = listOf("BOSS_NEW_KNOB"),
                includeAll = false,
                envProvider = { _ -> null },
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = local,
                embeddedProps = null,
            )
        assertEquals(listOf("BOSS_NEW_KNOB"), report.rows.map { it.key })
        assertEquals(ConfigSource.LOCAL_PROPERTIES, report.rows.single().source)
    }

    @Test
    fun `lower-priority tier does not overwrite a higher-priority one`() {
        // A common mistake: an operator sees the value in local.properties
        // and assumes that is what is live. We pin that the report uses the
        // resolved tier, not the file location.
        val env = { key: String -> if (key == "BOSS_MODE") "KERNEL" else null }
        val local = propsWith("BOSS_MODE" to "MONOLITH")
        val report =
            show.collect(
                keys = listOf("BOSS_MODE"),
                includeAll = false,
                envProvider = env,
                syspropProvider = { _ -> null },
                envVarsProps = emptyProps(),
                localProps = local,
                embeddedProps = null,
            )
        val row = report.rows.single()
        assertEquals("KERNEL", row.value)
        assertEquals(ConfigSource.ENV_VAR, row.source)
    }

    @Test
    fun `the report lists every tracked key by default`() {
        // Sanity check: nothing tracked is silently dropped from the
        // default output. If a maintainer adds a key and forgets to put
        // it in the list, this test will not catch that - the curated list
        // IS the source of truth here, and any change is reviewed in the
        // same commit.
        assertTrue(show.trackedKeys.contains("BOSS_MODE"))
        assertTrue(show.trackedKeys.contains("SUPABASE_URL"))
        assertTrue(show.trackedKeys.contains("SUPABASE_ANON_KEY"))
        assertTrue(show.trackedKeys.contains("GITHUB_TOKEN"))
        assertTrue(show.trackedKeys.contains("JXBROWSER_LICENSE_KEY"))
        assertTrue(show.trackedKeys.contains("jxbrowser.license.key"))
        assertTrue(show.trackedKeys.contains("BOSS_LOG_LEVEL"))
    }
}
