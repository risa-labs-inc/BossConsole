package ai.rever.boss.config

import ai.rever.boss.utils.VersionConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserEngineSettingsManagerTest {
    @Test
    fun `only nonblank developer properties override the bundled engine`() {
        val key = "boss.browser.engine.version"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertEquals(VersionConstants.JXBROWSER_VERSION, BrowserEngineSettingsManager.effectiveVersion)
            for (blank in listOf("", "   ")) {
                System.setProperty(key, blank)
                assertEquals(VersionConstants.JXBROWSER_VERSION, BrowserEngineSettingsManager.effectiveVersion)
            }
            System.setProperty(key, VersionConstants.JXBROWSER_VERSION)
            assertEquals(VersionConstants.JXBROWSER_VERSION, BrowserEngineSettingsManager.effectiveVersion)
            System.setProperty(key, " 9.3.0 ")
            assertEquals("9.3.0", BrowserEngineSettingsManager.effectiveVersion)
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
