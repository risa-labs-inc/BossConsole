package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Re-enabling a disabled plugin must report missing dependencies; reloads must not.
 *
 * #180: after #178 a required dependency can be removed while its dependent sits
 * disabled. `enablePlugin` and `handleAccessChange` used to register with no
 * `missingFor` check, which is the silent looks-alive-does-nothing state the
 * reporter exists to prevent.
 *
 * A source-level assertion because this seam is unreachable from a unit test:
 * `DynamicPluginManager` cannot be faked, and `PluginLoaderDelegateImpl` takes a
 * concrete one. Same approach as `DependencyPromptOnInstallOnlyTest`.
 */
class DependencyPromptOnReenableTest {
    private fun repoRoot(): File =
        assertNotNull(
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
            "could not locate the repository root",
        )

    private fun read(path: String): String {
        val file = File(repoRoot(), path)
        assertTrue(file.isFile, "not found: ${file.absolutePath}")
        return file.readText()
    }

    private fun managerSource() =
        read("composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt")

    private fun setupSource() =
        read("composeApp/src/desktopMain/kotlin/ai/rever/boss/components/plugin/PluginLoaderDelegateSetup.kt")

    private fun delegateSource() =
        read("composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginLoaderDelegateImpl.kt")

    /**
     * Body of the first `fun <name>(` in [source], brace-matched so a later
     * sibling of the same name (comments, calls) cannot steal the match.
     */
    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val match =
            assertNotNull(
                Regex("""fun\s+$name\s*\(""").find(source),
                "function $name not found",
            )
        val brace = source.indexOf('{', match.range.first)
        assertTrue(brace >= 0, "function $name has no body")
        var depth = 0
        for (i in brace until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(brace, i + 1)
                }
            }
        }
        error("unbalanced braces for $name")
    }

    /** Text of the first `mutex.withLock { ... }` in [body]. */
    private fun withLockBody(body: String): Pair<String, String> {
        val match =
            assertNotNull(
                Regex("""mutex\.withLock\s*\{""").find(body),
                "no mutex.withLock in body",
            )
        val open = body.indexOf('{', match.range.first)
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return body.substring(open, i + 1) to body.substring(i + 1)
                    }
                }
            }
        }
        error("unbalanced mutex.withLock")
    }

    /** Drop `//` comments so a sabotaged call left in a comment cannot pass. */
    private fun uncommented(kotlin: String): String =
        kotlin.lineSequence().joinToString("\n") { it.replace(Regex("//.*"), "") }

    @Test
    fun `re-enabling a disabled plugin reports missing dependencies`() {
        val body = uncommented(functionBody(managerSource(), "enablePlugin"))
        // The same skip notifyPanelsRefresh uses: a redundant enable of an
        // already-running plugin is not the user re-arming a disabled one.
        assertTrue(
            Regex(
                """if\s*\(\s*result\.isSuccess\s*&&\s*!wasAlreadyEnabled\s*\)\s*\{""" +
                    """[\s\S]{0,400}?notifyPluginActivated\(""",
            ).containsMatchIn(body),
            "enablePlugin must report missing dependencies when it actually re-enables",
        )
    }

    @Test
    fun `RBAC un-hide reports missing dependencies after a successful re-register`() {
        val body = uncommented(functionBody(managerSource(), "handleAccessChange"))
        val (insideLock, afterLock) = withLockBody(body)
        assertTrue(
            insideLock.contains("reactivated += pluginId") ||
                insideLock.contains("reactivated.add(pluginId)"),
            "handleAccessChange must record plugins it actually re-registered",
        )
        assertFalse(
            insideLock.contains("notifyPluginActivated"),
            "the report must not run while the mutex is held",
        )
        assertTrue(
            Regex("""reactivated\.forEach\s*\(::notifyPluginActivated\)""")
                .containsMatchIn(afterLock),
            "handleAccessChange must report missing dependencies after the mutex releases",
        )
    }

    @Test
    fun `a sandbox restart does not report missing dependencies`() {
        val body = uncommented(functionBody(managerSource(), "reregisterAfterRestart"))
        assertFalse(
            body.contains("notifyPluginActivated"),
            "reregisterAfterRestart is a reload, and reloads must not prompt",
        )
    }

    @Test
    fun `installPlugin does not report missing dependencies`() {
        // installPlugin also serves startup restore, bundled load and the api
        // hot-swap reload-all. Reporting here would be one dialog per plugin
        // on every launch - the reason the install reporters sit outside it.
        val body = uncommented(functionBody(managerSource(), "installPlugin"))
        assertFalse(
            body.contains("notifyPluginActivated"),
            "installPlugin must not raise the re-enable dependency prompt",
        )
    }

    @Test
    fun `the desktop layer wires re-activation to the existing reporter`() {
        val source = uncommented(setupSource())
        assertTrue(
            Regex(
                """onPluginActivated\s*=\s*\{[\s\S]{0,400}?""" +
                    """MissingDependencyReporter\.forManager\s*\(\s*dynamicPluginManager\s*\)""" +
                    """[\s\S]{0,200}?\.report\s*\(\s*manifest\s*\)""",
            ).containsMatchIn(source),
            "PluginLoaderDelegateSetup must wire onPluginActivated to MissingDependencyReporter.report",
        )
    }

    @Test
    fun `the enable delegate does not report on its own`() {
        // Reporting here AND via the manager callback would double-prompt.
        val body = uncommented(functionBody(delegateSource(), "enablePlugin"))
        assertFalse(
            body.contains("dependencyReporter") || Regex("""\.report\s*\(""").containsMatchIn(body),
            "PluginLoaderDelegateImpl.enablePlugin must not report; the manager callback does",
        )
    }
}
