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
 * A second instance, after this preload shipped (9.5.33, 2026-09-29, JxBrowser 9.5.2 / Chromium
 * 154.0.8037.58): +1.9s, `brk #0` at `libtoolkit+0x4c9f4` (the same fallback loop, four zones
 * checked), on the AppKit thread under `CA::Transaction::commit`. The preload then ran after
 * `DefaultWindowIcon.install()` had created the AWT toolkit, so Core Animation was committing
 * transactions (and freeing) on the AppKit thread during the swap. Reproduced outside BOSS, same
 * PC: a JVM with four threads freeing continuously trapped in 3 of 20 runs when it loaded these
 * libraries after starting them, 0 of 20 when it loaded them first. Memory allocated before the
 * swap and freed after it is harmless (20,000 blocks, no trap) - only a free() *during* the
 * swap traps.
 *
 * **What this changes.** The swap still happens, but on the main thread, before AWT creates
 * AppKit, before the startup class loading and before the pre-warm thread exists - so neither
 * Core Animation nor the busiest `free()` callers of that moment can be in the window.
 * `ChromiumBootstrap.preflight()` enforces the order. JxBrowser's own later
 * `System.load` of the same canonical path, from the same class loader, is a no-op in the JVM.
 *
 * **Why that was not enough, and the agent.** A third instance on 9.5.37 (2026-10-04, +1.09s,
 * same `libtoolkit+0x4c9f4`) had the free on the JVM's own `C2 CompilerThread4`, in
 * `Chunk::operator delete` after register allocation. GC and JIT threads free constantly and
 * nothing in Java code can hold them still, so on macOS packaged builds the native agent in
 * `native/toolkit-preload-agent` now does the swap from `Agent_OnLoad`, before the JVM has created
 * any of those threads (three threads exist then: the launcher's idle primordial thread, the
 * launcher thread blocked in `pthread_join`, and the one running the agent). The agent loads only
 * what this object recorded on the previous launch in [ToolkitPreloadManifest], and only while every
 * file that decision read is unchanged; otherwise it skips and this preload runs as before. Measured
 * with a JVM harness that churns `free()` on four native threads while loading the real toolkit:
 * 23 of 30 runs trapped at `libtoolkit+0x4c9f4` without the agent, 0 of 30 with it, and 20 of 30
 * again with the agent present but its manifest stale.
 *
 * Only the FIRST library to load swaps: measured with an interposer on `malloc_zone_register` /
 * `malloc_zone_unregister`, `libipc` and `libawt_toolkit` leave the zones alone once `libtoolkit`
 * has registered PartitionAlloc, and `libawt_toolkit` swaps only when it is the first. So once
 * `libtoolkit` is in, by either route, nothing later in the session repeats the swap.
 *
 * **What it does not.** The first launch after an install, an engine change or an app update has no
 * valid manifest, so that one launch keeps the in-JVM preload and its narrower window. A first-run
 * download-then-boot gets no preload at all, so packaged macOS relaunches once the download lands
 * (`ChromiumBootstrap.onEngineDownloadComplete`); Gradle runs still boot in process. Nothing here touches the other
 * PartitionAlloc failure mode, a native allocation failure, which is also `brk #0`; only moving
 * JxBrowser out of the host process removes that. The offsets above are for this JxBrowser build:
 * re-measure after a bump before relying on them.
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

    /**
     * First instrumented toolkit-creation entry point. This diagnostic covers calls to
     * [noteAwtToolkitCreating]; it does not probe uninstrumented library initialization.
     * `StartupOrderingTest` separately pins the known startup order and the icon entry point.
     */
    private val awtToolkitOrigin = ToolkitCreationOrigin()

    /**
     * Called by whatever is about to create the AWT toolkit (today `DefaultWindowIcon.install()`).
     * The first caller wins, so the report names the real culprit.
     */
    fun noteAwtToolkitCreating(by: String) {
        awtToolkitOrigin.record(by)
    }

    /**
     * Why a preload about to run is late, or null when it is not: on macOS, a toolkit created before
     * the preload means AppKit and Core Animation are already freeing on the AppKit thread.
     */
    internal fun lateLoadReason(
        createdBy: String?,
        isMac: Boolean,
    ): String? = if (isMac && createdBy != null) "the AWT toolkit was already created by $createdBy" else null

    /** What [plan] decided: the files to load, in order, or why nothing is loaded. */
    internal sealed interface Plan {
        data class Load(
            val files: List<File>,
            val executableName: String,
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
            else -> Plan.Load(files, name)
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
    // handOff defaults to null rather than AgentHandOff.live(): a default argument is evaluated
    // in the caller's bridge, outside this try, so a throwing live() would escape preflight().
    internal fun preload(
        engineDir: Path?,
        handOff: AgentHandOff? = null,
        load: (String) -> Unit = System::load,
    ): Int =
        try {
            preloadUnguarded(engineDir, load, handOff ?: AgentHandOff.live())
        } catch (e: Exception) {
            runCatching { logger.warn(LogCategory.BROWSER, "Native toolkit preload aborted", error = e) }
            0
        } catch (e: LinkageError) {
            runCatching { logger.warn(LogCategory.BROWSER, "Native toolkit preload aborted", error = e) }
            0
        }

    private val isMacHost: Boolean
        get() =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")

    @Suppress("ReturnCount") // One early return per named skip.
    private fun preloadUnguarded(
        engineDir: Path?,
        load: (String) -> Unit,
        handOff: AgentHandOff,
    ): Int {
        if (engineDir == null) return skipped("no engine directory", handOff)
        if (disabledFrom(System.getenv(DISABLED_KEY), System.getProperty(DISABLED_PROPERTY))) {
            return skipped("disabled by $DISABLED_KEY / -D$DISABLED_PROPERTY", handOff)
        }
        val isMac = isMacHost
        val plan =
            plan(
                engineDir = engineDir,
                isMac = isMac,
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
        val loadPlan =
            when (plan) {
                is Plan.Skip -> return skipped(plan.reason, handOff)
                is Plan.Load -> plan
            }
        val files = loadPlan.files
        handOff.reportAgent(files.map { it.canonicalPath })
        // Report the late entry, but do not skip: JxBrowser would load the same toolkit later anyway.
        lateLoadReason(awtToolkitOrigin.createdBy, isMac)?.let { reason ->
            logger.error(
                LogCategory.BROWSER,
                "Native toolkit preload is running after AppKit started; its malloc zone swap can race it",
                mapOf("reason" to reason),
            )
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
        handOff.recordOutcome(engineDir, loadPlan.executableName, files, loaded)
        return loaded
    }

    /**
     * Forgets the agent manifest when the engine will not boot from disk at startup (download
     * pending, or booting a mismatched engine to report it), so the next launch does not preload
     * from a decision this launch did not make.
     */
    fun forgetNextLaunch(reason: String) {
        runCatching { AgentHandOff.live().forget(reason) }
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

    private fun skipped(
        reason: String,
        handOff: AgentHandOff,
    ): Int {
        logger.info(LogCategory.BROWSER, "Native toolkit preload skipped", mapOf("reason" to reason))
        handOff.reportAgentWithoutPlan(reason)
        handOff.forget(reason)
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
