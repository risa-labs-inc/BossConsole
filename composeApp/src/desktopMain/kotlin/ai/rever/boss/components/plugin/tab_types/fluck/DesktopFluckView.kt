package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.view.compose.BrowserView
import com.teamdev.jxbrowser.view.compose.BrowserViewState

/**
 * Desktop actual for FluckView.
 *
 * This is a minimal stub — the full browser UI is provided by the dynamic
 * fluck-browser plugin (FluckBrowserTabComponent). This actual only exists
 * to satisfy the expect/actual contract for code paths that still reference
 * the common FluckTabComponent.
 */
@Composable
actual fun FluckView(
    fileId: String,
    content: String,
    browser: Any?,
    browserViewState: Any?,
    browserLock: Any?,
    onContentChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onIconChange: (ImageVector) -> Unit,
    onTabIconUpdate: (TabIcon) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onNavigationUpdate: ((String, String) -> Unit)?,
    onNavigationStateChange: ((isBack: Boolean) -> Unit)?,
    onFaviconCached: ((String?) -> Unit)?,
    onCloseTab: (() -> Unit)?,
) {
    val jxBrowser = browser as? Browser
    val jxBrowserViewState = browserViewState as? BrowserViewState

    if (jxBrowser != null && jxBrowserViewState != null && !jxBrowser.isClosed) {
        BrowserView(state = jxBrowserViewState)
    }
}
