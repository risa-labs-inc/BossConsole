package ai.rever.boss.updater

import androidx.compose.runtime.Composable

/**
 * Returns true if the window identified by [windowId] should host the
 * app-update dialog. Exactly one window owns the dialog at a time so it
 * isn't duplicated across windows; ownership transfers reactively if the
 * owning window closes.
 */
@Composable
expect fun rememberUpdateDialogOwnership(windowId: String): Boolean
