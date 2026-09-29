package ai.rever.boss.window

import androidx.compose.runtime.compositionLocalOf

/** Fullscreen placement of the owning app window, independent of keyboard focus. */
internal val LocalWindowFullscreen = compositionLocalOf { false }
