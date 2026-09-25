package ai.rever.boss.app

import ai.rever.boss.cli.HIDDEN_DISPLAY_CHARACTERS
import ai.rever.boss.cli.isSupplementaryFormatCharacter
import ai.rever.boss.components.dialogs.ConfirmationDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Each distinct request gets a fresh arming interval, including identical URLs. */
@Composable
internal fun UrlOpenApprovalDialog(
    request: PendingUrlOpen,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        val visibleUrl = visibleUrlForApproval(request.url)
        ConfirmationDialog(
            title = "Open this link? ($pendingCount pending)",
            message =
                "BOSS was asked from outside the app to open a link in a new browser tab. " +
                    "It has not been opened. Confirm only if you recognise it:\n\n$visibleUrl",
            confirmText = "Open link",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/** Render invisible and direction-changing characters as visible escapes in the approval prompt. */
internal fun visibleUrlForApproval(url: String): String =
    buildString {
        var index = 0
        while (index < url.length) {
            val character = url[index]
            val next = url.getOrNull(index + 1)
            if (next != null && isSupplementaryFormatCharacter(character, next)) {
                appendVisibleEscape(character)
                appendVisibleEscape(next)
                index += 2
            } else {
                if (isInvisibleOrDirectional(character)) appendVisibleEscape(character) else append(character)
                index++
            }
        }
    }

private fun StringBuilder.appendVisibleEscape(character: Char) {
    append("\\u")
    append(character.code.toString(16).padStart(4, '0'))
}

private fun isInvisibleOrDirectional(character: Char): Boolean {
    val hiddenCategory = character.category in HIDDEN_DISPLAY_CHARACTERS
    return hiddenCategory || character.code == 0x034f
}
