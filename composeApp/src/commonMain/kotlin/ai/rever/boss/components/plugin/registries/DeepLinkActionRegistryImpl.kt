package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.api.DeepLinkActionHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of plugin deep-link action handlers, dispatched by
 * DeepLinkHandler for `boss://plugin?id=<handlerId>&action=…` links.
 *
 * Deep links are external input: handlers own parameter validation, and this
 * registry logs parameter KEYS only — values may carry user data.
 */
object DeepLinkActionRegistryImpl {
    private val logger = BossLogger.forComponent("DeepLinkActionRegistry")

    private val _handlers = MutableStateFlow<Map<String, DeepLinkActionHandler>>(emptyMap())

    /** handlerId -> handler. */
    val handlers: StateFlow<Map<String, DeepLinkActionHandler>> = _handlers.asStateFlow()

    fun register(handler: DeepLinkActionHandler) {
        _handlers.update { existing ->
            if (existing.containsKey(handler.handlerId)) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Deep-link handler re-registered (replacing previous)",
                    mapOf(
                        "handlerId" to handler.handlerId,
                    ),
                )
            }
            existing + (handler.handlerId to handler)
        }
        logger.info(LogCategory.SYSTEM, "Deep-link action handler registered", mapOf("handlerId" to handler.handlerId))
    }

    fun unregister(handlerId: String) {
        _handlers.update { it - handlerId }
    }

    /**
     * Route an action deep link to its handler. Returns true only when a
     * handler exists AND reports the action handled; crash-isolated.
     */
    fun dispatch(
        handlerId: String,
        action: String,
        params: Map<String, String>,
    ): Boolean {
        val handler = _handlers.value[handlerId]
        if (handler == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "No deep-link action handler registered",
                mapOf(
                    "handlerId" to handlerId,
                    "action" to action,
                ),
            )
            return false
        }
        return try {
            val handled = handler.handle(action, params)
            if (!handled) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Deep-link action not handled",
                    mapOf(
                        "handlerId" to handlerId,
                        "action" to action,
                        "paramKeys" to params.keys.joinToString(","),
                    ),
                )
            }
            handled
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Deep-link action handler failed",
                mapOf(
                    "handlerId" to handlerId,
                    "action" to action,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
            false
        }
    }
}
