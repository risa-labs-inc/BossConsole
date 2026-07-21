package ai.rever.boss.components.windows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String?
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Settings are not yet implemented for iOS",
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface
        )
    }
}
