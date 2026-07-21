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

    private fun props(value: String?) = Properties().apply {
        if (value != null) setProperty(key, value)
    }

    private fun resolve(
        env: String? = null,
        sysProp: String? = null,
        local: String? = null,
        embedded: String? = null,
        default: String? = null,
    ) = ConfigLoader.resolve(
        key = key,
        defaultValue = default,
        envValue = env,
        sysPropValue = sysProp,
        localProps = props(local),
        embeddedProps = props(embedded),
    )

    @Test
    fun `env wins over all other tiers`() {
        assertEquals(
            "from-env",
            resolve(env = "from-env", sysProp = "x", local = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `system property wins below env`() {
        assertEquals(
            "from-sysprop",
            resolve(sysProp = "from-sysprop", local = "x", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `local properties win below system property`() {
        assertEquals(
            "from-local",
            resolve(local = "from-local", embedded = "x", default = "x"),
        )
    }

    @Test
    fun `embedded build config wins below local properties`() {
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
}
