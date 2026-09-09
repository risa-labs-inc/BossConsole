package ai.rever.boss.components.plugin

/**
 * Plugins whose live instance must never be force-unloaded and swapped in place.
 *
 * A hot-reload - an in-place jar overwrite picked up via `resetPluginInstances`/`doReloadPlugin`,
 * a Toolbox update, or a manual "Reload Panel" - force-unloads the running plugin's classloader
 * and loads a fresh one. That is safe for a plugin whose state is Compose composition plus
 * whatever its unload actions clean up: the next open of a panel/tab simply asks the new
 * classloader to build fresh content. It is NOT safe for a plugin that hands the OS a native peer
 * bound to the classloader that created it - closing that classloader does not un-bind the peer,
 * so every already-open surface, and every new one (the factory that would recreate it is gone
 * too), draws nothing (BossConsole#71).
 *
 * `fluck-browser` is exactly this: its browser tabs are JxBrowser views. The issue's own
 * reproduction confirms a SECOND hot-reload does not recover it either - only a full process
 * restart does, because the native view's owning classloader is never released until every
 * reference to it is gone, which force-unload-then-reload does not achieve.
 *
 * No manifest field or host-side capability probe says "I own a native OS surface" today, so -
 * same shape as [PluginDependencyResolution.NOT_USER_INSTALLABLE] and
 * [ai.rever.boss.plugin.RetiredPlugins] - this is a literal, documented set of plugin ids rather
 * than something derived at runtime.
 */
object HotReloadPolicy {
    /** `ai.rever.boss.plugin.dynamic.fluckbrowser` owns a JxBrowser native view - see the class doc. */
    val NOT_HOT_RELOADABLE = setOf(TabTypePlugins.FLUCK_BROWSER)

    /** True when [pluginId] must defer any pending update/reload to a full restart. */
    fun requiresRestartInsteadOfHotReload(pluginId: String): Boolean = pluginId in NOT_HOT_RELOADABLE
}
