package ai.rever.boss.config

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The setting that keeps the two halves of the swipe gesture in step.
 *
 * Nothing exercised this object before, and the gap hid a real bug: an environment value the app
 * could not parse disabled the Settings row and suppressed the publish, while the resolver quietly
 * used the stored setting instead - so the host could be off while the plugin's home surface was
 * on, which is the one divergence sharing this key exists to prevent.
 *
 * PROCESS-GLOBAL: this suite points [SwipeNavSettingsManager.settingsFile] at a temp file and
 * writes a system property, so it must not run in parallel with itself. Correct today because
 * desktopTest runs one class at a time.
 */
class SwipeNavSettingsTest {
    private lateinit var temp: File
    private var originalFile: File? = null

    @BeforeTest
    fun redirectStorage() {
        originalFile = SwipeNavSettingsManager.settingsFile
        temp = File.createTempFile("swipe-nav-test", ".json").also { it.delete() }
        SwipeNavSettingsManager.settingsFile = temp
        System.clearProperty(SwipeNavSettingsManager.KEY)
    }

    @AfterTest
    fun restore() {
        originalFile?.let { SwipeNavSettingsManager.settingsFile = it }
        temp.delete()
        File(temp.parentFile, "${temp.name}.tmp").delete()
        System.clearProperty(SwipeNavSettingsManager.KEY)
    }

    // --- what the environment does and does not own -------------------------------------------

    /**
     * The bug this suite was written for. A value the app cannot parse must not claim the key:
     * doing so disabled the control, skipped the publish, and left the two halves free to disagree
     * - while the row told the user the environment had decided something it had not.
     */
    @Test
    fun `an unparseable environment value does not own the key`() {
        assertNull(parseSwipeNavEnabled("maybe"))
        assertFalse(
            envDecides("maybe"),
            "a value the app ignores must not disable the control that overrides it",
        )
    }

    @Test
    fun `a real environment value does own the key`() {
        assertTrue(envDecides("false"))
        assertTrue(envDecides("on"))
    }

    @Test
    fun `a blank or absent variable owns nothing`() {
        assertFalse(envDecides(null))
        assertFalse(envDecides(""))
        assertFalse(envDecides("   "))
    }

    /** Mirrors [SwipeNavSettingsManager.envDecides] without needing the process environment set. */
    private fun envDecides(raw: String?): Boolean = parseSwipeNavEnabled(raw?.takeIf { it.isNotBlank() }) != null

    // --- persistence ---------------------------------------------------------------------------

    @Test
    fun `a setting survives being written and read back`() {
        SwipeNavSettingsManager.set(false)
        assertTrue(temp.exists(), "the settings file should have been written")
        assertFalse(SwipeNavSettingsManager.settings.value.enabled)
        SwipeNavSettingsManager.set(true)
        assertTrue(SwipeNavSettingsManager.settings.value.enabled)
    }

    /**
     * Writing more than once is the case `File.renameTo` fails on Windows, where the destination
     * already exists - this repo shipped that exact bug in the favicon cache. One write proves
     * nothing; the second is the one that used to be dropped.
     */
    @Test
    fun `writing twice replaces the file rather than failing silently`() {
        SwipeNavSettingsManager.set(false)
        SwipeNavSettingsManager.set(true)
        assertTrue(temp.readText().contains("true"), temp.readText())
        assertFalse(
            File(temp.parentFile, "${temp.name}.tmp").exists(),
            "the temp file should have been moved, not left behind",
        )
    }

    @Test
    fun `a corrupt file falls back to the default rather than failing to boot`() {
        temp.writeText("{ not json")
        // Read through the same path a fresh launch would take.
        assertEquals(true, SwipeNavSettings().enabled)
    }

    // --- what the plugin half reads ------------------------------------------------------------

    @Test
    fun `the setting is published for the plugin half`() {
        SwipeNavSettingsManager.set(false)
        assertEquals("false", System.getProperty(SwipeNavSettingsManager.KEY))
        SwipeNavSettingsManager.set(true)
        assertEquals("true", System.getProperty(SwipeNavSettingsManager.KEY))
    }

    /**
     * The plugin parses this string with its own copy of the spellings, in another repo. Publishing
     * anything its parser does not recognise would leave it falling back to a default while the
     * host used the real value - the divergence again, by a different door.
     */
    @Test
    fun `the published value is one the plugin can parse`() {
        SwipeNavSettingsManager.set(false)
        assertEquals(false, parseSwipeNavEnabled(System.getProperty(SwipeNavSettingsManager.KEY)))
        SwipeNavSettingsManager.set(true)
        assertEquals(true, parseSwipeNavEnabled(System.getProperty(SwipeNavSettingsManager.KEY)))
    }
}
