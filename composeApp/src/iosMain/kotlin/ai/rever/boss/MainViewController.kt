package ai.rever.boss

import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewControllerV4() = ComposeUIViewController {
    // Create root component with iOS lifecycle
    with(createBossAppContext) {
        // Display the app
        BossApp()
    }

}
