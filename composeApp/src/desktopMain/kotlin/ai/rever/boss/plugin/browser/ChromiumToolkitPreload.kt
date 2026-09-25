package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.Path

/**
 * Loads JxBrowser's `libtoolkit` and `libipc` on the main thread at startup, before the engine
 * pre-warm thread would.
 *
 * **Why.** Each of JxBrowser's macOS JNI libraries carries Chromium's allocator shim, and a static
 * initializer in each one makes PartitionAlloc the process's default malloc zone the way Chromium
 * does it: `malloc_zone_register(pa)`, `malloc_zone_unregister(default)`,
 * `malloc_zone_register(default)`. Between the last two calls the system zone is not listed. A
 * `free()` on any other thread in that window reaches the shim's fallback, which asks every
 * registered zone "is this yours?", finds no owner and executes `brk #0`: an EXC_BREAKPOINT /
 * SIGTRAP that kills BOSS with nothing Java can catch. Chromium does this at process start on
 * one thread; in BOSS it ran whenever JxBrowser first called `System.load`, which was the
 * `fluck-engine-prewarm` thread ~0.5s into launch, while the main thread was loading classes.
 *
 * Measured, not assumed (BOSS dev build on 0fa626d, 2026-09-23, JxBrowser 9.5.0 / Chromium
 * 152.0.7977.65): the crash PC is `libtoolkit+0x4b178`, the `brk #0` after a loop over
 * `malloc_get_all_zones` calling each zone's `size()`; the registers show three zones checked and
 * none claiming the pointer; the faulting thread is the Java main thread in
 * `ClassLoader.defineClass1`; `fluck-engine-prewarm` and `Chromium Process Thread` are alive; the
 * register / unregister / register sequence is in `libtoolkit`'s second `__init_offsets` entry
 * (`0x4b250`). The 27 July 2026 release crash (9.2.60, +514ms, libtoolkit on a `free` path under
 * `libxpc` dealloc) has the same signature.
 *
 * **What this changes.** The swap still happens, but on the main thread, before the startup class
 * loading and before the pre-warm thread exists - so the busiest `free()` callers of that moment
 * cannot be in the window. JxBrowser's own later `System.load` of the same canonical path, from
 * the same class loader, is a no-op in the JVM.
 *
 * **What it does not.** It narrows the race rather than removing it: JVM service threads (GC,
 * JIT) still run. `libawt_toolkit` is deliberately NOT preloaded - it links `@rpath/libjawt.dylib`
 * and so must load after AWT - and it still swaps zones when JxBrowser loads it for the first
 * browser view. A first-run download-then-boot gets no preload either (see the call site). Only
 * moving JxBrowser out of the host process removes the family. The offsets above are for this
 * JxBrowser build: re-measure after a bump before relying on them.
 *
 * Off switch: `BOSS_TOOLKIT_PRELOAD=false` (also `0` / `no` / `off`) or
 * `-Dboss.toolkit.preload=false`.
 */
object ChromiumToolkitPreload {
    private val logger by lazy { BossLogger.forComponent("ChromiumToolkitPreload") }

    /** Load order: `libtoolkit` first, as JxBrowser's `ToolkitLibrary` is the first it loads. */
    internal val PRELOADED_LIBRARIES = listOf("libtoolkit.dylib", "libipc.dylib")

    private const val DISABLED_KEY = "BOSS_TOOLKIT_PRELOAD"
    private const val DISABLED_PROPERTY = "boss.toolkit.preload"

    /** What [plan] decided: the files to load, in order, or why nothing is loaded. */
    internal sealed interface Plan {
        data class Load(
            val files: List<File>,
        ) : Plan

        data class Skip(
            val reason: String,
        ) : Plan
    }

    /**
     * The native libraries to preload for the engine at [engineDir], or why there is nothing safe
     * to load: not macOS, no usable `executable.name`, or the framework does not carry
     * [chromiumVersion] (the build this jar was compiled against - loading anything else would be
     * the version-mismatch failure `FluckEngine.chromiumVersionMismatch` exists to report, not
     * cause).
     *
     * [executableName] is a function so it is only read on macOS: every other OS skips before the
     * file is touched. Its content comes from a user-writable cache and ends up in a path handed
     * to `System.load`, so it must be a plain bundle name - no separators, no `..` - and every
     * resolved file must still sit under [engineDir]. Pure apart from `isFile`, so the rule is
     * testable off macOS.
     */
    @Suppress("ReturnCount") // One early Skip per reason, each named.
    internal fun plan(
        engineDir: Path,
        isMac: Boolean,
        executableName: () -> String?,
        chromiumVersion: String,
    ): Plan {
        if (!isMac) return Plan.Skip("not macOS")
        val name = executableName()?.trim()
        if (name.isNullOrEmpty() || !SAFE_BUNDLE_NAME.matches(name) || name.contains("..")) {
            return Plan.Skip("no usable executable.name")
        }
        val root = engineDir.toFile().canonicalFile
        val libraries =
            engineDir
                .resolve("$name.app/Contents/Frameworks/Chromium Framework.framework/Versions")
                .resolve(chromiumVersion)
                .resolve("Libraries")
                .toFile()
        val files = PRELOADED_LIBRARIES.map { libraries.resolve(it) }
        return when {
            files.any { !it.canonicalFile.startsWith(root) } -> Plan.Skip("library outside the engine directory")
            files.any { !it.isFile } -> Plan.Skip("engine does not carry Chromium $chromiumVersion")
            else -> Plan.Load(files)
        }
    }

