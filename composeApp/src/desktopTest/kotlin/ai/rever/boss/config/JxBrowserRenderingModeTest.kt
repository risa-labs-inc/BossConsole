package ai.rever.boss.config

import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rendering-mode decision in [JxBrowserConfig].
 *
 * Two things are pinned here, and both are load-bearing.
 *
 * The platform split: Windows defaults to HARDWARE_ACCELERATED because OFF_SCREEN
 * costs it ~47% on Speedometer 3.1 (see the KDoc for the measurements), while macOS
 * is measurably FINE on OFF_SCREEN — 47.9, ahead of Chrome — and Linux was never
 * measured. A regression that leaked the Windows default onto those platforms would
 * change how browser content composites against Compose overlays on machines that
 * had no problem to fix, so it is asserted explicitly per platform.
 *
 * The override precedence: an explicit OFF_SCREEN must win on Windows, because it is
 * the documented escape hatch if HARDWARE_ACCELERATED turns out to draw browser
 * content over Compose menus and dialogs there.
 */
class JxBrowserRenderingModeTest {
    private val windows = "windows 11"
    private val mac = "mac os x"
    private val linux = "linux"

    @Test
    fun `windows defaults to HARDWARE_ACCELERATED`() {
        for (raw in listOf(null, "", "   ")) {
            assertEquals(
                RenderingMode.HARDWARE_ACCELERATED,
                JxBrowserConfig.resolveRenderingMode(raw, windows),
                "expected the Windows default for '$raw'",
            )
        }
    }

    @Test
    fun `mac and linux keep OFF_SCREEN — the Windows finding must not leak`() {
        for (os in listOf(mac, linux, "freebsd", "sunos")) {
            assertEquals(
                RenderingMode.OFF_SCREEN,
                JxBrowserConfig.resolveRenderingMode(null, os),
                "expected OFF_SCREEN on '$os'",
            )
        }
    }

    @Test
    fun `an explicit OFF_SCREEN overrides the Windows default`() {
        // The escape hatch: if the HARDWARE overlay handling turns out to be
        // incomplete on some machine, this restores the old behaviour with no rebuild.
        // All three spellings are BossConsoleLite's, so one value works in both repos.
        for (raw in listOf("OFF_SCREEN", "off_screen", "  Off_Screen  ", "OFFSCREEN", "software")) {
            assertEquals(
                RenderingMode.OFF_SCREEN,
                JxBrowserConfig.resolveRenderingMode(raw, windows),
                "expected the override to win for '$raw'",
            )
        }
    }

    @Test
    fun `an explicit HARDWARE mode can be opted into on mac and linux`() {
        for (os in listOf(mac, linux)) {
            for (raw in listOf("hardware_accelerated", "HARDWARE", "gpu")) {
                assertEquals(
                    RenderingMode.HARDWARE_ACCELERATED,
                    JxBrowserConfig.resolveRenderingMode(raw, os),
                    "expected the opt-in to work for '$raw' on '$os'",
                )
            }
        }
    }

    @Test
    fun `an unrecognized value falls back to the platform default, never to a guess`() {
        // A near-miss must not be read as intent. Getting this wrong would change
        // compositing app-wide with no other signal than a log line. Note "hardware",
        // "gpu", "offscreen" and "software" are NOT here: those are Lite's accepted
        // spellings and are honoured (see the override tests above).
        for (raw in listOf("HARDWARE-ACCELERATED", "ACCELERATED", "off screen", "hard ware", "nonsense")) {
            assertEquals(
                RenderingMode.HARDWARE_ACCELERATED,
                JxBrowserConfig.resolveRenderingMode(raw, windows),
                "expected the Windows default for unrecognized '$raw'",
            )
            assertEquals(
                RenderingMode.OFF_SCREEN,
                JxBrowserConfig.resolveRenderingMode(raw, mac),
                "expected the mac default for unrecognized '$raw'",
            )
        }
    }

    @Test
    fun `recognition predicate matches exactly the values resolve honours`() {
        for (raw in listOf("OFF_SCREEN", " hardware_accelerated ", "GPU", "software", "OFFSCREEN")) {
            assertTrue(JxBrowserConfig.isRecognizedRenderingMode(raw), "should be recognized: '$raw'")
        }
        for (raw in listOf(null, "", "   ", "HARDWARE-ACCELERATED", "off screen", "nonsense")) {
            assertFalse(JxBrowserConfig.isRecognizedRenderingMode(raw), "should not be recognized: '$raw'")
        }
    }
}
