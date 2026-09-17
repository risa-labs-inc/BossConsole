package ai.rever.boss.components.home

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.plugin.ui.BossMotion
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The minimum width a tool tile is allowed to take.
 *
 * Feeds [homeToolColumns], so the grid derives its own column count from the panel it is given
 * rather than a fixed number that strands tiles off the right edge - which is what the old
 * screen's `Row(horizontalScroll(...))` of 120.dp cards did with 33 plugins installed.
 *
 * 132.dp is not arbitrary: it is the old `ActionCard` width plus one `space.md` on each side, so
 * a two-word label still fits on one line at the smallest column.
 */
internal val HomeToolMinWidth: Dp = 132.dp

/** Tile height, fixed so a grid row is even whether or not a tile carries a shortcut hint. */
private val HomeToolHeight: Dp = 104.dp

/** What the tile is currently doing, which is not derivable from [HomeTool] alone. */
internal enum class HomeToolState {
    READY,
    INSTALLABLE,
    INSTALLING,
}

/**
 * One tile in the tool grid: an installed tool to open, or a plugin to install.
 *
 * Both live in one composable rather than two because they are the same object at different
 * times - a tile the user installs becomes a tile they open - and because the grid sorts them
 * into one list. The difference is carried by [state] and shown as a border plus a verb, not as
 * a different shape.
 */
@Composable
internal fun HomeToolCard(
    tool: HomeTool,
    state: HomeToolState,
    shortcut: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    HoverTooltipBox(
        text = tool.description,
        modifier = modifier,
        maxWidth = 320.dp,
        focused = isFocused,
        placement = TooltipPlacement.TOP,
    ) {
        HomeToolCardContent(tool, state, shortcut, onClick, interactionSource)
    }
}

@Composable
private fun HomeToolCardContent(
    tool: HomeTool,
    state: HomeToolState,
    shortcut: String?,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
) {
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = BossTheme.colors
    val space = BossTheme.space

    val installable = state != HomeToolState.READY
    val enabled = state != HomeToolState.INSTALLING

    // Tint, not scale. The old cards grew 5% on hover, which in a wrapping grid nudges a tile
    // over its neighbours; a background change reads the same and cannot overlap.
    val hoverProgress by animateFloatAsState(
        targetValue = if (isHovered && enabled) 1f else 0f,
        animationSpec = tween(BossMotion.fastMs),
        label = "home_tool_hover",
    )
    val background =
        if (installable) {
            colors.panel
        } else {
            lerpSurface(colors.raised, colors.signalWash, hoverProgress)
        }
    val iconTint =
        when {
            state == HomeToolState.INSTALLING -> colors.textMuted
            installable -> colors.textSecondary
            hoverProgress > 0.5f -> colors.signal
            else -> colors.textSecondary
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    if (tool.description.isNotBlank()) contentDescription = "${tool.label}. ${tool.description}"
                }.height(HomeToolHeight)
                .clip(BossTheme.radius.cardShape)
                .background(background)
                // A dashed border is not available without a custom stroke, so an installable
                // tile is distinguished by having a border at all: ready tiles are borderless
                // fills, discoverable ones are outlines. Reads as "not here yet" at a glance
                // across a grid, and survives both light themes.
                .then(
                    if (installable) {
                        Modifier.border(1.dp, colors.line, BossTheme.radius.cardShape)
                    } else {
                        Modifier
                    },
                ).clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                ) { onClick() }
                .hoverable(interactionSource, enabled = enabled)
                .padding(space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(space.xs, Alignment.CenterVertically),
    ) {
        TileIcon(tool = tool, state = state, tint = iconTint)

        TileLabel(tool.label, if (installable) colors.textSecondary else colors.textPrimary)

        TileHint(state = state, shortcut = shortcut)
    }
}

@Composable
private fun TileLabel(
    label: String,
    color: Color,
) {
    Text(
        text = label,
        color = color,
        style = BossTheme.type.body,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TileIcon(
    tool: HomeTool,
    state: HomeToolState,
    tint: Color,
) {
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == HomeToolState.INSTALLING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = BossTheme.colors.signal,
                    strokeWidth = 2.dp,
                )
            }

            else -> {
                when (val icon = tool.icon) {
                    is HomeToolIcon.Vector -> {
                        Icon(
                            imageVector = icon.image,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    is HomeToolIcon.FromStore -> {
                        StoreIcon(icon = icon, tint = tint)
                    }
                }
            }
        }
    }
}

/**
 * A store plugin's icon: its `icon_url` if that can be fetched, otherwise its initials.
 *
 * The initials are drawn immediately and the image replaces them if and when it arrives, so a tile
 * is never blank and the grid does not reflow. Keyed on the URL, so a failed or absent icon is
 * attempted once per URL rather than on every recomposition (the loader caches failures too).
 */
@Composable
private fun StoreIcon(
    icon: HomeToolIcon.FromStore,
    tint: Color,
) {
    var painter by remember(icon.iconUrl) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(icon.iconUrl) {
        if (icon.iconUrl.isNotBlank()) {
            painter = loadPluginIcon(icon.iconUrl)
        }
    }

    val loaded = painter
    if (loaded != null) {
        Image(
            painter = loaded,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Text(
            text = icon.initials,
            color = tint,
            style = BossTheme.type.label,
            maxLines = 1,
        )
    }
}

/** One fixed-height slot for the tile's third line, so tiles with and without a hint align. */
@Composable
private fun TileHint(
    state: HomeToolState,
    shortcut: String?,
) {
    val colors = BossTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == HomeToolState.INSTALLING -> {
                HintText("Installing", colors.textMuted)
            }

            state == HomeToolState.INSTALLABLE -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BossTheme.space.hairline),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = colors.signalText,
                        modifier = Modifier.size(12.dp),
                    )
                    HintText("Install", colors.signalText)
                }
            }

            // Only rendered when a binding actually exists. The old screen printed "Cmd+O",
            // "Cmd+P" and "Cmd+`" for actions that have no keyboard shortcut at all.
            shortcut != null -> {
                HintText(shortcut, colors.textMuted)
            }
        }
    }
}

@Composable
private fun HintText(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        style = BossTheme.type.micro,
        maxLines = 1,
    )
}

/**
 * Blend two theme surfaces.
 *
 * Written out rather than using `lerp` on the colors directly so the hover tint stays inside the
 * theme's own two surface tokens - `raised` and `signalWash` - in every one of the five themes,
 * two of which are light. Reaching for a fixed alpha over a fixed color is what makes a hover
 * state look right in Blueprint and wrong in Daylight.
 */
private fun lerpSurface(
    from: Color,
    to: Color,
    progress: Float,
) = androidx.compose.ui.graphics
    .lerp(from, to, progress.coerceIn(0f, 1f))
