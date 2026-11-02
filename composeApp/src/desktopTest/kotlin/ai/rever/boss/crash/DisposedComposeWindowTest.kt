package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisposedComposeWindowTest {
    private fun lateResize(): IllegalArgumentException =
        IllegalArgumentException("ComposeContainer is disposed").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "androidx.compose.ui.awt.ComposeWindowPanel",
                        "getComposeContainer",
                        "ComposeWindowPanel.desktop.kt",
                        78,
                    ),
                    StackTraceElement(
                        "androidx.compose.ui.awt.ComposeWindowPanel",
                        "setBounds",
                        "ComposeWindowPanel.desktop.kt",
                        124,
                    ),
                    StackTraceElement("java.awt.Component", "reshape", "Component.java", 1),
                )
        }

    @Test
    fun `queued resize cannot quarantine a plugin or exhaust recovery`() {
        val policy = RenderCrashPolicy(now = { 0L })
        repeat(RenderCrashPolicy.DEFAULT_MAX_FAILURES + 2) {
            assertEquals(
                WindowExceptionRoute.IgnoreDisposedWindow,
                decideWindowExceptionRoute(lateResize(), "terminal", policy, "terminal"),
            )
        }
        assertEquals(0, policy.recentFailureCount())
        assertTrue(CrashHandler.isIgnorable(lateResize()))
        assertTrue(CrashHandler.isIgnorable(RuntimeException("AWT callback", lateResize())))
    }

    @Test
    fun `lookalikes remain reportable`() {
        assertFalse(CrashHandler.isIgnorable(IllegalArgumentException("ComposeContainer is disposed")))
        val otherOperation =
            lateResize().apply {
                stackTrace =
                    stackTrace.copyOf().also {
                        it[1] =
                            StackTraceElement(
                                "androidx.compose.ui.awt.ComposeWindowPanel",
                                "paint",
                                "ComposeWindowPanel.desktop.kt",
                                1,
                            )
                    }
            }
        assertFalse(isDisposedComposeWindowResize(otherOperation))
        assertFalse(
            isDisposedComposeWindowResize(
                IllegalStateException("ComposeContainer is disposed").apply {
                    stackTrace = lateResize().stackTrace
                },
            ),
        )
        assertFalse(
            isDisposedComposeWindowResize(
                IllegalArgumentException("another failure").apply {
                    stackTrace = lateResize().stackTrace
                },
            ),
        )
    }

    @Test
    fun `a fatal cause cannot hide behind a stale resize`() {
        val error = lateResize().apply { initCause(OutOfMemoryError("heap")) }
        assertFalse(CrashHandler.isIgnorable(error))
        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(error, null, RenderCrashPolicy(now = { 0L })),
        )
    }
}