    /** A bundle name as the engine archives write it ("BOSS"): letters, digits, space, `._-`. */
    private val SAFE_BUNDLE_NAME = Regex("[A-Za-z0-9 ._-]+")

    /**
     * Env wins over the system property, matching `BOSS_BROWSER_TELEMETRY_DISABLED` and the MCP
     * kill switch; a blank env var does not shadow the property.
     */
    internal fun disabledFrom(
        env: String?,
        property: String?,
    ): Boolean {
        val raw = (env?.takeIf { it.isNotBlank() } ?: property)?.trim()?.lowercase() ?: return false
        return raw == "false" || raw == "0" || raw == "no" || raw == "off"
    }

    /**
     * Preload for [engineDir], the directory the engine will boot from (the caller resolves it
     * with the same `FluckEngine.resolveEngineDir` the engine uses, so the path JxBrowser loads
     * later is this one).
     *
     * Never throws, and the whole body is inside the guard so that holds for the logger too: a
     * failure here must not stop a launch that would otherwise have worked, and JxBrowser still
     * loads the libraries itself. Every outcome is logged - including each skip reason - so a
     * crash report can be matched to whether this mitigation was in effect.
     *
     * **Requires** that `com.teamdev.jxbrowser.*` is loaded by the same class loader as this
     * object. A native library is owned by the loader of the class that called `System.load`,
     * and a second loader asking for it gets `UnsatisfiedLinkError: already loaded in another
     * classloader` - which would break the engine rather than protect it. Today JxBrowser is a
     * host `implementation` dependency and no plugin bundles it.
     *
     * [load] is injectable for tests; production passes `System::load`. Returns how many libraries
     * were loaded.
     */
    // TooGenericExceptionCaught: the guarantee is "never throws"; LinkageError covers
    // UnsatisfiedLinkError, which is an Error, and nothing narrower would keep the promise.
    @Suppress("TooGenericExceptionCaught")
    fun preload(
        engineDir: Path?,
        load: (String) -> Unit = System::load,
    ): Int =
        try {
            preloadUnguarded(engineDir, load)
        } catch (e: Exception) {
            runCatching { logger.warn(LogCategory.BROWSER, "Native toolkit preload aborted", error = e) }
            0
        } catch (e: LinkageError) {
            runCatching { logger.warn(LogCategory.BROWSER, "Native toolkit preload aborted", error = e) }
            0
        }

    @Suppress("ReturnCount") // One early return per named skip.
    private fun preloadUnguarded(
        engineDir: Path?,
        load: (String) -> Unit,
    ): Int {
        if (engineDir == null) return skipped("no engine directory")
        if (disabledFrom(System.getenv(DISABLED_KEY), System.getProperty(DISABLED_PROPERTY))) {
            return skipped("disabled by $DISABLED_KEY / -D$DISABLED_PROPERTY")
        }
        val plan =
            plan(
                engineDir = engineDir,
                isMac =
                    System
                        .getProperty("os.name")
                        .orEmpty()
                        .lowercase()
                        .contains("mac"),
                executableName = {
                    engineDir
                        .resolve("executable.name")
                        .toFile()
                        .takeIf { it.isFile }
                        ?.readText()
                },
                chromiumVersion =
                    com.teamdev.jxbrowser.VersionInfo
                        .chromiumVersion(),
            )
        val files =
            when (plan) {
                is Plan.Skip -> return skipped(plan.reason)
                is Plan.Load -> plan.files
            }
        val startNanos = System.nanoTime()
        var loaded = 0
        for (file in files) {
            val failure = loadOne(file, load)
            if (failure != null) {
                logFailure(file, failure)
                break
            }
            loaded++
        }
        if (loaded > 0) {
            logger.info(
                LogCategory.BROWSER,
                "Preloaded native toolkit on the main thread",
                mapOf("libraries" to loaded, "durationMs" to (System.nanoTime() - startNanos) / 1_000_000),
            )
        }
        return loaded
    }

    /** Loads [file], returning what went wrong instead of throwing. */
    @Suppress("TooGenericExceptionCaught") // LinkageError is how a failed System.load surfaces.
    private fun loadOne(
        file: File,
        load: (String) -> Unit,
    ): Throwable? =
        try {
            load(file.canonicalPath)
            null
        } catch (e: Exception) {
            e
        } catch (e: LinkageError) {
            e
        }

    private fun skipped(reason: String): Int {
        logger.info(LogCategory.BROWSER, "Native toolkit preload skipped", mapOf("reason" to reason))
        return 0
    }

    private fun logFailure(
        file: File,
        error: Throwable,
    ) {
        logger.warn(
            LogCategory.BROWSER,
            "Native toolkit preload failed; JxBrowser will load it later",
            mapOf("library" to file.name),
            error = error,
        )
    }
}
