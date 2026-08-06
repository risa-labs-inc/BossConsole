package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How much of itself BOSS is willing to run.
 *
 * The tiers exist because of a failure mode that has no software recovery. JxBrowser's
 * `libtoolkit.dylib` registers Chromium's PartitionAlloc as the process's default malloc
 * zone, so *every* native allocation in the JVM - Skia, fonts, JNA, not just browser work -
 * is served by an allocator whose documented contract is that failure is fatal. When it
 * cannot satisfy a request it records its crash keys and executes `brk #0`. There is no
 * null return, no `bad_alloc`, no `OutOfMemoryError` to catch, and no stack to unwind:
 * BOSS 9.4.0 died exactly this way on 2026-08-05 after 35 h, on an 18 MiB frame buffer,
 * on a 128 GB machine (PartitionAlloc allocates from fixed-size pools, not from system RAM).
 *
 * Short of moving JxBrowser out of the host process, the only defence is to keep demand
 * well below the wall. That is what these tiers do.
 *
 * [FULL] is unchanged behaviour. [LITE] caps the browser only, so nothing a user can see
 * disappears. [ULTRA_LITE] additionally stops loading plugins that are not on the
 * allowlist, which is the single largest consumer: the crashed session had 36 plugins
 * installed and 314 threads.
 *
 * Each tier carries its own numbers so the policy is one table rather than scattered
 * `when` blocks, and so [BossResourceModeTest] can assert on it without a running app.
 */
enum class BossResourceMode(
    /** `--renderer-process-limit` value, or null to leave Chromium's default alone. */
    val rendererProcessLimit: Int?,
    /** Ceiling on concurrent [ai.rever.boss.plugin.api.BrowserHandle]s, or null for uncapped. */
    val maxConcurrentBrowsers: Int?,
    /** Whether plugin loading is restricted to the `lite_eligible` allowlist. */
    val gatesPlugins: Boolean,
    /** Whether the background performance sampler runs. */
    val backgroundSamplingEnabled: Boolean,
) {
    FULL(
        rendererProcessLimit = null,
        maxConcurrentBrowsers = null,
        gatesPlugins = false,
        backgroundSamplingEnabled = true,
    ),
    LITE(
        rendererProcessLimit = 4,
        maxConcurrentBrowsers = 8,
        gatesPlugins = false,
        backgroundSamplingEnabled = true,
    ),
    ULTRA_LITE(
        rendererProcessLimit = 2,
        maxConcurrentBrowsers = 4,
        gatesPlugins = true,
        backgroundSamplingEnabled = false,
    ),
    ;

    /** True when this tier constrains anything at all, i.e. anything but [FULL]. */
    val isReduced: Boolean get() = this != FULL

    /** Human-readable name for Settings and notices. Not the enum name, which is shouty. */
    val displayName: String
        get() =
            when (this) {
                FULL -> "Full"
                LITE -> "Lite"
                ULTRA_LITE -> "Ultra Lite"
            }

    /** One line on what this tier gives up, for the Settings selector. */
    val summary: String
        get() =
            when (this) {
                FULL -> "Everything on. No caps."
                LITE -> "Caps the browser. Every plugin still loads."
                ULTRA_LITE -> "Caps the browser and loads only essential plugins."
            }
}

/**
 * Why [ResourceModeConfig.mode] resolved the way it did.
 *
 * Surfaced in Settings and in the startup log because a mode that silently drops plugins
 * is indistinguishable from a broken install unless the app can say why.
 */
enum class ResourceModeReason {
    /** An explicit, recognized BOSS_RESOURCE_MODE value. */
    EXPLICIT_OVERRIDE,

    /** Windows, which defaults to the most conservative tier. */
    PLATFORM_DEFAULT,

    /** Total physical RAM fell below a configured threshold. */
    DETECTED_MEMORY,

    /** Nothing asked for a reduced tier. */
    DEFAULT,
}

