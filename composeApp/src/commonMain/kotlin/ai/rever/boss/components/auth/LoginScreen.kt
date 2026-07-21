package ai.rever.boss.components.auth

import androidx.compose.runtime.Composable
import ai.rever.boss.components.auth.AuthScreenContainer

/**
 * Main entry point for authentication flow.
 * This component has been refactored to use the extracted AuthScreenContainer
 * for better separation of concerns and maintainability.
 *
 * @param onLoginSuccess Callback invoked when login/authentication is successful
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    AuthScreenContainer(
        onLoginSuccess = onLoginSuccess
    )
}



