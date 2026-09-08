package ai.rever.boss.app

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Whether the Space this window is showing has changes that are not on disk.
 *
 * Pure, so the rule can be tested without a bar: the flag is per WINDOW (see
 * `WorkspaceManager.unsavedWorkspaces`) and the Space on screen is per window too, so the
 * affordance is a lookup of one in the other.
 *
 * **A window with NO Space loaded reads as saved, deliberately.** That state lasts about two
 * seconds - the layout watcher writes it out as "Last Session" and the manager then has a current
 * Space - so lighting a Save button there would be a control that appears and vanishes on every
 * new window. "Never saved at all" is still covered for any Space that HAS an id, by
 * `isUnsaved`'s null-saved-copy branch: a template applied as-is has an entry in the Space list
 * that still carries `{projectPath}` and no file that matches the layout on screen.
 */
internal fun spaceIsUnsaved(
    currentWorkspaceId: String?,
    unsavedWorkspaceIds: Set<String>,
): Boolean = currentWorkspaceId != null && currentWorkspaceId in unsavedWorkspaceIds

/** Test tag of the save affordance - see `SpaceSaveAffordanceLayoutTest`. */
internal const val SPACE_SAVE_TAG = "vertical-bar-space-save"

/** What the save affordance says it does, and the string its layout test finds it by. */
internal const val SPACE_SAVE_DESCRIPTION = "Save this space"

/**
 * The Space button, with a save affordance beside it while there is something to save.
 *
 * A `Row` around the button rather than a slot inside it: `WorkspaceButton` is a `BossActionButton`
 * with a leading glyph, a label and a chevron, and it is the same control in the top bar, where
 * this affordance does not belong.
 *
 * **The Space button takes the WEIGHT and the save button does not.** A `Row` measures its
 * unweighted children first, so the save button gets its 24dp and the Space button gets whatever
 * is left - which at the bar's 120dp floor is what keeps the save button on screen at all. The
 * other way round is the failure `HostActionsFlowRow` measured: a `Row` too narrow for its
 * children hands the LAST one zero width rather than clipping it, so the button silently is not
 * there at a width the user can reach by dragging.
 */
@Composable
internal fun SpaceRow(
    unsaved: Boolean,
    onSave: () -> Unit,
    spaceButton: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SPACE_ROW_GAP),
    ) {
        Box(modifier = Modifier.weight(1f)) { spaceButton() }
        if (unsaved) SpaceSaveButton(onSave = onSave)
    }
}

/**
 * The save button itself: a compact-row-sized target around a 13dp glyph.
 *
 * Sized against [ai.rever.boss.components.buttons.BossActionButton] in COMPACT mode, which is what
 * the bar draws beside it - a 24dp row height and a 13dp leading glyph, not the 20dp `iconSize` an
 * icon-only button in the top bar gets. A 20dp glyph here is half again the size of the Space
 * glyph it sits next to and reads as a different class of control.
 *
 * The glyph is `signalText`, not `signal`: it is drawn as a glyph, and `signal` is the fill token,
 * which is held to no text contrast floor (Blueprint's is 3.5:1). It is the accent rather than
 * `textSecondary` because the button is only on screen when it has something to say.
 */
@Composable
private fun SpaceSaveButton(onSave: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
            Modifier
                .size(SPACE_SAVE_SIZE)
                .clip(RoundedCornerShape(SPACE_SAVE_RADIUS))
                .background(if (isHovered) BossTheme.colors.raised else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onSave)
                .testTag(SPACE_SAVE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Save,
            contentDescription = SPACE_SAVE_DESCRIPTION,
            modifier = Modifier.size(SPACE_SAVE_ICON),
            tint = BossTheme.colors.signalText,
        )
    }
}

/** `BossActionButton`'s compact row height, so the two buttons in this row are the same height. */
private val SPACE_SAVE_SIZE = 24.dp

/** `BossActionButton`'s compact leading glyph, so this glyph matches the Space glyph beside it. */
private val SPACE_SAVE_ICON = 13.dp

/** The bar's own 4dp radius, as the footer's action buttons use. */
private val SPACE_SAVE_RADIUS = 4.dp

/**
 * Air between the Space button and the save button.
 *
 * Not zero, for the reason the two ROWS of this footer are not flush either: adjacent click
 * targets mean a click a pixel off the one you meant opens a Space picker instead of saving.
 */
private val SPACE_ROW_GAP = 2.dp
