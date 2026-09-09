package ai.rever.boss.components.overlays

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Host-only opt-out from focus-loss dismissal. Wrap the whole `BossDialog` call so the inline
 * injected renderer inherits the policy. The local lives in commonMain so any host dialog can use
 * it without changing the binary-compatible plugin API or renderer signatures.
 *
 * The default preserves historical routing behavior, including New Tab. It is not a general
 * desktop modal convention: dialogs whose dismissal acknowledges or cancels work should provide
 * false and require an explicit action. Escape remains governed by `DialogProperties`.
 *
 * This controls dismissal only. Heavyweight windows still use always-on-top placement and bounds
 * captured at open; keeping one alive across app switches requires manual platform verification.
 */
internal val LocalDismissModalOnFocusLoss = staticCompositionLocalOf { true }
