package ai.rever.boss.components.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [findRelocatedPluginJar] — the fallback used by
 * [DynamicPluginManager.loadPersistedPlugins] when a persisted jar path went
 * stale because the background system-plugin updater replaced the file under
 * a new versioned name mid-startup.
 */
class FindRelocatedPluginJarTest {
    @Test
    fun `finds the jar with the matching pluginId`() {
        withTempDir { dir ->
            PluginJarTestFixtures.writeJar(dir, "other-plugin-9.9.9.jar", "com.example.other", "9.9.9")
            val target = PluginJarTestFixtures.writeJar(dir, "my-plugin-1.8.7.jar", "com.example.mine", "1.8.7")

            assertEquals(target, findRelocatedPluginJar(dir, "com.example.mine"))
        }
    }

    @Test
    fun `prefers the highest manifest version when several match`() {
        withTempDir { dir ->
            PluginJarTestFixtures.writeJar(dir, "my-plugin-1.8.6.jar", "com.example.mine", "1.8.6")
            val newest = PluginJarTestFixtures.writeJar(dir, "my-plugin-1.8.10.jar", "com.example.mine", "1.8.10")
            PluginJarTestFixtures.writeJar(dir, "my-plugin-1.8.9.jar", "com.example.mine", "1.8.9")

            // 1.8.10 > 1.8.9 numerically though not lexicographically.
            assertEquals(newest, findRelocatedPluginJar(dir, "com.example.mine"))
        }
    }

    @Test
    fun `ignores jars without a readable manifest`() {
        withTempDir { dir ->
            File(dir, "not-a-plugin.jar").writeText("garbage")
            val target = PluginJarTestFixtures.writeJar(dir, "my-plugin-1.0.0.jar", "com.example.mine", "1.0.0")

            assertEquals(target, findRelocatedPluginJar(dir, "com.example.mine"))
        }
    }

    @Test
    fun `returns null when nothing matches`() {
        withTempDir { dir ->
            PluginJarTestFixtures.writeJar(dir, "other-plugin-1.0.0.jar", "com.example.other", "1.0.0")

            assertNull(findRelocatedPluginJar(dir, "com.example.mine"))
            assertNull(findRelocatedPluginJar(null, "com.example.mine"))
            assertNull(findRelocatedPluginJar(File(dir, "missing-subdir"), "com.example.mine"))
        }
    }
}
