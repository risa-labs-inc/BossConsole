package ai.rever.boss.plugin.browser

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowBrowserProfilesTest {
    @AfterEach
    fun cleanUp() {
        listOf("w1", "w2", "w3").forEach(WindowBrowserProfiles::unbind)
    }

    @Test
    fun `an unbound window uses the default browser profile`() {
        assertNull(WindowBrowserProfiles.profileIdFor("w1"))
        // No binding means no engine is touched at all.
        assertNull(WindowBrowserProfiles.jxProfileFor("w1"))
    }

    @Test
    fun `windows bound to one profile share it, and closing one leaves the rest`() {
        WindowBrowserProfiles.bind("w1", "work")
        WindowBrowserProfiles.bind("w2", "work")
        WindowBrowserProfiles.bind("w3", "personal")

        assertEquals(setOf("w1", "w2"), WindowBrowserProfiles.windowsFor("work").toSet())
        WindowBrowserProfiles.unbind("w1")
        assertEquals(listOf("w2"), WindowBrowserProfiles.windowsFor("work"))
        assertEquals("personal", WindowBrowserProfiles.profileIdFor("w3"))
    }

    @Test
    fun `window profiles are never named like RPA profiles the orphan sweep deletes`() {
        val name = WindowBrowserProfiles.jxProfileName("work")
        assertEquals("boss-window-work", name)
        assertTrue(!name.startsWith("rpa-"))
    }

    @Test
    fun `an invalid profile id is refused`() {
        assertThrows<IllegalArgumentException> { WindowBrowserProfiles.bind("w1", "../x") }
        assertNull(WindowBrowserProfiles.profileIdFor("w1"))
    }
}
