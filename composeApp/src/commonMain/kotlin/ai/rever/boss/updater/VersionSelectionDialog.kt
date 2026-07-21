package ai.rever.boss.updater

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkContentBackground
import BossDarkError
import BossDarkSuccess
import BossDarkTextPrimary
import BossDarkTextSecondary
import BossDarkWarning
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.rever.boss.components.common.BossSearchBar
import ai.rever.boss.utils.Version

/**
 * Dialog for selecting a specific version to install
 */
@Composable
fun VersionSelectionDialog(
    currentVersion: Version,
    versions: List<VersionInfo>,
    isLoading: Boolean,
    error: String? = null,
    onVersionSelected: (VersionInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showStableOnly by remember { mutableStateOf(true) }

    // Calculate latest stable version (first non-prerelease in sorted list)
    val latestStableVersion = remember(versions) {
        versions.firstOrNull { !it.isPrerelease }?.version
    }

    val filteredVersions = remember(versions, searchQuery, showStableOnly) {
        versions
            .filter { if (showStableOnly) !it.isPrerelease else true }
            .filter {
                searchQuery.isEmpty() ||
                it.version.toString().contains(searchQuery, ignoreCase = true)
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 700.dp),
            backgroundColor = BossDarkBackground,
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Version",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossDarkTextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            "Close",
                            tint = BossDarkTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search bar
                BossSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search versions...",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Filter toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showStableOnly,
                        onCheckedChange = { showStableOnly = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BossDarkAccent
                        )
                    )
                    Text(
                        text = "Stable releases only",
                        color = BossDarkTextPrimary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Error display
                if (error != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BossDarkError.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = BossDarkError
                            )
                            Text(
                                text = error,
                                color = BossDarkError,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Version list
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = BossDarkAccent)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Loading versions...",
                                color = BossDarkTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else if (filteredVersions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No versions found",
                            color = BossDarkTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredVersions) { versionInfo ->
                            VersionItem(
                                versionInfo = versionInfo,
                                isCurrent = versionInfo.version == currentVersion,
                                isLatest = versionInfo.version == latestStableVersion,
                                onClick = { onVersionSelected(versionInfo) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual version item in the list
 */
@Composable
private fun VersionItem(
    versionInfo: VersionInfo,
    isCurrent: Boolean,
    isLatest: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = BossDarkContentBackground,
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        border = BorderStroke(
            1.dp,
            if (isCurrent) BossDarkAccent.copy(alpha = 0.5f) else BossDarkBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${versionInfo.version}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrent) BossDarkAccent else BossDarkTextPrimary
                    )

                    if (isCurrent) {
                        Card(
                            backgroundColor = BossDarkSuccess,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Current",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BossDarkTextPrimary
                            )
                        }
                    }

                    if (isLatest && !isCurrent) {
                        Card(
                            backgroundColor = BossDarkAccent,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Latest",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BossDarkTextPrimary
                            )
                        }
                    }

                    if (versionInfo.isPrerelease) {
                        Card(
                            backgroundColor = BossDarkWarning,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Beta",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Released: ${formatReleaseDate(versionInfo.releaseDate)} • ${formatFileSize(versionInfo.downloadSize)}",
                    fontSize = 12.sp,
                    color = BossDarkTextSecondary
                )
            }

            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = "Download",
                tint = BossDarkAccent
            )
        }
    }
}

/**
 * Format file size in MB
 */
private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}

/**
 * Format release date to simple format
 */
private fun formatReleaseDate(dateString: String): String {
    return try {
        // GitHub returns dates like "2024-01-15T10:30:00Z"
        // Extract just the date part
        dateString.substringBefore("T")
    } catch (e: Exception) {
        dateString
    }
}
