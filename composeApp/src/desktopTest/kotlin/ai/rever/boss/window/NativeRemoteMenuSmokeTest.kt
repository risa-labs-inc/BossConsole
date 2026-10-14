package ai.rever.boss.window

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.sharing.captureSurfaceSnapshot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntRect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class NativeRemoteMenuSmokeTest {
    @Test
    fun `remote toolbar popup is captured as an owned unfocusable dialog and retires on dismiss`() {
        val visible = mutableStateOf(true)
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setBounds(80, 400, 500, 300)
                    val ownerWindow = this
                    setContent {
                        CompositionLocalProvider(LocalAwtWindow provides ownerWindow) {
                            if (visible.value) {
                                NativeRemoteToolbarMenu(
                                    NativeToolbarMenuRequest(
                                        "synthetic",
                                        "Synthetic menu",
                                        IntRect(20, 20, 80, 40),
                                        listOf(NativeTitleBarAction("synthetic-item", "Synthetic item", onClick = {})),
                                        {},
                                    ),
                                ) { visible.value = false }
                            }
                        }
                    }
                    isVisible = true
                }
            }
        try {
            await { window.ownedWindows.any { it is ComposeDialog && it.isShowing } }
            onEdt {
                val popup = window.ownedWindows.single { it is ComposeDialog && it.isShowing }
                assertFalse(window.isFocused)
                assertFalse(popup.focusableWindowState)
                assertTrue(captureSurfaceSnapshot(window).surfaces.any { it.window === popup })
                visible.value = false
            }
            await { window.ownedWindows.none { it.isShowing } }
            onEdt { assertTrue(captureSurfaceSnapshot(window).surfaces.all { it.window === window }) }
        } finally {
            onEdt { window.dispose() }
        }
    }

    @Test
    fun `timed out AppKit input cannot execute when the queue resumes`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val invoked = AtomicBoolean(false)
        MacToolbarRuntime.dispatch {
            entered.countDown()
            resume.await(2, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(
                scopedNativeToolbarCall(Long.MAX_VALUE) {
                    invoked.set(true)
                    true
                },
            )
        } finally {
            resume.countDown()
        }
        val drained = CompletableFuture<Unit>()
        MacToolbarRuntime.dispatch { drained.complete(Unit) }
        drained.get(2, TimeUnit.SECONDS)
        assertFalse(invoked.get())
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (onEdt(predicate)) return
            Thread.sleep(25)
        }
        error("Synthetic owned menu lifecycle must settle")
    }

    private fun <T> onEdt(action: () -> T): T {
        val result = CompletableFuture<T>()
        SwingUtilities.invokeAndWait { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }
}
