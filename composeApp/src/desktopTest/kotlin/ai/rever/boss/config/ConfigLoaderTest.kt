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
    private val key = "SOME_KEY"

    private fun props(value: String?) =
        Properties().apply {
            if (value != null) setProperty(key, value)
        }

    private fun resolve(
        env: String? = null,
        sysProp: String? = null,
        local: String? = null,
        envVars: String? = null,
        embedded: String? = null,
        default: String? = null,
    ) = ConfigLoader.resolve(
        key = key,
        defaultValue = default,
        envValue = env,
        sysPropValue = sysProp,
        props(local),
        props(envVars),
        props(embedded),
    )

    @Test
    fun `env wins over all other tiers`() {
        assertEquals(
            "from-env",
            resolve(env = "from-env", sysProp = "x", local = "x", envVars = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `system property wins below env`() {
        assertEquals(
            "from-sysprop",
            resolve(sysProp = "from-sysprop", local = "x", envVars = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `local properties win below system property`() {
        assertEquals(
            "from-local",
            resolve(local = "from-local", envVars = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `env_vars wins below local properties`() {
        assertEquals(
            "from-env-vars",
            resolve(envVars = "from-env-vars", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `embedded build config wins below env_vars`() {
        assertEquals(
            "from-embedded",
            resolve(embedded = "from-embedded", default = "x"),
        )
    }

    @Test
    fun `default is used when no source has the key`() {
        assertEquals("from-default", resolve(default = "from-default"))
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
                    key = "K",
                    defaultValue = null,
                    envValue = blank,
                    sysPropValue = "from-sysprop",
                    local,
                    Properties(),
                ),
                "blank env '$blank' must not shadow the system property",
            )
            assertEquals(
                "from-local",
                ConfigLoader.resolve(
                    key = "K",
                    defaultValue = null,
                    envValue = blank,
                    sysPropValue = blank,
                    local,
                    Properties(),
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
                key = "K",
                defaultValue = null,
                envValue = null,
                sysPropValue = null,
                blankLocal,
                embedded,
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
                key = "K",
                defaultValue = "",
                envValue = null,
                sysPropValue = null,
                Properties(),
                Properties(),
            ),
        )
    }

    @Test
    fun `saved mode parser excludes unrelated configuration and honors the last assignment`() {
        val file =
            kotlin.io.path
                .createTempFile("saved-mode-", ".env")
                .toFile()
        try {
            file.writeText(
                "SUPABASE_URL=https://example.invalid\n" +
                    "SUPABASE_ANON_KEY=unrelated\nBOSS_MODE_EXTRA=keep\n" +
                    "BOSS_MODE=MONOLITH\n  # BOSS_MODE=MONOLITH\nBOSS_MODE=KERNEL\n",
            )
            val parsed = ConfigLoader.parseEnvVars(file)
            assertEquals(setOf("BOSS_MODE"), parsed.stringPropertyNames())
            assertEquals("KERNEL", parsed.getProperty("BOSS_MODE"))
            assertEquals(
                "embedded",
                ConfigLoader.resolve(
                    "SUPABASE_URL",
                    null,
                    null,
                    null,
                    parsed,
                    Properties().apply { setProperty("SUPABASE_URL", "embedded") },
                ),
            )
        } finally {
            file.delete()
        }
    }
}
