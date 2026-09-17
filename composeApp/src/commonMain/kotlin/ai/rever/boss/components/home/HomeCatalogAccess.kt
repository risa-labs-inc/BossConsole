package ai.rever.boss.components.home

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = BossLogger.forComponent("HomeCatalogAccess")

/**
 * The store half of the home screen's tool grid: what could be installed, and how to install it.
 *
 * Two methods rather than exposing a `PluginRepository`, because those are the only two
 * questions the grid asks, and narrowing them keeps the screen unable to do anything else with
 * the store.
 */
interface HomeCatalogProvider {
    /**
     * Store rows the grid could offer, already reduced and already compatibility-checked.
     *
     * An empty list means a successful empty catalogue. Failures propagate so Home can
     * offer retry while keeping installed tools available. Cancellation also propagates.
     */
    suspend fun discoverable(): List<HomeStorePluginInput>

    /**
     * Install one plugin by id, resolving it against the store.
     *
     * The failure message is shown to the user, so it says what happened rather than surfacing
     * a transport error.
     */
    suspend fun install(pluginId: String): Result<Unit>
}

/**
 * Holds the desktop implementation of [HomeCatalogProvider] for the commonMain home screen.
 *
 * A holder for the same reason as
 * [ai.rever.boss.services.llm.BrokeredCredentialAccess]: the implementation speaks HTTP to the
 * plugin store and installs jars, so it lives in `desktopMain`, while `HomeScreen` is in
 * `commonMain`. Desktop startup registers it.
 *
 * Null until then, and null on any build that registers none - in which case the grid shows
 * only what is installed, which is a strictly better screen than the one this replaces rather
 * than a broken one.
 */
object HomeCatalogAccess {
    private val _provider = MutableStateFlow<HomeCatalogProvider?>(null)

    /**
     * Observable, because the home screen can mount before this is populated.
     *
     * A plain nullable field made the discovery half of the grid a race. The screen is what an
     * empty panel renders, so on a cold start it composes before `DefaultPlugin` has reached
     * `PluginLoaderDelegateSetup.register` and before `PluginStoreSetup` has a repository. A
     * `LaunchedEffect(Unit)` then read null, `.orEmpty()` swallowed it, and nothing re-ran the
     * effect - so no Install tiles appeared until the panel happened to remount. It looked like it
     * worked only because closing a tab remounts the screen.
     *
     * Collecting this and keying the fetch on it means the grid fills in as soon as the store
     * exists, with no polling. Same pattern as `HomeToolAccess`.
     */
    val provider: StateFlow<HomeCatalogProvider?> = _provider.asStateFlow()

    /**
     * Called from desktop startup. **First caller wins.**
     *
     * Guarded rather than last-write-wins because the provider captures one window's
     * `DynamicPluginManager`, and that decides which window's manager an install started from any
     * window's grid loads into. Assigning unconditionally made that "whichever window opened
     * most recently", which is not a property anything should depend on. First-wins is at least
     * stable for the session, and the multi-window consequence is the one
     * `MissingDependencyPrompt` already documents: the answering window may not show the new
     * plugin until relaunch.
     */
    fun initialize(implementation: HomeCatalogProvider) {
        synchronized(this) {
            if (_provider.value != null) {
                logger.debug(LogCategory.SYSTEM, "HomeCatalogAccess already initialized; keeping the first provider")
                return
            }
            _provider.value = implementation
        }
        logger.debug(LogCategory.SYSTEM, "HomeCatalogAccess initialized")
    }

    fun current(): HomeCatalogProvider? = _provider.value
}
