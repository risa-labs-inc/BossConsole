package ai.rever.boss.components.auth.screens

import ai.rever.boss.components.auth.AuthDeepLink
import ai.rever.boss.components.auth.AuthDeepLinks
import ai.rever.boss.components.auth.forms.AuthButtonHeight
import ai.rever.boss.components.auth.forms.AuthScaffold
import ai.rever.boss.components.auth.forms.ErrorMessage
import ai.rever.boss.components.auth.forms.LoadingIndicator
import ai.rever.boss.components.auth.forms.PrimaryActionButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.auth.OAuthProviderKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shown while a Google or Apple sign-in is open in the system browser.
 *
 * The browser returns to BOSS through `boss://auth/callback`, which needs the OS to know that
 * BOSS handles `boss://`. For a machine where it does not, the page offers to copy the sign-in
 * link (when no browser opened) and to paste the callback link back in by hand.
 *
 * @param notice why the last callback did not sign in; the sign-in is still open
 * @param exchanging the callback arrived and the session is being established
 * @param onExpireIfStale ends the sign-in once it has run past its time limit; polled while shown,
 *     so a sign-in whose callback never arrives does not leave this screen up for ever
 * @param onPasteCallback a pasted `boss://auth/callback` link, already parsed
 */
@Composable
fun OAuthWaitingScreen(
    provider: OAuthProviderKind,
    authorizeUrl: String?,
    notice: String?,
    exchanging: Boolean,
    onExpireIfStale: suspend () -> Unit,
    onReopenBrowser: () -> Unit,
    onPasteCallback: (AuthDeepLink.OAuthCallback) -> Unit,
    onCancel: () -> Unit,
) {
    AuthScaffold(
        title = if (exchanging) "Signing you in" else "Continue in your browser",
        subtitle =
            if (exchanging) {
                "Finishing ${provider.displayName} sign-in"
            } else {
                "Finish signing in with ${provider.displayName} in the browser window we opened. " +
                    "BOSS will continue by itself."
            },
    ) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(EXPIRY_POLL_MS)
                onExpireIfStale()
            }
        }
        LoadingIndicator()
        Spacer(modifier = Modifier.height(BossTheme.space.lg))

        if (notice != null && !exchanging) {
            ErrorMessage(notice)
            Spacer(modifier = Modifier.height(BossTheme.space.md))
        }

        if (!exchanging) {
            PrimaryActionButton(text = "Reopen browser", onClick = onReopenBrowser)
            Spacer(modifier = Modifier.height(BossTheme.space.sm))
            if (authorizeUrl != null) CopySignInLinkButton(authorizeUrl)
            Spacer(modifier = Modifier.height(BossTheme.space.md))
            CallbackPaste(onPasteCallback)
        }

        TextButton(onClick = onCancel) {
            Text("Cancel", color = BossTheme.colors.textSecondary, style = BossTheme.type.body)
        }
    }
}

/** How often the screen asks whether its sign-in has expired; the limit itself is minutes. */
private const val EXPIRY_POLL_MS = 15_000L

/** For a machine where no browser opened: the user can open the page wherever they like. */
@Composable
private fun CopySignInLinkButton(authorizeUrl: String) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val colors = BossTheme.colors
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString(authorizeUrl))
            copied = true
        },
        modifier = Modifier.fillMaxWidth().height(AuthButtonHeight),
        shape = BossTheme.radius.buttonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        border = BorderStroke(1.dp, colors.line),
    ) {
        Text(if (copied) "Link copied" else "Copy sign-in link", style = BossTheme.type.title)
    }
}

/** "Paste callback link manually", collapsed until asked for. */
@Composable
private fun CallbackPaste(onPasteCallback: (AuthDeepLink.OAuthCallback) -> Unit) {
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val colors = BossTheme.colors
    if (!showPaste) {
        TextButton(onClick = { showPaste = true }) {
            Text(
                "Paste callback link manually",
                color = colors.signalText,
                style = BossTheme.type.body,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    OutlinedTextField(
        value = pasted,
        onValueChange = {
            pasted = it
            pasteError = null
        },
        label = { Text("Paste the boss://auth/callback link") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors =
            TextFieldDefaults.outlinedTextFieldColors(
                textColor = colors.textPrimary,
                backgroundColor = colors.ink,
                focusedBorderColor = colors.signal,
                unfocusedBorderColor = colors.line,
                focusedLabelColor = colors.signalText,
                unfocusedLabelColor = colors.textSecondary,
            ),
    )
    ErrorMessage(pasteError)
    Spacer(modifier = Modifier.height(BossTheme.space.sm))
    PrimaryActionButton(
        text = "Continue",
        enabled = pasted.isNotBlank(),
        onClick = {
            when (val link = AuthDeepLinks.parse(pasted.trim())) {
                is AuthDeepLink.OAuthCallback -> onPasteCallback(link)
                else -> pasteError = NOT_A_CALLBACK_LINK
            }
        },
    )
}

private const val NOT_A_CALLBACK_LINK =
    "That is not a BOSS sign-in callback. It starts with boss://auth/callback."
