package ai.rever.boss.plugin.workspace

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the constructors already-built plugins link against.
 *
 * This module is published to Maven Central and plugins compile against it, so its constructors
 * are ABI. A Kotlin default parameter does not preserve the previous shape: adding
 * `pinnedCount` to [PanelConfig] rewrote the primary constructor from `(String, List)` to
 * `(String, List, int)` plus a synthetic `$default`, and every plugin built against the old jar
 * went on calling the two-argument form. `BinaryCompatibilityValidator` rejects the whole plugin
 * when one member is missing and the host then disables it, so this cost the bookmarks plugin
 * entirely on a machine that had it - installed, enabled, and silently not running.
 *
 * The sibling `WorkspaceStableFieldTest` guards the `$stable` field for the same reason. Both are
 * on the publish path deliberately: co-location alone does not put a test there, and the failure
 * they catch is invisible until a plugin fails to load on a user's machine.
 *
 * Reflection rather than a compile-time call, because Kotlin source would resolve
 * `PanelConfig(id, tabs)` through the default and pass whatever the current shape is. Only the
 * emitted bytecode answers the question a plugin's linker asks.
 */
class PanelConfigBinaryCompatTest {
    @Test
    fun `the two-argument constructor plugins were built against still exists`() {
        val ctor =
            PanelConfig::class.java.constructors.firstOrNull { c ->
                c.parameterTypes.size == 2 &&
                    c.parameterTypes[0] == String::class.java &&
                    c.parameterTypes[1] == List::class.java
            }
        assertNotNull(
            ctor,
            "PanelConfig(String, List) is gone. Every plugin built against an older " +
                "plugin-workspace-types calls it, and losing it disables those plugins outright. " +
                "Restore it with @JvmOverloads on the primary constructor.",
        )
    }

    @Test
    fun `the two-argument constructor defaults pinnedCount to zero`() {
        // The overload has to mean what the old one meant. A workspace saved before pinning
        // existed, and any plugin still building panels the old way, must come back unpinned
        // rather than with an arbitrary leading run of tabs marked pinned.
        val ctor =
            PanelConfig::class.java.constructors.first { c ->
                c.parameterTypes.size == 2 && c.parameterTypes[0] == String::class.java
            }
        val panel = ctor.newInstance("panel-1", emptyList<TabConfig>()) as PanelConfig

        assertTrue(panel.pinnedCount == 0, "expected 0, got ${panel.pinnedCount}")
    }

    @Test
    fun `the full constructor still carries the field`() {
        // Guards the other direction: an over-eager "restore compatibility" fix that dropped the
        // parameter would compile, pass the test above, and lose pinning on every save.
        val panel = PanelConfig(id = "panel-1", tabs = emptyList(), pinnedCount = 3)
        assertTrue(panel.pinnedCount == 3)
    }
}
