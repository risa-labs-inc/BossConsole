package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The config keys the Chromium engine tunables are read from, named once.
 *
 * Every one of these was an environment variable before Settings existed, and the
 * variables keep working — this object is the shared vocabulary between the persisted
 * settings, the startup bridge that publishes them, and the Settings UI that shows
 * which of them the environment has taken over. Three copies of these strings is three
 * chances for a setting to write a key nothing reads.
 */
internal object ChromiumFlagKeys {
    const val RENDERING_MODE = "BOSS_RENDERING_MODE"
    const val SKIKO_RENDER_API = "BOSS_SKIKO_RENDER_API"
    const val TOP_INSET_DP = "BOSS_BROWSER_TOP_INSET_DP"
    const val PREWARM = "BOSS_BROWSER_PREWARM"
    const val RENDERER_PROCESS_LIMIT = "BOSS_RENDERER_PROCESS_LIMIT"
    const val SKIA_GRAPHITE = "BOSS_ENABLE_SKIA_GRAPHITE"
    const val DISABLE_SANDBOX = "BOSS_CHROMIUM_DISABLE_SANDBOX"
    const val EXTRA_SWITCHES = "BOSS_CHROMIUM_EXTRA_SWITCHES"
    const val REMOTE_DEBUGGING_PORT = "BOSS_BROWSER_REMOTE_DEBUGGING_PORT"

    /**
     * Keys [ChromiumFlagsSettingsManager.applyToSystemProperties] publishes. Notably
     * NOT [REMOTE_DEBUGGING_PORT]: that one is read straight off the settings object by
     * FluckEngine, so it can never arrive from local.properties or an embedded build
     * config. See [ChromiumFlagsSettings.remoteDebuggingPort].
     */
    val PUBLISHED =
        listOf(
            RENDERING_MODE,
            SKIKO_RENDER_API,
            TOP_INSET_DP,
            PREWARM,
            RENDERER_PROCESS_LIMIT,
            SKIA_GRAPHITE,
            DISABLE_SANDBOX,
            EXTRA_SWITCHES,
        )

    /**
     * Skiko backends [SKIKO_RENDER_API] accepts, shared with the Settings dropdown so it
     * cannot offer a value main.kt will reject.
     *
     * Validated against an allowlist rather than forwarded raw because main.kt applies
     * this before AWT and Skiko initialise: an unrecognised backend surfaces as a
     * startup crash with no BOSS log line, on exactly the GPU-less RDP/VM machines the
     * pin exists to help.
     */
    val SKIKO_RENDER_APIS = listOf("DIRECT3D", "OPENGL", "METAL", "SOFTWARE_FAST", "SOFTWARE")
}

/**
 * Chromium engine flags chosen in Settings > Browser Engine, persisted to
 * ~/.boss/chromium-flags.json.
 *
 * **Every field is nullable, and null means "no opinion"** — follow the platform
 * default, or whatever the environment says. That is not the same as false: a user who
 * has never opened this screen must get byte-identical engine options to the ones they
 * got before it existed, and a `Boolean` defaulting to false would instead silently
 * turn off flags (`--no-pings`, VA-API decode) that ship on. The nullable form also
 * makes "reset this one row" expressible without knowing the platform's answer.
 *
 * Nothing here applies to a running engine. Chromium's options are fixed when the
 * engine is built, once per process, and the heavyweight-overlay routing is decided at
 * startup from the rendering mode — so the Settings UI offers a restart rather than
 * pretending to be live.
 *
 * @property renderingMode HARDWARE / OFF_SCREEN spelling honoured by
 *   [JxBrowserConfig.resolveRenderingMode]; unrecognised values fall back to the
 *   platform default there rather than being rejected here, so one parser decides.
 * @property remoteDebuggingPort DevTools port, or null for off. Deliberately excluded
 *   from [ChromiumFlagKeys.PUBLISHED] and read directly by FluckEngine: an open
 *   DevTools port is full control of the browser profile, and routing it through
 *   ConfigLoader would let a line in someone's local.properties enable it for every
 *   future run of that checkout. Reachable only from the environment, or from this
 *   file — which the UI writes only behind a confirmation.
 */
