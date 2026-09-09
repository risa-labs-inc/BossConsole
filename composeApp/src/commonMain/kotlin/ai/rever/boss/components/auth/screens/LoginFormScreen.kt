package ai.rever.boss.components.auth.screens

import ai.rever.boss.components.auth.forms.AuthButtonHeight
import ai.rever.boss.components.auth.forms.AuthScaffold
import ai.rever.boss.components.auth.forms.EmailField
import ai.rever.boss.components.auth.forms.ErrorMessage
import ai.rever.boss.components.auth.forms.LoadingIndicator
import ai.rever.boss.components.auth.forms.PrimaryActionButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.supabase.models.AvailableWebAuthnCredential
import ai.rever.boss.viewmodels.LoginViewModel
import ai.rever.boss.viewmodels.auth.AuthOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Whether [email] is worth submitting.
 *
 * One definition, because this was written out inline in four places. Deliberately unchanged from
 * what those four copies said - tightening the rule is a behaviour change, and smuggling one in
 * under a layout fix is how it would go unnoticed.
 */
private fun emailLooksValid(email: String): Boolean = email.isNotBlank() && email.contains("@")

@Composable
fun LoginFormScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onMagicLinkSent: (String) -> Unit = {},
    onPasskeyAuthInitiated: (String) -> Unit = {},
    onPasskeySelectionRequired: (String) -> Unit = {},
) {
    var email by remember { mutableStateOf("") }
    var authOptions by remember { mutableStateOf<AuthOptions?>(null) }
    var showAuthOptions by remember { mutableStateOf(false) }

    // Collect loading states from ViewModels
    val checkingUserExists by viewModel.authOptionsManager.isLoading.collectAsState()
    val passkeyAuthLoading by viewModel.passkeyAuthViewModel.isLoading.collectAsState()

    // Collect available credentials from AuthOptionsManager
    val availableCredentials by viewModel.authOptionsManager.availableCredentials.collectAsState()

    val submit = {
        if (emailLooksValid(email) && !showAuthOptions) {
            viewModel.checkUserExists(email) { options ->
                authOptions = options
                showAuthOptions = true
            }
        }
    }

    AuthScaffold(title = "Sign in", subtitle = "Continue to BOSS") {
        EmailField(
            value = email,
            onValueChange = {
                email = it
                // Editing the email invalidates whatever the last check concluded.
                if (showAuthOptions) {
                    showAuthOptions = false
                    authOptions = null
                }
            },
            enabled = !isLoading && !checkingUserExists,
            // The only thing anyone does on this screen is type an address, so the caret starts there.
            // Combined with the Go key action below, signing in never needs the mouse.
            autoFocus = true,
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(BossTheme.space.sm))
            ErrorMessage(errorMessage)
        }

        Spacer(modifier = Modifier.height(BossTheme.space.lg))

        // Always rendered while the email step is open, disabled until the address is worth
        // submitting. Hiding it until the text contained an "@" left the card with nothing to act on
        // and a gap where the button belonged, and made the form change height as you typed.
        if (!showAuthOptions) {
            PrimaryActionButton(
                text = "Continue",
                onClick = submit,
                enabled = !isLoading && !checkingUserExists && emailLooksValid(email),
                isLoading = checkingUserExists,
            )
        }

        // Authentication Options (after email validation)
        if (showAuthOptions) {
            AuthOptionsStep(
                options = authOptions,
                email = email,
                viewModel = viewModel,
                isLoading = isLoading,
                passkeyAuthLoading = passkeyAuthLoading,
                availableCredentials = availableCredentials,
                onMagicLinkSent = onMagicLinkSent,
                onPasskeyAuthInitiated = onPasskeyAuthInitiated,
                onPasskeySelectionRequired = onPasskeySelectionRequired,
                onUseMagicLink = { authOptions = AuthOptions.MagicLinkOnly(email) },
            )
        }
    }
}

/**
 * What to offer once the server has said which methods this address can use.
 *
 * Extracted from [LoginFormScreen] because the two are separate steps with no shared local state
 * beyond what is passed here - and because one function holding both was long and branchy enough to
 * need two detekt suppressions.
 */