/** The resolved tier plus the reason, so callers can explain themselves. */
data class ResourceModeDecision(
    val mode: BossResourceMode,
    val reason: ResourceModeReason,
)

/**
 * Resolves the process's [BossResourceMode] once, at startup.
 *
 * Deliberately mirrors the shape of [JxBrowserConfig.renderingMode]: a logged `by lazy`
 * value over a **pure** resolver, so the platform and memory decisions are unit-testable
 * without an engine, a window or a machine of a particular size.
 *
 * Read through [ConfigLoader] (env var, system property or local.properties), matching
 * `BOSS_RENDERING_MODE`. The rule that capability-granting keys must bypass ConfigLoader
 * does not apply here: every tier only ever *removes* capability, so a local.properties
 * value cannot grant this process anything it did not already have.
 *
 * Not to be confused with `BOSS_MODE` / `boss.mode`, which is the orthogonal
 * kernel-versus-normal axis read by `PluginStoreSetup.isKernelMode`.
 */
object ResourceModeConfig {
    private val logger = BossLogger.forComponent("ResourceModeConfig")

    /** Selects the tier outright. `AUTO` (or unset) hands the decision to the resolver. */
    const val MODE_KEY = "BOSS_RESOURCE_MODE"

    /** Total-RAM ceiling, in GB, below which [BossResourceMode.LITE] is chosen. */
    const val LITE_THRESHOLD_GB_KEY = "BOSS_RESOURCE_LITE_GB"

    /** Total-RAM ceiling, in GB, below which [BossResourceMode.ULTRA_LITE] is chosen. */
    const val ULTRA_LITE_THRESHOLD_GB_KEY = "BOSS_RESOURCE_ULTRALITE_GB"

    /** Whether the live memory-pressure watchdog may downgrade mid-session. */
    const val LIVE_PRESSURE_KEY = "BOSS_RESOURCE_LIVE_PRESSURE"

    internal const val DEFAULT_LITE_THRESHOLD_GB = 16
    internal const val DEFAULT_ULTRA_LITE_THRESHOLD_GB = 8

    private val FULL_SPELLINGS = setOf("FULL", "NONE", "OFF")
    private val LITE_SPELLINGS = setOf("LITE")
    private val ULTRA_LITE_SPELLINGS = setOf("ULTRALITE", "ULTRA_LITE", "ULTRA-LITE", "MINIMAL")
    private val AUTO_SPELLINGS = setOf("AUTO", "DETECT")

    /**
     * The tier this process runs in, decided once at startup.
     *
     * Startup-only on purpose. Plugin gating cannot be undone without a restart (a
     * classloader that was never built cannot be un-built), so a value that changed
     * underneath callers would describe a process that does not exist. The live watchdog
     * in `MemoryPressureWatchdog` handles mid-session pressure with the reversible levers
     * only, and asks for a restart for the rest.
     */
    val decision: ResourceModeDecision by lazy {
        val settings = ResourceModeSettings.current()
        // An env var or system property still outranks the Settings choice, matching how
        // BOSS_RENDERING_MODE behaves: the operator's environment is the outer authority, and
        // it is the escape hatch when a persisted choice turns out to be the problem.
        val raw = ConfigLoader.getConfig(MODE_KEY) ?: settings.selectedMode
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val totalBytes = SystemMemory.totalPhysicalBytes()
        val resolved =
            resolveResourceMode(
                raw = raw,
                os = os,
                totalMemoryBytes = totalBytes,
                liteThresholdGb =
                    thresholdGb(LITE_THRESHOLD_GB_KEY, settings.liteThresholdGb),
                ultraLiteThresholdGb =
                    thresholdGb(ULTRA_LITE_THRESHOLD_GB_KEY, settings.ultraLiteThresholdGb),
            )

        if (!raw.isNullOrBlank() && !isRecognizedResourceMode(raw)) {
            // Unset and AUTO are the normal cases and stay silent. Only a value we could
            // not honour is worth a warning, since it otherwise silently keeps the default.
            logger.warn(
                LogCategory.SYSTEM,
                "Ignoring unrecognized $MODE_KEY - falling back to automatic selection",
                mapOf("value" to raw, "using" to resolved.mode.name),
            )
        }

        logger.info(
            LogCategory.SYSTEM,
            "Resource mode selected",
            mapOf(
                "mode" to resolved.mode.name,
                "reason" to resolved.reason.name,
                "override" to (raw ?: "auto"),
                "os" to os,
                "totalRamGb" to
                    if (totalBytes > 0) "%.1f".format(totalBytes / BYTES_PER_GB.toDouble()) else "unknown",
                "gatesPlugins" to resolved.mode.gatesPlugins.toString(),
                "rendererLimit" to (resolved.mode.rendererProcessLimit?.toString() ?: "default"),
            ),
        )
        resolved
    }

