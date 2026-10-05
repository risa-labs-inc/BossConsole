package ai.rever.boss.plugin.browser

import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * The hand-off between [ChromiumToolkitPreload] and the native preload agent
 * (`native/toolkit-preload-agent`), which loads the toolkit from `Agent_OnLoad` on the NEXT
 * launch, before the JVM has any GC or JIT thread that could `free()` during the zone swap.
 *
 * The agent decides nothing: it loads the `load` paths only when every `guard` still matches the
 * disk byte for byte (size, inode, mtime seconds, permission bits), every `absent` path is still
 * absent and the `stamp` names this build. So this side lists every file the engine decision read.
 * A path the agent loads that JxBrowser then does not would put a second libtoolkit image in the
 * process (duplicate Objective-C classes), which is why the agent errs towards skipping.
 *
 * The format is parsed by C, so it stays line-based with the path always last; a path containing
 * a newline cannot be represented and makes [render] return null.
 */
internal object ToolkitPreloadManifest {
    /** Under the BOSS data root; the agent derives the same root from `boss.dev.mode` / `BOSS_DEV_MODE`. */
    const val FILE_NAME = "boss-chromium.preload"

    const val HEADER = "boss-toolkit-preload 1"

    /** Set by the launcher to `<app version>+jxbrowser-<version>`; no stamp means no agent. */
    const val STAMP_PROPERTY = "boss.toolkit.preload.stamp"

    /** Written by the agent: `loaded <n> threads=<t> <path>|<path>` or `skipped <reason>`. */
    const val AGENT_RESULT_PROPERTY = "boss.toolkit.preload.agent"

    /** One file the engine decision depended on, as `stat` reports it. */
    data class Guard(
        val path: String,
        val size: Long,
        val inode: Long,
        val mtimeSeconds: Long,
        val mode: Int,
    )

    /** What the agent did before `main`, as reported through [AGENT_RESULT_PROPERTY]. */
    sealed interface AgentResult {
        /** The launcher has no agent (not macOS, a dev run, or an older build). */
        data object NotInstalled : AgentResult

        data class Skipped(
            val reason: String,
        ) : AgentResult

        data class Loaded(
            val paths: List<String>,
            val threads: Int?,
        ) : AgentResult
    }

    @Suppress("ReturnCount") // One early return per shape of report.
    fun parseAgentResult(raw: String?): AgentResult {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return AgentResult.NotInstalled
        if (value.startsWith("skipped ")) return AgentResult.Skipped(value.removePrefix("skipped "))
        val loaded = LOADED.matchEntire(value) ?: return AgentResult.Skipped("unrecognised report: $value")
        val paths = loaded.groupValues[3].split('|').filter { it.isNotEmpty() }
        // toIntOrNull: the property is command-line text, and a count that overflows Int must not throw.
        val count = loaded.groupValues[1].toIntOrNull()
        if (count == null || paths.size != count) return AgentResult.Skipped("unrecognised report: $value")
        return AgentResult.Loaded(paths, loaded.groupValues[2].toIntOrNull())
    }

    private val LOADED = Regex("loaded (\\d+) threads=(-?\\d+) (.+)")

    /** How the agent's work relates to what this launch resolved. */
    enum class AgentVerdict {
        /** No agent in this launcher, or it reported a skip: nothing was loaded before main. */
        NOTHING_LOADED,

        /** The agent loaded exactly the libraries this launch resolved. */
        MATCHED,

        /** The agent loaded a leading part of them and then failed; the rest load in the JVM. */
        PARTIAL,

        /** The agent loaded something this launch would not have: a second image is coming. */
        DIFFERENT,
    }

    fun verdict(
        result: AgentResult,
        expected: List<String>,
    ): AgentVerdict =
        when (result) {
            AgentResult.NotInstalled, is AgentResult.Skipped -> {
                AgentVerdict.NOTHING_LOADED
            }

            is AgentResult.Loaded -> {
                when {
                    result.paths == expected -> {
                        AgentVerdict.MATCHED
                    }

                    result.paths.size < expected.size && expected.take(result.paths.size) == result.paths -> {
                        AgentVerdict.PARTIAL
                    }

                    else -> {
                        AgentVerdict.DIFFERENT
                    }
                }
            }
        }

