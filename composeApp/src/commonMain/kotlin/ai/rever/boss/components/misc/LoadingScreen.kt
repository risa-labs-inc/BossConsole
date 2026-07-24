package ai.rever.boss.components.misc

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.boss_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun LoadingScreen() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BossTheme.colors.panel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // BOSS Logo
            Image(
                painter = painterResource(Res.drawable.boss_icon),
                contentDescription = "BOSS Logo",
                modifier = Modifier.size(80.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Loading indicator
            CircularProgressIndicator(
                color = BossTheme.colors.signal,
                modifier = Modifier.size(40.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Loading text
            Text(
                text = "Loading BOSS...",
                color = BossTheme.colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
