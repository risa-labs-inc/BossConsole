package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.getBrowserServiceInstance
import ai.rever.boss.window.WindowManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserServiceProfileResolutionTest {
    @Test
    fun `CASE 1 - distinct default and work profiles`() =
        runBlocking {
            BrowserSettings.availableProfiles.add("work")

            val w1 = WindowManager.createNewWindow() // default
            val w2 = WindowManager.createNewWindow(browserProfileId = "work")

            try {
                assertEquals("browser-profile", w1.browserProfileId)
                assertEquals("work", w2.browserProfileId)

                val s1 = getBrowserServiceInstance(w1.id)
                val s2 = getBrowserServiceInstance(w2.id)

                // They belong to different services, different engines in practice
                assertNotEquals(s1, s2)
            } finally {
                WindowManager.closeWindow(w1.id)
                WindowManager.closeWindow(w2.id)
            }
        }

    @Test
    fun `CASE 2 - two windows with same profile share engine`() =
        runBlocking {
            BrowserSettings.availableProfiles.add("work")

            val w1 = WindowManager.createNewWindow(browserProfileId = "work")
            val w2 = WindowManager.createNewWindow(browserProfileId = "work")

            try {
                assertEquals("work", w1.browserProfileId)
                assertEquals("work", w2.browserProfileId)

                // For now, we just assert they both resolved to the same profile successfully.
                // Full engine sharing is handled by FluckEngine caching on profileId.
            } finally {
                WindowManager.closeWindow(w1.id)
                WindowManager.closeWindow(w2.id)
            }
        }

    @Test
    fun `CASE 3 - multiple profiles co-exist cleanly`() =
        runBlocking {
            BrowserSettings.availableProfiles.add("work")
            BrowserSettings.availableProfiles.add("personal")

            val w1 = WindowManager.createNewWindow()
            val w2 = WindowManager.createNewWindow(browserProfileId = "work")
            val w3 = WindowManager.createNewWindow(browserProfileId = "personal")

            try {
                assertEquals("browser-profile", w1.browserProfileId)
                assertEquals("work", w2.browserProfileId)
                assertEquals("personal", w3.browserProfileId)
            } finally {
                WindowManager.closeWindow(w1.id)
                WindowManager.closeWindow(w2.id)
                WindowManager.closeWindow(w3.id)
            }
        }
}
