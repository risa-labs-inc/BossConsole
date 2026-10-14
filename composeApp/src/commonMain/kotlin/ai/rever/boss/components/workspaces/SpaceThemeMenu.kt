package ai.rever.boss.components.workspaces

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.plugin.ui.BossThemes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.RestartAlt

/** What the reset row says, and the one place the words live. */
const val SPACE_THEME_RESET_TEXT = "Use the Default Theme"

/**
 * The rows of the Space button's `Options > Space Theme` submenu.
 *
 * **The Options submenu is the home for this** because a Space's theme is a property of the Space
 * you are in, and that submenu is already the place for the things you do TO the Space on screen -
 * save it, delete it, open its folder, reset the layout. The rows above it are Spaces to switch to,
 * which is a different verb; putting a theme picker among them would read as "open the Blueprint
 * Space".
 *
 * **One trailing slot, so it carries one fact per row.** A submenu row renders `trailingIcon` and
 * nothing else (`SubMenuContent`), so the check and the swatch cannot both be drawn: the row that
 * is SHOWING gets the check, and every row you could move to gets its own colour. Which is the
 * better split anyway - the theme you are wearing is the whole window, so a swatch of it says
 * nothing a reader cannot already see.
 *
 * **Blueprint and Blueprint Light have an identical signal** (#0F5BFF in both), so a swatch alone
 * cannot tell them apart and the hue is not what separates them. The glyph carries the other half:
 * a FILLED dot for a dark theme, a RING for a light one - the same filled/outline vocabulary the
 * Space rows above already use for "running here" against "running elsewhere". Two blue marks, one
 * solid and one hollow, beside two names that differ by the word "Light".
 *
 * **The reset row appears only when there is something to reset.** Every Space resolves to some
 * theme, so a permanently present "use the default" row would be indistinguishable from the row
 * already ticked; showing it exactly when an override exists is what makes it mean "give this back
 * to the template default, or to Settings".
 *
 * [workspaceId] may be `last-session`, which is a slot rather than a document. Theming it is
 * allowed deliberately: the id is stable, the override is removable through the reset row, and
 * refusing would leave this menu dead in exactly the Space every launch lands in.
 */
fun spaceThemeMenuItems(
    workspaceId: String,
    overrides: Map<String, String>,
    settingsThemeId: String,
    onChoose: (String?) -> Unit,
): List<ContextMenuItem> {
    if (workspaceId.isEmpty()) return emptyList()
    val current = spaceThemeId(workspaceId, overrides, settingsThemeId)
    return buildList {
        BossThemes.all.forEach { theme ->
            add(
                ContextMenuItem(
                    text = theme.name,
                    trailingIcon = if (theme.id == current) Icons.Default.Check else swatchGlyphFor(theme.isLight),
                    trailingIconColor = if (theme.id == current) null else theme.colors.signal,
                    onClick = { onChoose(theme.id) },
                ),
            )
        }
        if (workspaceId in overrides) {
            add(ContextMenuItem(isDivider = true))
            add(
                ContextMenuItem(
                    text = SPACE_THEME_RESET_TEXT,
                    icon = Icons.Outlined.RestartAlt,
                    onClick = { onChoose(null) },
                ),
            )
        }
    }
}

/** A filled dot for a dark theme, a ring for a light one. See [spaceThemeMenuItems]. */
private fun swatchGlyphFor(isLight: Boolean) = if (isLight) Icons.Outlined.Circle else Icons.Filled.Circle
