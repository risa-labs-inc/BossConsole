package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.AppSurfaceSnapshot
import ai.rever.boss.sharing.AwtAppInputSink
import ai.rever.boss.sharing.captureSurfaceSnapshot
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.utils.SystemUtils
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class WindowInputModalBoundaryTest {
    @Test
    fun `logical modal blocks root input and actions while admitting exact child input`(): Unit =
        onEdt {
            ModalFixture().use { fixture ->
                val rootSnapshot = captureSurfaceSnapshot(fixture.root)
                fixture.sink.updateSurfaces(rootSnapshot)
                fixture.click(rootSnapshot, fixture.root, 0.8)
                assertTrue(fixture.sink.isAvailableFor(fixture.key))
                val child = fixture.child(fixture.root)
                val first = WindowInputModalBoundary.register(child)
                val replacement = WindowInputModalBoundary.register(child)
                try {
                    first.close()
                    assertTrue(WindowInputModalBoundary.isModal(child), "Late cleanup must retain replacement")
                    assertTrue(
                        CompletableFuture
                            .supplyAsync { WindowInputModalBoundary.isModal(child) }
                            .get(2, TimeUnit.SECONDS),
                        "Modal metadata reads must not wait for EDT",
                    )
                    assertFalse(child.isModal, "Remote input marker must not change local native modality")
                    assertFalse(fixture.sink.apply(fixture.key), "New logical modal fences old root keyboard focus")
                    assertFalse(fixture.sink.apply(AppInputEvent.Window("close")))
                    assertFalse(fixture.sink.apply(AppInputEvent.Window("restore")))
                    val nested = fixture.child(child)
                    val snapshot = captureSurfaceSnapshot(fixture.root)
                    assertTrue(snapshot.surfaces.single { it.window === child }.modal)
                    fixture.sink.updateSurfaces(snapshot)
                    assertFalse(fixture.sink.apply(AppInputEvent.Pointer("down", 0.8, 0.8, 0)))
                    fixture.click(snapshot, nested)
                    assertTrue(fixture.sink.apply(fixture.key), "Input may reach a descendant of the modal")
                    assertTrue(fixture.sink.apply(fixture.key.copy(action = "up")))
                    replacement.close()
                    assertFalse(WindowInputModalBoundary.isModal(child))
                    fixture.sink.updateSurfaces(captureSurfaceSnapshot(fixture.root))
                    assertTrue(fixture.sink.apply(AppInputEvent.Window("close")))
                    assertEquals(1, fixture.closes)
                } finally {
                    replacement.close()
                    first.close()
                }
            }
        }

    @Test
    fun `logical modal registration revokes queued native action without querying EDT from AppKit`() {
        assumeTrue(SystemUtils.isMacOS)
        val fixture = onEdt { ModalFixture() }
        val child = onEdt { fixture.child(fixture.root) }
        val pending = AtomicBoolean()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val delivered = AtomicInteger()
        val authority = onEdt { WindowInputModalBoundary.captureAuthority(fixture.root) }
        var marker: AutoCloseable? = null
        try {
            val accepted =
                onEdt {
                    queueNativeWindowAction(
                        System.currentTimeMillis() + 1000,
                        pending,
                        authority,
                        {
                            MacToolbarRuntime.dispatch {
                                entered.countDown()
                                resume.await(2, TimeUnit.SECONDS)
                            }
                            true
                        },
                        { delivered.incrementAndGet() },
                    )
                }
            assertTrue(accepted)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            marker = onEdt { WindowInputModalBoundary.register(child) }
            assertFalse(authority(), "Marker appearance must invalidate the captured atomic authority")
            resume.countDown()
            val drained = CompletableFuture<Unit>()
            MacToolbarRuntime.dispatch { drained.complete(Unit) }
            drained.get(2, TimeUnit.SECONDS)
            assertEquals(0, delivered.get())
            assertFalse(pending.get())
        } finally {
            resume.countDown()
            onEdt {
                marker?.close()
                fixture.close()
            }
        }
    }

    private class ModalFixture : AutoCloseable {
        val key = AppInputEvent.Key("down", "KeyA", "a", false, false, false, false)
        val root =
            ComposeWindow().apply {
                iconImages = BossWindowIcon.images
                focusableWindowState = false
                setContent { }
                glassPane = JPanel(BorderLayout()).apply { add(JTextField(), BorderLayout.CENTER) }
                glassPane.isVisible = true
                setBounds(100, 100, 420, 240)
                isVisible = true
                validate()
            }
        private val windows = mutableListOf<Window>(root)
        val sink = AwtAppInputSink(root, requireForeground = false)
        var closes = 0
        private val controls =
            OwnedWindowControls.register(
                "logical-modal-fixture",
                root,
                mapOf("close" to { closes++ }, "restore" to {}),
            )

        fun child(owner: Window): ComposeDialog =
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                iconImages = BossWindowIcon.images
                focusableWindowState = false
                setContent { }
                glassPane = JPanel(BorderLayout()).apply { add(JTextField(), BorderLayout.CENTER) }
                glassPane.isVisible = true
                setBounds(owner.x + 30, owner.y + 45, 120, 90)
                isVisible = true
                validate()
                windows.add(this)
            }

        fun click(
            snapshot: AppSurfaceSnapshot,
            target: Window,
            fraction: Double = 0.5,
        ) {
            val x = (target.x + target.width * fraction - snapshot.x) / (snapshot.logicalWidth - 1)
            val y = (target.y + target.height * fraction - snapshot.y) / (snapshot.logicalHeight - 1)
            val down = AppInputEvent.Pointer("down", x, y, 0)
            assertTrue(sink.apply(down))
            assertTrue(sink.apply(down.copy(action = "up")))
        }

        override fun close() {
            sink.releaseAll()
            controls.close()
            windows.asReversed().forEach(Window::dispose)
        }
    }
}
