@file:Suppress("UNUSED")

package ai.rever.boss.components.bars

/**
 * Re-exports from plugin-scrollbar module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.scrollbar
 */

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.rever.boss.plugin.scrollbar.HorizontalBarScrollbarConfig as PluginHorizontalBarScrollbarConfig
import ai.rever.boss.plugin.scrollbar.PanelScrollbarConfig as PluginPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.ScrollbarConfig as PluginScrollbarConfig
import ai.rever.boss.plugin.scrollbar.getBarScrollbarConfig as pluginGetBarScrollbarConfig
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig as pluginGetPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.horizontalLazyListScrollbar as pluginHorizontalLazyListScrollbar
import ai.rever.boss.plugin.scrollbar.horizontalScrollWithScrollbar as pluginHorizontalScrollWithScrollbar
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar as pluginLazyListScrollbar
import ai.rever.boss.plugin.scrollbar.scrollbar as pluginScrollbar
import ai.rever.boss.plugin.scrollbar.verticalScrollWithScrollbar as pluginVerticalScrollWithScrollbar

// Re-export type alias
typealias ScrollbarConfig = PluginScrollbarConfig

// Re-export static configs
val PanelScrollbarConfig = PluginPanelScrollbarConfig
val HorizontalBarScrollbarConfig = PluginHorizontalBarScrollbarConfig

// Re-export composable functions
@Composable
fun getPanelScrollbarConfig(): ScrollbarConfig = pluginGetPanelScrollbarConfig()

@Composable
fun getBarScrollbarConfig(): ScrollbarConfig = pluginGetBarScrollbarConfig()

// Re-export extension functions
fun Modifier.scrollbar(
    scrollState: ScrollState,
    direction: Orientation,
    config: ScrollbarConfig = ScrollbarConfig(),
): Modifier = pluginScrollbar(scrollState, direction, config)

fun Modifier.lazyListScrollbar(
    listState: LazyListState,
    direction: Orientation,
    config: ScrollbarConfig = ScrollbarConfig(),
): Modifier = pluginLazyListScrollbar(listState, direction, config)

fun Modifier.horizontalLazyListScrollbar(
    listState: LazyListState,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig(),
): Modifier = pluginHorizontalLazyListScrollbar(listState, scrollbarConfig)

fun Modifier.verticalScrollWithScrollbar(
    scrollState: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig(),
): Modifier = pluginVerticalScrollWithScrollbar(scrollState, enabled, flingBehavior, reverseScrolling, scrollbarConfig)

fun Modifier.horizontalScrollWithScrollbar(
    scrollState: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig(),
): Modifier = pluginHorizontalScrollWithScrollbar(scrollState, enabled, flingBehavior, reverseScrolling, scrollbarConfig)
