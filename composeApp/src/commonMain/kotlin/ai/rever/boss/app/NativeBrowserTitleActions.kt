package ai.rever.boss.app

import ai.rever.boss.cache.loadHighQualityFavicon
import ai.rever.boss.components.common.rememberFaviconCacheKey
import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
import ai.rever.boss.plugin.browser.BrowserTabOwnership
import ai.rever.boss.plugin.browser.BrowserTitleBarBridge
import ai.rever.boss.sharing.AppSharingTitleBarAction
import ai.rever.boss.window.NativeTitleBarAction
import ai.rever.boss.window.NativeTitleBarTextInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.arkivanov.decompose.extensions.compose.subscribeAsState

/** Only the focused browser pane contributes navigation chrome to its window. */
@Composable
internal fun nativeBrowserTitleActions(state: BossAppState): List<NativeTitleBarAction> {
    val active by ActiveBrowserRegistry.activeHandleIdByWindow.collectAsState()
    val tabHandles by BrowserTabOwnership.handleIds.collectAsState()
    val component = state.splitViewState.getActiveTabsComponent()
    val tabs = component?.tabsState?.subscribeAsState()?.value
    val activeTabId = tabs?.tabs?.getOrNull(tabs.activeIndex)?.id
    // Home replaces BrowserHandle.Content with a dashboard. Its tab still owns the handle
    // and publishes navigation state, even though no webpage view is currently composed.
    val handleId = tabHandles[activeTabId] ?: active[state.windowId]
    val browser = handleId?.let(BrowserTitleBarBridge::state)
    val sharingAction = AppSharingTitleBarAction(state.windowId, browser?.share)
    if (handleId == null || browser == null) return listOf(sharingAction)
    val favicon = activeBrowserFavicon(state, browser.url)
    val focusOwner = remember(handleId) { Any() }
    return buildList {
        add(
            NativeTitleBarAction(
                "browser_back",
                "Back",
                "chevron.left",
                enabled = browser.canGoBack,
                onClick = browser.back,
            ),
        )
        add(
            NativeTitleBarAction(
                "browser_forward",
                "Forward",
                "chevron.right",
                enabled = browser.canGoForward,
                onClick = browser.forward,
            ),
        )
        add(
            NativeTitleBarAction(
                "browser_reload",
                if (browser.loading) "Stop loading" else "Reload",
                if (browser.loading) "xmark" else "arrow.clockwise",
                onClick = browser.reloadOrStop,
            ),
        )
        add(browserAddressAction(handleId, browser, favicon, focusOwner))
        add(
            NativeTitleBarAction(
                "browser_bookmark",
                "Bookmark",
                if (browser.bookmarked) "star.fill" else "star",
                onClick = browser.bookmark,
            ),
        )
        add(sharingAction)
    }
}

@Composable
private fun activeBrowserFavicon(
    state: BossAppState,
    url: String,
): androidx.compose.ui.graphics.painter.Painter? {
    val component = state.splitViewState.getActiveTabsComponent() ?: return null
    val tabs by component.tabsState.subscribeAsState()
    val tab = tabs.tabs.getOrNull(tabs.activeIndex)
    val cacheKey = tab?.let { rememberFaviconCacheKey(it) }
    val icon by produceState<ai.rever.boss.plugin.api.TabIcon.Image?>(null, url, cacheKey) {
        value = null
        value = loadHighQualityFavicon(url, cacheKey)
    }
    return icon?.painter
}

@Composable
internal fun NativeBrowserHostAvailability(
    windowId: String,
    ready: Boolean,
) {
    DisposableEffect(windowId, ready) {
        BrowserTitleBarBridge.hostWindow(windowId, ready)
        onDispose { BrowserTitleBarBridge.hostWindow(windowId, false) }
    }
}

private fun browserAddressAction(
    handleId: String,
    browser: ai.rever.boss.plugin.browser.BrowserTitleBarState,
    favicon: androidx.compose.ui.graphics.painter.Painter?,
    focusOwner: Any,
): NativeTitleBarAction =
    NativeTitleBarAction(
        "browser_url",
        "Address",
        textInput =
            NativeTitleBarTextInput(
                handleId,
                browser.url,
                browser.navigate,
                { BrowserTitleBarBridge.host(handleId, focusOwner, it) },
                browser.address,
                favicon,
            ),
        onClick = {},
    )
