package ai.rever.boss.startup

/** Compose Desktop's native-window layer selector, read when the first Compose container starts. */
internal const val COMPOSE_LAYER_TYPE_PROPERTY = "compose.layers.type"

/** The value Compose 1.12 maps to `LayerType.OnWindow`. */
internal const val COMPOSE_WINDOW_LAYER_TYPE = "WINDOW"

internal enum class MaterialPopupLayeringResult {
    ENABLED,
    ALREADY_ENABLED,
    EXPLICIT_OVERRIDE,
    UNCHANGED_PLATFORM,
}

/**
 * Gives ordinary Material popups their own native layer on macOS.
 *
 * Dynamic plugins share the host's Compose runtime, but their Material `DropdownMenu` calls
 * `androidx.compose.ui.window.Popup` directly. It cannot see BOSS's `BossPopup` routing local.
 * Compose Desktop 1.12's window layer is therefore the integration point that reaches the real
 * plugin call path while retaining Material's own measurement, edge-aware position provider,
 * keyboard focus and dismissal behavior.
 *
 * The property must be set before any Compose/AWT container is created. An explicit JVM property
 * is respected as a recovery escape hatch. Other platforms retain their existing overlay strategy
 * because the reported OFF_SCREEN sibling-pane failure is macOS-specific.
 */
internal fun configureMaterialPopupLayering(
    osName: String = System.getProperty("os.name").orEmpty(),
    currentLayerType: String? = System.getProperty(COMPOSE_LAYER_TYPE_PROPERTY),
    setProperty: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
): MaterialPopupLayeringResult =
    when {
        !osName.contains("mac", ignoreCase = true) -> {
            MaterialPopupLayeringResult.UNCHANGED_PLATFORM
        }

        currentLayerType == null -> {
            setProperty(COMPOSE_LAYER_TYPE_PROPERTY, COMPOSE_WINDOW_LAYER_TYPE)
            MaterialPopupLayeringResult.ENABLED
        }

        currentLayerType.trim().equals(COMPOSE_WINDOW_LAYER_TYPE, ignoreCase = true) -> {
            MaterialPopupLayeringResult.ALREADY_ENABLED
        }

        else -> {
            MaterialPopupLayeringResult.EXPLICIT_OVERRIDE
        }
    }
