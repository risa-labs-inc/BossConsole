package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.tab_types.fluck.FluckView
import ai.rever.boss.components.plugin.tab_types.fluck.createBrowser
import ai.rever.boss.components.plugin.tab_types.fluck.createBrowserViewState
import ai.rever.boss.plugin.api.FluckPanelContentProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Implementation of FluckPanelContentProvider that wraps browser functionality.
 */
class FluckPanelContentProviderImpl : FluckPanelContentProvider {
    private val logger = BossLogger.forComponent("FluckPanelContentProvider")

    // Create browser instance with error handling
    private var browserError: Throwable? = null
    private val browser: Any? = try {
        createBrowser()
    } catch (e: Throwable) {
        browserError = e
        logger.warn(LogCategory.BROWSER, "Failed to create browser", error = e)
        null
    }

    private val browserViewState: Any? = browser?.let {
        try {
            createBrowserViewState(it)
        } catch (e: Throwable) {
            browserError = e
            logger.warn(LogCategory.BROWSER, "Failed to create browser view state", error = e)
            null
        }
    }

    private var isDisposed = false
    private val currentTitle = mutableStateOf("Fluck Browser")

    @Composable
    override fun FluckPanelContent() {
        if (!isDisposed) {
            when {
                browserError != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF2B2D30)),
                        contentAlignment = Alignment.Center
                    ) {
                        FluckPanelErrorView(error = browserError!!)
                    }
                }
                browser != null && browserViewState != null -> {
                    FluckView(
                        fileId = "fluck_panel",
                        content = "https://chat.openai.com",
                        browser = browser,
                        browserViewState = browserViewState,
                        onContentChange = { },
                        onTitleChange = { newTitle ->
                            currentTitle.value = newTitle
                        },
                        onIconChange = { },
                        onTabIconUpdate = { },
                        onOpenInNewTab = { },
                        onNavigationUpdate = null,
                        onNavigationStateChange = null
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF2B2D30)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun FluckPanelErrorView(error: Throwable) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Browser Not Available",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        val errorMessage = when {
            error.message?.contains("already in use") == true -> {
                "Another instance of BOSS is using the browser.\nPlease close other instances and refresh."
            }
            else -> "Unable to initialize the browser component."
        }

        Text(
            text = errorMessage,
            fontSize = 12.sp,
            color = Color(0xFFCCCCCC),
            textAlign = TextAlign.Center
        )

        if (error.message?.contains("already in use") == true) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Tip: The browser will automatically try alternative\nprofiles on next restart.",
                fontSize = 11.sp,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center
            )
        }
    }
}
