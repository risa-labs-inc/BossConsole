package ai.rever.boss.performance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins #1092: one failed open or toggle of the Performance panel must not make the status-bar
 * indicator unclickable, and must not be reported as a crash.
 *
 * Unconfined, so each launch runs to completion inside the call and the assertions need no waiting.
 */
class PanelActionScopeTest {
    private class Boom : RuntimeException("install prompt failed")

    @Test
    fun `a failed panel action does not stop the next one from running`() {
        val failures = mutableListOf<Throwable>()
        val scope = panelActionScope(Dispatchers.Unconfined) { failures += it }

        scope.launch { throw Boom() }
        var secondRan = false
        scope.launch { secondRan = true }

        assertTrue(scope.isActive, "a failed child must not cancel the scope")
        assertTrue(secondRan, "the click after a failure must still open the panel")
        assertEquals(1, failures.size)
        assertTrue(failures.single() is Boom)
    }

    @Test
    fun `a failed panel action is handled, not sent to the crash handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val handled = mutableListOf<Throwable>()
            val scope = panelActionScope(Dispatchers.Unconfined) { handled += it }

            scope.launch { throw Boom() }

            assertEquals(1, handled.size)
            assertTrue(uncaught.isEmpty(), "CrashHandler would raise the crash dialog for this: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