@Serializable
data class ChromiumFlagsSettings(
    val renderingMode: String? = null,
    val skikoRenderApi: String? = null,
    val topInsetDp: Int? = null,
    val browserPrewarm: Boolean? = null,
    val rendererProcessLimit: Int? = null,
    val enableSkiaGraphite: Boolean? = null,
    val disableSandbox: Boolean? = null,
    val diskCacheMb: Int? = null,
    val noPings: Boolean? = null,
    val disableDomainReliability: Boolean? = null,
    val disableWinOcclusion: Boolean? = null,
    val enableVaapi: Boolean? = null,
    val remoteDebuggingPort: Int? = null,
    val extraSwitches: String? = null,
) {
    /** Whether the user has expressed any opinion at all — drives "Reset" being offered. */
    val isDefault: Boolean get() = this == ChromiumFlagsSettings()

    /**
     * The value to publish for [key], or null to publish nothing.
     *
     * **There is no compile-time guarantee here, despite the `when`.** It matches on a
     * `String` with an `else` branch, so adding a field to this class and forgetting to
     * add it below compiles perfectly and publishes nothing — a Settings row that
     * persists, reads back, and changes nothing. An earlier version of this doc claimed
     * exhaustiveness; it was wrong, and the claim was worse than no claim because it
     * discouraged looking for the guard that actually catches this.
     *
     * The real guard is in ChromiumFlagsSettingsTest, which derives the field count from
     * the serializer descriptor rather than restating it, so a new field fails the suite.
     */
    internal fun publishedValue(key: String): String? =
        when (key) {
            ChromiumFlagKeys.RENDERING_MODE -> renderingMode
            ChromiumFlagKeys.SKIKO_RENDER_API -> skikoRenderApi
            ChromiumFlagKeys.TOP_INSET_DP -> topInsetDp?.toString()
            ChromiumFlagKeys.PREWARM -> browserPrewarm?.toString()
            ChromiumFlagKeys.RENDERER_PROCESS_LIMIT -> rendererProcessLimit?.toString()
            ChromiumFlagKeys.SKIA_GRAPHITE -> enableSkiaGraphite?.toString()
            ChromiumFlagKeys.DISABLE_SANDBOX -> disableSandbox?.toString()
            ChromiumFlagKeys.EXTRA_SWITCHES -> extraSwitches?.takeIf { it.isNotBlank() }
            else -> null
        }
}

/**
 * Persistence and startup publication for [ChromiumFlagsSettings].
 *
 * Loaded synchronously in `init`, matching [BrowserEngineSettingsManager]: main.kt
 * publishes these before AWT and Skiko initialise and long before the first frame, so
 * an async load would race the very reads it exists to feed.
 */
object ChromiumFlagsSettingsManager {
    private val logger = BossLogger.forComponent("ChromiumFlagsSettingsManager")

    // `internal var` purely so tests can redirect it. updateSettings persists on every call, so a
    // test exercising it against the default path writes the developer's (and CI's) real
    // ~/.boss/chromium-flags.json - a test that mutates the machine it runs on. Production never
    // reassigns this.
    //
    // Redirection covers WRITES ONLY. `bootSettings = loadSync()` runs at object initialisation,
    // off the default path, before any test can reassign - so a test that redirects and then
    // expects a load to follow will read the real file, not its temp one.
    internal var settingsFile = BossDirectories.resolve("chromium-flags.json")
        set(value) {
            // A different file means nothing is known to be persisted in it, so the coalescing
            // cache must not carry across. Without this a redirected test could have its first
            // write skipped because an earlier test in the same JVM happened to persist the same
            // snapshot - order- and machine-dependent, and it would present as a missing file.
            field = value
            lastPersisted = null
        }

