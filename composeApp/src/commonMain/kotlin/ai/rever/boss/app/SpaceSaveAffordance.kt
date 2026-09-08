package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
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
import androidx.compose.foundation.shape.CircleShape
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
 * Pure, so the rule can be tested without a bar: the manager's flag is per WINDOW (see
 * `WorkspaceManager.unsavedWorkspaces`) and the Space on screen is per window too, so most of this
 * is a lookup of one in the other.
 *
 * **Last Session is ALWAYS unsaved, and that is the whole of the third branch.** The record
 * existing is not the same as the user's work being saved: `last-session` is one app-level slot,
 * overwritten on every layout change and by every window, and nothing in it is addressable,
 * nameable, or safe from the next session. The manager's set is honest about DISK and so cannot
 * answer this - the watcher keeps that record current, so `isUnsaved` compares the live layout
 * against a file that really does match it and says "saved". True about the file, wrong about the
 * user: a layout built in Last Session is unsaved work.
 *
 * It matters because it is the DEFAULT state, not an edge case - every launch restores Last
 * Session as the current Space, so suppressing the mark there meant the affordance could never
 * appear on a fresh launch, and the mark cleared itself within the settle window exactly as it
 * used to for named Spaces.
 *
 * **Unconditional rather than "only once the layout has changed since the restore".** The earlier
 * argument against lighting a control the instant a window opens was about FLICKER - the no-Space
 * state below lasts about two seconds and then goes away by itself, and a control that appears and
 * vanishes is noise. This mark is stable: it stays until the user saves, which is the action it
 * offers. A true, persistent mark is not the thing that argument was against, and the alternative
 * needs a frozen per-window baseline captured at restore and reset on every switch - more state,
 * to say "nothing is saved" slightly later.
 *
 * **A window with NO Space loaded still reads as saved.** That one really is the flicker case: the
 * layout watcher writes the first change out as Last Session and the manager then has a current
 * Space, at which point the branch above takes over and the mark appears for good.
 */
internal fun spaceIsUnsaved(
    currentWorkspaceId: String?,
    unsavedWorkspaceIds: Set<String>,
): Boolean =
    when {
        currentWorkspaceId == null -> false
        currentWorkspaceId == LAST_SESSION_ID -> true
        else -> currentWorkspaceId in unsavedWorkspaceIds
    }

/** Test tag of the save affordance - see `SpaceSaveAffordanceLayoutTest`. */
internal const val SPACE_SAVE_TAG = "vertical-bar-space-save"

/** Test tag of the unsaved dot. */
internal const val SPACE_UNSAVED_DOT_TAG = "vertical-bar-space-unsaved"

/**
 * What the affordance says, and the string its layout test finds it by.
 *
 * **The STATE first, then the action.** It said "Save this space", which is what a floppy glyph
 * already looks like - so the whole control read as a save button that is always there, and the one
 * thing it exists to say, that this Space is unsaved, was carried only by its presence. The state is
 * the news; saving is what you can do about it.
 */
internal const val SPACE_SAVE_DESCRIPTION = "Unsaved changes - press to save this space"

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
        if (unsaved) {
            UnsavedDot()
            SpaceSaveButton(onSave = onSave)
        }
    }
}

/**
 * The mark that says the Space is unsaved, as opposed to the button that offers to fix it.
 *
 * **A state needs its own mark.** Relying on the button's mere presence made the whole affordance
 * read as a save button, because a floppy glyph is what a save button looks like whether or not
 * anything has changed. A filled dot is the editor vocabulary for "modified", it is the same mark
 * the Space menu already uses for a running Space (`Icons.Filled.Circle`), and it cannot be
 * confused with something to press.
 *
 * `signalText` and 6dp, so it belongs to the tinted Space glyph on its left rather than announcing
 * itself: three marks of one colour saying one thing. No `contentDescription` - a screen reader
 * would otherwise hear the state twice, since the button beside it leads with exactly that.
 */
@Composable
private fun UnsavedDot() {
    Box(
        modifier =
            Modifier
                .size(SPACE_UNSAVED_DOT)
                .testTag(SPACE_UNSAVED_DOT_TAG)
                .background(color = BossTheme.colors.signalText, shape = CircleShape),
    )
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
 * The unsaved dot.
 *
 * 6dp: smaller than the 8dp dot the Space menu prints beside a running Space, because that one sits
 * alone in a menu row and this one sits between a 13dp glyph and a 13dp glyph.
 */
private val SPACE_UNSAVED_DOT = 6.dp

/**
 * Air between the Space button and the save button.
 *
 * Not zero, for the reason the two ROWS of this footer are not flush either: adjacent click
 * targets mean a click a pixel off the one you meant opens a Space picker instead of saving.
 */
private val SPACE_ROW_GAP = 2.dp
