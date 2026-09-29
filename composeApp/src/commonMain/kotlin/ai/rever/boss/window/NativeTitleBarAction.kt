package ai.rever.boss.window

import ai.rever.boss.plugin.browser.BrowserAddressBarState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

internal data class NativeTitleBarAction(
    val id: String,
    val label: String,
    val symbol: String? = null,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val textInput: NativeTitleBarTextInput? = null,
    val icon: ImageVector? = null,
    val contextMenu: List<NativeTitleBarAction> = emptyList(),
    val menu: List<NativeTitleBarAction>? = null,
    val subtitle: String? = null,
    val sidebarLeading: Float = 0f,
    val sidebarWidth: Float = 0f,
    val onClick: () -> Unit,
)

/** The AppKit title-bar/sidebar integration is exclusive to macOS. */
internal fun usesNativeSidebarTitleBar(
    isMacOs: Boolean,
    position: TabBarPosition,
): Boolean = isMacOs && position == TabBarPosition.LEFT

internal class NativeTitleBarTextInput(
    val identity: String,
    val value: String,
    val onSubmit: (String) -> Unit,
    val onHosted: ((() -> Unit)?) -> Unit,
    val address: BrowserAddressBarState? = null,
    val favicon: Painter? = null,
)
