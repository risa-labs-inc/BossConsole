package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * iOS-specific implementation of FluckView
 * Shows a placeholder since JxBrowser doesn't support iOS
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
    onCloseTab: (() -> Unit)?
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Web browser not available on iOS")
    }
}