    /**
     * The tier currently in force, which is [decision]'s tier unless the live memory-pressure
     * watchdog has tightened it since startup.
     *
     * Callers get whichever is stricter *at the moment they ask*, which is why the levers
     * divide the way they do. Anything read per-use follows a live downgrade
     * (`BrowserServiceImpl`'s ceiling); anything read once at startup does not
     * (`FluckEngine`'s renderer cap, plugin gating), and those are exactly the levers a
     * downgrade cannot apply without a restart.
     */
    val effectiveMode: StateFlow<BossResourceMode> by lazy { MutableStateFlow(decision.mode) }

    /** Convenience accessor for the common case where the reason does not matter. */
    val mode: BossResourceMode get() = effectiveMode.value

    /**
     * Tightens the tier in force for the rest of this session. Never loosens it.
     *
     * One-way on purpose. Relaxing on a memory reading that recovered would let the app
     * oscillate between capped and uncapped as the user's own browsing moves the number, and
     * each loosening is an invitation to allocate right back into the wall that this whole
     * feature exists to stay away from. The user can still widen it explicitly by choosing a
     * tier in Settings, which takes effect on the next launch.
     *
     * @return true when this call actually changed the tier.
     */
    internal fun tightenTo(target: BossResourceMode): Boolean {
        val flow = effectiveMode as MutableStateFlow<BossResourceMode>
        val current = flow.value
        // Ordinal order is FULL < LITE < ULTRA_LITE, i.e. increasingly constrained.
        if (target.ordinal <= current.ordinal) return false
        flow.value = target
        logger.info(
            LogCategory.SYSTEM,
            "Resource mode tightened for this session",
            mapOf("from" to current.name, "to" to target.name),
        )
        return true
    }

    /** Whether the live memory-pressure watchdog is allowed to act. Defaults to on. */
    val livePressureEnabled: Boolean by lazy {
        val raw = ConfigLoader.getConfig(LIVE_PRESSURE_KEY)?.trim()?.lowercase()
        raw?.let { it !in FALSY_FLAGS } ?: ResourceModeSettings.current().livePressureEnabled
    }

    private val FALSY_FLAGS = setOf("false", "0", "no", "off")

    /**
     * Persists Ultra Lite as the choice for the next launch.
     *
     * Used by the memory-pressure notice's restart action: the remaining saving is plugin
     * gating, which only a fresh startup can apply.
     */
    fun requestUltraLiteOnNextLaunch() {
        ResourceModeSettings.update { it.copy(selectedMode = BossResourceMode.ULTRA_LITE.name) }
        logger.info(LogCategory.SYSTEM, "Ultra Lite requested for the next launch")
    }

