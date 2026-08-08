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
 * [FULL] is unchanged behaviour. [LITE] and [ULTRA_LITE] hibernate idle browser tabs sooner,
 * cap Chromium's renderer processes, and (at the tightest tier) stop the background sampler.
 * Every plugin still loads at every tier: gating them was tried and removed, because it took
 * features away from the user to save memory that hibernation reclaims without asking.
 *
 * Nothing here refuses work. The browser lever is hibernation - an idle background tab gives its
 * Chromium process tree back and reloads when you return to it - because the concurrent-browser
 * ceiling this replaced refused the tab you had just asked for while idle ones sat untouched,
 * and could not coexist with hibernation at all (waking a tab needs a slot).
 *
 * Each tier carries its own numbers so the policy is one table rather than scattered
 * `when` blocks, and so [BossResourceModeTest] can assert on it without a running app.
 */
enum class BossResourceMode(
    /** `--renderer-process-limit` value, or null to leave Chromium's default alone. */
    val rendererProcessLimit: Int?,
    /**
     * How long a browser tab may sit in the background before the fluck-browser plugin
     * hibernates it - tearing down its Chromium process tree and recreating it from the saved
     * URL when it is next shown.
     *
     * This replaced a ceiling on concurrent browsers, which was the wrong tool twice over. It
     * fired on tab *count* rather than on idleness, so it refused a tab you had just asked for
     * while four idle ones sat untouched. And it is actively incompatible with hibernation: a
     * hibernated tab recreates its browser when you switch to it, so under a cap merely moving
     * between tabs gets refused. Observed with 7 tabs open - four wakes in four seconds, then
     * "Browser limit reached" on the fifth.
     *
     * Hibernation is the better bound in any case: it reclaims memory from tabs that are not
     * being used, rather than refusing work.
     *
     * **Published, not yet obeyed.** The host writes this to [HIBERNATION_IDLE_PROPERTY] at
     * startup, which is the seam the plugin needs, but fluck-browser still reads only its own
     * `BOSS_TAB_HIBERNATION_IDLE_MS` env var. Until the plugin reads the property, these numbers
     * describe intent rather than behaviour - which is why no user-facing string mentions them.
     */
    val hibernationIdleMs: Long,
    /** Whether the background performance sampler runs. */
    val backgroundSamplingEnabled: Boolean,
) {
    FULL(
        rendererProcessLimit = null,
        hibernationIdleMs = 30 * 60 * 1000L,
        backgroundSamplingEnabled = true,
    ),
    LITE(
        rendererProcessLimit = 4,
        // The plugin's own default, which is Lite's.
        hibernationIdleMs = 10 * 60 * 1000L,
        backgroundSamplingEnabled = true,
    ),
    ULTRA_LITE(
        rendererProcessLimit = 2,
        hibernationIdleMs = 2 * 60 * 1000L,
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

    /**
     * One line on what this tier gives up, for the Settings selector.
     *
     * Describes only what the tier does **today**. An earlier version promised "idle browser tabs
     * sleep after 10 minutes", which nothing in the app delivers: [hibernationIdleMs] has no
     * consumer yet. Copy that describes a planned lever as a shipped one is worse than no copy,
     * because the user cannot tell the difference between a feature that is off and one that is
     * broken. `ResourceModeCopyTest` holds these strings against the tier table.
     */
    val summary: String
        get() =
            when (this) {
                FULL -> "Everything on."
                LITE -> "Fewer Chromium processes for the browser. Every plugin still loads."
                ULTRA_LITE -> "Fewest Chromium processes, and no background performance sampling."
            }
}

/**
 * Why [ResourceModeConfig.mode] resolved the way it did.
 *
 * Surfaced in Settings and in the startup log: a tier the app chose on the user's behalf should
 * be able to say why it chose it.
 */
enum class ResourceModeReason {
    /**
     * An explicit, recognized BOSS_RESOURCE_MODE from outside the app: env var, system property
     * **or `local.properties`**. All three arrive through one [ConfigLoader] call, so they are
     * indistinguishable here and are reported as one reason.
     *
     * Distinct from [USER_SELECTION] because the two need different sentences. When the operator
     * sets it externally, Settings still shows whatever the user picked in the dropdown, and
     * telling them the tier is what they selected is a flat contradiction of the control sitting
     * directly above it.
     *
     * `local.properties` is worth calling out because it persists across every run of a checkout
     * with nothing in the UI to reveal it, and because under an override the Settings radios are
     * disabled - so a developer who set it and forgot keeps launching reduced, with the one
     * control that looks like it should fix it greyed out. See the object KDoc for why that is
     * accepted, and why "a tier only removes capability" is *not* the reason.
     */
    ENVIRONMENT_OVERRIDE,

    /** The tier chosen in Settings. */
    USER_SELECTION,

    /**
     * A one-shot tier requested by the memory-pressure notice's "Restart in Ultra Lite" button.
     *
     * Distinct from [USER_SELECTION] because it is neither permanent nor, in any ordinary sense,
     * selected. It previously wrote the same field the dropdown writes, which made Settings report
     * "Ultra Lite, because you selected it" to a user who had done nothing of the kind - on the
     * one screen whose entire purpose is explaining how the tier was chosen - and made a single
     * click under pressure the tier for every launch thereafter.
     */
    PRESSURE_RESTART,

    /** Windows, which defaults to a reduced tier. */
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
 * Where an explicit tier came from, which decides which [ResourceModeReason] explains it.
 *
 * An enum rather than the boolean this replaced: there are three origins now, and a boolean
 * forced the third (a pressure restart) to masquerade as one of the other two.
 */
enum class ResourceModeSource {
    /** `BOSS_RESOURCE_MODE` from env, system property or local.properties. */
    ENVIRONMENT,

    /** The one-shot request written by the memory-pressure notice. */
    PRESSURE_RESTART,

    /** The tier picked in Settings or the View menu. */
    SETTINGS,
    ;

    fun toReason(): ResourceModeReason =
        when (this) {
            ENVIRONMENT -> ResourceModeReason.ENVIRONMENT_OVERRIDE
            PRESSURE_RESTART -> ResourceModeReason.PRESSURE_RESTART
            SETTINGS -> ResourceModeReason.USER_SELECTION
        }

    /** Where to tell the operator to look, when a value from here could not be honoured. */
    fun describeOrigin(): String =
        when (this) {
            ENVIRONMENT -> ResourceModeConfig.MODE_KEY
            PRESSURE_RESTART -> "resource-mode.json (nextLaunchMode)"
            SETTINGS -> "resource-mode.json (selectedMode)"
        }
}

/**
 * Total-RAM ceilings, in GB, that automatic selection compares against.
 *
 * A pair rather than two loose parameters because they are only ever meaningful together: the
 * ultra ceiling has to sit at or below the lite one, and [normalized] is the single place that
 * invariant is enforced.
 */
data class ResourceModeThresholds(
    val liteGb: Int = ResourceModeConfig.DEFAULT_LITE_THRESHOLD_GB,
    val ultraLiteGb: Int = ResourceModeConfig.DEFAULT_ULTRA_LITE_THRESHOLD_GB,
) {
    /**
     * Ultra Lite is tested first, so an ultra ceiling above the lite one would make LITE
     * unreachable and quietly put a mid-size machine in the tightest tier. These arrive as two
     * independent free-form Settings inputs, so coerce to the nearer intent rather than reject.
     */
    fun normalized(): ResourceModeThresholds = copy(ultraLiteGb = ultraLiteGb.coerceAtMost(liteGb))
}

/**
 * Resolves the process's [BossResourceMode] once, at startup.
 *
 * Deliberately mirrors the shape of [JxBrowserConfig.renderingMode]: a logged `by lazy`
 * value over a **pure** resolver, so the platform and memory decisions are unit-testable
 * without an engine, a window or a machine of a particular size.
 *
 * Read through [ConfigLoader] (env var, system property or local.properties), matching
 * `BOSS_RENDERING_MODE`.
 *
 * **This is not covered by "a tier only removes capability", and that justification should not be
 * reused.** It was the original reasoning here and it is wrong: `--renderer-process-limit` is a
 * security control, not only a memory one. Lowering it forces unrelated origins to share a
 * renderer, which is exactly the boundary Site Isolation exists to hold, so a tighter tier widens
 * the blast radius of a renderer compromise rather than narrowing anything. That is a capability
 * *change*, not a removal.
 *
 * It is accepted here on narrower grounds: `local.properties` is a developer-checkout file that
 * ships in no build, the tightest limit BOSS sets (2) is still Chromium's own documented switch
 * rather than `--disable-site-isolation-trials`, and the value is surfaced in Settings and in the
 * startup log. The sharp edge left is that a stale entry persists across every run of a checkout
 * with nothing in the UI to clear it - see [ResourceModeReason.ENVIRONMENT_OVERRIDE]. A key that
 * granted capability would still have to bypass ConfigLoader entirely.
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

    /** Matches `range = 1..1024` on the Settings inputs, so both paths agree. */
    internal const val MIN_THRESHOLD_GB = 1
    internal const val MAX_THRESHOLD_GB = 1024

    internal const val DEFAULT_LITE_THRESHOLD_GB = 16
    internal const val DEFAULT_ULTRA_LITE_THRESHOLD_GB = 8

    private val FULL_SPELLINGS = setOf("FULL", "NONE", "OFF")
    private val LITE_SPELLINGS = setOf("LITE")
    private val ULTRA_LITE_SPELLINGS = setOf("ULTRALITE", "ULTRA_LITE", "ULTRA-LITE", "MINIMAL")
    private val AUTO_SPELLINGS = setOf("AUTO", "DETECT")

    /**
     * The tier this process runs in, decided once at startup.
     *
     * Startup-only on purpose. `--renderer-process-limit` is a Chromium command-line switch
     * read once when the engine initialises, so a value that changed underneath callers would
     * describe a process that does not exist. The live watchdog in `MemoryPressureWatchdog`
     * handles mid-session pressure with the reversible levers only, and asks for a restart for
     * the rest.
     */
    val decision: ResourceModeDecision by lazy {
        val settings = ResourceModeSettings.current()
        // An env var or system property still outranks the Settings choice, matching how
        // BOSS_RENDERING_MODE behaves: the operator's environment is the outer authority, and
        // it is the escape hatch when a persisted choice turns out to be the problem.
        val fromEnvironment = ConfigLoader.getConfig(MODE_KEY)
        val (raw, source) =
            resolveRawAndSource(
                fromEnvironment = fromEnvironment,
                fromPressure = settings.nextLaunchMode,
                fromSettings = settings.selectedMode,
            )
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val totalBytes = SystemMemory.totalPhysicalBytes()
        val resolved =
            resolveResourceMode(
                raw = raw,
                os = os,
                totalMemoryBytes = totalBytes,
                thresholds =
                    ResourceModeThresholds(
                        liteGb = thresholdGb(LITE_THRESHOLD_GB_KEY, settings.liteThresholdGb),
                        ultraLiteGb =
                            thresholdGb(ULTRA_LITE_THRESHOLD_GB_KEY, settings.ultraLiteThresholdGb),
                    ),
                explicitSource = source,
            )

        if (!raw.isNullOrBlank() && !isRecognizedResourceMode(raw)) {
            // Unset and AUTO are the normal cases and stay silent. Only a value we could
            // not honour is worth a warning, since it otherwise silently keeps the default.
            //
            // Named by its real origin. This used to say "$MODE_KEY" whatever the source, so an
            // unreadable `selectedMode` in resource-mode.json - written by a newer build, or hand
            // edited - pointed the operator at an environment variable they had never set.
            logger.warn(
                LogCategory.SYSTEM,
                "Ignoring unrecognized resource mode - falling back to automatic selection",
                mapOf(
                    "value" to raw,
                    "from" to source.describeOrigin(),
                    "using" to resolved.mode.name,
                ),
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
                "rendererLimit" to (resolved.mode.rendererProcessLimit?.toString() ?: "default"),
            ),
        )
        resolved
    }

    /**
     * The tier currently in force, which is [decision]'s tier unless the live memory-pressure
     * watchdog has tightened it since startup.
     *
     * Callers get whichever is stricter *at the moment they ask*. Note that every lever this
     * tier currently owns is read once at startup (`FluckEngine`'s renderer cap, the sampler),
     * so a live tighten changes little until the plugin reads [HIBERNATION_IDLE_PROPERTY]; the
     * notice dialog says as much rather than claiming a saving that did not happen.
     */
    private val _effectiveMode: MutableStateFlow<BossResourceMode> by lazy {
        MutableStateFlow(decision.mode)
    }

    val effectiveMode: StateFlow<BossResourceMode> get() = _effectiveMode

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
        val current = _effectiveMode.value
        // Ordinal order is FULL < LITE < ULTRA_LITE, i.e. increasingly constrained.
        if (target.ordinal <= current.ordinal) return false
        _effectiveMode.value = target
        logger.info(
            LogCategory.SYSTEM,
            "Resource mode tightened for this session",
            mapOf("from" to current.name, "to" to target.name),
        )
        return true
    }

    /**
     * Whether the live memory-pressure watchdog is allowed to act. Defaults to on.
     *
     * Three-state, not two. An unrecognized value must fall through to the Settings toggle rather
     * than count as "on": reading `BOSS_RESOURCE_LIVE_PRESSURE=disabled` as an enable would both
     * invert the operator's obvious intent and silently override a user who turned the watchdog
     * off in Settings.
     */
    val livePressureEnabled: Boolean by lazy {
        val raw = ConfigLoader.getConfig(LIVE_PRESSURE_KEY)?.trim()?.lowercase()
        when (raw) {
            in TRUTHY_FLAGS -> true
            in FALSY_FLAGS -> false
            else -> ResourceModeSettings.current().livePressureEnabled
        }
    }

    private val TRUTHY_FLAGS = setOf("true", "1", "yes", "on")
    private val FALSY_FLAGS = setOf("false", "0", "no", "off")

    /**
     * Requests Ultra Lite for **the next launch only**.
     *
     * Written to a one-shot field rather than to the Settings selection, and consumed by
     * [decision]. Writing the selection instead made a single click under memory pressure the
     * permanent tier, and made Settings attribute it to the user; see
     * [ResourceModeReason.PRESSURE_RESTART].
     */
    fun requestUltraLiteOnNextLaunch() {
        ResourceModeSettings.update { it.copy(nextLaunchMode = BossResourceMode.ULTRA_LITE.name) }
        logger.info(LogCategory.SYSTEM, "Ultra Lite requested for the next launch only")
    }

    /**
     * System property carrying the tier's [BossResourceMode.hibernationIdleMs] to plugins.
     *
     * Plugins cannot see host classes, so a published property is the seam - the same shape as
     * `boss.api.version` and `boss.ipc.version`. fluck-browser does not read it yet.
     */
    const val HIBERNATION_IDLE_PROPERTY = "boss.browser.hibernationIdleMs"

    /**
     * Publishes the tier's settings where plugins can read them, and consumes any one-shot
     * request. Call once, at startup.
     *
     * The consumption lives here rather than in [decision] so that merely *asking* what tier this
     * is never writes to disk. A lazy that wrote to `~/.boss` as a side effect made every test
     * that so much as rendered the Settings screen touch the developer's real home directory, and
     * made the one-shot's "exactly once" depend on who read the value first.
     */
    fun publishToPlugins() {
        System.setProperty(HIBERNATION_IDLE_PROPERTY, mode.hibernationIdleMs.toString())
        // Cleared whenever one is present, not only when it resolved. Keying on the reason left an
        // unrecognized value stuck in the file for good, since a value that fails to resolve never
        // produces PRESSURE_RESTART. A one-shot has had its one launch either way.
        if (ResourceModeSettings.current().nextLaunchMode != null) {
            ResourceModeSettings.update { it.copy(nextLaunchMode = null) }
            logger.info(
                LogCategory.SYSTEM,
                "Consumed the one-shot resource-mode request",
                mapOf("honoured" to (decision.reason == ResourceModeReason.PRESSURE_RESTART).toString()),
            )
        }
    }

    /**
     * Picks the explicit tier string and says where it came from. Pure, so the precedence is
     * testable without an environment, a settings file or a machine.
     *
     * Environment beats the one-shot beats the stored selection. The one-shot has to outrank the
     * selection - it exists precisely because that selection was too loose for this machine - and
     * must never *become* it.
     */
    internal fun resolveRawAndSource(
        fromEnvironment: String?,
        fromPressure: String?,
        fromSettings: String?,
    ): Pair<String?, ResourceModeSource> =
        when {
            fromEnvironment != null -> fromEnvironment to ResourceModeSource.ENVIRONMENT

            // Only a one-shot we can actually honour gets to outrank the selection. Taking it
            // unconditionally made an unrecognized value - written by a newer build, truncated, or
            // hand-edited - shadow `selectedMode` permanently: it won the precedence, failed to
            // resolve, and so was never consumed either, because consumption keys on the decision
            // having come out as PRESSURE_RESTART. The user's Settings choice then silently never
            // applied again, on any launch, with nothing in the UI able to clear it.
            isRecognizedResourceMode(fromPressure) -> fromPressure to ResourceModeSource.PRESSURE_RESTART

            else -> fromSettings to ResourceModeSource.SETTINGS
        }

    /**
     * Pure part of [decision], split out so the platform and memory decisions are testable
     * without a machine of a particular size.
     *
     * Precedence, highest first:
     *  1. An explicit, recognized [raw] value. Also the escape hatch: a user on a small
     *     machine who wants everything can set FULL, and one on a large machine who wants
     *     the footprint can set ULTRALITE.
     *  2. Windows, which defaults to [BossResourceMode.LITE].
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
        thresholds: ResourceModeThresholds = ResourceModeThresholds(),
        explicitSource: ResourceModeSource = ResourceModeSource.SETTINGS,
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
            ?.let { ResourceModeDecision(it, explicitSource.toReason()) }
            ?: detectResourceMode(os, totalMemoryBytes, thresholds.normalized())
    }

    /** The automatic half of [resolveResourceMode], reached when nothing explicit applied. */
    private fun detectResourceMode(
        os: String,
        totalMemoryBytes: Long,
        thresholds: ResourceModeThresholds,
    ): ResourceModeDecision {
        // Windows defaults to LITE. It is the platform where memory pressure is worst-felt and
        // where OFF_SCREEN forced the entire Compose UI through the CPU before #128.
        //
        // LITE and not ULTRA_LITE, deliberately. The renderer limit is a *security* control as
        // well as a memory one: capping renderers forces unrelated origins to share a process,
        // which is exactly the boundary Chromium's Site Isolation exists to hold, and at
        // ULTRA_LITE's limit of 2 a browser used for real work puts very nearly every tab in one
        // process. That widens the blast radius of any single renderer compromise or crash to
        // every site open in it. Paying that on every Windows machine by default is a poor trade
        // when the tier's other distinguishing lever (plugin gating) has been removed and its
        // remaining one (hibernation timing) is not yet read by the plugin. Users who want the
        // tightest tier can still select it.
        //
        // startsWith, NOT contains: "darwin" contains "win". Windows reports "Windows 10",
        // "Windows 11", "Windows Server 2022" - all prefixes. JxBrowserRenderingModeTest
        // keeps a "darwin" case alive for exactly this reason.
        if (os.startsWith("win")) {
            return ResourceModeDecision(
                BossResourceMode.LITE,
                ResourceModeReason.PLATFORM_DEFAULT,
            )
        }

        val totalGb = totalMemoryBytes.toDouble() / BYTES_PER_GB
        return when {
            // 0 or negative means "could not read", which is not evidence of a small machine.
            totalMemoryBytes <= 0L -> {
                ResourceModeDecision(BossResourceMode.FULL, ResourceModeReason.DEFAULT)
            }

            totalGb < thresholds.ultraLiteGb -> {
                ResourceModeDecision(BossResourceMode.ULTRA_LITE, ResourceModeReason.DETECTED_MEMORY)
            }

            totalGb < thresholds.liteGb -> {
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

    /**
     * A GB threshold from the environment, bounded to the same range the Settings input allows.
     *
     * Bounded because the UI's `range = 1..1024` was the only thing enforcing it, which left
     * `BOSS_RESOURCE_LITE_GB=100000` putting every machine that ever runs BOSS into LITE.
     */
    private fun thresholdGb(
        key: String,
        default: Int,
    ): Int =
        ConfigLoader
            .getConfig(key)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.coerceIn(MIN_THRESHOLD_GB, MAX_THRESHOLD_GB)
            ?: default

    internal const val BYTES_PER_GB = 1024L * 1024L * 1024L
}
