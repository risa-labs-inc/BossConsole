package ai.rever.boss.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Switching projects cancels the old scan only after composition commits. */
@Composable
internal fun rememberSpotlightFileIndexer(
    owner: SpotlightFileIndexOwner,
    projectPath: String,
): FileIndexer? {
    var indexer by remember(owner, projectPath) { mutableStateOf<FileIndexer?>(null) }
    LaunchedEffect(owner, projectPath) { indexer = owner.indexerFor(projectPath) }
    return indexer
}
