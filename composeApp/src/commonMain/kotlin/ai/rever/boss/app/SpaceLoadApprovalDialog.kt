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

/**
 * Asks the operator before an externally requested Space starts its terminal commands.
 *
 * Armed after the same interval [TerminalCommandApprovalDialog] uses, so a click already in
 * flight when the prompt appears cannot land on its confirm button.
 */
@Composable
internal fun SpaceLoadApprovalDialog(
    request: PendingSpaceLoad,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Load this Space and run its commands?",
            message = spaceLoadApprovalMessage(request),
            confirmText = "Load and run",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/** The prompt's body: where the request points and every command, numbered, in full. */
internal fun spaceLoadApprovalMessage(request: PendingSpaceLoad): String =
    buildString {
        append("BOSS was asked from outside the app to load the Space \"")
        append(request.workspace.name.safeSpacePromptLabel())
        append("\" from:\n")
        append(request.workspacePath.safeSpacePromptLabel())
        append("\n\nIts terminal tabs would run these commands. Nothing has loaded or run. ")
        append("Confirm only if you recognise them:\n")
        if (request.commands.any { command -> SPACE_COMMAND_PLACEHOLDERS.any(command::contains) }) {
            append("\nPlaceholders in braces expand when the Space loads, so the shell may receive different text.\n")
        }
        request.commands.forEachIndexed { index, command ->
            append("\n")
            append(index + 1)
            append(". ")
            append(command)
        }
    }

private const val SPACE_PROMPT_LABEL_MAX_LENGTH = 512

/** Keeps untrusted Space metadata from forging or overwhelming the command confirmation copy. */
private fun String.safeSpacePromptLabel(): String {
    val visible =
        buildString {
            var index = 0
            while (index < this@safeSpacePromptLabel.length) {
                val character = this@safeSpacePromptLabel[index]
                val low = this@safeSpacePromptLabel.getOrNull(index + 1)
                if (low != null && isSupplementaryFormatCharacter(character, low)) {
                    append('\uFFFD')
                    index += 2
                } else {
                    append(if (character.category in HIDDEN_DISPLAY_CHARACTERS) '\uFFFD' else character)
                    index++
                }
            }
        }
    return if (visible.length <= SPACE_PROMPT_LABEL_MAX_LENGTH) {
        visible
    } else {
        val proposedEnd = SPACE_PROMPT_LABEL_MAX_LENGTH - 1
        val safeEnd =
            if (visible[proposedEnd - 1].isHighSurrogate() && visible[proposedEnd].isLowSurrogate()) {
                proposedEnd - 1
            } else {
                proposedEnd
            }
        visible.take(safeEnd) + "…"
    }
}

private val SPACE_COMMAND_PLACEHOLDERS =
    setOf("{projectPath}", "{gitRemoteUrl}", "{currentFile}", "{claudeContinueFlag}")
