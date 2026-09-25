package ai.rever.boss.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossProjectDetectTest {
    private val detector = ProjectDetector()
    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File {
        val d = Files.createTempDirectory("boss-project-detect-test").toFile()
        tempDirs += d
        return d
    }

    private fun writeFile(
        parent: File,
        relativePath: String,
        content: String = "",
    ): File {
        val target = File(parent, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun commandOutput(vararg args: String): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(captured))
            createBossCLI().parse(listOf("project-detect") + args)
        } finally {
            System.setOut(original)
        }
        return captured.toString().trim()
    }

    @Test
    fun `kotlin gradle project is detected`() {
        val root = tempDir()
        writeFile(root, "build.gradle.kts")
        writeFile(root, "settings.gradle.kts")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages, "expected Kotlin in ${report.languages}")
        assertTrue("Gradle (Kotlin DSL)" in report.buildTools, "expected Gradle in ${report.buildTools}")
        assertTrue(report.markers.contains("build.gradle.kts"))
    }

    @Test
    fun `maven project is detected`() {
        val root = tempDir()
        writeFile(root, "pom.xml")
        val report = detector.detect(root)
        assertTrue("Java" in report.languages)
        assertTrue("Maven" in report.buildTools)
        assertTrue("Maven" in report.packageManagers)
    }

    @Test
    fun `rust cargo project is detected`() {
        val root = tempDir()
        writeFile(root, "Cargo.toml")
        writeFile(root, "Cargo.lock")
        val report = detector.detect(root)
        assertTrue("Rust" in report.languages)
        assertTrue("Cargo" in report.buildTools)
        assertTrue("Cargo" in report.packageManagers)
    }

    @Test
    fun `node project with package-lock is detected as npm`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "package-lock.json")
        val report = detector.detect(root)
        assertTrue("JavaScript" in report.languages)
        assertTrue("npm" in report.packageManagers)
    }

    @Test
    fun `node project with yarn lockfile is detected as Yarn`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "yarn.lock")
        val report = detector.detect(root)
        assertTrue("Yarn" in report.packageManagers)
        assertFalse("package-lock.json" in report.markers, "yarn project has no package-lock.json marker")
    }

    @Test
    fun `pnpm and yarn lockfiles are both reported`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "yarn.lock")
        writeFile(root, "pnpm-lock.yaml")
        val report = detector.detect(root)
        assertTrue("pnpm" in report.packageManagers)
        assertTrue("Yarn" in report.packageManagers, "all three lockfiles can coexist, report all")
    }

    @Test
    fun `typescript is detected via tsconfig`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "tsconfig.json")
        val report = detector.detect(root)
        assertTrue("TypeScript" in report.languages)
        assertFalse(
            "JavaScript" in report.languages,
            "TypeScript configuration alone does not imply JavaScript sources",
        )
    }

    @Test
    fun `python project with poetry is detected`() {
        val root = tempDir()
        writeFile(root, "pyproject.toml")
        writeFile(root, "poetry.lock")
        val report = detector.detect(root)
        assertTrue("Python" in report.languages)
        assertTrue("poetry" in report.packageManagers)
    }

    @Test
    fun `pytest is detected as a test framework`() {
        val root = tempDir()
        writeFile(root, "pytest.ini")
        val report = detector.detect(root)
        assertTrue("pytest" in report.testFrameworks)
    }

    @Test
    fun `conftest file is a pytest marker`() {
        val root = tempDir()
        writeFile(root, "tests/conftest.py")
        val report = detector.detect(root)
        assertTrue("pytest" in report.testFrameworks)
        assertTrue("tests/conftest.py" in report.markers)
    }

    @Test
    fun `go project with _test dot go files is detected with Go testing`() {
        val root = tempDir()
        writeFile(root, "go.mod")
        writeFile(root, "main.go")
        writeFile(root, "main_test.go")
        val report = detector.detect(root)
        assertTrue("Go" in report.languages)
        assertTrue("Go testing" in report.testFrameworks, "_test.go implies Go testing")
    }

    @Test
    fun `package-json dependencies feed test framework detection`() {
        val root = tempDir()
        writeFile(
            root,
            "package.json",
            """{"name":"x","devDependencies":{"jest":"^29.0.0","@playwright/test":"^1.0.0"}}""",
        )
        val report = detector.detect(root)
        assertTrue("Jest" in report.testFrameworks)
        assertTrue("Playwright" in report.testFrameworks)
    }

    @Test
    fun `package-json dependencies feed framework detection`() {
        val root = tempDir()
        writeFile(
            root,
            "package.json",
            """{"name":"x","dependencies":{"react":"^18.0.0","next":"^14.0.0"}}""",
        )
        val report = detector.detect(root)
        assertTrue("React" in report.frameworks)
        assertTrue("Next.js" in report.frameworks)
    }

    @Test
    fun `angular is detected from angular dot json`() {
        val root = tempDir()
        writeFile(root, "angular.json")
        val report = detector.detect(root)
        assertTrue("Angular" in report.frameworks)
    }

    @Test
    fun `nested build file in a subdirectory still counts the project`() {
        val root = tempDir()
        writeFile(root, "subdir/build.gradle.kts")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages)
        assertTrue("Gradle (Kotlin DSL)" in report.buildTools)
        assertTrue("subdir/build.gradle.kts" in report.markers)
    }

    @Test
    fun `ignored directories do not contribute markers or test frameworks`() {
        val root = tempDir()
        writeFile(root, "node_modules/foo/Cargo.toml")
        writeFile(root, "vendor/bar/package.json", """{"dependencies":{"react":"1"}}""")
        writeFile(root, "build/go.mod")
        writeFile(root, ".git/tests/example_test.go")
        val report = detector.detect(root)
        assertEquals(emptyList(), report.languages)
        assertEquals(emptyList(), report.frameworks)
        assertEquals(emptyList(), report.testFrameworks)
        assertEquals(emptyList(), report.markers)
    }

    @Test
    fun `scan depth limit excludes distant markers`() {
        val root = tempDir()
        val distant = List(25) { "level$it" }.joinToString("/")
        writeFile(root, "$distant/Cargo.toml")
        assertFalse("Rust" in detector.detect(root).languages)
    }

    @Test
    fun `directory symlink does not import markers outside the project`() {
        val root = tempDir()
        val external = tempDir()
        writeFile(external, "Cargo.toml")
        val link = File(root, "external").toPath()
        try {
            Files.createSymbolicLink(link, external.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }
        try {
            assertFalse("Rust" in detector.detect(root).languages)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `pseudo marker filenames do not imply test frameworks`() {
        val root = tempDir()
        writeFile(root, "scripts/go-test")
        writeFile(root, "scripts/cargo-test")
        val report = detector.detect(root)
        assertEquals(emptyList(), report.testFrameworks)
        assertEquals(emptyList(), report.markers)
    }

    @Test
    fun `nested package manifests contribute frameworks and test runners`() {
        val root = tempDir()
        writeFile(
            root,
            "frontend/package.json",
            """{"dependencies":{"react":"1"},"devDependencies":{"jest":"1","vitest":"1"}}""",
        )
        val report = detector.detect(root)
        assertEquals(listOf("Jest", "Vitest"), report.testFrameworks)
        assertEquals(listOf("React"), report.frameworks)
        assertTrue("frontend/package.json" in report.markers)
    }

    @Test
    fun `jest helper package does not imply jest and scoped vitest package does imply vitest`() {
        val root = tempDir()
        writeFile(
            root,
            "package.json",
            """{"devDependencies":{"jest-environment-jsdom":"1","@vitest/ui":"1"}}""",
        )
        assertEquals(listOf("Vitest"), detector.detect(root).testFrameworks)
    }

    @Test
    fun `mocha and cypress are both detected from package dependencies`() {
        val root = tempDir()
        writeFile(root, "package.json", """{"devDependencies":{"mocha":"1","cypress":"1"}}""")
        assertEquals(listOf("Cypress", "Mocha"), detector.detect(root).testFrameworks)
    }

    @Test
    fun `cargo tests in root tests directory are detected`() {
        val root = tempDir()
        writeFile(root, "Cargo.toml")
        writeFile(root, "tests/example.rs")
        assertTrue("Cargo test" in detector.detect(root).testFrameworks)
    }

    @Test
    fun `cargo test lookup stays within a per-directory ancestor budget for many crates`() {
        val root = File("workspace").toPath()
        val cargoProjects = (1..2_000).map { root.resolve("crate$it") }.toSet()
        val testDirectories = (1..2_000).map { root.resolve("other$it/tests") }

        val (found, checks) = detector.findCargoTests(cargoProjects, testDirectories)

        assertFalse(found)
        assertTrue(checks <= testDirectories.size * 3, "unexpected Cargo lookup work: $checks checks")
        assertTrue(detector.findCargoTests(cargoProjects, listOf(root.resolve("crate2/tests"))).first)
    }

    @Test
    fun `malformed and oversized package manifests do not fail detection`() {
        val root = tempDir()
        writeFile(root, "package.json", "{invalid")
        writeFile(root, "nested/package.json", " ".repeat(1_048_577))
        val report = detector.detect(root)
        assertTrue("JavaScript" in report.languages)
        assertEquals(emptyList(), report.frameworks)
    }

    @Test
    fun `command json output has stable fields and empty arrays`() {
        val root = tempDir()
        writeFile(root, "README.md")
        val output = commandOutput("--path", root.absolutePath, "--json")
        val obj = Json.parseToJsonElement(output).jsonObject
        assertEquals(
            setOf("root", "languages", "buildTools", "packageManagers", "testFrameworks", "frameworks", "markers"),
            obj.keys,
        )
        assertEquals(root.canonicalPath, obj.getValue("root").jsonPrimitive.content)
        for (key in obj.keys - "root") assertEquals(JsonArray(emptyList()), obj.getValue(key).jsonArray)
    }

    @Test
    fun `default path reports the canonical current directory`() {
        val obj = Json.parseToJsonElement(commandOutput("--json")).jsonObject
        assertEquals(File(".").canonicalPath, obj.getValue("root").jsonPrimitive.content)
    }

    @Test
    fun `command json output reports fixture findings`() {
        val root = tempDir()
        writeFile(root, "frontend/package.json", """{"dependencies":{"react":"1"},"devDependencies":{"vitest":"1"}}""")
        val obj = Json.parseToJsonElement(commandOutput("--path", root.absolutePath, "--json")).jsonObject
        assertEquals(
            "React",
            obj
                .getValue("frameworks")
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "Vitest",
            obj
                .getValue("testFrameworks")
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "frontend/package.json",
            obj
                .getValue("markers")
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `human output reports empty markers`() {
        val root = tempDir()
        assertTrue("marker files: (none detected)" in commandOutput("--path", root.absolutePath))
    }

    @Test
    fun `missing path and file path exit with usage code`() {
        val root = tempDir()
        val file = writeFile(root, "README.md")
        for (path in listOf(File(root, "missing"), file)) {
            val exit =
                assertFailsWith<ProgramResult> {
                    createBossCLI().parse(listOf("project-detect", "--path", path.path))
                }
            assertEquals(1, exit.statusCode)
        }
    }

    @Test
    fun `a directory symlink cycle does not block detection`() {
        val root = tempDir()
        val source = File(root, "source").apply { mkdirs() }
        writeFile(source, "build.gradle.kts")
        val link = File(source, "loop").toPath()
        try {
            Files.createSymbolicLink(link, root.toPath())
        } catch (_: UnsupportedOperationException) {
            return // Some Windows test hosts do not grant symlink creation.
        } catch (_: java.nio.file.FileSystemException) {
            return
        }
        try {
            assertTrue("Kotlin" in detector.detect(root).languages)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `a directory with no markers reports empty buckets`() {
        val root = tempDir()
        writeFile(root, "README.md")
        val report = detector.detect(root)
        assertEquals(emptyList(), report.languages)
        assertEquals(emptyList(), report.buildTools)
        assertEquals(emptyList(), report.packageManagers)
        assertEquals(emptyList(), report.testFrameworks)
        assertEquals(emptyList(), report.frameworks)
    }

    @Test
    fun `mixed java and kotlin gradle project lists both languages`() {
        val root = tempDir()
        writeFile(root, "build.gradle.kts")
        writeFile(root, "src/main/kotlin/Main.kt")
        writeFile(root, "src/main/java/Main.java")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages)
        assertTrue("Java" in report.languages)
    }

    @Test
    fun `kotlin gradle build does not imply java source`() {
        val root = tempDir()
        writeFile(root, "build.gradle.kts")
        writeFile(root, "src/main/kotlin/Main.kt")
        assertFalse("Java" in detector.detect(root).languages)
    }

    @Test
    fun `groovy gradle build with kotlin source reports kotlin`() {
        val root = tempDir()
        writeFile(root, "settings.gradle")
        writeFile(root, "build.gradle")
        writeFile(root, "src/main/kotlin/Main.kt")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages)
        assertTrue("Gradle (Groovy DSL)" in report.buildTools)
        assertTrue("settings.gradle" in report.markers)
    }

    @Test
    fun `marker files list is sorted alphabetically`() {
        val root = tempDir()
        writeFile(root, "Cargo.toml")
        writeFile(root, "build.gradle.kts")
        writeFile(root, "package.json")
        val report = detector.detect(root)
        assertEquals(report.markers.sorted(), report.markers, "markers must be sorted")
    }
}
