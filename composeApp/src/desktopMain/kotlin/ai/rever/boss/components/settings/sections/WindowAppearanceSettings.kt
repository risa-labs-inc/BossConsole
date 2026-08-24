package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.displayName
import ai.rever.boss.components.bars.isBarVisible
import ai.rever.boss.components.bars.withBarVisible
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.plugin.ui.menu.NativeContextMenus
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.TabWidthMode
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.window.displayName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Slider stops for the vertical bar width: one every 10dp across
 * [TabBarVerticalWidthRange]. `steps` counts the stops BETWEEN the ends, hence the -1.
 */
private val VERTICAL_WIDTH_SLIDER_STEPS =
    ((TabBarVerticalWidthRange.endInclusive - TabBarVerticalWidthRange.start) / 10f).toInt() - 1

@Composable
fun WindowAppearanceSettings() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Determine platform default
    val os = System.getProperty("os.name").lowercase()
    val platformDefault =
        when {
            os.contains("mac") -> "Shown"
            os.contains("linux") -> "Hidden"
            os.contains("windows") -> "Hidden"
            else -> "Platform-dependent"
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Title Bar") {
            SettingsToggle(
                label = "Show Title Bar",
                checked = settings.showTitleBar,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.copy(showTitleBar = enabled),
                        )
                    }
                },
                description = "Display the \"Boss Console\" title bar at the top of the window",
            )

            SettingsInfoRow(
                label = "Platform Default",
                value = platformDefault,
                description = "The default setting for your operating system",
            )
        }

        BarsSection()

        NativeContextMenuSection()

        TabBarSection()
    }
}

/**
 * Tab bar position and the settings that only apply to one of the two positions.
 *
 * Its own composable for the same reason [BarsSection] is: the page function was already at
 * detekt's length limit, and a section that owns four controls and a derived `vertical` flag is a
 * unit rather than four more lines of the page.
 */
@Composable
private fun TabBarSection() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsSection(title = "Tab Bar") {
        val vertical = settings.tabBarPosition == TabBarPosition.LEFT

        SettingsDropdown(
            label = "Position",
            options = TabBarPosition.entries.map { it.displayName },
            selectedOption = settings.tabBarPosition.displayName,
            onOptionSelected = { selected ->
                val position = TabBarPosition.entries.first { it.displayName == selected }
                coroutineScope.launch {
                    WindowAppearanceSettingsManager.updateSettings(
                        settings.copy(tabBarPosition = position),
                    )
                }
            },
            description =
                "Top: a strip across the top of each panel. Left: a column down its leading edge, " +
                    "which trades width for readable titles and a list that scrolls instead of shrinking. " +
                    "Each panel in a split gets its own bar either way.",
        )

        SettingsDropdown(
            label = "Tab Sizing",
            options = TabWidthMode.entries.map { it.displayName },
            selectedOption = settings.tabWidthMode.displayName,
            onOptionSelected = { selected ->
                val mode = TabWidthMode.entries.first { it.displayName == selected }
                coroutineScope.launch {
                    WindowAppearanceSettingsManager.updateSettings(
                        settings.copy(tabWidthMode = mode),
                    )
                }
            },
            description =
                "Shrink to Fit: tabs shrink evenly so they all stay visible, scrolling only " +
                    "when each is favicon-sized (Safari style). Fixed Width: tabs keep their natural width " +
                    "and the bar scrolls when they overflow.",
            // Greyed out rather than hidden when the bar is vertical: a setting that vanishes
            // reads as a bug, where a disabled one says "not for this layout". A vertical tab
            // is the bar's width, so there is no budget for these to divide.
            enabled = !vertical,
        )

        VerticalTabBarRows(settings = settings, coroutineScope = coroutineScope, enabled = vertical)
    }
}

/**
 * The two controls that mean nothing unless the bar is vertical.
 *
 * Split from [TabBarSection] along the line that already exists in the settings themselves:
 * Position and Tab Sizing describe the bar you have, these two describe how the left bar behaves.
 * They stay in the same `SettingsSection` so the page is unchanged.
 */
@Composable
private fun ColumnScope.VerticalTabBarRows(
    settings: ai.rever.boss.window.WindowAppearanceSettings,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
) {
    // Local while dragging, persisted on release. updateSettings writes the settings file, and a
    // slider fires onValueChange on every frame of a drag - persisting each one puts a file write
    // behind every pixel and makes the bar redraw from disk state mid-gesture. The dragged value
    // still drives the live preview, because it is what is rendered here and what BossMainPanel
    // reads once committed.
    var draggedWidth by remember(settings.tabBarVerticalWidth) {
        mutableStateOf(settings.tabBarVerticalWidth)
    }

    SettingsSlider(
        label = "Vertical Bar Width",
        value = draggedWidth,
        onValueChange = { draggedWidth = it },
        onValueChangeFinished = {
            coroutineScope.launch {
                WindowAppearanceSettingsManager.updateSettings(
                    WindowAppearanceSettingsManager.currentSettings.value
                        .copy(tabBarVerticalWidth = draggedWidth),
                )
            }
        },
        valueRange = TabBarVerticalWidthRange,
        steps = VERTICAL_WIDTH_SLIDER_STEPS,
        valueDisplay = { "${it.toInt()} dp" },
        description = "Width of the left tab bar. Only applies when Position is Left",
        enabled = enabled,
    )

    SettingsToggle(
        label = "Expand on Hover",
        checked = settings.tabBarHoverExpand,
        onCheckedChange = { enabled ->
            coroutineScope.launch {
                WindowAppearanceSettingsManager.updateSettings(
                    settings.copy(tabBarHoverExpand = enabled),
                )
            }
        },
        description =
            "Reveal the full bar as an overlay while the pointer rests on the collapsed rail, " +
                "whether the rail was forced by a narrow panel or chosen with the chevron. " +
                "Off means the chevron is the only way back",
        enabled = enabled,
    )

    PaneTabStripRows(settings = settings, coroutineScope = coroutineScope, enabled = enabled)
}

