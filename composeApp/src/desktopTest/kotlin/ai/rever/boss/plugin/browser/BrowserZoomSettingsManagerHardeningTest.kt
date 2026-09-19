package ai.rever.boss.plugin.browser

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the BrowserZoomSettingsManager hardening (#925, the
 * manager the settings-migration cluster left out):
 * - a corrupt settings file is renamed aside (self-heal) instead of silently
 *   re-failing every launch at the live path;
 * - the rename-aside helper is a pure file operation, so it is pinned on a
 *   caller-supplied directory without touching the shared BossDirectories root.
 */
class BrowserZoomSettingsManagerHardeningTest {
    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = File.createTempFile("zoom-hardening", null)
        tmp.delete()
        tmp.mkdir()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `a corrupt settings file is renamed aside and no longer sits at the live path`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("{ this is not valid json")

        moveCorruptSettingsAside(live) { 1726000000000L }

        // The corrupt bytes no longer sit at the live name; an aside copy survives for diagnosis.
        assertFalse(live.exists(), "the corrupt file must not remain at the live path")
        val aside = File(tmp, "browser-zoom-settings.json.corrupt.1726000000000")
        assertTrue(aside.exists(), "the aside copy must exist at the documented name")
        assertEquals(true, aside.readText().startsWith("{ this"))
    }

    @Test
    fun `an absent file is a no-op - no aside is created`() {
        val absent = File(tmp, "never-existed.json")
        moveCorruptSettingsAside(absent) { 1726000000000L }
        assertEquals(false, File(tmp, "never-existed.json.corrupt.1726000000000").exists())
        assertEquals(0, tmp.listFiles()!!.size, "the directory must stay empty - no aside for an absent file")
    }

    @Test
    fun `repeated corrupt-decode cycles accumulate timestamped asides instead of clobbering`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("garbage one")
        moveCorruptSettingsAside(live) { 1L }
        live.writeText("garbage two")
        moveCorruptSettingsAside(live) { 2L }

        val asides = tmp.listFiles()!!.filter { it.name.contains(".corrupt.") }
        assertEquals(2, asides.size, "two distinct corrupt cycles must keep two diagnosable asides")
        assertEquals(setOf("garbage one", "garbage two"), asides.map { it.readText() }.toSet())
    }
}
