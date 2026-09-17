package ai.rever.boss.components.overlays

import org.junit.Test
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingTooltipTextTest {
    @Test fun descriptionCannotIntroduceHtmlImagesAndLongTextWraps() {
        val markup = swingTooltipMarkup("<html><img src='https://example.test/private'> & plain text")
        assertFalse(markup.contains("<img"))
        assertTrue(markup.contains("&lt;img"))
        assertTrue(markup.contains("&amp;"))
        SwingUtilities.invokeAndWait {
            val label = JLabel(swingTooltipMarkup("A tool to write notes and organize project information. ".repeat(8)))
            assertTrue(label.preferredSize.width <= 330, "Long description should wrap instead of spanning the desktop")
            assertTrue(label.preferredSize.height > 25)
        }
    }
}
