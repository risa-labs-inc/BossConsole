package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.profile.Profile
import java.util.concurrent.ConcurrentHashMap

/**
 * Which browser profile each window's tabs run on.
 *
 * A window opened for a same-account BOSS profile shares this process's sign-in, plugins and
 * plugin settings, but its browser tabs get a JxBrowser profile of their own - separate cookies,
 * logins, storage and history - inside the one shared engine. A window with no binding (every
 * window by default) uses the engine's default profile, exactly as before.
 *
 * Deliberately NOT the managed-profile machinery in [BrowserServiceImpl]: a named managed profile
 * holds a per-name fence for the life of each browser, so it serves one browser at a time and is
 * LRU-evicted under a disk cap. A window profile serves every tab in its windows at once and is
 * never evicted - it holds the user's logins.
 */
object WindowBrowserProfiles {
    private val logger = BossLogger.forComponent("WindowBrowserProfiles")

    /** JxBrowser profile names for window profiles; never swept as RPA orphans. */
    internal const val PROFILE_PREFIX = "boss-window-"

    private val profileByWindow = ConcurrentHashMap<String, String>()

    /**
     * What a window profile needs from the engine, separable so the same-engine guarantee is
     * testable without JxBrowser. [withEngine] must hold whatever lock replaces the engine.
     */
    internal interface EngineOps<E : Any, P : Any> {
        fun <T> withEngine(block: (E) -> T): T

        fun findOrCreate(
            engine: E,
            name: String,
        ): P

        fun prepare(profile: P)
    }

    /** One window-profile lookup, with its engine, profile and handler claim from one engine. */
    internal class Resolver<E : Any, P : Any>(
        private val ops: EngineOps<E, P>,
    ) {
        private val prepared = EnginePerProfileOnce()

        fun profileNamed(name: String): P =
            ops.withEngine { engine ->
                val profile = ops.findOrCreate(engine, name)
                if (prepared.claim(engine, name)) {
                    // Handlers the default profile gets at engine creation; a recycled engine is a
                    // new object, so they are installed again on its copy of the profile.
                    runCatching { ops.prepare(profile) }
                        .onFailure {
                            prepared.release(engine, name)
                            logger.warn(LogCategory.BROWSER, "Could not prepare a window browser profile", error = it)
                        }
                }
                profile
            }
    }

    private val resolver =
        Resolver(
            object : EngineOps<Engine, Profile> {
                override fun <T> withEngine(block: (Engine) -> T): T = FluckEngine.withCurrentEngine(block)

                override fun findOrCreate(
                    engine: Engine,
                    name: String,
                ): Profile {
                    val profiles = engine.profiles()
                    return profiles.list().firstOrNull { it.name() == name } ?: profiles.newProfile(name)
                }

                override fun prepare(profile: Profile) = FluckEngine.setupPermissionHandlers(profile)
            },
        )

    /** Runs [windowId]'s browser tabs on BOSS profile [profileId]'s browser profile. */
    fun bind(
        windowId: String,
        profileId: String,
    ) {
        require(BossDirectories.isValidProfileId(profileId)) { "Invalid BOSS profile id '$profileId'" }
        profileByWindow[windowId] = profileId
    }

    fun unbind(windowId: String) {
        profileByWindow.remove(windowId)
    }

    /** The BOSS profile [windowId]'s tabs run on, or null for the default browser profile. */
    fun profileIdFor(windowId: String): String? = profileByWindow[windowId]

    /** Windows currently open on BOSS profile [profileId]. */
    fun windowsFor(profileId: String): List<String> = profileByWindow.filterValues { it == profileId }.keys.toList()

    internal fun jxProfileName(profileId: String): String = PROFILE_PREFIX + profileId

    /**
     * The JxBrowser profile a plain tab in [windowId] should be created on, creating it the first
     * time; null for a window on the default profile. Persistent: JxBrowser keeps a named
     * profile's data in the engine's user data directory across restarts.
     */
    fun jxProfileFor(windowId: String): Profile? {
        val profileId = profileByWindow[windowId] ?: return null
        return resolver.profileNamed(jxProfileName(profileId))
    }
}