    /**
     * Pure part of [decision], split out so the platform and memory decisions are testable
     * without a machine of a particular size.
     *
     * Precedence, highest first:
     *  1. An explicit, recognized [raw] value. Also the escape hatch: a user on a small
     *     machine who wants everything can set FULL, and one on a large machine who wants
     *     the footprint can set ULTRALITE.
     *  2. Windows, which defaults to [BossResourceMode.ULTRA_LITE].
     *  3. Total physical RAM against the configured thresholds.
     *  4. [BossResourceMode.FULL].
     *
     * An unrecognized value falls through to automatic selection rather than to a guess,
     * so a typo can never silently pick a tier.
     *
     * [totalMemoryBytes] of 0 means "could not detect", and is treated as *not* a reason
     * to reduce - an unreadable MXBean is not evidence of a small machine, and silently
     * dropping every plugin because a reflective call failed would be a bad trade.
     */
    internal fun resolveResourceMode(
        raw: String?,
        os: String,
        totalMemoryBytes: Long,
        liteThresholdGb: Int = DEFAULT_LITE_THRESHOLD_GB,
        ultraLiteThresholdGb: Int = DEFAULT_ULTRA_LITE_THRESHOLD_GB,
    ): ResourceModeDecision {
        val explicit =
            when (raw?.trim()?.uppercase()) {
                in FULL_SPELLINGS -> BossResourceMode.FULL

                in LITE_SPELLINGS -> BossResourceMode.LITE

                in ULTRA_LITE_SPELLINGS -> BossResourceMode.ULTRA_LITE

                // AUTO, unset and anything unrecognized all fall through to detection.
                else -> null
            }

        return explicit
            ?.let { ResourceModeDecision(it, ResourceModeReason.EXPLICIT_OVERRIDE) }
            ?: detectResourceMode(os, totalMemoryBytes, liteThresholdGb, ultraLiteThresholdGb)
    }

    /** The automatic half of [resolveResourceMode], reached when nothing explicit applied. */
    private fun detectResourceMode(
        os: String,
        totalMemoryBytes: Long,
        liteThresholdGb: Int,
        ultraLiteThresholdGb: Int,
    ): ResourceModeDecision {
        // Windows defaults to the most conservative tier. It is the platform where the
        // memory pressure is worst-felt and where OFF_SCREEN forced the entire Compose UI
        // through the CPU before #128.
        //
        // startsWith, NOT contains: "darwin" contains "win". Windows reports "Windows 10",
        // "Windows 11", "Windows Server 2022" - all prefixes. JxBrowserRenderingModeTest
        // keeps a "darwin" case alive for exactly this reason; the same trap applies here,
        // and here it would silently strip every plugin from a Mac.
        if (os.startsWith("win")) {
            return ResourceModeDecision(
                BossResourceMode.ULTRA_LITE,
                ResourceModeReason.PLATFORM_DEFAULT,
            )
        }

        val totalGb = totalMemoryBytes.toDouble() / BYTES_PER_GB
        return when {
            // 0 or negative means "could not read", which is not evidence of a small machine.
            totalMemoryBytes <= 0L -> {
                ResourceModeDecision(BossResourceMode.FULL, ResourceModeReason.DEFAULT)
            }

            totalGb < ultraLiteThresholdGb -> {
                ResourceModeDecision(BossResourceMode.ULTRA_LITE, ResourceModeReason.DETECTED_MEMORY)
            }

            totalGb < liteThresholdGb -> {
                ResourceModeDecision(BossResourceMode.LITE, ResourceModeReason.DETECTED_MEMORY)
            }

            else -> {
                ResourceModeDecision(BossResourceMode.FULL, ResourceModeReason.DEFAULT)
            }
        }
    }

    /** Whether [raw] names a tier we honour, as opposed to falling through to auto-selection. */
    internal fun isRecognizedResourceMode(raw: String?): Boolean =
        raw?.trim()?.uppercase().let {
            it in FULL_SPELLINGS ||
                it in LITE_SPELLINGS ||
                it in ULTRA_LITE_SPELLINGS ||
                it in AUTO_SPELLINGS
        }

    private fun thresholdGb(
        key: String,
        default: Int,
    ): Int =
        ConfigLoader
            .getConfig(key)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: default

    internal const val BYTES_PER_GB = 1024L * 1024L * 1024L
}
