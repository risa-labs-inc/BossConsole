package ai.rever.boss.plugin.workspace

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the constructors, copy bridges, and serialization descriptors in the published API.
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
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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

    @Test
    fun `old copy descriptor preserves host pinned count`() {
        val panel = PanelConfig("old", emptyList(), pinnedCount = 2)
        val copy = PanelConfig::class.java.getMethod("copy", String::class.java, List::class.java)
        val result = copy.invoke(panel, "new", emptyList<TabConfig>()) as PanelConfig
        assertEquals("new", result.id)
        assertEquals(2, result.pinnedCount)
        assertEquals(1, panel.copy(pinnedCount = 1).pinnedCount)
        assertEquals(2, panel.copy(id = "source-copy").pinnedCount)
    }

    @Test
    fun `old default copy bridge retains unspecified properties`() {
        val panel = PanelConfig("old", emptyList(), pinnedCount = 2)
        val bridge =
            PanelConfig::class.java.getMethod(
                "copy\$default",
                PanelConfig::class.java,
                String::class.java,
                List::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
        val result = bridge.invoke(null, panel, null, null, 3, null) as PanelConfig
        assertEquals(panel, result)
        val renamed = bridge.invoke(null, panel, "renamed", null, 2, null) as PanelConfig
        assertEquals("renamed", renamed.id)
        assertEquals(2, renamed.pinnedCount)
    }

    @Test
    fun `old serialization constructor defaults pinned count and enforces required fields`() {
        val constructor =
            PanelConfig::class.java.constructors.single {
                it.parameterCount == 4 && it.parameterTypes.first() == Int::class.javaPrimitiveType
            }
        val panel = constructor.newInstance(3, "restored", emptyList<TabConfig>(), null) as PanelConfig
        assertEquals(PanelConfig("restored", emptyList(), 0), panel)
        val failure =
            assertFailsWith<InvocationTargetException> {
                constructor.newInstance(2, null, emptyList<TabConfig>(), null)
            }
        assertIs<MissingFieldException>(failure.cause)
    }

    @Test
    fun `host serialization still round trips pinned count and reads old json`() {
        val panel = PanelConfig("panel", emptyList(), 2)
        assertEquals(panel, Json.decodeFromString<PanelConfig>(Json.encodeToString(panel)))
        assertEquals(0, Json.decodeFromString<PanelConfig>("""{"id":"old","tabs":[]}""").pinnedCount)
    }
}
