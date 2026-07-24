package ai.rever.boss.components.auth

import ai.rever.boss.components.auth.AuthScreenContainer
import androidx.compose.runtime.Composable

/**
 * Main entry point for authentication flow.
 * This component has been refactored to use the extracted AuthScreenContainer
 * for better separation of concerns and maintainability.
 *
 * @param onLoginSuccess Callback invoked when login/authentication is successful
 */
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    AuthScreenContainer(
        onLoginSuccess = onLoginSuccess,
    )
}
