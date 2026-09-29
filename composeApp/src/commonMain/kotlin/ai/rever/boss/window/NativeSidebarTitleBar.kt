package ai.rever.boss.window

import androidx.compose.runtime.Composable

/** Draw the native toolbar inset, returning false when the caller should render its fallback. */
@Composable
internal expect fun NativeSidebarTitleBar(
    title: String,
    actions: List<NativeTitleBarAction>,
): Boolean
