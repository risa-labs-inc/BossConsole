package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.DevPluginArtifacts
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginScaffolder
import ai.rever.boss.plugin.launchpad.PluginValidator
import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.utils.ReloadResult
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Scaffolds a new third-party plugin project.
 * Usage: boss plugin init <name> [--template <type>] [--dir <path>] [--force] [--json]
 */
@Suppress("TooGenericExceptionCaught")
class BossPluginInitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) = "Scaffolds a new plugin project"

    private val logger = BossLogger.forComponent("BossPluginInitCommand")

    val name by argument(help = "Plugin name (e.g. 'my-tools')")
    val template by option(
        "-t",
        "--template",
        help = "Plugin template: mcp-tool, ui-panel, background-service, full (default: mcp-tool)",
    ).default("mcp-tool")
    val dir by option("-d", "--dir", help = "Target directory path")
    val force by option("-f", "--force", help = "Overwrite non-empty target directory").flag(default = false)
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val targetDir = if (dir != null) File(dir!!).absoluteFile else File(name).absoluteFile

        try {
            val result =
                PluginScaffolder.scaffold(
                    name = name,
                    templateName = template,
                    targetDir = targetDir,
                    force = force,
                )

            if (json) {
                val payload =
                    buildJsonObject {
                        put("status", "scaffolded")
                        put("pluginId", result.pluginId)
                        put("template", template)
                        put("targetDir", result.targetDirectory.absolutePath.replace('\\', '/'))
                        put(
                            "files",
                            buildJsonArray {
                                result.filesCreated.forEach { add(it.name) }
                            },
                        )
                    }
                echo(payload.toString())
            } else {
                echo(
                    "[✓] Plugin '${result.pluginId}' scaffolded successfully at " +
                        result.targetDirectory.absolutePath,
                )
                echo("Template: $template")
                echo("Generated files:")
                result.filesCreated.forEach { echo("  - ${it.name}") }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to scaffold plugin"
            logger.error(LogCategory.SYSTEM, "Failed to scaffold plugin: $errorMsg", error = e)
            if (json) {
                val errPayload =
                    buildJsonObject {
                        put("status", "error")
                        put("error", errorMsg)
                    }
                echo(errPayload.toString())
            } else {
                echo("Error: $errorMsg", err = true)
            }
            throw ProgramResult(1)
        }
    }
}

/**
 * Validates a plugin directory or packaged archive (.jar / .zip).
 * Usage: boss plugin validate [<path>] [--json]
 */
class BossPluginValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validates a plugin manifest and bytecode structure"

    private val logger = BossLogger.forComponent("BossPluginValidateCommand")

    val path by argument(help = "Path to plugin directory or JAR/ZIP archive")
        .path(canBeDir = true, canBeFile = true)
        .default(Paths.get("."))
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val target = path.toFile().absoluteFile
        val result = PluginValidator.validate(target)
        val report = PluginValidator.toReport(target.absolutePath, result)

        if (json) {
            val jsonText = launchpadJson.encodeToString(report)
            echo(jsonText)
            if (!report.success) {
                throw ProgramResult(1)
            }
        } else {
            echo("Validating plugin at ${target.absolutePath}...")
            result.checks.forEach { check ->
                val mark = if (check.passed) "[✓]" else "[✗]"
                echo("$mark ${check.name}: ${check.message}")
            }
            if (report.success) {
                echo("\n[✓] Validation passed (${report.checksPassed}/${report.totalChecks} checks passed)")
            } else {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Validation failed with ${report.failures.size} errors at ${target.absolutePath}",
                )
                echo("\nValidation failed with ${report.failures.size} errors:", err = true)
                report.failures.forEach { echo("  [✗] ${it.checkName}: ${it.message}", err = true) }
                throw ProgramResult(1)
            }
        }
    }
}

