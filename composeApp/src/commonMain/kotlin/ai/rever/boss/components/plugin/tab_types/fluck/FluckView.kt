package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

// Platform-specific implementation
@Composable
expect fun FluckView(
    fileId: String,
    content: String,
    browser: Any? = null, // Browser instance (platform-specific type)
    browserViewState: Any? = null, // Browser view state (platform-specific type)
    browserLock: Any? = null, // ReentrantReadWriteLock for thread-safe browser access
    onContentChange: (String) -> Unit,
    onTitleChange: (String) -> Unit = {},
    onIconChange: (ImageVector) -> Unit = {},
    onTabIconUpdate: (TabIcon) -> Unit = {},
    onOpenInNewTab: (String) -> Unit = {},
    onNavigationUpdate: ((String, String) -> Unit)? = null,
    onNavigationStateChange: ((isBack: Boolean) -> Unit)? = null,
    onFaviconCached: ((String?) -> Unit)? = null,
    onCloseTab: (() -> Unit)? = null
)

