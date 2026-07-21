package ai.rever.boss

import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.body?.let { body ->
        ComposeViewport(body) {
            // Create root component with iOS lifecycle
            with(createBossAppContext) {
                // Display the app
                BossApp()
            }
        }
    }
}
