package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CloningProgressContent(progressMessage: String) {
    Column(
        modifier =
            Modifier
                .padding(16.dp)
                .heightIn(min = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = BossTheme.colors.signal,
            strokeWidth = 3.dp,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Cloning Repository",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = progressMessage,
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "This may take a few moments...",
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary.copy(alpha = 0.6f),
        )
    }
}
