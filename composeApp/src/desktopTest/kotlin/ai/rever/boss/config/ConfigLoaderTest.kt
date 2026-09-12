package ai.rever.boss.config

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the config source precedence contract:
 * env > system property > local.properties > embedded build config > default.
 *
 * The embedded tier is how packaged apps receive the JxBrowser license and
 * Supabase settings (baked in by the generateEmbeddedConfig Gradle task), so
 * a silent precedence regression would break production credential delivery.
 */
class ConfigLoaderTest {
    private val key = "BOSS_MODE"

    private fun props(value: String?) =
        Properties().apply {
            if (value != null) setProperty(key, value)
        }

    private fun resolve(
        env: String? = null,
        sysProp: String? = null,
        envVars: String? = null,
        local: String? = null,
        embedded: String? = null,
        default: String? = null,
    ) = ConfigLoader.resolve(
        key = key,
        defaultValue = default,
        envValue = env,
        sysPropValue = sysProp,
        envVarsProps = props(envVars),
        localProps = props(local),
        embeddedProps = props(embedded),
    )

    @Test
    fun `env wins over all other tiers`() {
        assertEquals(
            "FROM-ENV",
            resolve(env = "FROM-ENV", sysProp = "x", envVars = "x", local = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `system property wins below env`() {
        assertEquals(
            "FROM-SYSPROP",
            resolve(sysProp = "FROM-SYSPROP", envVars = "x", local = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `envVars properties win below system property`() {
        assertEquals(
            "FROM-ENVVARS",
            resolve(envVars = "FROM-ENVVARS", local = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `local properties win below envVars properties`() {
        assertEquals(
            "FROM-LOCAL",
            resolve(local = "FROM-LOCAL", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `embedded build config wins below local properties`() {
        assertEquals(
            "FROM-EMBEDDED",
            resolve(embedded = "FROM-EMBEDDED", default = "x"),
        )
    }

    @Test
    fun `default is used when no source has the key`() {
        assertEquals("FROM-DEFAULT", resolve(default = "FROM-DEFAULT"))
    }

    @Test
    fun `null when no source has the key and no default given`() {
        assertNull(resolve())
    }

    @Test
    fun `getConfig picks up a live system property and falls back to default`() {
        val liveKey = "BOSS_CONFIG_LOADER_TEST_${System.nanoTime()}"
        assertEquals("fallback", ConfigLoader.getConfig(liveKey, "fallback"))
        System.setProperty(liveKey, "live-value")
        try {
            assertEquals("live-value", ConfigLoader.getConfig(liveKey))
        } finally {
            System.clearProperty(liveKey)
        }
    }

    /**
     * A blank value at any tier must fall through, not shadow the tiers below it.
     *
     * `export BOSS_RENDERING_MODE=` yields an empty string, which is non-null and used to win the
     * chain. Testable here and nowhere else: a JVM cannot set its own environment variables, so the
     * pure resolver taking `envValue` as a parameter is the only place this branch is reachable -
     * which is why the fix belongs here rather than at the call sites.
     */
    @Test
    fun `a blank value falls through to the next source`() {
        val local = Properties().apply { setProperty("K", "from-local") }
        for (blank in listOf("", "   ", "\t")) {
            assertEquals(
                "from-sysprop",
                ConfigLoader.resolve(
                    "K",
                    null,
                    envValue = blank,
                    sysPropValue = "from-sysprop",
                    envVarsProps = Properties(),
                    localProps = local,
                    embeddedProps = Properties(),
                ),
                "blank env '$blank' must not shadow the system property",
            )
            assertEquals(
                "from-local",
                ConfigLoader.resolve(
                    "K",
                    null,
                    envValue = blank,
                    sysPropValue = blank,
                    envVarsProps = Properties(),
                    localProps = local,
                    embeddedProps = Properties(),
                ),
                "blank env and sysprop '$blank' must both fall through",
            )
        }
        // A blank properties entry is the same mistake with the same consequence.
        val blankLocal = Properties().apply { setProperty("K", "") }
        val embedded = Properties().apply { setProperty("K", "from-embedded") }
        assertEquals(
            "from-embedded",
            ConfigLoader.resolve(
                "K",
                null,
                envValue = null,
                sysPropValue = null,
                envVarsProps = Properties(),
                localProps = blankLocal,
                embeddedProps = embedded,
            ),
        )
    }

    @Test
    fun `an explicit blank default is still returned`() {
        // Not blank-filtered, unlike the sources: a caller passing "" as its default has said so,
        // where an exported variable merely happens to be empty.
        assertEquals(
            "",
            ConfigLoader.resolve(
                "K",
                defaultValue = "",
                envValue = null,
                sysPropValue = null,
                envVarsProps = Properties(),
                localProps = Properties(),
                embeddedProps = Properties(),
            ),
        )
    }

    @Test
    fun `env vars cannot override authentication endpoints`() {
        assertEquals(
            "embedded",
            ConfigLoader.resolve(
                key = "SUPABASE_URL",
                defaultValue = null,
                envValue = null,
                sysPropValue = null,
                envVarsProps = Properties().apply { setProperty("SUPABASE_URL", "untrusted") },
                localProps = Properties(),
                embeddedProps = Properties().apply { setProperty("SUPABASE_URL", "embedded") },
            ),
        )
    }

    @Test
    fun `kernel mode is canonical for lowercase env and blank env with saved mode`() {
        for (env in listOf(" kernel ", "")) {
            assertEquals(
                "KERNEL",
                ConfigLoader.resolve(
                    key = "BOSS_MODE",
                    defaultValue = null,
                    envValue = env,
                    sysPropValue = null,
                    envVarsProps = Properties().apply { setProperty("BOSS_MODE", "kernel") },
                    localProps = Properties(),
                    embeddedProps = Properties(),
                ),
            )
        }
    }
}
