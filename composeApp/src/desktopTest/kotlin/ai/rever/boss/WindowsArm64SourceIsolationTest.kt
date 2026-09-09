package ai.rever.boss

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Windows ARM64 compiles with less source and fewer modules than every other target — see
 * the `isWindowsArm64Build` branches in `composeApp/build.gradle.kts` — because boss-ipc's
 * protoc ships no win-arm64 binary. Source that survives may therefore not reference what
 * was dropped: not the excluded directories, and not the excluded modules.
 *
 * A static reference compiles everywhere *except* there, and no PR job builds that target,
 * so it surfaces during a release after the other four platforms have already published.
 * It has happened twice: `RemoteWidgetRendererColorTest` (noted at the `desktopTest`
 * exclusion in the build file), and the self-healing settings UI, which imported three
 * types from `ai.rever.boss.kernel` that turned out not to need boss-ipc at all — they now
 * live in `ai.rever.boss.config`.
 *
 * These tests run on every platform, so the mistake fails a PR instead of a release.
 */
class WindowsArm64SourceIsolationTest {
    /**
     * Packages unavailable on Windows ARM64: the excluded host directories, plus the
     * modules dropped from the dependency list. Module packages are the sturdier half —
     * they hold regardless of where anyone puts a directory.
     */
    private val unavailablePackages =
        listOf(
            "ai.rever.boss.kernel.",
            "ai.rever.boss.plugin.remote.",
            "ai.rever.boss.ipc.",
            "ai.rever.boss.ui.sdk.",
            "ai.rever.boss.process.",
        )

    /** Path fragments dropped from `desktopMain` on Windows ARM64. */
    private val desktopMainExclusions =
        listOf(
            "/kernel/",
            "/plugin/remote/",
            "/plugin/OutOfProcessPluginSpawnerImpl.kt",
            "/plugin/PluginStateBridge.kt",
        )

    /** The `desktopTest` mirror: the two directories, plus tests naming boss-ipc types. */
    private val desktopTestExclusions =
        listOf(
            "/kernel/",
            "/plugin/remote/",
            "/plugin/IpcCompatibilityTest.kt",
            "/plugin/PluginStoreSetupIpcGateTest.kt",
            "/plugin/PluginStateDeltaTest.kt",
        )

    /** Excluded for reasons unrelated to the platform, so not part of the mirror. */
    private val unconditionalExclusions = listOf("/SkipListDriftTest.kt")

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }

    private fun sourcesSurviving(
        root: File,
        sourceSet: String,
        excluded: List<String>,
    ): List<File> =
        File(root, "composeApp/src/$sourceSet/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val path = file.path.replace(File.separatorChar, '/')
                excluded.any { path.contains(it) }
            }.toList()

    @Test
    fun `source kept in the Windows ARM64 build imports nothing dropped from it`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")

        val scanned =
            sourcesSurviving(root, "desktopMain", desktopMainExclusions) +
                sourcesSurviving(root, "desktopTest", desktopTestExclusions + unconditionalExclusions)

        // Deliberately not a skip: a guard that silently passes when it can't see the tree
        // is how this kind of check rots into decoration.
        assertTrue(scanned.size > 100, "only ${scanned.size} files scanned - the walk is not seeing the source")

        val offenders =
            scanned
                .filter { file -> importsUnavailablePackage(file.readLines(), unavailablePackages) }
                .map { it.name }
                .sorted()

        assertTrue(
            offenders.isEmpty(),
            "These files stay in the Windows ARM64 build but import a package dropped from it " +
                "($unavailablePackages). Reach it via Class.forName, or move what they need out " +
                "of the excluded package: $offenders",
        )
    }

    /**
     * The path exclusions are directory-based, so a file *declaring* an excluded package
     * from somewhere else survives the build and then fails to resolve its own
     * dependencies. Keeping package and directory aligned is what makes the path-based
     * exclusion equivalent to a package-based one.
     */
    @Test
    fun `files declaring an excluded package live in the excluded directory`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")

        val misplaced =
            listOf("desktopMain", "desktopTest")
                .flatMap { File(root, "composeApp/src/$it/kotlin").walkTopDown() }
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val declares =
                        file
                            .readLines()
                            .firstOrNull { it.startsWith("package ") }
                            ?.removePrefix("package ")
                            ?.trim()
                    val path = file.path.replace(File.separatorChar, '/')
                    (declares == "ai.rever.boss.kernel" && !path.contains("/kernel/")) ||
                        (declares == "ai.rever.boss.plugin.remote" && !path.contains("/plugin/remote/"))
                }.map { it.name }

        assertTrue(
            misplaced.isEmpty(),
            "These declare a package excluded on Windows ARM64 but sit outside the directory the " +
                "build excludes, so they survive a build their dependencies do not: $misplaced",
        )
    }

    /**
     * The lists above duplicate `build.gradle.kts`. A fifth exclusion added there would
     * leave this guard quietly under-covering while staying green — the same shape as the
     * bug it exists to prevent.
     */
    @Test
    fun `the exclusion list matches the build file`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val buildFile = File(root, "composeApp/build.gradle.kts").readText()

        val declared =
            Regex("""kotlin\.exclude\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(buildFile)
                .flatMap { match -> Regex(""""([^"]+)"""").findAll(match.groupValues[1]).map { it.groupValues[1] } }
                .map { it.removePrefix("**").removeSuffix("**") }
                .toSet()

        assertTrue(declared.isNotEmpty(), "found no kotlin.exclude(...) entries - the parse is wrong, not the build")
        assertEquals(
            (desktopMainExclusions + desktopTestExclusions + unconditionalExclusions).toSet(),
            declared,
            "build.gradle.kts and this test disagree about what the build excludes",
        )
    }

    companion object {
        /** Extracted so the walk can be refactored without quietly neutering the check. */
        internal fun importsUnavailablePackage(
            lines: List<String>,
            unavailable: List<String>,
        ): Boolean =
            lines.any { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("import ") && unavailable.any { trimmed.startsWith("import $it") }
            }
    }
}
