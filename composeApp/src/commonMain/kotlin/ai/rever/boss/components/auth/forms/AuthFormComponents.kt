package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.painterResource
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.boss_icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared component for BOSS logo display
 */
@Composable
fun BossLogo(
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(Res.drawable.boss_icon),
        contentDescription = "BOSS Logo",
        modifier = modifier.size(64.dp)
    )
}

/**
 * Shared component for authentication cards
 */
@Composable
fun AuthCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 400.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = 2.dp,
        backgroundColor = BossTheme.colors.raised
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

/**
 * Shared component for card titles
 */
@Composable
fun AuthCardTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = BossTheme.colors.textPrimary,
        modifier = modifier.padding(bottom = 24.dp)
    )
}

/**
 * Shared email input field component
 */
@Composable
fun EmailField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Email", color = BossTheme.colors.textSecondary) },
        leadingIcon = {
            Icon(
                Icons.Default.Email,
                contentDescription = "Email",
                tint = BossTheme.colors.textSecondary
            )
        },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Go
        ),
        keyboardActions = keyboardActions,
        enabled = enabled,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = BossTheme.colors.textPrimary,
            backgroundColor = BossTheme.colors.panel,
            focusedBorderColor = BossTheme.colors.signal,
            unfocusedBorderColor = BossTheme.colors.line,
            cursorColor = BossTheme.colors.signal,
            focusedLabelColor = BossTheme.colors.signal,
            unfocusedLabelColor = BossTheme.colors.textSecondary
        )
    )
}

/**
 * Shared primary action button component
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = BossTheme.colors.signal,
            contentColor = Color.White
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Shared error message display component
 */
@Composable
fun ErrorMessage(
    message: String?,
    modifier: Modifier = Modifier
) {
    if (message != null) {
        Text(
            text = message,
            color = BossTheme.colors.alert,
            fontSize = 12.sp,
            modifier = modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
    }
}

/**
 * Shared loading indicator component
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Int = 24
) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        color = BossTheme.colors.signal,
        strokeWidth = 2.dp
    )
}
