package ai.rever.boss.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

/**
 * Desktop implementation of clipboard helper using Java AWT StringSelection
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun createTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
