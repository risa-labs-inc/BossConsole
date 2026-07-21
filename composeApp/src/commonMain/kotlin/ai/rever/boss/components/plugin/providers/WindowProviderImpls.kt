package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.WindowIdProvider
import ai.rever.boss.plugin.api.WindowProjectStateProvider
import ai.rever.boss.window.WindowProjectState

/**
 * Implementation of WindowIdProvider.
 */
class WindowIdProviderImpl(private val windowId: String) : WindowIdProvider {
    override fun getWindowId(): String = windowId
}

/**
 * Implementation of WindowProjectStateProvider.
 */
class WindowProjectStateProviderImpl(
    private val windowProjectState: WindowProjectState?
) : WindowProjectStateProvider {
    override fun getSelectedProjectPath(): String? {
        return windowProjectState?.selectedProject?.value?.path
    }
}
