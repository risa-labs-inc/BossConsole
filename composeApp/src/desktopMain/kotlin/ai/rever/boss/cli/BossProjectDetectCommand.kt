package ai.rever.boss.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Static project-type detector.
 *
 * Walks a directory and reads marker files (`build.gradle.kts`,
 * `package.json`, `Cargo.toml`, `pyproject.toml`, `go.mod`,
 * `pom.xml`, ...) to identify the languages, build tools, package
 * managers, and test frameworks in use. Pure read - no execution, no
 * network, no `git` invocations.
 *
 * The output is a JSON-friendly shape so an agent or CI step can decide
 * "is this a Kotlin project" without parsing free text. Findings are
 * sorted alphabetically, with marker paths included as evidence.
 *
 * Usage:
 *   boss project-detect [--path <dir>] [--json]
 *
 * Exit codes: 0 for a report (including no findings). 1 if the path is
 * missing or not a directory.
 */
class BossProjectDetectCommand : CliktCommand(name = "project-detect") {
    override fun help(context: Context) = "Identifies languages, build tools, and test frameworks in a directory"

    private val detector = ProjectDetector()

    private val path by option("--path", help = "Directory to inspect (defaults to current directory)").default(".")
    private val json by option("--json", help = "Output the report as JSON").flag(default = false)

    override fun run() {
        val root =
            try {
                File(path).canonicalFile
            } catch (_: IOException) {
                echo("Error: cannot resolve path: $path", err = true)
                throw ProgramResult(1)
            }
        if (!root.exists()) {
            echo("Error: path does not exist: $path", err = true)
            throw ProgramResult(1)
        }
        if (!root.isDirectory) {
            echo("Error: not a directory: $path", err = true)
            throw ProgramResult(1)
        }
        val report = detector.detect(root)
        render(report, json)
    }

    private fun render(
        report: ProjectReport,
        asJson: Boolean,
    ) {
        if (asJson) {
            echo(ProjectDetectJson.encode(report))
        } else {
            echo("Project at ${report.root}")
            if (report.languages.isEmpty()) {
                echo("  languages: (none detected)")
            } else {
                echo("  languages:")
                for (lang in report.languages) echo("    - $lang")
            }
            if (report.buildTools.isEmpty()) {
                echo("  build tools: (none detected)")
            } else {
                echo("  build tools:")
                for (bt in report.buildTools) echo("    - $bt")
            }
            if (report.packageManagers.isEmpty()) {
                echo("  package managers: (none detected)")
            } else {
                echo("  package managers:")
                for (pm in report.packageManagers) echo("    - $pm")
            }
            if (report.testFrameworks.isEmpty()) {
                echo("  test frameworks: (none detected)")
            } else {
                echo("  test frameworks:")
                for (tf in report.testFrameworks) echo("    - $tf")
            }
            if (report.frameworks.isEmpty()) {
                echo("  frameworks: (none detected)")
            } else {
                echo("  frameworks:")
                for (fw in report.frameworks) echo("    - $fw")
            }
            if (report.markers.isEmpty()) {
                echo("  marker files: (none detected)")
            } else {
                echo("  marker files:")
                for (m in report.markers) echo("    - $m")
            }
        }
    }
}

data class ProjectReport(
    val root: String,
    val languages: List<String>,
    val buildTools: List<String>,
    val packageManagers: List<String>,
    val testFrameworks: List<String>,
    val frameworks: List<String>,
    val markers: List<String>,
)

/**
 * Pure detector. The [Marker] table maps known filenames to findings,
 * while source extensions and package dependencies add language and
 * framework findings.
 *
 * A path that exists at any depth within the project is counted; we do
 * not require the marker to be at the root, because a Gradle project
 * checked into a monorepo subdirectory is still a Gradle project.
 */
@Suppress("TooManyFunctions")
class ProjectDetector {
    private companion object {
        const val MAX_SCAN_DEPTH = 24
        const val MAX_SCAN_ENTRIES = 20_000
        const val MAX_PACKAGE_JSON_BYTES = 1_048_576L
        val IGNORED_DIRECTORIES =
            setOf(".git", ".gradle", ".venv", "build", "dist", "node_modules", "out", "target", "vendor")
    }

