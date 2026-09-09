package ai.rever.boss.components.home

import ai.rever.boss.components.dashboard.cards.ReleaseCard
import ai.rever.boss.components.dashboard.sections.DashboardSection
import ai.rever.boss.components.dialogs.dialogScrollFence
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.updater.ReleaseNotesContent
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.updater.VersionInfo
import ai.rever.boss.updater.VersionSelectionDialog
import ai.rever.boss.updater.VersionSelectionMode
import ai.rever.boss.updater.isUnseenRelease
import ai.rever.boss.utils.AppVersion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Recent releases shown on the Dashboard.
 *
 * The section uses the application-wide VersionListManager, hides when no
 * release data is available, and owns its dialogs so every HomeScreen mount
 * behaves consistently.
 */
@Composable
internal fun WhatsNewSection(updateCoordinator: UpdateCoordinator = UpdateCoordinator.instance) {
    val versionListManager = updateCoordinator.versionListManager
    val versions by versionListManager.versions.collectAsState()
    val isLoading by versionListManager.isLoading.collectAsState()
    val lastSeenReleaseVersion by
        updateCoordinator.lastSeenReleaseVersion.collectAsState()

    var selectedRelease by remember { mutableStateOf<VersionInfo?>(null) }
    var showAllReleases by remember { mutableStateOf(false) }

    LaunchedEffect(versionListManager) {
        versionListManager.fetchVersions()
    }

    val latestReleases = remember(versions) { versions.take(WHATS_NEW_LIMIT) }

    // Empty covers initial loading, offline use and fetch failures without
    // placing an updater error message on the Dashboard.
    if (latestReleases.isEmpty()) return

    val openRelease: (VersionInfo) -> Unit = { release ->
        selectedRelease = release
        updateCoordinator.markReleaseSeenInBackground(release.version)
    }

    WhatsNewFeed(
        releases = latestReleases,
        lastSeenReleaseVersion = lastSeenReleaseVersion,
        onOpenRelease = openRelease,
        onViewAll = { showAllReleases = true },
    )

    selectedRelease?.let { release ->
        ReleaseNotesDialog(
            release = release,
            onDismiss = { selectedRelease = null },
        )
    }

    if (showAllReleases) {
        VersionSelectionDialog(
            currentVersion = AppVersion.CURRENT,
            versions = versions,
            isLoading = isLoading,
            // A stale fetch error must not replace usable cached releases.
            error = null,
            onVersionSelected = { release ->
                showAllReleases = false
                openRelease(release)
            },
            onDismiss = { showAllReleases = false },
            mode = VersionSelectionMode.BROWSE,
        )
    }
}

@Composable
private fun WhatsNewFeed(
    releases: List<VersionInfo>,
    lastSeenReleaseVersion: String?,
    onOpenRelease: (VersionInfo) -> Unit,
    onViewAll: () -> Unit,
) {
    DashboardSection(
        title = "What's New",
        subtitle = "Latest BOSS releases and improvements",
        actionText = "View all",
        onAction = onViewAll,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(BossTheme.space.md),
        ) {
            releases.forEach { release ->
                ReleaseCard(
                    release = release,
                    isNew =
                        isUnseenRelease(
                            version = release.version,
                            lastSeenReleaseVersion = lastSeenReleaseVersion,
                        ),
                    onClick = { onOpenRelease(release) },
                )
            }
        }
    }
}

/**
 * Full notes for one release, using the same reusable renderer as the update
 * dialog instead of duplicating its Markdown parsing and fallback behavior.
 */
@Composable
private fun ReleaseNotesDialog(
    release: VersionInfo,
    onDismiss: () -> Unit,
) {
    BossAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
        title = {
            Text(
                text = "BossConsole v${release.version}",
                color = BossTheme.colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .dialogScrollFence(RELEASE_NOTES_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Released ${formatReleaseDate(release.releaseDate)}",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )

                if (release.releaseNotes.isBlank()) {
                    Text(
                        text = "No release notes are available.",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    ReleaseNotesContent(release.releaseNotes)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    color = BossTheme.colors.signalText,
                    fontSize = 13.sp,
                )
            }
        },
        backgroundColor = BossTheme.colors.panel,
        contentColor = BossTheme.colors.textPrimary,
    )
}

private fun formatReleaseDate(releaseDate: String): String = releaseDate.substringBefore("T").ifBlank { releaseDate }

private const val WHATS_NEW_LIMIT = 5
private val RELEASE_NOTES_MAX_HEIGHT = 420.dp
