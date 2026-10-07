package ai.rever.boss.components.overlays

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.sharing.MacAppWindowStream
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real owned suggestion host, with synthetic content and no user window capture. */
@OptIn(ExperimentalTestApi::class)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class OwnedCornerTransparencySmokeTest {
    @Test
    fun `owned suggestions configure transparent rendering without stealing field focus`(): Unit =
        withOwnedCorner { dialog ->
            onEdt {
                assertTrue(dialog.isTransparent, "AWT alpha alone does not configure the Skia renderer")
                assertFalse(dialog.focusableWindowState)
                assertFalse(dialog.isModal)
            }
        }

    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
    fun `padding outside a rounded suggestion card has native zero alpha`() {
        assumeTrue(SystemUtils.isMacOS && MacOSScreenCapture.hasPermission())
        withOwnedCorner { dialog ->
            val handle = onEdt { dialog.windowHandle }
            MacAppWindowStream.open(handle, 240, 160, pixelFormat = "BGRA").use { stream ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var painted = false
                while (System.nanoTime() < deadline) {
                    val frame = stream.latest()
                    if (frame != null) {
                        val center = ((frame.height / 2) * frame.width + frame.width / 2) * 4
                        val channels = (0..3).map { frame.bgra[center + it].toInt() and 255 }
                        if (channels[3] >= 245 && channels.take(3).all { it in 20..100 }) {
                            val edge = (4 * frame.width + 4) * 4
                            val alpha = frame.bgra[edge + 3].toInt() and 255
                            assertTrue(alpha <= 8, "Suggestion padding must be clear, not a white window: alpha=$alpha")
                            painted = true
                            break
                        }
                    }
                    Thread.sleep(10)
                }
                assertTrue(painted, "Synthetic suggestion card did not paint")
            }
        }
    }

    private fun withOwnedCorner(check: (ComposeDialog) -> Unit): Unit =
        runComposeUiTest {
            var owner: java.awt.Window? = null
            setContent {
                Window(
                    onCloseRequest = {},
                    title = "Synthetic suggestion transparency fixture",
                    state = rememberWindowState(width = 360.dp, height = 240.dp),
                    focusable = false,
                    icon = BossWindowIcon.painter,
                ) {
                    ApplyBossWindowIcon(window)
                    DisposableEffect(window) {
                        owner = window
                        onDispose { }
                    }
                    CompositionLocalProvider(LocalAwtWindow provides window) {
                        HeavyweightCorner(
                            alignment = Alignment.TopStart,
                            initialSize = DpSize(240.dp, 160.dp),
                            focusable = false,
                            owned = true,
                        ) {
                            Box(Modifier.size(240.dp, 160.dp).padding(16.dp)) {
                                Box(
                                    Modifier
                                        .size(208.dp, 128.dp)
                                        .background(Color(0xFF333333), RoundedCornerShape(18.dp)),
                                )
                            }
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { owner?.ownedWindows?.any { it is ComposeDialog && it.isShowing } == true }
            }
            val dialog = onEdt { owner!!.ownedWindows.filterIsInstance<ComposeDialog>().single { it.isShowing } }
            check(dialog)
        }
}