    /**
     * Each entry pairs a filename against a list of contributions.
     * The name is matched against the project's file tree; contributions
     * accumulate in [ProjectReport] in the order they are encountered.
     */
    private data class Marker(
        val path: String,
        val contributes: List<Contribution>,
    )

    /**
     * One finding. A single marker can contribute to several categories,
     * so [Contribution] is a single key/value pair, not a list.
     */
    private data class Contribution(
        val bucket: String,
        val value: String,
    )

    private val markers: List<Marker> =
        listOf(
            // Kotlin / Java ecosystem
            Marker(
                path = "build.gradle.kts",
                contributes =
                    listOf(
                        Contribution("languages", "Kotlin"),
                        Contribution("buildTools", "Gradle (Kotlin DSL)"),
                    ),
            ),
            Marker(
                path = "build.gradle",
                contributes =
                    listOf(
                        Contribution("languages", "Groovy"),
                        Contribution("buildTools", "Gradle (Groovy DSL)"),
                    ),
            ),
            Marker(
                path = "settings.gradle.kts",
                contributes = listOf(Contribution("buildTools", "Gradle (Kotlin DSL)")),
            ),
            Marker(
                path = "settings.gradle",
                contributes = listOf(Contribution("buildTools", "Gradle (Groovy DSL)")),
            ),
            Marker(
                path = "pom.xml",
                contributes =
                    listOf(
                        Contribution("languages", "Java"),
                        Contribution("buildTools", "Maven"),
                        Contribution("packageManagers", "Maven"),
                    ),
            ),
            Marker(
                path = "gradle.lockfile",
                contributes = listOf(Contribution("packageManagers", "Gradle dependency lock")),
            ),
            // Node / TypeScript
            Marker(
                path = "package.json",
                contributes =
                    listOf(
                        Contribution("languages", "JavaScript"),
                        Contribution("packageManagers", "npm"),
                    ),
            ),
            Marker(
                path = "package-lock.json",
                contributes = listOf(Contribution("packageManagers", "npm")),
            ),
            Marker(
                path = "yarn.lock",
                contributes = listOf(Contribution("packageManagers", "Yarn")),
            ),
            Marker(
                path = "pnpm-lock.yaml",
                contributes = listOf(Contribution("packageManagers", "pnpm")),
            ),
            Marker(
                path = "tsconfig.json",
                contributes = listOf(Contribution("languages", "TypeScript")),
            ),
            // Rust
            Marker(
                path = "Cargo.toml",
                contributes =
                    listOf(
                        Contribution("languages", "Rust"),
                        Contribution("buildTools", "Cargo"),
                        Contribution("packageManagers", "Cargo"),
                    ),
            ),
            // Go
            Marker(
                path = "go.mod",
                contributes =
                    listOf(
                        Contribution("languages", "Go"),
                        Contribution("buildTools", "Go modules"),
                    ),
            ),
            // Python
            Marker(
                path = "pyproject.toml",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pip"),
                    ),
            ),
            Marker(
                path = "requirements.txt",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pip"),
                    ),
            ),
            Marker(
                path = "setup.py",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "setuptools"),
                    ),
            ),
            Marker(
                path = "Pipfile",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pipenv"),
                    ),
            ),
            Marker(
                path = "poetry.lock",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "poetry"),
                    ),
            ),
            // Ruby
            Marker(
                path = "Gemfile",
                contributes =
                    listOf(
                        Contribution("languages", "Ruby"),
                        Contribution("packageManagers", "Bundler"),
                    ),
            ),
            // JavaScript / TypeScript frameworks (common ones, picked up from package.json deps)
            Marker(
                path = "next.config.js",
                contributes = listOf(Contribution("frameworks", "Next.js")),
            ),
            Marker(
                path = "next.config.ts",
                contributes = listOf(Contribution("frameworks", "Next.js")),
            ),
            Marker(
                path = "nuxt.config.ts",
                contributes = listOf(Contribution("frameworks", "Nuxt")),
            ),
            Marker(
                path = "angular.json",
                contributes = listOf(Contribution("frameworks", "Angular")),
            ),
            Marker(
                path = "vue.config.js",
                contributes = listOf(Contribution("frameworks", "Vue")),
            ),
            Marker(
                path = "svelte.config.js",
                contributes = listOf(Contribution("frameworks", "Svelte")),
            ),
            // Test frameworks (marker-based; reading the manifest for the
            // test runner is in [detectFromPackageJson] below)
            Marker(
                path = "pytest.ini",
                contributes = listOf(Contribution("testFrameworks", "pytest")),
            ),
            Marker(
                path = "conftest.py",
                contributes = listOf(Contribution("testFrameworks", "pytest")),
            ),
        )

    fun detect(root: File): ProjectReport {
        val tree = scan(root)
        val sources = tree.filter { it.isFile }
        val filesByName = sources.groupBy { it.name }
        val contributions = mutableMapOf<String, MutableSet<String>>()
        val foundMarkers = mutableListOf<String>()

        for (marker in markers) {
            applyMarker(marker, root, filesByName, contributions, foundMarkers)
        }

        val languages = contributions.getOrPut("languages") { mutableSetOf() }
        if (sources.any { it.extension == "kt" || it.extension == "kts" }) languages.add("Kotlin")
        if (sources.any { it.extension == "java" }) languages.add("Java")
        if (sources.any { it.extension == "ts" || it.extension == "tsx" }) languages.add("TypeScript")
        val hasJavaScript = sources.any { it.extension in setOf("js", "jsx", "mjs", "cjs") }
        if (hasJavaScript) languages.add("JavaScript")
        if (sources.any { it.name == "tsconfig.json" } && !hasJavaScript) languages.remove("JavaScript")

        // Special-case: package.json dependencies are inspected for known
        // test runners and frameworks that don't have a marker of their own.
        for (packageJson in filesByName["package.json"].orEmpty()) {
            contributionsOfPackageJson(packageJson, contributions)
        }

        // Test files and Cargo test directories supply additional evidence.
        applyFileBasedTestFrameworks(tree, contributions)

        return ProjectReport(
            root = root.absolutePath,
            languages = sorted(contributions["languages"]),
            buildTools = sorted(contributions["buildTools"]),
            packageManagers = sorted(contributions["packageManagers"]),
            testFrameworks = sorted(contributions["testFrameworks"]),
            frameworks = sorted(contributions["frameworks"]),
            markers = foundMarkers.sorted(),
        )
    }

    private fun applyMarker(
        marker: Marker,
        root: File,
        filesByName: Map<String, List<File>>,
        contributions: MutableMap<String, MutableSet<String>>,
        foundMarkers: MutableList<String>,
    ) {
        val matches = filesByName[marker.path].orEmpty()
        if (matches.isEmpty()) return
        foundMarkers += matches.map { it.relativePath(root) }
        for (c in marker.contributes) {
            contributions.getOrPut(c.bucket) { mutableSetOf() }.add(c.value)
        }
    }

    private fun applyFileBasedTestFrameworks(
        tree: List<File>,
        contributions: MutableMap<String, MutableSet<String>>,
    ) {
        if (tree.any { it.isFile && it.name.endsWith("_test.go") }) {
            contributions.getOrPut("testFrameworks") { mutableSetOf() }.add("Go testing")
        }
        val cargoProjects =
            tree
                .filter { it.isFile && it.name == "Cargo.toml" }
                .map { it.parentFile.toPath() }
                .toSet()
        val testDirectories = tree.filter { it.isDirectory && it.name == "tests" }.map { it.toPath() }
        val hasCargoTests = findCargoTests(cargoProjects, testDirectories).first
        if (hasCargoTests) {
            contributions.getOrPut("testFrameworks") { mutableSetOf() }.add("Cargo test")
        }
    }

    /** Check test directories against their ancestors, bounded by scan depth rather than crate count. */
    internal fun findCargoTests(
        cargoProjects: Set<Path>,
        testDirectories: List<Path>,
    ): Pair<Boolean, Int> {
        var ancestorChecks = 0
        for (directory in testDirectories) {
            var ancestor: Path? = directory
            while (ancestor != null) {
                ancestorChecks++
                if (ancestor in cargoProjects) return true to ancestorChecks
                ancestor = ancestor.parent
            }
        }
        return false to ancestorChecks
    }

    private fun sorted(set: MutableSet<String>?): List<String> = set?.sorted() ?: emptyList()

    private fun File.relativePath(root: File): String =
        root
            .toPath()
            .relativize(toPath())
            .toString()
            .replace('\\', '/')

    /** One bounded snapshot serves every marker, avoiding repeated traversal and symlink loops. */
    private fun scan(root: File): List<File> =
        root
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { directory ->
                directory == root ||
                    (!Files.isSymbolicLink(directory.toPath()) && directory.name !in IGNORED_DIRECTORIES)
            }.take(MAX_SCAN_ENTRIES)
            .toList()

    /**
     * Inspect package.json for test runners and frameworks that don't
     * ship a marker file of their own. Invalid or oversized manifests
     * contribute no dependency-based findings.
     */
    private fun contributionsOfPackageJson(
        file: File,
        sink: MutableMap<String, MutableSet<String>>,
    ) {
        val deps = readPackageJsonDependencies(file) ?: return
        applyPackageJsonTestRunners(deps, sink)
        applyPackageJsonFrameworks(deps, sink)
    }

    private fun readPackageJsonDependencies(file: File): Set<String>? =
        try {
            if (file.length() > MAX_PACKAGE_JSON_BYTES) return null
            val bytes = file.inputStream().use { it.readNBytes(MAX_PACKAGE_JSON_BYTES.toInt() + 1) }
            if (bytes.size > MAX_PACKAGE_JSON_BYTES) return null
            val obj =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(bytes.toString(Charsets.UTF_8))
                    .let { it as? kotlinx.serialization.json.JsonObject ?: return null }
            obj["dependencies"].let { (it as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty() } +
                obj["devDependencies"].let { (it as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty() }
        } catch (_: Exception) {
            // Malformed package.json is not a hard failure; we just lose the
            // dependency-driven findings for that project.
            null
        }

    private fun applyPackageJsonTestRunners(
        deps: Set<String>,
        sink: MutableMap<String, MutableSet<String>>,
    ) {
        if ("jest" in deps) {
            sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Jest")
        }
        if ("vitest" in deps || deps.any { it.startsWith("@vitest/") }) {
            sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Vitest")
        }
        if (deps.contains("mocha")) {
            sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Mocha")
        }
        if ("@playwright/test" in deps) {
            sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Playwright")
        }
        if (deps.contains("cypress")) {
            sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Cypress")
        }
    }

    private fun applyPackageJsonFrameworks(
        deps: Set<String>,
        sink: MutableMap<String, MutableSet<String>>,
    ) {
        val frameworkByDep =
            mapOf(
                "react" to "React",
                "vue" to "Vue",
                "@angular/core" to "Angular",
                "svelte" to "Svelte",
                "next" to "Next.js",
                "nuxt" to "Nuxt",
                "express" to "Express",
            )
        for ((dep, name) in frameworkByDep) {
            if (deps.contains(dep)) {
                sink.getOrPut("frameworks") { mutableSetOf() }.add(name)
            }
        }
    }
}

private object ProjectDetectJson {
    fun encode(report: ProjectReport): String =
        buildJsonObject {
            put("root", report.root)
            put("languages", buildJsonArray { report.languages.forEach { add(it) } })
            put("buildTools", buildJsonArray { report.buildTools.forEach { add(it) } })
            put("packageManagers", buildJsonArray { report.packageManagers.forEach { add(it) } })
            put("testFrameworks", buildJsonArray { report.testFrameworks.forEach { add(it) } })
            put("frameworks", buildJsonArray { report.frameworks.forEach { add(it) } })
            put("markers", buildJsonArray { report.markers.forEach { add(it) } })
        }.toString()
}
