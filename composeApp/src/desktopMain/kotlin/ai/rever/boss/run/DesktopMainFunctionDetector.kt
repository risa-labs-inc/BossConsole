package ai.rever.boss.run

import ai.rever.boss.components.workspaces.ShellPathQuoting
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

actual fun createMainFunctionDetector(): MainFunctionDetector = DesktopMainFunctionDetector()

/**
 * Desktop implementation of MainFunctionDetector.
 * Detects main functions in Kotlin, Java, Python, JavaScript, TypeScript, Go, and Rust.
 */
class DesktopMainFunctionDetector : MainFunctionDetector {
    private val logger = BossLogger.forComponent("DesktopMainFunctionDetector")

    companion object {
        // Regex patterns for main function detection
        private val KOTLIN_MAIN_PATTERN =
            Regex(
                """^\s*(?:@JvmStatic\s+)?fun\s+main\s*\(""",
                RegexOption.MULTILINE,
            )
        private val KOTLIN_PACKAGE_PATTERN =
            Regex(
                """^\s*package\s+([\w.]+)""",
                RegexOption.MULTILINE,
            )

        private val JAVA_MAIN_PATTERN =
            Regex(
                """^\s*public\s+static\s+void\s+main\s*\(\s*String\s*\[?\s*\]?\s*\w*\s*\)""",
                RegexOption.MULTILINE,
            )
        private val JAVA_CLASS_PATTERN =
            Regex(
                """^\s*(?:public\s+)?class\s+(\w+)""",
                RegexOption.MULTILINE,
            )
        private val JAVA_PACKAGE_PATTERN =
            Regex(
                """^\s*package\s+([\w.]+)\s*;""",
                RegexOption.MULTILINE,
            )

        private val PYTHON_MAIN_PATTERN =
            Regex(
                """^if\s+__name__\s*==\s*['""]__main__['""]""",
                RegexOption.MULTILINE,
            )

        private val GO_MAIN_PATTERN =
            Regex(
                """^\s*func\s+main\s*\(\s*\)""",
                RegexOption.MULTILINE,
            )
        private val GO_PACKAGE_MAIN_PATTERN =
            Regex(
                """^\s*package\s+main\b""",
                RegexOption.MULTILINE,
            )

        private val RUST_MAIN_PATTERN =
            Regex(
                """^\s*fn\s+main\s*\(\s*\)""",
                RegexOption.MULTILINE,
            )

        // File extensions to scan
        private val SCANNABLE_EXTENSIONS =
            setOf(
                "kt",
                "kts",
                "java",
                "py",
                "js",
                "jsx",
                "mjs",
                "ts",
                "tsx",
                "go",
                "rs",
            )

        // Directories to skip
        private val SKIP_DIRECTORIES =
            setOf(
                "build",
                "node_modules",
                ".git",
                ".gradle",
                ".idea",
                "target",
                "__pycache__",
                "venv",
                ".venv",
                "dist",
                "out",
                "bin",
            )
    }

    override suspend fun scanProject(projectPath: String): List<RunConfiguration> =
        withContext(Dispatchers.IO) {
            val projectDir = File(projectPath)
            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext emptyList()
            }

