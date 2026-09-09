package ai.rever.boss.components.plugin

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabAudioRegistryTest {
    private val tasks = mutableListOf<() -> Unit>()
    private val sources = mutableListOf<TabAudioSource>()

    private fun source(): TabAudioSource = TabAudioSource { tasks.add(it) }.also { sources.add(it) }

    private fun drain() {
        val pending = tasks.toList()
        tasks.clear()
        pending.forEach { it() }
    }

    @AfterTest
    fun cleanup() {
        sources.forEach { it.close() }
        drain()
    }

    @Test
    fun `playback before ownership is replayed on binding`() {
        val browser = source()
        browser.update(true)
        drain()
        assertFalse(TabAudioRegistry.isPlaying("late"))
        browser.bind("late")
        drain()
        assertTrue(TabAudioRegistry.isPlaying("late"))
    }

    @Test
    fun `native callbacks publish only through the UI dispatcher`() {
        val browser = source()
        browser.bind("thread")
        drain()
        Thread { browser.update(true) }.apply {
            start()
            join()
        }
        assertFalse(TabAudioRegistry.isPlaying("thread"))
        drain()
        assertTrue(TabAudioRegistry.isPlaying("thread"))
    }

    @Test
    fun `late delivery of start cannot undo the latest stop`() {
        val browser = source()
        browser.bind("ordering")
        drain()
        browser.update(true)
        browser.update(false)
        tasks.reverse()
        drain()
        assertFalse(TabAudioRegistry.isPlaying("ordering"))
    }

    @Test
    fun `initial native state is replayed without an event`() {
        val browser = source()
        browser.seed { true }
        browser.bind("seed")
        drain()
        assertTrue(TabAudioRegistry.isPlaying("seed"))
    }

    @Test
    fun `event during initial native read wins over stale result`() {
        val browser = source()
        browser.bind("seed-race")
        browser.seed {
            browser.update(false)
            true
        }
        drain()
        assertFalse(TabAudioRegistry.isPlaying("seed-race"))
    }

    @Test
    fun `replacement browser is immune to old events and disposal`() {
        val first = source()
        first.bind("replacement")
        first.update(true)
        drain()
        val second = source()
        second.bind("replacement")
        drain()
        assertFalse(TabAudioRegistry.isPlaying("replacement"), "a silent replacement clears old playback")
        second.update(true)
        drain()
        first.update(false)
        first.close()
        drain()
        assertTrue(TabAudioRegistry.isPlaying("replacement"))
        second.close()
        drain()
        assertFalse(TabAudioRegistry.isPlaying("replacement"))
    }

    @Test
    fun `same tab identity keeps playing when its panel changes`() {
        val browser = source()
        browser.bind("moving")
        browser.update(true)
        drain()
        browser.bind("moving")
        drain()
        assertTrue(TabAudioRegistry.isPlaying("moving"))
    }

    @Test
    fun `changing owner releases the previous tab`() {
        val browser = source()
        browser.bind("old")
        browser.update(true)
        drain()
        browser.bind("new")
        drain()
        assertFalse(TabAudioRegistry.isPlaying("old"))
        assertTrue(TabAudioRegistry.isPlaying("new"))
    }

    @Test
    fun `close invalidates pending binding and late callbacks`() {
        val browser = source()
        browser.bind("closed")
        browser.update(true)
        browser.close()
        browser.update(true)
        browser.bind("resurrected")
        browser.seed { true }
        tasks.reverse()
        drain()
        assertFalse(TabAudioRegistry.isPlaying("closed"))
        assertFalse(TabAudioRegistry.isPlaying("resurrected"))
    }

    @Test
    fun `unknown and absent tab identities are silent`() {
        assertFalse(TabAudioRegistry.isPlaying("never-registered"))
        assertFalse(TabAudioRegistry.isPlaying(null))
    }
}
