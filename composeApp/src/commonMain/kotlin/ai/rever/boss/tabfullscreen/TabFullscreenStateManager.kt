package ai.rever.boss.tabfullscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages tab fullscreen state. When a browser tab enters fullscreen,
 * the browser is displayed in a separate fullscreen window.
 */
object TabFullscreenStateManager {
    private val _fullscreenTabId = MutableStateFlow<String?>(null)
    val fullscreenTabId: StateFlow<String?> = _fullscreenTabId.asStateFlow()

    /**
     * Signal that a tab's BrowserViewState needs to be recreated.
     * Contains the tab ID that needs recreation, or null when no recreation is needed.
     *
     * JxBrowser can only have one active rendering surface per browser instance.
     * After exiting fullscreen, the existing BrowserViewState has lost its connection
     * to JxBrowser's rendering pipeline. Creating a fresh BrowserViewState forces
     * JxBrowser to establish a new rendering connection.
     */
    private val _needsViewStateRecreation = MutableStateFlow<String?>(null)
    val needsViewStateRecreation: StateFlow<String?> = _needsViewStateRecreation.asStateFlow()

    fun isTabInFullscreen(tabId: String): Boolean = _fullscreenTabId.value == tabId

    fun enterFullscreen(tabId: String) {
        _fullscreenTabId.value = tabId
    }

    fun exitFullscreen() {
        val exitingTabId = _fullscreenTabId.value
        _fullscreenTabId.value = null
        // Signal that this tab needs its BrowserViewState recreated
        _needsViewStateRecreation.value = exitingTabId
    }

    /**
     * Clear the recreation signal after the tab has recreated its BrowserViewState.
     * Called by FluckTabComponent after handling the recreation.
     */
    fun clearRecreationSignal() {
        _needsViewStateRecreation.value = null
    }
}
