package ai.rever.boss.crash

import ai.rever.boss.components.plugin.PluginLoaderDelegateSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the branch that decides how a crash-disable is written down.
 *
 * This is the fix that was found by hand rather than by reasoning:
 * `PluginPersistence.setPluginEnabled` updates an existing `installed.json` entry
 * and does *nothing at all* when there is none, logging only at debug. A jar
 * dropped into the plugins directory has no entry - the directory scan installs it
 * without writing one - so a plugin that crashed on load was disabled, produced a
 * crash dialog, and came back at the next launch, which is the exact loop
 * persisting exists to break. A live run confirmed the call ran and the file was
 * unchanged.
 *
 * The seams are parameters with production defaults rather than a filesystem
 * override, because `PluginPersistence` resolves its file from
 * `PluginStoreSetup.getPluginDir()` and a test that let it do so would rewrite the
 * developer's real `installed.json`.
 */
class PersistCrashDisableTest {
    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val JAR = "/Users/someone/.boss/plugins/probe-1.0.0.jar"
    }

    private val enabledUpdates = mutableListOf<Pair<String, Boolean>>()
    private val added = mutableListOf<Triple<String, String, Boolean>>()

    private fun persist(
        installed: Boolean,
        jarPath: String?,
    ) = PluginLoaderDelegateSetup.persistCrashDisable(
        pluginId = PLUGIN,
        isInstalled = { installed },
        jarPathOf = { jarPath },
        setEnabled = { id, enabled -> enabledUpdates.add(id to enabled) },
        addInstalled = { id, jar, enabled -> added.add(Triple(id, jar, enabled)) },
    )

    @Test
    fun `an installed plugin has its existing entry flipped`() {
        assertTrue(persist(installed = true, jarPath = JAR))

        assertEquals(listOf(PLUGIN to false), enabledUpdates)
        assertEquals(emptyList(), added, "an existing entry must be updated, not replaced")
    }

    @Test
    fun `a sideloaded plugin gets an entry written for it`() {
        // The branch that was missing. Without it the call was a silent no-op and
        // the plugin returned, enabled, at the next launch.
        assertTrue(persist(installed = false, jarPath = JAR))

        assertEquals(listOf(Triple(PLUGIN, JAR, false)), added)
        assertEquals(emptyList(), enabledUpdates)
    }

    @Test
    fun `a plugin with no entry and no known jar reports that it was not persisted`() {
        // Nothing can be written, and saying so is what lets recovery correct the
        // "is being disabled" notice instead of leaving the user to find out at the
        // next launch.
        assertFalse(persist(installed = false, jarPath = null))

        assertEquals(emptyList(), added)
        assertEquals(emptyList(), enabledUpdates)
    }

    @Test
    fun `restart-limit disable records attempts only through the automatic path`() {
        var recorded: Pair<String, Int>? = null

        val persisted =
            PluginLoaderDelegateSetup.persistRestartLimitDisable(
                pluginId = PLUGIN,
                restartAttempts = 3,
                ensureInstalled = { true },
                recordFailure = { id, attempts ->
                    recorded = id to attempts
                    true
                },
            )

        assertTrue(persisted)
        assertEquals(PLUGIN to 3, recorded)
        assertEquals(emptyList(), enabledUpdates, "manual-disable persistence is not used here")
    }

    @Test
    fun `unknown plugin cannot gain a restart failure record`() {
        assertFalse(
            PluginLoaderDelegateSetup.persistRestartLimitDisable(
                pluginId = PLUGIN,
                restartAttempts = 3,
                ensureInstalled = { false },
                recordFailure = { _, _ -> error("no installed entry to update") },
            ),
        )
    }
}