/**
 * Stages and links a plugin to BossConsole and dispatches a live hot-reload signal.
 * Usage: boss plugin link [<path>] [--json]
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
class BossPluginLinkCommand : CliktCommand(name = "link") {
    override fun help(context: Context) = "Links a local plugin into BossConsole development environment"

    private val logger = BossLogger.forComponent("BossPluginLinkCommand")

    val path by argument(help = "Path to plugin directory or JAR/ZIP archive")
        .path(canBeDir = true, canBeFile = true)
        .default(Paths.get("."))
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val inputPath = path.toAbsolutePath()

        if (!Files.exists(inputPath)) {
            val msg = "Target path does not exist: $inputPath"
            logger.error(LogCategory.SYSTEM, msg)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }

        val targetJarPath =
            try {
                PluginValidator.resolveStagingTarget(inputPath)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to resolve staging target JAR"
                logger.error(LogCategory.SYSTEM, msg, error = e)
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", msg)
                        }.toString(),
                    )
                } else {
                    echo("Error: $msg", err = true)
                }
                throw ProgramResult(1)
            }

        val targetJarFile = targetJarPath.toFile()
        val validation = PluginValidator.validate(targetJarFile)
        if (!validation.isValid) {
            val errMsg = "Cannot link invalid plugin at ${targetJarFile.absolutePath}"
            logger.error(LogCategory.SYSTEM, errMsg)
            val report = PluginValidator.toReport(targetJarFile.absolutePath, validation)
            if (json) {
                echo(launchpadJson.encodeToString(report))
            } else {
                echo("$errMsg:", err = true)
                validation.checks.filter { !it.passed }.forEach {
                    echo("  [✗] ${it.name}: ${it.message}", err = true)
                }
            }
            throw ProgramResult(1)
        }

        val manifest =
            try {
                PluginValidator.readManifestFromJar(targetJarFile)
            } catch (e: Exception) {
                val msg = "Failed to extract plugin manifest from JAR: ${e.message}"
                logger.error(LogCategory.SYSTEM, msg, error = e)
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", msg)
                        }.toString(),
                    )
                } else {
                    echo("Error: $msg", err = true)
                }
                throw ProgramResult(1)
            }
        val pluginId = manifest.pluginId

        // Version-rotated staging to prevent Windows file locking collisions
        val pluginDevBase = File(DevPluginArtifacts.stagingRoot(), pluginId)
        val timestamp = System.currentTimeMillis()
        val versionDir = File(pluginDevBase, "v$timestamp")
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            val msg = "Failed to create version staging directory: ${versionDir.absolutePath}"
            logger.error(LogCategory.SYSTEM, msg)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }
        val partJar = File(versionDir, "$pluginId.jar.part")
        val stagedJar = File(versionDir, "$pluginId.jar")

        try {
            targetJarFile.copyTo(partJar, overwrite = true)
            try {
                Files.move(
                    partJar.toPath(),
                    stagedJar.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (
                @Suppress("SwallowedException") e: AtomicMoveNotSupportedException,
            ) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "ATOMIC_MOVE not supported, falling back to REPLACE_EXISTING",
                    mapOf("pluginId" to pluginId),
                )
                Files.move(
                    partJar.toPath(),
                    stagedJar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            val msg = "Failed to stage plugin in ${stagedJar.absolutePath}: ${e.message}"
            logger.error(LogCategory.SYSTEM, msg, error = e)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }

        DevPluginArtifacts.pruneStagingHistory(pluginDevBase, maxVersionsToKeep = 3)

        val reloadResult = SingleInstanceManager.reloadDevPlugin(pluginId)
        when (reloadResult) {
            is ReloadResult.Success -> {
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "linked_and_reloaded")
                            put("pluginId", pluginId)
                            put("running", true)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                        }
                    echo(payload.toString())
                } else {
                    echo(
                        "[✓] Plugin '$pluginId' staged at ${stagedJar.absolutePath} " +
                            "and reload signal confirmed by BossConsole.",
                    )
                }
            }

            is ReloadResult.HostOffline -> {
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "staged")
                            put("pluginId", pluginId)
                            put("running", false)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                            put("notice", "BossConsole is offline; plugin staged for next launch")
                        }
                    echo(payload.toString())
                } else {
                    echo(
                        "[✓] Plugin '$pluginId' staged at ${stagedJar.absolutePath}. " +
                            "BossConsole is offline; plugin staged for next launch.",
                    )
                }
            }

            is ReloadResult.Failed -> {
                val msg = "Plugin '$pluginId' staged, but reload failed: ${reloadResult.reason}"
                logger.error(LogCategory.SYSTEM, msg)
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "linked_reload_failed")
                            put("pluginId", pluginId)
                            put("running", true)
                            put("error", reloadResult.reason)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                        }
                    echo(payload.toString())
                    throw ProgramResult(1)
                } else {
                    echo(msg, err = true)
                    throw ProgramResult(1)
                }
            }
        }
    }
}