    /**
     * The guard for [path], or null when the file does not exist (the caller records it as
     * absent instead, so its later appearance also invalidates the manifest).
     */
    fun guardFor(path: Path): Guard? {
        if (!Files.exists(path)) return null
        val attrs = Files.readAttributes(path, "unix:size,ino,lastModifiedTime,mode")
        return Guard(
            path = path.toString(),
            size = attrs["size"] as Long,
            inode = attrs["ino"] as Long,
            // FileTime.to(SECONDS) truncates, matching the agent's st_mtimespec.tv_sec.
            mtimeSeconds = (attrs["lastModifiedTime"] as FileTime).to(TimeUnit.SECONDS),
            mode = (attrs["mode"] as Int) and PERMISSION_BITS,
        )
    }

    private const val PERMISSION_BITS = 0xFFF // 07777: the agent masks st_mode the same way

    /**
     * The manifest text, or null when it cannot be represented safely: no loads, a relative path,
     * or a path with a line break in it.
     */
    fun render(
        stamp: String,
        guards: List<Guard>,
        absent: List<String>,
        loads: List<String>,
    ): String? {
        if (!representable(stamp, guards, absent, loads)) return null
        return buildString {
            appendLine(HEADER)
            appendLine("stamp $stamp")
            guards.forEach { g ->
                appendLine("guard ${g.size} ${g.inode} ${g.mtimeSeconds} ${Integer.toOctalString(g.mode)} ${g.path}")
            }
            absent.forEach { appendLine("absent $it") }
            loads.forEach { appendLine("load $it") }
        }
    }

    private fun representable(
        stamp: String,
        guards: List<Guard>,
        absent: List<String>,
        loads: List<String>,
    ): Boolean {
        val paths = guards.map { it.path } + absent + loads
        val stampOk = stamp.isNotBlank() && stamp.none { it == '\n' || it == '\r' }
        val pathsOk = paths.all { it.startsWith("/") && it.none { c -> c == '\n' || c == '\r' } }
        // The agent refuses a load target that is not also guarded; refuse here first.
        val loadsGuarded = loads.isNotEmpty() && loads.all { load -> guards.any { it.path == load } }
        return stampOk && pathsOk && loadsGuarded
    }

    /**
     * Every file [FluckEngine.resolveEngineDir] and `ChromiumAutoDownloader.isChromiumInstalled`
     * read to accept [engineDir], plus the engine settings that can change which version is
     * required. Missing files go to the absent list.
     */
    fun inputsFor(
        engineDir: Path,
        executableName: String,
        libraries: List<File>,
        settingsFile: File,
        pendingDir: File,
    ): Pair<List<Guard>, List<String>> {
        val bundle = engineDir.resolve("$executableName.app/Contents")
        val watched =
            // Canonical, because that is the string the in-JVM preload hands System.load and the
            // agent refuses a load target that is not guarded under the same string.
            libraries.map { it.canonicalFile.toPath() } +
                listOf(
                    engineDir.resolve("version.txt"),
                    engineDir.resolve("executable.name"),
                    bundle.resolve("MacOS/$executableName"),
                    bundle.resolve("Info.plist"),
                    settingsFile.toPath(),
                )
        val guards = mutableListOf<Guard>()
        val absent = mutableListOf(pendingDir.path)
        watched.forEach { path -> guardFor(path)?.let(guards::add) ?: absent.add(path.toString()) }
        return guards to absent
    }
}

/**
 * This launch's side of the agent hand-off: what the agent reported, and where to record the
 * decision for the next launch. Inert when [stamp] is null, i.e. the launcher carries no agent
 * (not macOS, a Gradle run, an older build): then nothing is read or written.
 *
 * Never throws; a failure to record only costs the next launch its early preload.
 */