    // `internal var` purely so tests can stub the environment - a JVM cannot set its own
    // environment variables, and one exported BOSS_* variable would otherwise leak into every
    // test that reads envOverride. Production never reassigns this; the default System::getenv
    // is what runs in the field.
    //
    // Declared before `bootSettings = loadSync()` on purpose: object initialisers run in
    // declaration order, so an initialiser above this line that reached envOverride would
    // read a not-yet-assigned envReader and die at object init before a logger could say why.
    // In the old layout this var sat below bootSettings, so the very first load ran first.
    internal var envReader: (String) -> String? = System::getenv
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * The settings this process started with.
     *
     * Every flag here is restart-scoped, so this is what "needs a restart" is measured
     * against: any difference from it is a change the running engine cannot have picked
     * up. Comparing against the live engine's switch list instead would miss the flags
     * that are not switches at all — the sandbox opt-out, the Skiko backend, the
     * DevTools port, prewarm — and would have nothing to say before the engine boots.
     */
    val bootSettings: ChromiumFlagsSettings = loadSync()

    private val _currentSettings = MutableStateFlow(bootSettings)
    val currentSettings: StateFlow<ChromiumFlagsSettings> = _currentSettings.asStateFlow()

    private fun loadSync(): ChromiumFlagsSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<ChromiumFlagsSettings>(settingsFile.readText())
            } else {
                ChromiumFlagsSettings()
            }
        } catch (e: Exception) {
            // Defaults, not a crash: this file decides how the browser composites, and a
            // hand-edited or truncated one must not be able to stop the app booting.
            logger.warn(LogCategory.BROWSER, "Error loading Chromium flag settings, using defaults", error = e)
            ChromiumFlagsSettings()
        }

    /**
     * Publish the persisted flags as system properties so every existing read site sees
     * them through [ConfigLoader] with no change to how it resolves anything.
     *
     * [ConfigLoader.resolve] already ranks **env > system property > local.properties >
     * embedded > default**, so slotting settings in at the system-property tier gives
     * two properties worth stating plainly, because users will hit both:
     *
     *  - **An environment variable still wins.** Debugging one session by exporting a
     *    variable keeps working, and keeps working the way it always did.
     *  - **Settings beat local.properties.** A developer's checked-out defaults no
     *    longer silently outrank a choice made in the app.
     *
     * A key the environment already owns is skipped rather than set, purely so the log
     * says so — [ConfigLoader] would rank the env value first either way. The Settings
     * UI surfaces the same conflict per row, since otherwise a user with
     * BOSS_RENDERING_MODE exported watches a dropdown do nothing.
     *
     * Must run before anything reads these keys — before the Skiko block in main.kt
     * (which reads [ChromiumFlagKeys.SKIKO_RENDER_API] before AWT starts) and before
     * the first touch of [JxBrowserConfig.renderingMode], whose `by lazy` caches
     * forever.
     */
    fun applyToSystemProperties() {
        val settings = _currentSettings.value
        val wanted = ChromiumFlagKeys.PUBLISHED.mapNotNull { key -> settings.publishedValue(key)?.let { key to it } }
        val (envOwned, toPublish) = wanted.partition { (key, _) -> envOverride(key) != null }

        val published = toPublish.toMap()
        published.forEach { (key, value) -> System.setProperty(key, value) }

        if (published.isNotEmpty()) {
            // An audit trail on a par with the extra-switches one in FluckEngine: these
            // values change how the browser composites and how hardened it is, so a
            // session should say what it is running with.
            logger.info(
                LogCategory.BROWSER,
                "Applied Chromium flag settings",
                mapOf("settings" to published.entries.joinToString(" ") { "${it.key}=${it.value}" }),
            )
        }
        if (envOwned.isNotEmpty()) {
            logger.info(
                LogCategory.BROWSER,
                "Chromium flag settings ignored for keys set in the environment",
                mapOf("keys" to envOwned.joinToString(" ") { it.first }),
            )
        }
    }

    /**
     * The environment's value for [key], or null. Used by the Settings UI to say a row
     * is overridden instead of letting it look broken. Reads the environment ONLY: a
     * system property here would report this object's own publication back to it.
     *
     * Blank reads as UNSET. `FOO= boss` exports an empty string, which is non-null, so a bare
     * getenv let an empty variable claim ownership of a key and silently suppress the user's
     * setting - reported in the UI as an override with no value to show.
     */
    fun envOverride(key: String): String? = envReader(key)?.takeIf { it.isNotBlank() }

    /**
     * What [key] would resolve to on the next launch given [settings] — **env first,
     * then the setting**, which is the same order [applyToSystemProperties] produces.
     *
     * Exists so the "next launch" command-line preview cannot drift from the real
     * resolution. It deliberately does not consult system properties: those hold the
     * values published from [bootSettings] at startup, so a preview reading them would
     * show what this session already has rather than what the user just chose. It also
     * skips local.properties and the embedded config — a preview that silently swapped
     * in a checkout-level default would misreport the effect of the visible setting.
     */
    internal fun previewValue(
        settings: ChromiumFlagsSettings,
        key: String,
    ): String? = envOverride(key) ?: settings.publishedValue(key)

    /**
     * Serialises the disk writes. This screen writes far more often than the version dropdown
     * this persistence pattern was copied from: `SettingsNumberInput` fires on every keystroke
     * that parses in range, so typing `8192` into the disk-cache field launches four coroutines
     * in order and, unsynchronised, they can land out of order and persist `819`.
     */
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * The last snapshot this process actually wrote, so a queued write that would produce
     * identical bytes can be skipped. Guarded by [writeMutex].
     *
     * **Starts null, deliberately, and must not be seeded from [bootSettings].** They look
     * interchangeable and are not: [loadSync] answers a parse failure with a DEFAULT object while
     * the corrupt file stays on disk, so seeding would record "defaults are persisted" about a
     * file that holds nothing of the sort - and "Reset engine flags", which writes exactly those
     * defaults, would match the cache and skip the write, leaving the corrupt file in place
     * forever with the UI reporting success. Null means "nothing known to be on disk", which is
     * the truth at startup, and it costs one redundant first write.
     */
    private var lastPersisted: ChromiumFlagsSettings? = null

    /**
     * Apply [transform] to the current settings, atomically, and persist the result.
     *
     * A TRANSFORM rather than a finished value. The callers are UI controls computing
     * `current.copy(field = new)` from a snapshot they collected earlier, so two edits landing in
     * the same frame - which per-keystroke number inputs make ordinary - both derive from that one
     * snapshot and the second silently discards the first. `updateAndGet` closes it: each caller
     * reads the value at the moment it applies, not at the moment it composed.
     *
     * **[transform] must be side-effect free.** `updateAndGet` is a CAS loop, so under contention
     * it re-invokes the lambda until the swap succeeds. Every call site today is a pure `copy`,
     * which is fine; logging, toasting or writing from inside it would fire more than once.
     */
    suspend fun updateSettings(transform: (ChromiumFlagsSettings) -> ChromiumFlagsSettings) {
        // In-memory state first, on the CALLER's thread, so the UI and any subsequent read see
        // the new value immediately and cannot observe a write that is still queued behind the
        // mutex. Only the disk write is asynchronous.
        _currentSettings.updateAndGet(transform)
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    settingsFile.parentFile?.mkdirs()
                    // Temp + atomic rename, never a truncating write in place. A crash midway
                    // through writeText leaves a truncated file, and loadSync answers a parse
                    // failure with ChromiumFlagsSettings() — so an interrupted keystroke could
                    // silently reset every flag the user had set, including the security ones.
                    val temp = java.io.File(settingsFile.parentFile, "${settingsFile.name}.tmp")
                    // Read under the lock rather than using a value captured before it: with two
                    // writers, the second could otherwise persist the older of the two snapshots.
                    val toPersist = _currentSettings.value
                    // Per-keystroke inputs queue several writes that all resolve to the same final
                    // snapshot once `toPersist` is re-read under the lock, so typing "8192" used to
                    // write the identical bytes four times - now five syscalls each. Skip the ones
                    // that would change nothing.
                    if (toPersist == lastPersisted) return@withLock
                    createOwnerOnly(temp)
                    temp.writeText(json.encodeToString(ChromiumFlagsSettings.serializer(), toPersist))
                    restrictAfterWrite(temp)
                    java.nio.file.Files.move(
                        temp.toPath(),
                        settingsFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                    lastPersisted = toPersist
                    logger.debug(LogCategory.BROWSER, "Chromium flag settings saved")
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Error saving Chromium flag settings", error = e)
                }
            }
        }
    }

    /**
     * Owner-only permissions, because of what this file can do on the next launch.
     *
     * Anything that can write it gets arbitrary Chromium switches and the sandbox opt-out applied
     * at startup, with none of it passing through the confirmation dialogs. That makes it strictly
     * more powerful than local.properties, which [ChromiumFlagsSettings.remoteDebuggingPort] is
     * already careful to keep out of the DevTools path for the same class of reason — so leaving
     * this at the default umask would have reasoned hard about the weaker surface and ignored the
     * stronger one. `~/.boss/run/single-instance` is already owner-only; same treatment.
     *
     * Best-effort: a filesystem without POSIX permissions (a Windows share) must not stop the
     * settings from saving, so a failure is logged and the write proceeds.
     */
    private fun createOwnerOnly(file: java.io.File) {
        try {
            val perms =
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------")
            // Created WITH the permissions rather than chmod-ed after writing. Writing first and
            // restricting second left the contents readable at the default umask for the width of
            // that call - a small window, but an avoidable one on a file that can turn off the
            // Chromium sandbox.
            java.nio.file.Files
                .deleteIfExists(file.toPath())
            java.nio.file.Files
                .createFile(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions
                        .asFileAttribute(perms),
                )
        } catch (e: UnsupportedOperationException) {
            logger.debug(
                LogCategory.BROWSER,
                "Filesystem has no POSIX permissions - Chromium flag settings left at default",
                mapOf("error" to e.toString()),
            )
        } catch (e: Exception) {
            logger.warn(
                LogCategory.BROWSER,
                "Could not create Chromium flag settings owner-only",
                mapOf("error" to e.toString()),
            )
        }
    }

    /**
     * Restrict [file] after the fact, as a fallback for [createOwnerOnly].
     *
     * Creating the file with its permissions closes the write window, but it also became the ONLY
     * mechanism - so any failure there left the file at the default umask with nothing but a log
     * line, which is weaker than the chmod-after-write it replaced. Belt and braces: create
     * restricted, then confirm restricted. Cheap, and the failure mode it guards is a file that
     * can turn off the Chromium sandbox being world-readable.
     */
    private fun restrictAfterWrite(file: java.io.File) {
        try {
            java.nio.file.Files
                .setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions
                        .fromString("rw-------"),
                )
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem. createOwnerOnly already reported it, so this is debug rather
            // than a second warning for the same cause - but it is logged, not swallowed.
            logger.debug(
                LogCategory.BROWSER,
                "Filesystem has no POSIX permissions - fallback restrict skipped",
                mapOf("error" to e.toString()),
            )
        } catch (e: Exception) {
            logger.warn(
                LogCategory.BROWSER,
                "Could not restrict Chromium flag settings to owner-only",
                mapOf("error" to e.toString()),
            )
        }
    }

    suspend fun resetToDefault() = updateSettings { ChromiumFlagsSettings() }
}
