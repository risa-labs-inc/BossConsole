package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.importer.browser.DetectedBrowser
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage bodies for ImportDataDialog. Kept in their own file so the dialog's
// state machine stays readable next to its own logic.
//
// Every size, colour and weight here comes from BossTheme so the dialog
// re-skins with whichever theme is active rather than pinning itself to one
// palette.

@Composable
internal fun ChooseSourceContent(
    browsers: List<DetectedBrowser>,
    scanning: Boolean,
    onPickBrowser: (DetectedBrowser) -> Unit,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Text(
            "Import from a browser installed on this computer, or from a file you exported.",
            style = BossTheme.type.body,
            color = BossTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(BossTheme.space.lg))

        SectionLabel("INSTALLED BROWSERS")

        when {
            scanning -> {
                Text(
                    "Looking for installed browsers…",
                    style = BossTheme.type.body,
                    color = BossTheme.colors.textSecondary,
                )
            }

            browsers.isEmpty() -> {
                Text(
                    "No browser profiles found. Use an exported file instead.",
                    style = BossTheme.type.body,
                    color = BossTheme.colors.textMuted,
                )
            }

            else -> {
                Column(
                    Modifier
                        .heightIn(max = BROWSER_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    browsers.forEach { detected ->
                        BrowserRow(detected = detected, onClick = { onPickBrowser(detected) })
                    }
                }
            }
        }

        Spacer(Modifier.height(BossTheme.space.lg))
        SectionLabel("FROM A FILE")
        Text(
            "Passwords from CSV, Bitwarden JSON or KeePass XML; bookmarks from HTML - Chrome ⋮ ▸ Passwords ▸ Export, " +
                "Safari File ▸ Export, Firefox Passwords ▸ Export Logins.",
            style = BossTheme.type.body,
            color = BossTheme.colors.textSecondary,
        )

        DialogActions(
            confirmText = "Choose File…",
            onConfirm = onChoose,
            onCancel = onCancel,
        )
    }
}

/** One detected profile: what it holds, and why anything is missing. */
@Composable
private fun BrowserRow(
    detected: DetectedBrowser,
    onClick: () -> Unit,
) {
    val caps = detected.capabilities
    val parts =
        buildList {
            caps.bookmarkCount?.let { add("$it bookmarks") }
            caps.passwordCount?.let { add("$it passwords") }
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(BossTheme.colors.panel, BossTheme.radius.cardShape)
            .padding(horizontal = BossTheme.space.md, vertical = BossTheme.space.sm),
    ) {
        Text(
            detected.profile.displayName,
            style = BossTheme.type.body,
            color = BossTheme.colors.textPrimary,
        )
        Text(
            parts.joinToString(" · ").ifEmpty { "nothing readable" },
            style = BossTheme.type.micro,
            color = BossTheme.colors.textSecondary,
        )
        // The bookmark note is the actionable one (Full Disk Access), so it
        // reads as a warning rather than a footnote.
        caps.bookmarkNote?.let { note ->
            Text(note, style = BossTheme.type.micro, color = BossTheme.colors.warn)
        }
        caps.passwordNote?.let { note ->
            Text(note, style = BossTheme.type.micro, color = BossTheme.colors.textMuted)
        }
    }
    Spacer(Modifier.height(BossTheme.space.xs))
}

@Composable
internal fun FailedContent(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Text(message, style = BossTheme.type.body, color = BossTheme.colors.alert)
        DialogActions(
            confirmText = "Choose Another File",
            onConfirm = onRetry,
            onCancel = onCancel,
        )
    }
}

/** Small caps section heading — the design system's `label` voice. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = BossTheme.type.label,
        color = BossTheme.colors.textMuted,
    )
    Spacer(Modifier.height(BossTheme.space.xs))
}

/**
 * Trailing Cancel / confirm pair.
 *
 * Uses the design system's buttons rather than bare TextButtons so the import
 * dialog presses, disables and re-skins like every other BOSS surface.
 */
@Composable
internal fun DialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelText: String = "Cancel",
) {
    Spacer(Modifier.height(BossTheme.space.lg))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        BossSecondaryButton(text = cancelText, onClick = onCancel)
        Spacer(Modifier.padding(horizontal = BossTheme.space.xs))
        BossPrimaryButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
        )
    }
}

/** Keeps a long browser list from pushing the action row off-screen. */
private val BROWSER_LIST_MAX_HEIGHT = 200.dp
