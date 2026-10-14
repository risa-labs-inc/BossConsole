package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.auth.OAuthProviderKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.ic_apple_logo
import boss_kotlin.composeapp.generated.resources.ic_google_g
import org.jetbrains.compose.resources.painterResource

/**
 * "Continue with Google" and "Continue with Apple", stacked full width.
 *
 * Both follow their brand rules with theme tokens rather than literals. Google's button is a
 * neutral `raised` surface with a hairline and the full-colour "G", which is a brand asset and
 * never tinted. Apple's is solid in the theme's strongest contrast: `textPrimary` fill with an
 * `ink` label reads as Apple's white button on the dark themes and its black button on the light
 * ones, which is the pairing the Human Interface Guidelines ask for.
 *
 * @param busyProvider the provider whose sign-in is starting, drawn with a spinner; every button
 *     is disabled while it is set
 */
@Composable
fun OAuthProviderButtons(
    enabled: Boolean,
    busyProvider: OAuthProviderKind?,
    onSignIn: (OAuthProviderKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors
    val clickable = enabled && busyProvider == null
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BossTheme.space.sm)) {
        ProviderButton(
            label = "Continue with Google",
            enabled = clickable,
            busy = busyProvider == OAuthProviderKind.GOOGLE,
            background = colors.raised,
            content = colors.textPrimary,
            border = BorderStroke(1.dp, colors.line),
            onClick = { onSignIn(OAuthProviderKind.GOOGLE) },
        ) {
            Image(
                painter = painterResource(Res.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        ProviderButton(
            label = "Continue with Apple",
            enabled = clickable,
            busy = busyProvider == OAuthProviderKind.APPLE,
            background = colors.textPrimary,
            content = colors.ink,
            border = null,
            onClick = { onSignIn(OAuthProviderKind.APPLE) },
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_apple_logo),
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ProviderButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    background: Color,
    content: Color,
    border: BorderStroke?,
    onClick: () -> Unit,
    logo: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(AuthButtonHeight),
        enabled = enabled,
        shape = BossTheme.radius.buttonShape,
        border = border,
        colors =
            ButtonDefaults.outlinedButtonColors(
                backgroundColor = background,
                contentColor = content,
                disabledContentColor = content.copy(alpha = DISABLED_ALPHA),
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
                } else {
                    logo()
                }
            }
            Spacer(modifier = Modifier.width(BossTheme.space.sm))
            Text(text = label, style = BossTheme.type.title, fontWeight = FontWeight.Medium)
        }
    }
}

/** A hairline with a word in it, between the provider buttons and the email form. */
@Composable
fun AuthOrDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = BossTheme.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Divider(modifier = Modifier.weight(1f), color = BossTheme.colors.line)
        Text(
            text = "or",
            color = BossTheme.colors.textMuted,
            style = BossTheme.type.body,
            modifier = Modifier.padding(horizontal = BossTheme.space.sm),
        )
        Divider(modifier = Modifier.weight(1f), color = BossTheme.colors.line)
    }
}

private const val DISABLED_ALPHA = 0.5f
