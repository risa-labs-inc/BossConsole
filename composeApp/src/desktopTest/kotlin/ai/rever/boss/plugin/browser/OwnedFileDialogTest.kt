package ai.rever.boss.plugin.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The directory-mode discipline every AWT file panel in the app now shares.
 *
 * `apple.awt.fileDialogForDirectories` is a PROCESS-WIDE system property read by the native peer
 * when a panel is created, and a modal panel runs a nested event loop on the EDT that keeps
 * dispatching other `invokeLater` blocks. So one panel really can be created inside another's loop,
 * and a panel that does not state its own mode inherits whatever the outer one left. That is not
 * hypothetical: `FilePickerProviderFactory` did not state its mode, so a plugin's file pick opened
 * while the browser's folder panel was up came up as a directory chooser.
 *
 * Showing a panel needs a display. The property discipline does not, so it is extracted and tested
 * here; the owner-window selection and the panel itself are not covered and cannot be from a
 * headless test.
 */
class OwnedFileDialogTest {
    private var original: String? = null

    @BeforeTest
    fun capture() {
        original = System.getProperty(MAC_DIRECTORY_MODE)
        System.clearProperty(MAC_DIRECTORY_MODE)
    }

    @AfterTest
    fun restore() {
        val value = original
        if (value == null) {
            System.clearProperty(MAC_DIRECTORY_MODE)
        } else {
            System.setProperty(MAC_DIRECTORY_MODE, value)
        }
    }

    @Test
    fun `the body sees the mode it asked for`() {
        withDirectoryMode(directories = true) {
            assertEquals("true", System.getProperty(MAC_DIRECTORY_MODE))
        }
        withDirectoryMode(directories = false) {
            assertEquals("false", System.getProperty(MAC_DIRECTORY_MODE))
        }
    }

    @Test
    fun `an absent property is cleared afterwards, not written back as false`() {
        // Writing "false" back would leave the property SET for every later panel that reads the
        // ambient value, which is a different state from the one this process started in.
        withDirectoryMode(directories = true) { }

        assertNull(
            System.getProperty(MAC_DIRECTORY_MODE),
            "an absent property must stay absent",
        )
    }

    @Test
    fun `a property that was present is restored verbatim`() {
        System.setProperty(MAC_DIRECTORY_MODE, "true")

        withDirectoryMode(directories = false) {
            assertEquals("false", System.getProperty(MAC_DIRECTORY_MODE))
        }

        assertEquals("true", System.getProperty(MAC_DIRECTORY_MODE))
    }

    @Test
    fun `nesting unwinds in reverse, which is the case the EDT loop creates`() {
        // A folder panel is up (true). A plugin's file pick is dispatched inside its nested loop
        // and must see false, then hand the folder panel its own mode back.
        withDirectoryMode(directories = true) {
            assertEquals("true", System.getProperty(MAC_DIRECTORY_MODE))
            withDirectoryMode(directories = false) {
                assertEquals("false", System.getProperty(MAC_DIRECTORY_MODE))
            }
            assertEquals(
                "true",
                System.getProperty(MAC_DIRECTORY_MODE),
                "the outer folder panel lost its mode to the inner file panel",
            )
        }
        assertNull(System.getProperty(MAC_DIRECTORY_MODE))
    }

    @Test
    fun `a throwing body still restores the property`() {
        System.setProperty(MAC_DIRECTORY_MODE, "true")

        assertFailsWith<IllegalStateException> {
            withDirectoryMode(directories = false) { error("panel blew up") }
        }

        assertEquals("true", System.getProperty(MAC_DIRECTORY_MODE))
    }

    @Test
    fun `fixture teardown preserves a property present before setup`() {
        System.setProperty(MAC_DIRECTORY_MODE, "true")
        val fixture = OwnedFileDialogTest()
        fixture.capture()
        fixture.restore()
        assertEquals("true", System.getProperty(MAC_DIRECTORY_MODE))
    }

    @Test
    fun `the body's value is returned`() {
        assertEquals(7, withDirectoryMode(directories = false) { 7 })
    }

    @Test
    fun `asking for the owner window never throws, even with nothing focused`() {
        // Called on every pick. In a headless or unfocused process the answer is simply null, and
        // the callers fall back to an ownerless panel rather than failing the pick.
        val owner = runCatching { activeDialogOwner() }

        assertTrue(owner.isSuccess, "activeDialogOwner threw: ${owner.exceptionOrNull()}")
    }
}