internal class AgentHandOff(
    private val stamp: String?,
    val agentResult: ToolkitPreloadManifest.AgentResult,
    private val manifestFile: File,
    private val settingsFile: File,
    private val pendingDir: File,
) {
    private val logger by lazy { BossLogger.forComponent("ChromiumToolkitPreload") }

    /** Records [libraries] of the engine at [engineDir] for the agent to load next launch. */
    @Suppress("ReturnCount") // Inert without a stamp; unchanged records are not rewritten.
    fun remember(
        engineDir: Path,
        executableName: String,
        libraries: List<File>,
    ) {
        val stamp = stamp ?: return
        runCatching {
            val (guards, absent) =
                ToolkitPreloadManifest.inputsFor(engineDir, executableName, libraries, settingsFile, pendingDir)
            val text =
                ToolkitPreloadManifest.render(stamp, guards, absent, libraries.map { it.canonicalPath })
                    ?: return forget("engine paths cannot be represented in the manifest")
            if (manifestFile.isFile && manifestFile.readText() == text) return
            manifestFile.parentFile?.mkdirs()
            manifestFile.atomicWriteText(text)
        }.onFailure { e ->
            logger.warn(LogCategory.BROWSER, "Could not record the native toolkit for the next launch", error = e)
            forget("recording failed")
        }
    }

    /**
     * Records [libraries] when every one of them loaded; a partial load means the next launch
     * should not repeat it before the JVM even starts.
     */
    fun recordOutcome(
        engineDir: Path,
        executableName: String,
        libraries: List<File>,
        loaded: Int,
    ) {
        if (loaded == libraries.size) {
            remember(engineDir, executableName, libraries)
        } else {
            forget("the in-JVM preload did not complete")
        }
    }

    /**
     * Logs what the agent did before `main`. A mismatch cannot be undone - a dylib cannot be
     * unloaded - and JxBrowser will load [expected] regardless, so it is reported rather than acted
     * on; the manifest rewritten after this launch makes the next one consistent.
     */
    fun reportAgent(expected: List<String>) {
        val result = agentResult
        when (ToolkitPreloadManifest.verdict(result, expected)) {
            ToolkitPreloadManifest.AgentVerdict.NOTHING_LOADED -> {
                if (result is ToolkitPreloadManifest.AgentResult.Skipped) {
                    logger.info(LogCategory.BROWSER, "Native toolkit agent skipped", mapOf("reason" to result.reason))
                }
            }

            ToolkitPreloadManifest.AgentVerdict.MATCHED -> {
                val loaded = result as ToolkitPreloadManifest.AgentResult.Loaded
                logger.info(
                    LogCategory.BROWSER,
                    "Native toolkit loaded by the agent before the JVM started its threads",
                    mapOf("libraries" to loaded.paths.size, "threads" to (loaded.threads ?: -1)),
                )
            }

            ToolkitPreloadManifest.AgentVerdict.PARTIAL -> {
                // The swap happened with the first library, so the race is still closed; the
                // remaining ones load in the JVM below. Worth a warning, not a hunt for duplicates.
                logger.warn(
                    LogCategory.BROWSER,
                    "Native toolkit agent loaded only part of the toolkit; the JVM loads the rest",
                    mapOf("agent" to describe(result), "resolved" to expected.joinToString("|")),
                )
            }

            ToolkitPreloadManifest.AgentVerdict.DIFFERENT -> {
                logger.error(
                    LogCategory.BROWSER,
                    "Native toolkit agent loaded a different engine than this launch resolved",
                    mapOf("agent" to describe(result), "resolved" to expected.joinToString("|")),
                )
            }
        }
    }

    /**
     * For a launch that resolved nothing to load: if the agent loaded something anyway, that image
     * is in the process whatever this launch decides, and nobody would otherwise know.
     */
    fun reportAgentWithoutPlan(reason: String) {
        val result = agentResult as? ToolkitPreloadManifest.AgentResult.Loaded ?: return
        logger.error(
            LogCategory.BROWSER,
            "Native toolkit agent loaded a toolkit this launch did not resolve",
            mapOf("agent" to describe(result), "reason" to reason),
        )
    }

    private fun describe(result: ToolkitPreloadManifest.AgentResult): String =
        (result as? ToolkitPreloadManifest.AgentResult.Loaded)?.paths?.joinToString("|").orEmpty()

    /** Removes the record, so the next launch's agent loads nothing. */
    fun forget(reason: String) {
        if (stamp == null) return
        runCatching {
            if (manifestFile.exists() && manifestFile.delete()) {
                logger.info(
                    LogCategory.BROWSER,
                    "Native toolkit agent will not preload next launch",
                    mapOf("reason" to reason),
                )
            }
        }
    }

    companion object {
        fun live(): AgentHandOff =
            AgentHandOff(
                stamp = System.getProperty(ToolkitPreloadManifest.STAMP_PROPERTY)?.takeIf { it.isNotBlank() },
                agentResult =
                    ToolkitPreloadManifest.parseAgentResult(
                        System.getProperty(ToolkitPreloadManifest.AGENT_RESULT_PROPERTY),
                    ),
                manifestFile = BossDirectories.resolve(ToolkitPreloadManifest.FILE_NAME),
                settingsFile = BossDirectories.resolve(BrowserEngineSettingsManager.SETTINGS_FILE_NAME),
                pendingDir = ChromiumAutoDownloader.getPendingChromiumDir().toFile(),
            )
    }
}
