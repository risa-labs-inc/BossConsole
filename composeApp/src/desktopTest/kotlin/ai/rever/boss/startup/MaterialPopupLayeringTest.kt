package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialPopupLayeringTest {
    @Test
    fun `macOS defaults Compose popup layers to native windows`() {
        val writes = mutableListOf<Pair<String, String>>()

        val result =
            configureMaterialPopupLayering(
                osName = "Mac OS X",
                currentLayerType = null,
                setProperty = { key, value -> writes += key to value },
            )

        assertEquals(MaterialPopupLayeringResult.ENABLED, result)
        assertEquals(listOf(COMPOSE_LAYER_TYPE_PROPERTY to COMPOSE_WINDOW_LAYER_TYPE), writes)
        // LayerType is internal in Kotlin but public in the pinned desktop bytecode. Exercise the
        // actual parser reflectively so a Compose upgrade cannot silently turn WINDOW into the
        // same-canvas fallback while this test keeps passing.
        val layerTypeClass = Class.forName("androidx.compose.ui.LayerType")
        val companion = layerTypeClass.getField("Companion").get(null)
        val parsed =
            companion.javaClass
                .getMethod("parse", String::class.java)
                .invoke(companion, writes.single().second)
        assertEquals("OnWindow", parsed.toString())
    }

    @Test
    fun `an explicit layer override remains an operator escape hatch`() {
        var wrote = false

        val result =
            configureMaterialPopupLayering(
                osName = "macOS",
                currentLayerType = "COMPONENT",
                setProperty = { _, _ -> wrote = true },
            )

        assertEquals(MaterialPopupLayeringResult.EXPLICIT_OVERRIDE, result)
        assertTrue(!wrote)
    }

    @Test
    fun `already enabled native layers are not rewritten`() {
        var wrote = false

        val result =
            configureMaterialPopupLayering(
                osName = "Mac OS X",
                currentLayerType = " window ",
                setProperty = { _, _ -> wrote = true },
            )

        assertEquals(MaterialPopupLayeringResult.ALREADY_ENABLED, result)
        assertTrue(!wrote)
    }

    @Test
    fun `other platforms keep their existing popup layer strategy`() {
        var wrote = false

        val result =
            configureMaterialPopupLayering(
                osName = "Windows 11",
                currentLayerType = null,
                setProperty = { _, _ -> wrote = true },
            )

        assertEquals(MaterialPopupLayeringResult.UNCHANGED_PLATFORM, result)
        assertTrue(!wrote)
    }
}
