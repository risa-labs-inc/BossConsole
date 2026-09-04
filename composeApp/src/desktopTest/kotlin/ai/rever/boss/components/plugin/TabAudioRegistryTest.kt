package ai.rever.boss.components.plugin

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract the tab-bar speaker glyph rests on (issue #308): last-writer-wins per
 * tab id, ownership-checked unregistration, and update reaching only the registered handler.
 * The registry is a global singleton, so these tests use ids unique to this file and clear
 * in tearDown - the same discipline PanelComponentStoreResetTest applies.
 */
class TabAudioRegistryTest {
    private val received = mutableListOf<Pair<String, Boolean>>()

    /** A handler that records which tab id it serves and what it was told. */
    private fun handler(id: String): (Boolean) -> Unit = { playing -> received += id to playing }

    @AfterTest
    fun tearDown() {
        TabAudioRegistry.clear()
    }

    @Test
    fun `update reaches the registered handler with the playback state`() {
        TabAudioRegistry.register("audio-registry-basic", handler("audio-registry-basic"))

        TabAudioRegistry.update("audio-registry-basic", true)
        TabAudioRegistry.update("audio-registry-basic", false)

        assertEquals(
            listOf("audio-registry-basic" to true, "audio-registry-basic" to false),
            received,
        )
    }

    @Test
    fun `re-registering a tab id replaces the handler`() {
        val first = handler("first")
        val second = handler("second")
        TabAudioRegistry.register("audio-registry-replace", first)
        TabAudioRegistry.register("audio-registry-replace", second)

        TabAudioRegistry.update("audio-registry-replace", true)

        // The destination panel's handler after adoptTab must be the one that fires, or a
        // moved tab's glyph updates go to the panel that no longer shows it.
        assertEquals(listOf("second" to true), received)
    }

    @Test
    fun `unregister is ownership-checked, so a stale handler cannot wipe a newer one`() {
        val source = handler("source")
        val destination = handler("destination")
        TabAudioRegistry.register("audio-registry-ownership", source)
        TabAudioRegistry.register("audio-registry-ownership", destination)

        // The move sequence: destination registers, then the source panel's close runs.
        TabAudioRegistry.unregister("audio-registry-ownership", source)
        TabAudioRegistry.update("audio-registry-ownership", true)
        assertEquals(listOf("destination" to true), received, "the source's close must not disturb the adoption")

        TabAudioRegistry.unregister("audio-registry-ownership", destination)
        TabAudioRegistry.update("audio-registry-ownership", false)
        assertEquals(listOf("destination" to true), received, "and only the owner's unregister removes the entry")
    }

    @Test
    fun `an unknown tab id is a silent no-op`() {
        TabAudioRegistry.update("never-registered", true)
        assertTrue(received.isEmpty(), "no handler, no delivery, no throw")
    }
}