@Composable
private fun AuthOptionsStep(
    options: AuthOptions?,
    email: String,
    viewModel: LoginViewModel,
    isLoading: Boolean,
    passkeyAuthLoading: Boolean,
    availableCredentials: List<AvailableWebAuthnCredential>,
    onMagicLinkSent: (String) -> Unit,
    onPasskeyAuthInitiated: (String) -> Unit,
    onPasskeySelectionRequired: (String) -> Unit,
    onUseMagicLink: () -> Unit,
) {
    // Auto-send the magic link when it is the only option, so a new user is not asked to click a
    // button that has no alternative. Lives here rather than in the caller because every value it
    // reads is already a parameter of this step.
    LaunchedEffect(options) {
        if (options is AuthOptions.MagicLinkOnly && !isLoading) {
            viewModel.sendMagicLink(email) { onMagicLinkSent(email) }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (options) {
            null -> {
                // Loading state - show checking indicator
                LoadingIndicator()
            }

            is AuthOptions.Invalid -> {
                // alert, not textPrimary: this is the same kind of message ErrorMessage renders.
                Text(
                    text = options.message,
                    color = BossTheme.colors.alert,
                    style = BossTheme.type.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is AuthOptions.WithPasskey -> {
                // Set available passkeys from already-fetched credentials
                LaunchedEffect(availableCredentials) {
                    viewModel.passkeyAuthViewModel.setAvailablePasskeys(availableCredentials)
                }

                PasskeyButton(
                    enabled = !isLoading && !passkeyAuthLoading,
                    isLoading = passkeyAuthLoading,
                    onClick = {
                        // One passkey authenticates directly; several need the selection screen.
                        val passkeyCount = viewModel.passkeyAuthViewModel.availablePasskeys.value.size
                        if (passkeyCount > 1) {
                            onPasskeySelectionRequired(email)
                        } else {
                            onPasskeyAuthInitiated(email)
                        }
                    },
                )

                Spacer(modifier = Modifier.height(BossTheme.space.sm))

                MagicLinkFallbackButton(enabled = !isLoading, onClick = onUseMagicLink)
            }

            is AuthOptions.MagicLinkOnly -> {
                MagicLinkOnlyOption(
                    isLoading = isLoading,
                    onSend = { viewModel.sendMagicLink(email) { onMagicLinkSent(email) } },
                )
            }
        }
    }
}

/**
 * What a known address with no passkey sees.
 *
 * The button is usually skipped: the step's own effect sends the link as soon as this becomes the
 * only option. It stays for the case where that send failed and the user wants to retry.
 */
@Composable
private fun MagicLinkOnlyOption(
    isLoading: Boolean,
    onSend: () -> Unit,
) {
    Text(
        "We'll send you a secure magic link to sign in - no password needed!",
        color = BossTheme.colors.textSecondary,
        style = BossTheme.type.body,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = BossTheme.space.sm),
    )
    Spacer(modifier = Modifier.height(BossTheme.space.lg))
    PrimaryActionButton(
        text = "Send Magic Link",
        onClick = onSend,
        enabled = !isLoading,
        isLoading = isLoading,
    )
}

/** The primary action when the address has a passkey registered. */
@Composable
private fun PasskeyButton(
    enabled: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(AuthButtonHeight),
        enabled = enabled,
        shape = BossTheme.radius.buttonShape,
        colors =
            ButtonDefaults.buttonColors(
                backgroundColor = colors.signal,
                contentColor = colors.onSignal,
            ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                // onSignal, like the icon it replaces: this spinner sits on the signal fill, not on
                // the page, so textPrimary was the wrong token for it.
                color = colors.onSignal,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = "Passkey",
                tint = colors.onSignal,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(BossTheme.space.sm))
            Text(
                text = "Sign in with passkey",
                style = BossTheme.type.title,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** The secondary way in, for someone who cannot use their passkey right now. */
@Composable
private fun MagicLinkFallbackButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(AuthButtonHeight),
        enabled = enabled,
        shape = BossTheme.radius.buttonShape,
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = BossTheme.colors.textPrimary,
            ),
        border = BorderStroke(1.dp, BossTheme.colors.line),
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Magic link",
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(BossTheme.space.sm))
        Text(
            text = "Send magic link",
            style = BossTheme.type.title,
            fontWeight = FontWeight.Medium,
        )
    }
}
