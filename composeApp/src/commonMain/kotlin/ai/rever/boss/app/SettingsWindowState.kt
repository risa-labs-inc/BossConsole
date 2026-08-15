package ai.rever.boss.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the settings window is up, which section it was asked to land on, and how many times it
 * has been asked to raise itself.
 *
 * One holder rather than three loose flags on [BossAppState], because the three only mean anything
 * together and the bug this exists to prevent came from writing one of them directly. Every
 * Settings affordance - the top bar, the focus-mode quick actions, the menu action, the dashboard,
 * the shortcut-help deep link - assigned `showSettingsDialog = true`, and assigning `true` to a
 * flag that is already `true` changes nothing at all. With the window open and buried behind the
 * main one, clicking Settings did nothing and read as a dead button. Nothing about a `var Boolean`
 * says that, so the fields are `private set` and [open] / [close] are the only ways in.
 */
class SettingsWindowState {
    /** Whether the window is composed at all. */
    var visible by mutableStateOf(false)
        private set

    /** Section to land on, for the callers that deep-link - KEYMAP from shortcut help, menu items. */
    var section by mutableStateOf<String?>(null)
        private set

    /**
     * Bumped once per request to show settings while it is already [visible].
     *
     * A counter rather than a flag, because the window has to act on *every* request and "a flag
     * that is already set" is precisely the failure above. The window keys an effect on it and
     * deiconifies, raises and focuses itself on each new value.
     */
    var focusRequest by mutableStateOf(0)
        private set

    /**
     * Show the settings window, or ask the one already open to raise itself.
     *
     * [section] is applied only when given. Passing null means "just show settings" and must not
     * clear a section another caller navigated to, which a plain assignment would.
     */
    fun open(section: String? = null) {
        if (section != null) {
            this.section = section
        }
        if (visible) {
            focusRequest++
        } else {
            visible = true
        }
    }

    /**
     * The window was closed.
     *
     * The section is cleared with it so a later plain [open] starts at the default rather than
     * re-landing on wherever a deep link last went. [focusRequest] deliberately is NOT reset: it is
     * an ever-increasing signal, and zeroing it could make the next request repeat a value the
     * window has already handled, which would swallow it.
     */
    fun close() {
        visible = false
        section = null
    }
}
