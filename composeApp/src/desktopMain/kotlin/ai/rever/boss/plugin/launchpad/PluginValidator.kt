package ai.rever.boss.plugin.launchpad

import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.loader.PluginManifestReader
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipException

/**
 * Validates a plugin source directory or packaged archive (.jar / .zip).
 */
@Suppress(
    "ReturnCount",
    "TooGenericExceptionCaught",
    "LongMethod",
    "ComplexMethod",
    "NestedBlockDepth",
    "TooManyFunctions",
)
object PluginValidator {
    private val ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_-]*(?:\\.[a-zA-Z0-9_-]+)+$")
    private val MCP_TOOL_REGEX = Regex("^mcp__[a-zA-Z0-9_-]+__[a-zA-Z0-9_-]+$")
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
            val candidates =
                Files.list(libsDir).use { stream ->
                    stream
                        .filter {
                            val name = it.fileName.toString()
                            name.endsWith(".jar") &&
                                !name.endsWith("-sources.jar") &&
                                !name.endsWith("-javadoc.jar") &&
                                !name.endsWith("-plain.jar") &&
                                !name.endsWith("-all.jar")
                        }.toList()
                }
            if (candidates.size == 1) {
                return candidates.single()
            }
            if (candidates.size > 1) {
                // Disambiguate by directory name or pick the newest modified build
                val dirName =
                    inputPath.fileName
                        ?.toString()
                        ?.lowercase()
                        .orEmpty()
                val matchingByName =
                    candidates.filter {
                        val name = it.fileName.toString().lowercase()
                        dirName.isNotEmpty() && name.startsWith(dirName)
                    }
                val pool = if (matchingByName.isNotEmpty()) matchingByName else candidates
                val newest = pool.maxByOrNull { Files.getLastModifiedTime(it) }
                if (newest != null) return newest
            }
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
                jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                    ?: jar.getJarEntry("plugin.json")
                    ?: error("plugin.json missing in archive: ${file.name}")
            val content = jar.getInputStream(entry).bufferedReader().use { it.readText() }
            return launchpadJson.decodeFromString<PluginManifest>(content)
        }
    }

    private fun validateDirectory(
        dir: File,
        checks: MutableList<ValidationCheck>,
    ): ValidationResult {
        val resourceManifest = File(dir, "src/main/resources/META-INF/boss-plugin/plugin.json")
        val rootManifest = File(dir, "plugin.json")
        val manifestFile =
            when {
                resourceManifest.exists() && resourceManifest.isFile -> resourceManifest
                rootManifest.exists() && rootManifest.isFile -> rootManifest
                else -> null
            }

        if (manifestFile == null) {
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
                message = "plugin.json found at ${manifestFile.relativeTo(dir).path.replace('\\', '/')}",
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
        val archiveData =
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
            parseManifest(archiveData.manifestContent, checks)
                ?: return ValidationResult(isValid = false, checks = checks)
        validateManifestFields(manifest, checks)

        // Bytecode verification for entrypointClass
        val entrypointBytecodePath = manifest.mainClass.replace('.', '/') + ".class"
        val entrypointExists = archiveData.hasEntry(entrypointBytecodePath)
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

        if (entrypointExists) {
            val implementsPlugin = verifyImplementsPlugin(file, manifest.mainClass, archiveData)
            checks +=
                ValidationCheck(
                    name = "bytecode-implements-plugin",
                    passed = implementsPlugin,
                    message =
                        if (implementsPlugin) {
                            "Entrypoint class '${manifest.mainClass}' implements ai.rever.boss.plugin.api.Plugin"
                        } else {
                            "Entrypoint class '${manifest.mainClass}' must implement ai.rever.boss.plugin.api.Plugin"
                        },
                )
        }

        return ValidationResult(isValid = checks.all { it.passed }, checks = checks)
    }

    private data class ArchiveData(
        val manifestContent: String,
        val hasEntry: (String) -> Boolean,
        val readBytes: (String) -> ByteArray?,
    )

    private fun readArchive(
        file: File,
        checks: MutableList<ValidationCheck>,
    ): ArchiveData? =
        try {
            JarFile(file).use { jarFile ->
                val manifestEntry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                        ?: jarFile.getJarEntry("plugin.json")
                if (manifestEntry == null) {
                    checks +=
                        ValidationCheck(
                            name = "manifest-exists",
                            passed = false,
                            message = "plugin.json not found in archive: ${file.name}",
                        )
                    null
                } else {
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

                    ArchiveData(
                        manifestContent = manifestContent,
                        hasEntry = { entryName -> entryName in allEntries },
                        readBytes = { entryName ->
                            val e = jarFile.getJarEntry(entryName)
                            e?.let { jarFile.getInputStream(it).use { stream -> stream.readBytes() } }
                        },
                    )
                }
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

    /**
     * Verifies that [mainClass] implements [Plugin].
     * Uses [Class.forName] with initialize = false to avoid running static initializers (<clinit>).
     * Falls back to inspecting classfile bytes for direct interface declaration if dependencies are missing.
     */
    private fun verifyImplementsPlugin(
        jarFile: File,
        mainClass: String,
        archiveData: ArchiveData,
    ): Boolean {
        return try {
            val parentLoader = Plugin::class.java.classLoader
            // The classloader holds the jar open (notably on Windows), so it is
            // closed as soon as the assignability question is answered.
            URLClassLoader(arrayOf(jarFile.toURI().toURL()), parentLoader).use { urlClassLoader ->
                // initialize = false ensures static initializers (<clinit>) are never executed
                val clazz = Class.forName(mainClass, false, urlClassLoader)
                Plugin::class.java.isAssignableFrom(clazz)
            }
        } catch (_: Throwable) {
            // Manual classfile fallback when classloading fails due to external dependencies.
            // Note: Manual classfile parser supports direct implementations of ai.rever.boss.plugin.api.Plugin.
            val classEntryPath = mainClass.replace('.', '/') + ".class"
            val classBytes = archiveData.readBytes(classEntryPath) ?: return false
            return checkDirectInterfaceImplementation(classBytes)
        }
    }

    /**
     * Parses the constant pool and interfaces of [classBytes] to determine if it directly implements
     * ai.rever.boss.plugin.api.Plugin.
     *
     * Note: Manual classfile parser supports direct implementations of
     * ai.rever.boss.plugin.api.Plugin when classloading is unavailable.
     */
    internal fun checkDirectInterfaceImplementation(classBytes: ByteArray): Boolean {
        if (classBytes.size < 10) return false
        val buffer = ByteBuffer.wrap(classBytes)
        if (buffer.int != 0xCAFEBABE.toInt()) return false
        buffer.short // minor
        buffer.short // major
        val cpCount = buffer.short.toInt() and 0xFFFF
        val strings = arrayOfNulls<String>(cpCount)
        val classRefs = IntArray(cpCount)

        var i = 1
        while (i < cpCount) {
            when (buffer.get().toInt() and 0xFF) {
                1 -> { // Utf8
                    val length = buffer.short.toInt() and 0xFFFF
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    strings[i] = String(bytes, Charsets.UTF_8)
                }

                7 -> { // Class
                    classRefs[i] = buffer.short.toInt() and 0xFFFF
                }

                8 -> {
                    buffer.short
                }

                // String
                3, 4 -> {
                    buffer.int
                }

                // Integer, Float
                5, 6 -> { // Long, Double (takes 2 CP slots)
                    buffer.long
                    i++
                }

                9, 10, 11 -> {
                    buffer.short
                    buffer.short
                }

                // Fieldref, Methodref, InterfaceMethodref
                12 -> {
                    buffer.short
                    buffer.short
                }

                // NameAndType
                15 -> {
                    buffer.get()
                    buffer.short
                }

                // MethodHandle
                16 -> {
                    buffer.short
                }

                // MethodType
                17, 18 -> {
                    buffer.short
                    buffer.short
                }

                // Dynamic, InvokeDynamic
                19, 20 -> {
                    buffer.short
                }

                // Module, Package
                else -> {
                    return false
                }
            }
            i++
        }

        buffer.short // access flags
        buffer.short // this class
        buffer.short // super class
        val interfacesCount = buffer.short.toInt() and 0xFFFF
        repeat(interfacesCount) {
            val ifaceRef = buffer.short.toInt() and 0xFFFF
            val nameIndex = classRefs.getOrNull(ifaceRef) ?: 0
            val ifaceName = strings.getOrNull(nameIndex)
            if (ifaceName == "ai/rever/boss/plugin/api/Plugin") {
                return true
            }
        }
        return false
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
        // ID format (supports reverse-domain dotted IDs e.g. com.example.tool matching PluginManifestReader)
        val idValid = ID_REGEX.matches(manifest.pluginId)
        checks +=
            ValidationCheck(
                name = "id-format",
                passed = idValid,
                message =
                    if (idValid) {
                        "Plugin ID '${manifest.pluginId}' follows reverse domain notation"
                    } else {
                        "Plugin ID '${manifest.pluginId}' must follow reverse domain notation " +
                            "with at least one dot (e.g. com.example.my-tools)"
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

        // apiVersion compatibility
        val minApiValid =
            SemVerValidator.isValid(manifest.apiVersion) &&
                SemVerValidator.isCompatible(manifest.apiVersion, HostMeta.CURRENT_API_VERSION)
        checks +=
            ValidationCheck(
                name = "min-api-version",
                passed = minApiValid,
                message =
                    if (minApiValid) {
                        "apiVersion '${manifest.apiVersion}' is compatible " +
                            "(host: ${HostMeta.CURRENT_API_VERSION})"
                    } else {
                        "apiVersion '${manifest.apiVersion}' is incompatible or exceeds " +
                            "host API version '${HostMeta.CURRENT_API_VERSION}'"
                    },
            )

        // mainClass format
        val entrypointValid = CLASS_NAME_REGEX.matches(manifest.mainClass)
        checks +=
            ValidationCheck(
                name = "entrypoint-class",
                passed = entrypointValid,
                message =
                    if (entrypointValid) {
                        "Entrypoint class '${manifest.mainClass}' is a valid fully-qualified class name"
                    } else {
                        "Entrypoint class '${manifest.mainClass}' must be a valid fully-qualified class name"
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

        // Host loader manifest contract check
        var hostValid = true
        var hostMsg = "Manifest conforms to host PluginManifestReader contract"
        try {
            val hostManifest =
                PluginManifestReader.parseManifest(
                    launchpadJson.encodeToString(manifest),
                )
            PluginManifestReader.validateManifest(hostManifest)
        } catch (e: Exception) {
            hostValid = false
            hostMsg = "Host manifest validation failed: ${e.message ?: "invalid"}"
        }
        checks +=
            ValidationCheck(
                name = "host-manifest-contract",
                passed = hostValid,
                message = hostMsg,
            )
    }
}
