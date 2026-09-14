package ai.rever.boss.components.auth

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The brand panel's browser view must be owned by the composition that shows it (#629).
 *
 * It was built with a fresh `MainScope()` that nothing ever cancelled, and the view state itself was
 * never closed - so every sign-in screen, and every re-attach when the browser or window changed, left a
 * live scope and a registered view behind. Observing that at runtime needs a real Chromium, so this pins
 * the call site instead, the same way [ai.rever.boss.plugin.browser.BrowserMainThreadRoundTripTest] does.
 */
class AuthBrandSiteViewScopeTest {
    private val source: String by lazy {
        val file =
            File(
                repoRoot(),
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/auth/forms/DesktopAuthBrandSite.kt",
            )
        codeOnly(file.readText())
    }

    @Test
    fun `the view state is not given a scope nothing cancels`() {
        assertFalse(
            "MainScope(" in source,
            "DesktopAuthBrandSite builds a MainScope() again; nothing cancels it when the panel leaves",
        )
        assertTrue(
            "BrowserViewState(browser, viewScope, window)" in source,
            "the brand page view should run in the composition's scope (rememberCoroutineScope)",
        )
    }

    @Test
    fun `view failures are isolated and the child scope is disposed`() {
        assertTrue("SupervisorJob(" in source, "view failures must not cancel the load callback scope")
        assertTrue(
            "onDispose { viewScope.cancel() }" in source,
            "the supervised child must not outlive the composition",
        )
    }

    @Test
    fun `the view state is closed when it is forgotten`() {
        val dispose = Regex("""DisposableEffect\(state\)\s*\{\s*onDispose\s*\{[^}]*state\.close\(\)""")
        assertTrue(
            dispose.containsMatchIn(source),
            "a replaced or dropped brand page view must be closed in onDispose, not left registered",
        )
    }

    /** Strips comments, so prose that names `MainScope()` cannot satisfy or trip the checks. */
    private fun codeOnly(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }
}
