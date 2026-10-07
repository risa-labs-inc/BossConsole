package ai.rever.boss.window

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts every site that opens a top-level window also brands it.
 *
 * A Compose `Window` given no `icon`, or a bare `JFrame`, leaves `Frame.iconImages` empty, and
 * Windows then draws the JDK's default Java icon in the title bar, the taskbar button and the
 * Alt-Tab card. macOS reads the `.app` bundle and Linux the `.desktop` file, so neither shows it -
 * which is exactly why this needs a test rather than review vigilance. The omission is invisible in
 * a diff (an absent argument), invisible on the two platforms most of this is developed on, and the
 * window works perfectly in every other respect. Nine windows shipped that way, for years, before
 * anyone noticed the Settings window was wearing a coffee cup.
 *
 * **Per call site, not per file.** An earlier version of this test asked "does this file open a
 * window and fail to mention the icon", which meant one branded site immunised the whole file -
 * `FullscreenBrowserWindow.kt` already has two `JFrame()` sites and `main.kt` has a `Window(`, so a
 * third added tomorrow would have passed unbranded. Since the entire premise here is that the
 * omission hides in a file that otherwise looks correct, that was the most likely hole in the guard.
 *
 * Still a text check, like [ai.rever.boss.components.overlays.NoRawDialogConventionTest] next door,
 * and it inherits that test's limits: a window opened through some helper of its own can slip past.
 * Qualified native/Compose calls remain included; the sharing event named Window is not an opener.
 */
class WindowIconConventionTest {
    /**
     * The call shapes that open a window with an icon surface.
     *
     * `\b` before each name matters: without it `Window\(` also matches `positionInWindow()` and
     * `boundsInWindow()`, which are all over the layout code in this repo. It is also why
     * `DialogWindow` needs its own entry - the `g` before its `W` is a word character, so the
     * `Window` pattern never sees it.
     */
    private val windowOpener =
        Regex("""\bWindow\(|\bDialogWindow\(|\bsingleWindowApplication\(|\bJFrame\(""")

    private val inputEventQualifier = Regex("""\bAppInputEvent\s*\.\s*$""")
    private val declarationPrefix = Regex("""\b(?:class|interface|fun)\s+$""")

    private fun opensWindow(line: String): Boolean =
        windowOpener.findAll(line).any { match ->
            val prefix = line.substring(0, match.range.first)
            val event = match.value == "Window(" && inputEventQualifier.containsMatchIn(prefix)
            !event && !declarationPrefix.containsMatchIn(prefix)
        }

    /** Any shape of the shared icon: the Compose painter, the AWT image list, or the upgrade call. */
    private val branding = Regex("""\bBossWindowIcon\b|\bApplyBossWindowIcon\b""")

    /**
     * How far after an opener the branding may appear.
     *
     * Generous on purpose. A Compose `Window(` carries `icon =` within a few lines, but the raw
     * `JFrame` sites assign `iconImages` after a run of other frame setup, and `CrashHandler` puts a
     * controller and a comment block in between. Wide enough not to be brittle, narrow enough that
     * branding one site cannot vouch for an unrelated one further down the file.
     */
    private val lookaheadLines = 40

    /**
     * Files where an opener match is prose or a definition rather than a call site.
     *
     * `WindowIcon.kt` documents `Window(icon = null)` in its KDoc; the tests quote the patterns.
     */
    private val allowed = setOf("WindowIcon.kt", "WindowIconConventionTest.kt", "WindowIconTest.kt")

    @Test
    fun `every window call site brands the window`() {
        val root = repoRoot()
        val offenders =
            kotlinSourcesUnder(root, "composeApp/src", "plugin-platform")
                .filter { it.name !in allowed }
                .flatMap { file -> unbrandedSitesIn(file, root) }
                .sorted()

        if (offenders.isNotEmpty()) {
            fail(
                "These window call sites do not brand the window, so on Windows it will show the " +
                    "default Java icon in the title bar, taskbar and Alt-Tab. Pass " +
                    "`icon = BossWindowIcon.painter` and call `ApplyBossWindowIcon(window)` in a " +
                    "Compose Window/DialogWindow body, or assign `frame.iconImages = " +
                    "BossWindowIcon.images` on a raw JFrame (ai.rever.boss.window.WindowIcon):\n  " +
                    offenders.joinToString("\n  "),
            )
        }
    }

    /** Repo-relative `path:line` for every opener in [file] with no branding within the lookahead. */
    private fun unbrandedSitesIn(
        file: File,
        root: File,
    ): List<String> {
        val lines = file.readLines()
        return lines.indices
            .filter { opensWindow(lines[it]) }
            .filterNot { i ->
                val end = minOf(lines.size, i + lookaheadLines)
                (i until end).any { branding.containsMatchIn(lines[it]) }
            }.map { "${file.relativeTo(root).path.replace('\\', '/')}:${it + 1}" }
    }

    /**
     * Guards the guard: if the opener pattern ever stops matching, the test above passes vacuously
     * and would keep passing while every window in the app went unbranded.
     *
     * Counts call sites rather than files, and skips [allowed] so that prose in this feature's own
     * KDoc cannot be what satisfies it - the earlier version's floor was partly met by
     * `WindowIcon.kt` documenting `Window(icon = null)`, which is a little too self-referential to
     * be a safety net.
     */
    @Test
    fun `the window-opening pattern still matches the known call sites`() {
        val root = repoRoot()
        val sites =
            kotlinSourcesUnder(root, "composeApp/src")
                .filter { it.name !in allowed }
                .sumOf { file -> file.readLines().count(::opensWindow) }

        // Fifteen real sites at the time of writing: ten Compose windows and five raw frames. A
        // floor, not an equality - adding a window must fail the test above, never this one.
        if (sites < 12) {
            fail("expected the window-opening pattern to match many call sites, matched only $sites")
        }
    }

    @Test
    fun `sharing control events and definitions are not native window openers`() {
        assertFalse(opensWindow("val event = AppInputEvent.Window(\"close\")"))
        assertFalse(opensWindow("ai.rever.boss.sharing.AppInputEvent . Window(\"restore\")"))
        assertFalse(opensWindow("data class Window("))
        assertFalse(opensWindow("fun Window("))
    }

    @Test
    fun `qualified windows remain guarded even beside a sharing event`() {
        assertTrue(opensWindow("Window("))
        assertTrue(opensWindow("androidx.compose.ui.window.Window("))
        assertTrue(opensWindow("javax.swing.JFrame("))
        assertTrue(opensWindow("Other.Window("))
        assertTrue(opensWindow("AppInputEvent.Window(\"close\"); Window("))
        assertTrue(opensWindow("DialogWindow("))
        assertTrue(opensWindow("singleWindowApplication("))
    }

    /**
     * The net is one line in `main.kt` and nothing else references it. Delete that line and every
     * test here still passes while windows BOSS does not compose go back to the Java icon.
     */
    @Test
    fun `main installs the default window icon net`() {
        val main = File(repoRoot(), "composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt")
        check(main.isFile) { "main.kt not found at ${main.absolutePath}" }

        if (!main.readText().contains("DefaultWindowIcon.install()")) {
            fail(
                "main.kt no longer calls DefaultWindowIcon.install(), so windows BOSS does not " +
                    "compose itself (JxBrowser's Swing dialogs, JFileChooser, plugin frames) will " +
                    "show the default Java icon on Windows",
            )
        }
    }
}
