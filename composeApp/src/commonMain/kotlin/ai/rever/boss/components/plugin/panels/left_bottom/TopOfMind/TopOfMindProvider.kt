package ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.WorkspaceManager
import androidx.compose.runtime.compositionLocalOf

// CompositionLocal to provide SplitViewState to panels
val LocalSplitViewState = compositionLocalOf<SplitViewState?> { null }

// CompositionLocal to provide WorkspaceManager to panels
val LocalWorkspaceManager = compositionLocalOf<WorkspaceManager?> { null }
