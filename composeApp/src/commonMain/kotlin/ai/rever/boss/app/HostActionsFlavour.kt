package ai.rever.boss.app

import ai.rever.boss.plugin.api.Panel
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The guarded builder every host's flavour delegates to: this placement's buttons, or nothing.
 *
 * One body, four call sites - the right rail, the bar's foot, the rail's column and the panel's
 * foot. The floating cluster is the fifth placement but not a fifth caller: it has no "am I the
 * owner" question to ask, so it still builds `focusQuickActionButtons` directly. Each flavour
 * differs in exactly two things - the placement it belongs to and which way its hints point - and
 * each was a character-for-character copy of the other three, so a fifth host meant a fourth copy
 * and three `@Suppress` annotations to go with it.
 *
 * The empty list is the whole contract: it lets every host call its own flavour unconditionally
 * and render nothing when the actions live elsewhere. The right rail also RESERVES height from the
 * list's size, so a non-empty list on the wrong placement is not a stray icon - it is a gap torn
 * out of the icon budget of a bar that is not hosting anything.
 *
 * [modifier] defaults to one rail-sized button. That is 32.dp where the floating cluster leaves
 * them at their own 28.dp: in a bar or a rail these have to match the icons around them, and
 * `DraggableSidebarSection` sizes those the same way. An outer fixed size wins over the inner one
 * `BossActionButton` applies in `imageVector` mode, because a fixed constraint coerces what it
 * wraps - which is also why the cluster passes `Modifier` and gets its own 28.dp back.
 */
// One parameter per action, plus the placement pair, the launcher slot and the size.
@Suppress("LongParameterList")
internal fun focusQuickActionsFor(
    owner: FocusQuickActionsPlacement,
    hintDirection: Panel,
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier.size(SIDEBAR_ICON_SIZE),
): List<@Composable () -> Unit> =
    if (placement != owner) {
        emptyList()
    } else {
        focusQuickActionButtons(
            hintDirection = hintDirection,
            modifier = modifier,
            onShowSettings = onShowSettings,
            toolbox = toolbox,
            onShowSearch = onShowSearch,
            onSignOut = onSignOut,
            toolLauncher = toolLauncher,
        )
    }
