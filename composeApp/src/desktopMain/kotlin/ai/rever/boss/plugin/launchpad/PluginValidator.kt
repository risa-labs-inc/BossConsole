package ai.rever.boss.plugin.launchpad

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipException

/**
 * Validates a plugin source directory or packaged archive (.jar / .zip).
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod")
object PluginValidator {
    private val ID_REGEX = Regex("^[a-z0-9-]+$")
    private val MCP_TOOL_REGEX = Regex("^mcp__[a-z0-9_-]+__[a-z0-9_-]+$")
    private val CLASS_NAME_REGEX = Regex("""^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\.[a-zA-Z_$][a-zA-Z0-9_$]*)+$""")

    fun validate(target: File): ValidationResult {
        val checks = mutableListOf<ValidationCheck>()

        if (!target.exists()) {
            checks +=
                ValidationCheck(
                    name = "target-exists",
                    passed = false,
                    message = "Target path does not exist: ${target.absolutePath}",
                )
            return ValidationResult(isValid = false, checks = checks)
        }

        checks +=
            ValidationCheck(
                name = "target-exists",
                passed = true,
                message = "Target path exists: ${target.absolutePath}",
            )

        return if (target.isDirectory) {
            validateDirectory(target, checks)
        } else {
            validateArchive(target, checks)
        }
    }

    /**
     * Resolves the target JAR artifact for staging into BossConsole.
     * If [inputPath] is already a JAR, returns it.
     * If [inputPath] is a project directory, locates the compiled JAR in `build/libs/`.
     */
    fun resolveStagingTarget(inputPath: Path): Path {
        if (Files.isRegularFile(inputPath) && inputPath.toString().endsWith(".jar")) {
            return inputPath
        }
        val libsDir = inputPath.resolve("build").resolve("libs")
        if (Files.isDirectory(libsDir)) {
            val candidateJar =
                Files.list(libsDir).use { stream ->
                    stream
                        .filter {
                            val name = it.fileName.toString()
                            name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
                        }.findFirst()
                }
            if (candidateJar.isPresent) return candidateJar.get()
        }
        error("No compiled JAR found in target directory. Run './gradlew build' before linking.")
    }

    fun toReport(
        targetPath: String,
        result: ValidationResult,
    ): ValidationReport =
        ValidationReport(
            success = result.isValid,
            targetPath = targetPath.replace('\\', '/'),
            checksPassed = result.checks.count { it.passed },
            totalChecks = result.checks.size,
            failures = result.checks.filter { !it.passed }.map { ValidationFailure(it.name, it.message) },
            checks = result.checks,
        )

    fun readManifestFromJar(file: File): PluginManifest {
        JarFile(file).use { jar ->
            val entry =
                jar.getJarEntry("plugin.json")
                    ?: error("plugin.json missing in archive: ${file.name}")
            val content = jar.getInputStream(entry).bufferedReader().use { it.readText() }
            return launchpadJson.decodeFromString<PluginManifest>(content)
        }
    }

    private fun validateDirectory(
        dir: File,
        checks: MutableList<ValidationCheck>,
    ): ValidationResult {
        val manifestFile = File(dir, "plugin.json")
        if (!manifestFile.exists() || !manifestFile.isFile) {
            checks +=
                ValidationCheck(
                    name = "manifest-exists",
                    passed = false,
                    message = "plugin.json not found in directory: ${dir.absolutePath}",
                )
            return ValidationResult(isValid = false, checks = checks)
        }

        checks +=
            ValidationCheck(
                name = "manifest-exists",
                passed = true,
                message = "plugin.json found",
            )

        val jsonContent =
            try {
                manifestFile.readText()
            } catch (e: IOException) {
                checks +=
                    ValidationCheck(
                        name = "manifest-readable",
                        passed = false,
                        message = "Unable to read plugin.json: ${e.message}",
                    )
                return ValidationResult(isValid = false, checks = checks)
            }

        val manifest = parseManifest(jsonContent, checks) ?: return ValidationResult(isValid = false, checks = checks)
        validateManifestFields(manifest, checks)

        return ValidationResult(isValid = checks.all { it.passed }, checks = checks)
    }

    private fun validateArchive(
        file: File,
        checks: MutableList<ValidationCheck>,
    ): ValidationResult {
        val (manifestContent, hasBytecodeEntry) =
            try {
                readArchive(file, checks)
            } catch (e: Exception) {
                checks +=
                    ValidationCheck(
                        name = "archive-readable",
                        passed = false,
                        message = "Failed to read archive: ${e.message}",
                    )
                return ValidationResult(isValid = false, checks = checks)
            } ?: return ValidationResult(isValid = false, checks = checks)

        val manifest =
            parseManifest(manifestContent, checks)
                ?: return ValidationResult(isValid = false, checks = checks)
        validateManifestFields(manifest, checks)

        // Bytecode verification for entrypointClass
        val entrypointBytecodePath = manifest.entrypointClass.replace('.', '/') + ".class"
        val entrypointExists = hasBytecodeEntry(entrypointBytecodePath)
        checks +=
            ValidationCheck(
                name = "bytecode-entrypoint",
                passed = entrypointExists,
                message =
                    if (entrypointExists) {
                        "Bytecode entry '$entrypointBytecodePath' verified in archive"
                    } else {
                        "Bytecode entry '$entrypointBytecodePath' not found in archive"
                    },
            )

        return ValidationResult(isValid = checks.all { it.passed }, checks = checks)
    }

    private fun readArchive(
        file: File,
        checks: MutableList<ValidationCheck>,
    ): Pair<String, (String) -> Boolean>? {
        return try {
            val jar = JarFile(file)
            jar.use { jarFile ->
                val manifestEntry = jarFile.getJarEntry("plugin.json")
                if (manifestEntry == null) {
                    checks +=
                        ValidationCheck(
                            name = "manifest-exists",
                            passed = false,
                            message = "plugin.json not found in archive: ${file.name}",
                        )
                    return null
                }
                checks +=
                    ValidationCheck(
                        name = "manifest-exists",
                        passed = true,
                        message = "plugin.json found in archive",
                    )

                val manifestContent = jarFile.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                val allEntries =
                    jarFile
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toSet()
                Pair(manifestContent) { entryName -> entryName in allEntries }
            }
        } catch (e: ZipException) {
            checks +=
                ValidationCheck(
                    name = "archive-valid",
                    passed = false,
                    message = "Corrupt or invalid ZIP/JAR archive: ${e.message}",
                )
            null
        }
    }

    private fun parseManifest(
        jsonContent: String,
        checks: MutableList<ValidationCheck>,
    ): PluginManifest? =
        try {
            val manifest = launchpadJson.decodeFromString<PluginManifest>(jsonContent)
            checks +=
                ValidationCheck(
                    name = "manifest-json-valid",
                    passed = true,
                    message = "plugin.json parsed successfully",
                )
            manifest
        } catch (e: Exception) {
            checks +=
                ValidationCheck(
                    name = "manifest-json-valid",
                    passed = false,
                    message = "Malformed plugin.json: ${e.message ?: "invalid JSON"}",
                )
            null
        }

    private fun validateManifestFields(
        manifest: PluginManifest,
        checks: MutableList<ValidationCheck>,
    ) {
        // ID format
        val idValid = ID_REGEX.matches(manifest.id)
        checks +=
            ValidationCheck(
                name = "id-format",
                passed = idValid,
                message =
                    if (idValid) {
                        "Plugin ID '${manifest.id}' matches ^[a-z0-9-]+$"
                    } else {
                        "Plugin ID '${manifest.id}' must match pattern ^[a-z0-9-]+$"
                    },
            )

        // Version SemVer format
        val versionValid = SemVerValidator.isValid(manifest.version)
        checks +=
            ValidationCheck(
                name = "version-format",
                passed = versionValid,
                message =
                    if (versionValid) {
                        "Plugin version '${manifest.version}' is valid SemVer"
                    } else {
                        "Plugin version '${manifest.version}' must follow SemVer (e.g. 1.0.0)"
                    },
            )

        // minApiVersion compatibility
        val minApiValid =
            SemVerValidator.isValid(manifest.minApiVersion) &&
                SemVerValidator.isCompatible(manifest.minApiVersion, HostMeta.CURRENT_API_VERSION)
        checks +=
            ValidationCheck(
                name = "min-api-version",
                passed = minApiValid,
                message =
                    if (minApiValid) {
                        "minApiVersion '${manifest.minApiVersion}' is compatible " +
                            "(host: ${HostMeta.CURRENT_API_VERSION})"
                    } else {
                        "minApiVersion '${manifest.minApiVersion}' is incompatible or exceeds " +
                            "host API version '${HostMeta.CURRENT_API_VERSION}'"
                    },
            )

        // entrypointClass format
        val entrypointValid = CLASS_NAME_REGEX.matches(manifest.entrypointClass)
        checks +=
            ValidationCheck(
                name = "entrypoint-class",
                passed = entrypointValid,
                message =
                    if (entrypointValid) {
                        "Entrypoint class '${manifest.entrypointClass}' is a valid fully-qualified class name"
                    } else {
                        "Entrypoint class '${manifest.entrypointClass}' must be a valid fully-qualified class name"
                    },
            )

        // Permissions registry check
        val invalidPermissions = manifest.permissions.filter { !PluginPermission.isValid(it) }
        val permissionsValid = invalidPermissions.isEmpty()
        checks +=
            ValidationCheck(
                name = "permissions",
                passed = permissionsValid,
                message =
                    if (permissionsValid) {
                        "All declared permissions (${manifest.permissions.size}) are allowed"
                    } else {
                        "Unknown permission(s): ${invalidPermissions.joinToString(", ")}. " +
                            "Allowed: ${HostMeta.ALLOWED_PERMISSIONS}"
                    },
            )

        // MCP tools check
        val invalidTools =
            manifest.mcpTools.filter { tool ->
                !MCP_TOOL_REGEX.matches(tool.name) || tool.description.isBlank()
            }
        val toolsValid = invalidTools.isEmpty()
        checks +=
            ValidationCheck(
                name = "mcp-tools",
                passed = toolsValid,
                message =
                    if (toolsValid) {
                        if (manifest.mcpTools.isEmpty()) {
                            "No MCP tools declared (optional)"
                        } else {
                            "All ${manifest.mcpTools.size} MCP tool declarations are valid"
                        }
                    } else {
                        "Invalid MCP tool declarations: ${invalidTools.map { it.name }}"
                    },
            )
    }
}
