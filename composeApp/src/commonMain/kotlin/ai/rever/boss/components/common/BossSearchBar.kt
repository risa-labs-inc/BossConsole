@file:Suppress("UNUSED")
package ai.rever.boss.components.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search

/**
 * Re-exports from plugin-search module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.search
 */

@Composable
fun BossSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Outlined.Search,
    showClearButton: Boolean = true,
    onFocusChanged: ((Boolean) -> Unit)? = null
) = ai.rever.boss.plugin.search.BossSearchBar(
    query = query,
    onQueryChange = onQueryChange,
    placeholder = placeholder,
    modifier = modifier,
    leadingIcon = leadingIcon,
    showClearButton = showClearButton,
    onFocusChanged = onFocusChanged
)