            val configurations = mutableListOf<RunConfiguration>()
            scanDirectory(projectDir, projectPath, configurations)
            configurations
        }

    private fun scanDirectory(
        directory: File,
        projectPath: String,
        configurations: MutableList<RunConfiguration>,
    ) {
        directory.listFiles()?.forEach { file ->
            when {
                file.isDirectory && !SKIP_DIRECTORIES.contains(file.name) && !file.name.startsWith(".") -> {
                    scanDirectory(file, projectPath, configurations)
                }

                file.isFile && SCANNABLE_EXTENSIONS.contains(file.extension.lowercase()) -> {
                    try {
                        val content = file.readText()
                        val detected = detectInFile(file.absolutePath, content)
                        detected.forEach { mainFunc ->
                            val configName = mainFunc.toShortNameWithProject(projectPath)
                            configurations.add(
                                RunConfiguration(
                                    id = UUID.randomUUID().toString(),
                                    name = configName,
                                    type = RunConfigurationType.MAIN_FUNCTION,
                                    filePath = file.absolutePath,
                                    lineNumber = mainFunc.lineNumber,
                                    language = mainFunc.language,
                                    command = generateCommand(mainFunc, projectPath),
                                    // Run at the script's own project root (where the
                                    // generated command — e.g. relative ./gradlew — must
                                    // execute), not the scanned workspace path which may
                                    // differ and leave the command without its ./gradlew.
                                    workingDirectory = findProjectRoot(file.absolutePath),
                                    isAutoDetected = true,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.EDITOR,
                            "Error scanning file",
                            mapOf("path" to file.absolutePath, "error" to e.toString()),
                        )
                    }
                }
            }
        }
    }

    override fun detectInFile(
        filePath: String,
        content: String,
        language: Language?,
    ): List<DetectedMainFunction> {
        val detectedLanguage = language ?: Language.fromFileName(filePath)
        return when (detectedLanguage) {
            Language.KOTLIN -> detectKotlinMain(filePath, content)
            Language.JAVA -> detectJavaMain(filePath, content)
            Language.PYTHON -> detectPythonMain(filePath, content)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> detectJsMain(filePath, detectedLanguage)
            Language.GO -> detectGoMain(filePath, content)
            Language.RUST -> detectRustMain(filePath, content)
            Language.UNKNOWN -> emptyList()
        }
    }

    private fun detectKotlinMain(
        filePath: String,
        content: String,
    ): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package name
        val packageMatch = KOTLIN_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        // Track if we're inside a multi-line string (triple quotes)
        var inMultiLineString = false

        // Find main functions
        lines.forEachIndexed { index, line ->
            // Count triple quotes to track multi-line string state
            val tripleQuoteCount = line.windowed(3).count { it == "\"\"\"" }
            if (tripleQuoteCount % 2 == 1) {
                inMultiLineString = !inMultiLineString
            }

            // Only detect main if not inside a multi-line string
            if (!inMultiLineString && KOTLIN_MAIN_PATTERN.containsMatchIn(line)) {
                // Also skip if the line looks like it's inside a regular string
                val trimmedLine = line.trim()
                if (!trimmedLine.startsWith("\"") && !trimmedLine.startsWith("text =")) {
                    results.add(
                        DetectedMainFunction(
                            lineNumber = index,
                            functionName = "main",
                            className = null,
                            packageName = packageName,
                            language = Language.KOTLIN,
                            filePath = filePath,
                        ),
                    )
                }
            }
        }

        return results
    }

    private fun detectJavaMain(
        filePath: String,
        content: String,
    ): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package and class name
        val packageMatch = JAVA_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        val classMatch = JAVA_CLASS_PATTERN.find(content)
        val className = classMatch?.groupValues?.get(1)

        // Find main methods
        lines.forEachIndexed { index, line ->
            if (JAVA_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(
                    DetectedMainFunction(
                        lineNumber = index,
                        functionName = "main",
                        className = className,
                        packageName = packageName,
                        language = Language.JAVA,
                        filePath = filePath,
                    ),
                )
            }
        }

        return results
    }

    private fun detectPythonMain(
        filePath: String,
        content: String,
    ): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (PYTHON_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(
                    DetectedMainFunction(
                        lineNumber = index,
                        functionName = "__main__",
                        className = null,
                        packageName = null,
                        language = Language.PYTHON,
                        filePath = filePath,
                    ),
                )
            }
        }

        return results
    }

    private fun detectJsMain(
        filePath: String,
        language: Language,
    ): List<DetectedMainFunction> {
        // For JS/TS, we consider files with certain patterns as entry points
        // This is a simplified detection - real detection would check package.json
        val results = mutableListOf<DetectedMainFunction>()

        // Check if it's a typical entry point file
        val fileName = File(filePath).name.lowercase()
        val isEntryPoint =
            fileName in
                listOf(
                    "index.js",
                    "index.ts",
                    "index.jsx",
                    "index.tsx",
                    "main.js",
                    "main.ts",
                    "app.js",
                    "app.ts",
                    "server.js",
                    "server.ts",
                )

        if (isEntryPoint) {
            results.add(
                DetectedMainFunction(
                    lineNumber = 0,
                    functionName = "entry",
                    className = null,
                    packageName = null,
                    language = language,
                    filePath = filePath,
                ),
            )
        }

        return results
    }

    private fun detectGoMain(
        filePath: String,
        content: String,
    ): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()

        // Go requires both package main and func main()
        if (!GO_PACKAGE_MAIN_PATTERN.containsMatchIn(content)) {
            return results
        }

        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            if (GO_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(
                    DetectedMainFunction(
                        lineNumber = index,
                        functionName = "main",
                        className = null,
                        packageName = "main",
                        language = Language.GO,
                        filePath = filePath,
                    ),
                )
            }
        }

        return results
    }

    private fun detectRustMain(
        filePath: String,
        content: String,
    ): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (RUST_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(
                    DetectedMainFunction(
                        lineNumber = index,
                        functionName = "main",
                        className = null,
                        packageName = null,
                        language = Language.RUST,
                        filePath = filePath,
                    ),
                )
            }
        }

        return results
    }

    override fun generateCommand(
        detected: DetectedMainFunction,
        projectPath: String,
    ): String = generateCommand(detected, projectPath, ShellUtils.isWindows)

    /**
     * Platform-explicit form of [generateCommand], for the same reason as
     * [ShellUtils.escapeForDoubleQuotes]'s overload: [ShellUtils.isWindows] is fixed at
     * class-load from the real OS, so the interface method can only ever reach one branch
     * on a given host. Taking the platform as a parameter exercises both the PowerShell
     * and the POSIX form from any host, rather than relying on the CI matrix.
     *
     * @param forWindows Build for Windows PowerShell when true, POSIX shells otherwise
     * @param tempDirPath Where a compile-then-run fallback puts its build output
     */
    internal fun generateCommand(
        detected: DetectedMainFunction,
        projectPath: String,
        forWindows: Boolean,
        tempDirPath: String = System.getProperty("java.io.tmpdir"),
    ): String {
        // Find the actual project root by walking up from the file's directory
        val fileDir = File(detected.filePath).parentFile
        val projectDir = findProjectRootInternal(fileDir) ?: File(projectPath)

        return when (detected.language) {
            Language.KOTLIN -> generateKotlinCommand(detected, projectDir, forWindows, tempDirPath)
            Language.JAVA -> generateJavaCommand(detected, projectDir, forWindows)
            Language.PYTHON -> generatePythonCommand(detected, forWindows)
            Language.JAVASCRIPT -> generateJavaScriptCommand(detected, forWindows)
            Language.TYPESCRIPT -> generateTypeScriptCommand(detected, forWindows)
            Language.GO -> generateGoCommand(detected, forWindows)
            Language.RUST -> generateRustCommand(detected, projectDir, forWindows, tempDirPath)
            Language.UNKNOWN -> "echo 'Unknown language'"
        }
    }

    /**
     * Public interface implementation - finds project root from a file path.
     */
    override fun findProjectRoot(filePath: String): String {
        val fileDir = File(filePath).parentFile
        return findProjectRootInternal(fileDir)?.absolutePath ?: fileDir?.absolutePath ?: filePath
    }

    /**
     * Find the project root by walking up the directory tree looking for project markers.
     * Markers: gradlew, build.gradle.kts, pom.xml, Cargo.toml, package.json, .git
     */
    private fun findProjectRootInternal(startDir: File?): File? {
        var current = startDir
        while (current != null && current.exists()) {
            // Check for Gradle project (prioritize this)
            if (File(current, "gradlew").exists() || File(current, "gradlew.bat").exists()) {
                return current
            }
            // Check for standalone Gradle build file
            if (File(current, "build.gradle.kts").exists() || File(current, "build.gradle").exists()) {
                // Only use this if no gradlew found above - might be a submodule
                if (current.parentFile?.let { findProjectRootInternal(it) } == null) {
                    return current
                }
            }
            // Check for Maven project
            if (File(current, "pom.xml").exists()) {
                return current
            }
            // Check for Cargo project
            if (File(current, "Cargo.toml").exists()) {
                return current
            }
            // Check for Node.js project
            if (File(current, "package.json").exists()) {
                return current
            }
            // Check for Git root (last resort)
            if (File(current, ".git").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun generateKotlinCommand(
        detected: DetectedMainFunction,
        projectDir: File,
        forWindows: Boolean,
        tempDirPath: String,
    ): String {
        val filePath = detected.filePath

        // For .kts scripts, use kotlinc -script
        if (filePath.endsWith(".kts")) {
            return "kotlinc -script ${quoteArg(filePath, forWindows)}"
        }

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // Fallback: compile and run with kotlinc (for simple standalone files)
        val jarName = File(filePath).nameWithoutExtension.replace("'", "_")
        val jarPath = quoteArg(tempFilePath(tempDirPath, "$jarName.jar", forWindows), forWindows)
        val compileCmd = "kotlinc ${quoteArg(filePath, forWindows)} -include-runtime -d $jarPath"
        val runCmd = "java -jar $jarPath"
        return chain(forWindows, compileCmd, runCmd)
    }

    private fun generateJavaCommand(
        detected: DetectedMainFunction,
        projectDir: File,
        forWindows: Boolean,
    ): String {
        val filePath = detected.filePath

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // For Maven projects, use mvn exec:java
        if (File(projectDir, "pom.xml").exists()) {
            val className = buildClassName(detected)
            // Class names are validated by compiler, so they should be safe
            return "mvn exec:java -Dexec.mainClass=${quoteArg(className, forWindows)}"
        }

        // Fallback: Java 11+ single-file source-code execution
        return "java ${quoteArg(filePath, forWindows)}"
    }

    /**
     * Detect the Gradle module name from the file path.
     * Looks for common patterns like /moduleName/src/main/... or /moduleName/src/...Main/...
     */
    private fun detectModuleName(
        filePath: String,
        projectDir: File,
    ): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath =
            file.absolutePath
                .removePrefix(projectPath)
                .removePrefix(File.separator)
                .removePrefix("/") // Remove Unix separator if present
                .removePrefix("\\") // Remove Windows separator if present

        // Pattern: moduleName/src/... (split by platform separator)
        val parts =
            relativePath
                .split(File.separator, "/", "\\")
                .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialModule = parts[0]
            // Verify it's a valid module by checking for build.gradle(.kts)
            val moduleDir = File(projectDir, potentialModule)
            if (moduleDir.isDirectory &&
                (File(moduleDir, "build.gradle.kts").exists() || File(moduleDir, "build.gradle").exists())
            ) {
                return potentialModule
            }
        }

        return null
    }

    /**
     * Build the fully qualified class name from detected function info.
     */
    private fun buildClassName(detected: DetectedMainFunction): String {
        val pkg = detected.packageName
        val cls = detected.className
        return when {
            pkg != null && cls != null -> "$pkg.$cls"
            cls != null -> cls
            else -> "Main"
        }
    }

    private fun hasGradleWrapper(projectDir: File): Boolean =
        File(projectDir, "gradlew").exists() ||
            File(projectDir, "gradlew.bat").exists()

    /**
     * Quote a single argument so it survives as one word and stays inert - the shell's
     * own expansions never see it. Delegates to [ShellPathQuoting], the repo's one
     * definition of shell literal quoting (see `CommandProcessor.quotePath`, which picks
     * the same two strategies by the host OS): a POSIX shell takes `'…'` with an embedded
     * `'` written `'\''`, PowerShell takes `'…'` with an embedded `'` doubled. The two
     * forms are not interchangeable - the POSIX escape is a *parse error* in PowerShell,
     * which is what this used to emit on Windows.
     *
     * Not limited to paths despite that name: `DesktopGitService` quotes branch names
     * with it, and the class and crate names here are the same kind of literal argument.
     */
    private fun quoteArg(
        value: String,
        forWindows: Boolean,
    ): String = if (forWindows) ShellPathQuoting.powershell(value) else ShellPathQuoting.posix(value)

    private fun generatePythonCommand(
        detected: DetectedMainFunction,
        forWindows: Boolean,
    ): String = "python3 ${quoteArg(detected.filePath, forWindows)}"

    private fun generateJavaScriptCommand(
        detected: DetectedMainFunction,
        forWindows: Boolean,
    ): String = "node ${quoteArg(detected.filePath, forWindows)}"

    private fun generateTypeScriptCommand(
        detected: DetectedMainFunction,
        forWindows: Boolean,
    ): String = "npx ts-node ${quoteArg(detected.filePath, forWindows)}"

    private fun generateGoCommand(
        detected: DetectedMainFunction,
        forWindows: Boolean,
    ): String = "go run ${quoteArg(detected.filePath, forWindows)}"

    /**
     * Join a compile step to its run step. The platform-explicit twin of
     * [ShellUtils.chainCommands], which can only reach the host's own separator.
     */
    private fun chain(
        forWindows: Boolean,
        vararg commands: String,
    ): String = commands.joinToString(ShellUtils.separatorFor(forWindows))

    /**
     * Where a compile-then-run fallback writes its build output.
     *
     * The separator comes from [forWindows] rather than [File], whose own is fixed by the
     * host: joining with [File] would make the POSIX branch emit a `\` when generated on
     * Windows, and the seam exists precisely so either branch can be produced anywhere.
     */
    private fun tempFilePath(
        tempDirPath: String,
        fileName: String,
        forWindows: Boolean,
    ): String = tempDirPath.trimEnd('/', '\\') + (if (forWindows) "\\" else "/") + fileName

    private fun generateRustCommand(
        detected: DetectedMainFunction,
        projectDir: File,
        forWindows: Boolean,
        tempDirPath: String,
    ): String {
        val filePath = detected.filePath

        // For Cargo projects, use cargo run
        if (File(projectDir, "Cargo.toml").exists()) {
            // Check if it's in a workspace member
            val moduleName = detectCargoModule(filePath, projectDir)
            if (moduleName != null) {
                // Module names are validated by Cargo, but escape for safety
                return "cargo run -p ${quoteArg(moduleName, forWindows)}"
            }
            return "cargo run"
        }

        // Fallback: Compile and run the specific Rust file directly
        val outputName = File(filePath).nameWithoutExtension.replace("'", "_")
        val binaryPath = quoteArg(tempFilePath(tempDirPath, outputName, forWindows), forWindows)
        val compileCmd = "rustc ${quoteArg(filePath, forWindows)} -o $binaryPath"
        return chain(forWindows, compileCmd, binaryPath)
    }

    /**
     * Detect Cargo workspace member name from file path.
     */
    private fun detectCargoModule(
        filePath: String,
        projectDir: File,
    ): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath =
            file.absolutePath
                .removePrefix(projectPath)
                .removePrefix(File.separator)
                .removePrefix("/") // Remove Unix separator if present
                .removePrefix("\\") // Remove Windows separator if present

        // Pattern: crate-name/src/... (split by platform separator)
        val parts =
            relativePath
                .split(File.separator, "/", "\\")
                .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialCrate = parts[0]
            // Verify it's a valid crate by checking for Cargo.toml
            val crateDir = File(projectDir, potentialCrate)
            if (crateDir.isDirectory && File(crateDir, "Cargo.toml").exists()) {
                return potentialCrate
            }
        }

        return null
    }
}
