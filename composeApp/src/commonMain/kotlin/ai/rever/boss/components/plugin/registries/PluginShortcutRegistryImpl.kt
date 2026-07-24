package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.api.PluginShortcutSpec
import ai.rever.boss.plugin.api.ShortcutActionProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A plugin shortcut with its owning provider, as exposed to the interceptor
 * and the Shortcuts settings UI (which read only [providerId]/[spec]). The
 * provider reference rides along so the registered list is the single source
 * of truth for dispatch — no parallel provider map to keep in lockstep.
 */
class RegisteredPluginShortcut internal constructor(
    val providerId: String,
    val spec: PluginShortcutSpec,
    internal val provider: ShortcutActionProvider,
)

/**
 * Process-wide registry of plugin-contributed GLOBAL keyboard actions.
 *
 * Specs are snapshotted at registration ([ShortcutActionProvider.shortcuts]
 * is queried once, outside any lock — McpToolRegistry convention); action ids
 * must be namespaced `plugin.<pluginId>.<name>` and non-conforming or
 * duplicate ids are skipped with a warning, guarding collisions with built-in
 * keymap actions.
 *
 * Binding resolution lives at the dispatch site (AWTKeyboardInterceptor):
 * user override in the keymap settings under the actionId wins, else the
 * spec's defaultBinding; host bindings always win conflicts. Unregistration
 * (automatic on plugin disable/unload) makes the chord inert; user rebinds
 * persist in the keymap file across plugin reloads.
 */
object PluginShortcutRegistryImpl {
    private val logger = BossLogger.forComponent("PluginShortcutRegistry")

    /** Action-id namespace required for every plugin shortcut. */
    const val ACTION_ID_PREFIX = "plugin."

    /** Modifiers that make a chord reachable past the interceptor's gate. */
    private val REQUIRED_MODIFIERS = setOf("cmd", "meta", "ctrl", "control", "alt", "option")

    private val _shortcuts = MutableStateFlow<List<RegisteredPluginShortcut>>(emptyList())

    /** All registered plugin shortcuts (interceptor + settings UI read this). */
    val shortcuts: StateFlow<List<RegisteredPluginShortcut>> = _shortcuts.asStateFlow()

    fun register(provider: ShortcutActionProvider) {
        // Snapshot outside any lock; a throwing provider registers with none.
        val specs =
            try {
                provider.shortcuts()
            } catch (t: Throwable) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Shortcut provider shortcuts() failed; registering with none",
                    mapOf(
                        "providerId" to provider.providerId,
                        "error" to (t.message ?: t::class.simpleName),
                    ),
                )
                emptyList()
            }

        val valid =
            specs
                .filter { spec ->
                    val conforming = spec.actionId.startsWith(ACTION_ID_PREFIX)
                    if (!conforming) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Plugin shortcut rejected: actionId must start with '$ACTION_ID_PREFIX'",
                            mapOf(
                                "providerId" to provider.providerId,
                                "actionId" to spec.actionId,
                            ),
                        )
                    }
                    conforming
                }.map { spec ->
                    // The interceptor's early gate drops events without Cmd/Ctrl/Alt,
                    // so a bare or Shift-only default chord can never fire; register
                    // the ACTION unbound instead of pretending the chord works (the
                    // user can still rebind it to a reachable chord in Settings).
                    val default = spec.defaultBinding
                    if (default != null && default.modifiers.none { it.lowercase() in REQUIRED_MODIFIERS }) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Plugin shortcut default requires Cmd/Ctrl/Alt; registering unbound",
                            mapOf(
                                "providerId" to provider.providerId,
                                "actionId" to spec.actionId,
                                "chord" to "${default.modifiers}+${default.key}",
                            ),
                        )
                        spec.copy(defaultBinding = null)
                    } else {
                        spec
                    }
                }

        _shortcuts.update { existing ->
            val others = existing.filterNot { it.providerId == provider.providerId }
            val taken = others.map { it.spec.actionId }.toHashSet()
            others +
                valid.mapNotNull { spec ->
                    if (!taken.add(spec.actionId)) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Duplicate plugin shortcut actionId skipped",
                            mapOf(
                                "providerId" to provider.providerId,
                                "actionId" to spec.actionId,
                            ),
                        )
                        null
                    } else {
                        RegisteredPluginShortcut(provider.providerId, spec, provider)
                    }
                }
        }
        logger.info(
            LogCategory.SYSTEM,
            "Plugin shortcuts registered",
            mapOf(
                "providerId" to provider.providerId,
                "count" to valid.size,
            ),
        )
    }

    fun unregister(providerId: String) {
        _shortcuts.update { list -> list.filterNot { it.providerId == providerId } }
    }

    /**
     * Fire [actionId]'s handler. Returns true when a registered action was
     * dispatched. Called on the UI thread by the interceptor; crash-isolated.
     */
    fun dispatch(
        actionId: String,
        windowId: String?,
    ): Boolean {
        val owner = _shortcuts.value.firstOrNull { it.spec.actionId == actionId } ?: return false
        return try {
            owner.provider.onAction(actionId, windowId)
            true
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin shortcut handler failed",
                mapOf(
                    "actionId" to actionId,
                    "providerId" to owner.providerId,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
            true
        }
    }
}
