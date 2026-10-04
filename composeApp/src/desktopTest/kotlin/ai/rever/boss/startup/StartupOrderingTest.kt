package ai.rever.boss.startup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the order in `main` that keeps the native toolkit preload clear of other threads.
 *
 * Loading JxBrowser's toolkit swaps the process's malloc zones, and a free() on another thread
 * during the swap is an uncatchable SIGTRAP (9.5.33, 2026-09-29). The race is far too rare for any
 * test run to hit, so a reorder would ship silently: this reads `main.kt` and fails on the order
 * instead. The runtime half is `ChromiumToolkitPreload.lateLoadReason`.
 */
class StartupOrderingTest {
    private val main: String by lazy {
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("Cannot locate the BOSS source root from ${System.getProperty("user.dir")}")
        // Prose and literals cannot be mistaken for startup calls.
        File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt")
            .readText()
            .let(::startupCodeOnly)
    }

    private fun at(call: String): Int {
        val index = main.indexOf(call)
        assertTrue(index >= 0, "main.kt no longer calls $call")
        assertTrue(main.indexOf(call, index + 1) < 0, "main.kt calls $call more than once")
        return index
    }

    private val preflight get() = at("ChromiumBootstrap.preflight()")

    @Test
    fun `preflight runs while the single-instance lock is held`() {
        assertTrue(at("SingleInstanceManager.acquireLock()") < preflight)
    }

    @Test
    fun `preflight runs before anything creates the AWT toolkit`() {
        assertTrue(preflight < at("DefaultWindowIcon.install()"))
        // Pin known direct entry points; helper implementations still require code review.
        val beforePreflight = main.substring(0, preflight)
        listOf("Toolkit.getDefaultToolkit()", "SwingUtilities.invoke", "EventQueue.invoke", "Taskbar.")
            .forEach { call -> assertTrue(call !in beforePreflight, "$call runs before the preflight") }
    }

    @Test
    fun `background warm-ups start after the preflight`() {
        assertTrue(preflight < at("WorkspaceSettingsManager.currentSettings"))
        assertTrue(preflight < at("MacOSScrollGesturePhases"))
    }

    @Test
    fun `icon creation records its entry point before accessing the toolkit`() {
        val source = File(System.getProperty("user.dir"))
        val root =
            generateSequence(source) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("Cannot locate the BOSS source root from $source")
        val icon =
            startupCodeOnly(
                File(
                    root,
                    "composeApp/src/desktopMain/kotlin/ai/rever/boss/window/WindowIcon.kt",
                ).readText(),
            )
        val install = icon.indexOf("fun install()")
        val marker = icon.indexOf(".noteAwtToolkitCreating(", install)
        val toolkit = icon.indexOf("Toolkit.getDefaultToolkit()", install)
        assertTrue(
            install >= 0 && marker > install && toolkit > marker,
            "Window icon installation must record creation before accessing AWT",
        )
    }

    @Test
    fun `pre-warm is handed the preflight`() {
        assertTrue(preflight < at("ChromiumBootstrap.prepare(chromiumPreflight)"))
    }
}
