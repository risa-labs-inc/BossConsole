package ai.rever.boss.components.auth

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The WebAuthn browser view must be owned by the composition that shows it.
 *
 * It was built with a fresh `MainScope()` that nothing ever cancelled, and the view state was never
 * closed - so every passkey prompt, and every re-attach when the browser or window changed, left a live
 * scope and a registered view behind. The same leak as #629 in the brand panel. Observing it at runtime
 * needs a real Chromium, so this pins the call site instead.
 */
class PasskeyBrowserViewScopeTest {
    private val source: String by lazy {
        val file =
            File(
                repoRoot(),
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/auth/screens/DesktopPasskeyBrowserView.kt",
            )
        codeOnly(file.readText())
    }

    @Test
    fun `the view state is not given a scope nothing cancels`() {
        assertFalse(
            "MainScope(" in source,
            "DesktopPasskeyBrowserView builds a MainScope() again; nothing cancels it when the view leaves",
        )
        assertTrue(
            "BrowserViewState(browser!!, viewScope, window)" in source,
            "the passkey view should run in the composition's scope (rememberCoroutineScope)",
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
        val dispose =
            Regex("""DisposableEffect\(browserViewState\)\s*\{\s*onDispose\s*\{[^}]*browserViewState\.close\(\)""")
        assertTrue(
            dispose.containsMatchIn(source),
            "a replaced or dropped passkey view must be closed in onDispose, not left registered",
        )
    }

    /** Strips comments, so prose that names `MainScope()` cannot satisfy or trip the checks. */
    private fun codeOnly(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }
}