/**
 * The strip's own two rows: whether it is drawn, and whether the pane count gates it.
 *
 * Their own composable because [VerticalTabBarRows] is at detekt's length ceiling. They must stay
 * ABOVE the next `SettingsSection` in this file: `SettingsSearchIndexDriftTest` attributes a label
 * to whichever section precedes it textually, so a helper placed at the end of the file indexes
 * its rows under the last group on the page.
 */
@Composable
private fun ColumnScope.PaneTabStripRows(
    settings: ai.rever.boss.window.WindowAppearanceSettings,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
) {
    SettingsToggle(
        label = "Pane Tab Strip",
        checked = settings.showPaneTabStrip,
        onCheckedChange = { showStrip ->
            coroutineScope.launch {
                WindowAppearanceSettingsManager.updateSettings(
                    settings.copy(showPaneTabStrip = showStrip),
                )
            }
        },
        description =
            "Show each pane's own tabs as a row of favicons across the top of that pane. The " +
                "left bar hides names when it collapses a pane you are not working in, and when " +
                "it collapses itself to the rail, so this is where a pane's tabs stay reachable",
        // Alongside the other vertical-bar rows, and disabled with them: the strip only exists
        // because the LEFT bar collapses panes. In Top position every pane already draws its own
        // full strip, so a toggle that appeared to work here would control nothing.
        enabled = enabled,
    )

    SettingsToggle(
        label = "Only in Split Windows",
        checked = settings.paneTabStripOnlyWhenSplit,
        onCheckedChange = { onlyWhenSplit ->
            coroutineScope.launch {
                WindowAppearanceSettingsManager.updateSettings(
                    settings.copy(paneTabStripOnlyWhenSplit = onlyWhenSplit),
                )
            }
        },
        description =
            "Hide the strip while the window has a single pane, where the bar can list every " +
                "tab by name. Switch it off if you want the strip there too: the bar collapses " +
                "to the rail even with one pane, and then nothing else on screen names its tabs",
        // Disabled when the strip itself is off, not only when the bar is horizontal - a row
        // qualifying something switched off is a control with nothing to control.
        enabled = enabled && settings.showPaneTabStrip,
    )
}

/**
 * The four bar visibility flags, which each bar's right-click "Hide" and the View menu's checkmarks
 * write too.
 *
 * Surfaced here as well as in those two places because Settings is where someone looks for chrome
 * they cannot find. A bar hidden from its own context menu leaves nothing behind pointing at where
 * it went, and "it is gone and I cannot get it back" is the failure this whole set of toggles is
 * here to prevent.
 */
@Composable
private fun BarsSection() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsSection(title = "Bars") {
        ChromeBar.entries.forEach { bar ->
            SettingsToggle(
                label = "Show ${bar.displayName()}",
                checked = settings.isBarVisible(bar),
                onCheckedChange = { visible ->
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.withBarVisible(bar, visible),
                        )
                    }
                },
            )
        }

        SettingsInfoRow(
            label = "Applies to",
            value = "All windows",
            description =
                "These stay hidden until you switch them back on, in every window - hiding a bar " +
                    "from its right-click menu hides it everywhere. Focus Mode is separate: it " +
                    "hides bars temporarily and reveals them when you move the pointer to the edge.",
        )
    }
}

/**
 * macOS only - see `shouldUseNativeMenus` for why Windows and Linux stay on the drawn menus.
 * Offering a toggle where it does nothing would just be a lie, so the whole section is hidden.
 */
@Composable
private fun NativeContextMenuSection() {
    if (!NativeContextMenus.isSupported()) return
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsSection(title = "Menus") {
        SettingsToggle(
            label = "Native Context Menus",
            checked = settings.useNativeContextMenus,
            onCheckedChange = { enabled ->
                coroutineScope.launch {
                    WindowAppearanceSettingsManager.updateSettings(
                        settings.copy(useNativeContextMenus = enabled),
                    )
                }
            },
            description =
                "Use macOS's own right-click menus. They follow the system appearance rather " +
                    "than the BOSS theme, and are never hidden behind a web page. Off restores " +
                    "the BOSS-styled menus.",
        )
    }
}

private val TabWidthMode.displayName: String
    get() =
        when (this) {
            TabWidthMode.SHRINK_TO_FIT -> "Shrink to Fit"
            TabWidthMode.FIXED -> "Fixed Width"
        }
