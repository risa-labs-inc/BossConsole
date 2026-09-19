package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins this module's test-home isolation: the Test tasks point `user.home` at a fresh
 * `<build>/test-home/<task-name>` directory (see build.gradle.kts), so the object under test
 * cannot create or write the developer's real ~/.boss.
 *
 * Why a task-level redirect rather than a reset hook: `rootDir` is a `by lazy` process-global
 * resolved from `user.home` at first access, so by the time any test code runs the path is
 * already fixed to whatever home the JVM started with. Issue #818 is what happens without the
 * redirect - BossDirectoriesTest merely reading `rootDir` creates the directory (the lazy does
 * the mkdirs), so a test run on a machine that had never launched the app left a real ~/.boss
 * behind, and every assertion in the suite bound to the developer's own home.
 *
 * The real home is still recoverable inside the test JVM: Gradle's systemProperty("user.home",
 * ...) redirect does not touch environment variables, so HOME (or USERPROFILE on Windows) keeps
 * pointing at it. That is what lets these tests tell the two homes apart and fail loudly if the
 * redirect is ever removed, instead of the suite silently going back to touching the real one.
 */
class BossDirectoriesHomeIsolationTest {
    /**
     * The home the JVM was actually started in - HOME, or USERPROFILE where HOME is not
     * exported. Aborts the test where neither is visible rather than passing vacuously or
     * failing on a runner that genuinely has no ambient home to protect.
     */
    private fun ambientHome(): File {
        val path = System.getenv("HOME") ?: System.getenv("USERPROFILE")
        if (path == null) {
            throw TestAbortedException("no ambient HOME/USERPROFILE is exported to compare against")
        }
        return File(path)
    }

    @Test
    fun `rootDir resolves to the task's fresh test home, not the developer's real one`() {
        val ambient = ambientHome()

        // The redirect is the whole guarantee; fail loudly if it ever comes off the Test task.
        assertTrue(
            File(System.getProperty("user.home")) != ambient,
            "user.home still points at the real home (${ambient.absolutePath}); the Test task in " +
                "build.gradle.kts must redirect it to the build's test-home directory",
        )
        // Loading rootDir is what BossDirectoriesTest does on every run, and the lazy creates
        // the directory, so this is what keeps that suite from doing it to the real home.
        assertTrue(
            BossDirectories.rootDir != File(ambient, BossDirectories.rootDir.name),
            "rootDir resolved to the developer's real ${BossDirectories.rootDir.name} under " +
                "${ambient.absolutePath}",
        )
    }

    @Test
    fun `a write through BossDirectories lands in the test home and misses the real one`() {
        val ambient = ambientHome()

        val probe = BossDirectories.resolve("boss-directories-isolation-probe.txt")
        probe.writeText("sentinel")

        val redirectedHome = System.getProperty("user.home")
        assertTrue(
            probe.absolutePath.startsWith(redirectedHome),
            "expected the probe at ${probe.absolutePath} to land under the redirected home " +
                "($redirectedHome)",
        )
        val realProbe = File(File(ambient, BossDirectories.rootDir.name), probe.name)
        assertFalse(
            realProbe.exists(),
            "the probe written through BossDirectories exists at ${realProbe.absolutePath}; the " +
                "suite is writing into the developer's real home",
        )
    }
}
