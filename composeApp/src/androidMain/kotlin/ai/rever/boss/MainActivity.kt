package ai.rever.boss

import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.arkivanov.decompose.defaultComponentContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize WorkspaceFileManager with application context
        WorkspaceFileManager.init(this)

        setContent {
            with((defaultComponentContext())) {
                BossApp()
            }
        }

    }
}

@Preview(
//    showSystemUi = true,
    device = Devices.AUTOMOTIVE_1024p,
    widthDp = 1280,
    heightDp = 640
)
@Composable
fun AppAndroidPreview() {
    with(createBossAppContext) {
        BossApp()
    }
}
