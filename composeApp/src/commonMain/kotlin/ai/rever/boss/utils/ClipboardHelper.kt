package ai.rever.boss.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

/**
 * Platform-specific clipboard helper for creating ClipEntry from plain text
 */
@OptIn(ExperimentalComposeUiApi::class)
expect fun createTextClipEntry(text: String): ClipEntry
