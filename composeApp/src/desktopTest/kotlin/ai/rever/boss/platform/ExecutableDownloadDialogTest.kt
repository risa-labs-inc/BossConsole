package ai.rever.boss.platform

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutableDownloadDialogTest {
    // addNotify() creates the AWT peer (and so flips isDisplayable) without setVisible(true),
    // so these never actually appear on screen - the property pickDialogOwner reads, exercised
    // without a real visible window. Constructing a Frame at all throws HeadlessException on a
    // display-less CI runner (ubuntu-latest here), before isDisplayable ever comes into it - so
    // callers skip rather than fail where no display exists, the same way TabPathsTest skips its
    // OS-specific path cases.
    private val displayableFrames = mutableListOf<Frame>()

    private fun displayableFrame(): Frame {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "needs a real display to construct an AWT Frame")
        return Frame().also { it.addNotify() }.also(displayableFrames::add)
    }

    private fun nonDisplayableFrame(): Frame {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "needs a real display to construct an AWT Frame")
        return Frame().also(displayableFrames::add)
    }

    @AfterTest
    fun disposeFrames() {
        displayableFrames.forEach { it.dispose() }
        displayableFrames.clear()
    }

    @Test
    fun `only explicit acceptance permits the download`() {
        assertTrue(confirmExecutableDownloadOnEdt { 0 })
        assertFalse(confirmExecutableDownloadOnEdt { 1 })
        assertFalse(confirmExecutableDownloadOnEdt { -1 })
        assertFalse(confirmExecutableDownloadOnEdt { 2 })
    }

    @Test
    fun `a worker dispatches the prompt to the EDT and waits for the answer`() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertTrue(
            confirmExecutableDownloadOnEdt {
                assertTrue(SwingUtilities.isEventDispatchThread())
                0
            },
        )
    }

    @Test
    fun `an EDT caller can answer without invoking and waiting on itself`() {
        SwingUtilities.invokeAndWait {
            assertTrue(confirmExecutableDownloadOnEdt { 0 })
            assertFalse(confirmExecutableDownloadOnEdt { error("Dialog unavailable") })
        }
    }

    @Test
    fun `dialog creation failure cancels the download`() {
        assertFalse(confirmExecutableDownloadOnEdt { throw java.awt.HeadlessException() })
    }

    // ---- pickDialogOwner: BossConsole#484 review R1/R2 ----

    @Test
    fun `a displayable active window wins over every candidate`() {
        val active = displayableFrame()
        val candidate = displayableFrame()
        assertSame(active, pickDialogOwner(active, listOf(candidate)))
    }

    @Test
    fun `a non-displayable active window falls back to the first displayable candidate`() {
        val active = nonDisplayableFrame()
        val candidate = displayableFrame()
        assertSame(candidate, pickDialogOwner(active, listOf(candidate)))
    }

    @Test
    fun `a null active window falls back to the candidates the same way`() {
        val candidate = displayableFrame()
        assertSame(candidate, pickDialogOwner(null, listOf(candidate)))
    }

    @Test
    fun `a non-displayable first candidate does not refuse when a later one is usable`() {
        // R2: resolveActionableWindowId names exactly one id, which can be a window that has been
        // disposed but not yet unregistered - the fix is scanning every registered window rather
        // than refusing on the first miss.
        val stale = nonDisplayableFrame()
        val usable = displayableFrame()
        assertSame(usable, pickDialogOwner(null, listOf(stale, usable)))
    }

    @Test
    fun `no displayable window anywhere refuses rather than orphaning a modal`() {
        val active = nonDisplayableFrame()
        val candidate = nonDisplayableFrame()
        assertNull(pickDialogOwner(active, listOf(candidate)))
    }

    @Test
    fun `no candidates at all refuses`() {
        assertNull(pickDialogOwner(null, emptyList()))
    }
}
