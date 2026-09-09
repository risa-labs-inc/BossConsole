package ai.rever.boss.updater

import ai.rever.boss.components.dialogs.DownloadCenterDialog
import ai.rever.boss.layout.TRAFFIC_LIGHT_HEIGHT
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.Version
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Update notification banner that appears at the top of the application.
 * Uses a sleek, compact dark theme matching BossTerm's design.
 */
@Composable
fun UpdateBanner(
    updateState: UpdateState,
    /**
     * Indent at the start of the banner, for the macOS traffic lights.
     *
     * The banner is the TOPMOST chrome when there is no title row - above the top bar, above the
     * columns - so whatever else has been given clearance, the lights are drawn over this while it
     * is up. It is also transient, which is why it is not one of `TrafficLightInset`'s cases: it
     * does not change where anything else goes, it just indents itself while it exists.
     */
    startInset: Dp = 0.dp,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onDiscardDownload: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    // Hoisted ABOVE the `when`, and this is load-bearing rather than tidiness. Held inside a
    // branch, the state it exists to show is what destroys it: a download finishing moves
    // Downloading -> ReadyToInstall, that branch leaves composition, and an open dialog vanishes
    // at the exact moment its row gains an Install button. Pressing Install then does it again
    // (-> Installing -> no banner). The DownloadCenter row survives both transitions, and the
    // bottom bar's item keeps its dialog open across them, so the banner's copy was the only
    // thing disappearing - reintroducing one step later the very asymmetry this set out to remove.
    //
    // Surviving branch changes means inheriting the hazard DownloadCenterStatusItem documents,
    // where a dialog whose subtree is removed cannot dismiss itself. It does not apply here:
    // nothing early-returns this composable, so DownloadCenterDialog's own
    // LaunchedEffect(transfers.isEmpty()) is always alive to dispatch.
    var showDialog by remember { mutableStateOf(false) }
    val openDownloadCenter = { showDialog = true }

    when (updateState) {
        is UpdateState.UpdateAvailable -> {
            UpdateAvailableBanner(
                startInset = startInset,
                updateInfo = updateState.updateInfo,
                onDownload = { onDownloadUpdate(updateState.updateInfo) },
                onDismiss = onDismiss,
            )
        }

        is UpdateState.Downloading -> {
            DownloadProgressBanner(
                startInset = startInset,
                progress = updateState.progress,
                onCancel = onCancelDownload,
                onOpenDownloadCenter = openDownloadCenter,
            )
        }

        is UpdateState.ReadyToInstall -> {
            ReadyToInstallBanner(
                startInset = startInset,
                onInstall = { onInstallUpdate(updateState.downloadPath) },
                onDiscard = onDiscardDownload,
                onOpenDownloadCenter = openDownloadCenter,
            )
        }

        is UpdateState.RestartRequired -> {
            RestartRequiredBanner(startInset = startInset)
        }

        is UpdateState.Error -> {
            ErrorBanner(
                startInset = startInset,
                message = updateState.message,
                onRetry = onCheckForUpdates,
                onDismiss = onDismiss,
            )
        }

        else -> { /* No banner for other states */ }
    }

    // Outside the `when`, so a state change cannot take the dialog with it. Rendered even for
    // the states that draw no banner: Installing draws nothing here but still has a row, and a
    // dialog opened from the ready banner should survive pressing Install inside it.
    if (showDialog) {
        DownloadCenterDialog(onDismiss = { showDialog = false })
    }
}

@Composable
private fun UpdateAvailableBanner(
    startInset: Dp,
    updateInfo: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossTheme.colors.panel,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bannerPad(startInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Update Available",
                    tint = BossTheme.colors.signalText,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Update v${updateInfo.latestVersion} available",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "(current: v${updateInfo.currentVersion})",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            Row {
                TextButton(
                    onClick = onDownload,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Download", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBanner(
    startInset: Dp,
    progress: Float,
    onCancel: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossTheme.colors.panel,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The banner opens the download center, matching the bottom bar's
                    // progress item - this is the app's own update, so it is one row in
                    // the same dialog rather than a separate surface. Cancel below is a
                    // TextButton and consumes its own press, so hitting it does not also
                    // open the dialog behind it.
                    .clickable(onClick = onOpenDownloadCenter)
                    .bannerPad(startInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Downloading",
                tint = BossTheme.colors.ok,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Downloading... ${(progress * 100).toInt()}%",
                color = BossTheme.colors.textPrimary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(4.dp),
                color = BossTheme.colors.ok,
                backgroundColor = BossTheme.colors.raised,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Cancel", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReadyToInstallBanner(
    startInset: Dp,
    onInstall: () -> Unit,
    onDiscard: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossTheme.colors.panel,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDownloadCenter)
                    .bannerPad(startInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Ready to Install",
                    tint = BossTheme.colors.warn,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Update ready to install",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 12.sp,
                )
            }

            Row {
                TextButton(
                    onClick = onInstall,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.warn),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Install Now", fontSize = 11.sp)
                }
                // "Cancel", not "Discard", because the download center's dialog calls the same
                // action Cancel on this row and so does the banner one state earlier. The word
                // is doing less work than the consistency is: one action, three surfaces.
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Cancel", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun RestartRequiredBanner(startInset: Dp) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossTheme.colors.panel,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bannerPad(startInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Installing",
                tint = BossTheme.colors.signalText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Installing update... Please wait.",
                color = BossTheme.colors.textPrimary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    startInset: Dp,
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossTheme.colors.panel,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bannerPad(startInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = BossTheme.colors.alert,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Update error: $message",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 12.sp,
                    // Two lines, because installer errors now carry the actual
                    // reason rather than a fixed string. The OS-floor refusal ends
                    // with "Your current version of BOSS has been kept" — the part
                    // that stops it reading as a broken install — and at one line
                    // that reassurance is exactly what gets ellipsized away.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Row {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Retry", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Update settings section for the Settings window
 */
@Composable
fun UpdateSettingsSection(updateCoordinator: UpdateCoordinator = UpdateCoordinator.instance) {
    val updateState by updateCoordinator.updateState.collectAsState()
    val lastCheckTime by updateCoordinator.lastCheckTime.collectAsState()
    val currentVersion = updateCoordinator.currentVersion()
    val coroutineScope = rememberCoroutineScope()

    // Version selection state
    var showVersionDialog by remember { mutableStateOf(false) }
    var showDowngradeWarning by remember { mutableStateOf(false) }
    var selectedVersion by remember { mutableStateOf<VersionInfo?>(null) }

    val versionListManager = updateCoordinator.versionListManager
    val versions by versionListManager.versions.collectAsState()
    val isLoadingVersions by versionListManager.isLoading.collectAsState()
    val versionError by versionListManager.error.collectAsState()

    // Prefetch version list on Settings load to avoid 2-5 second delay on button click
    // Uses cache if available (1 hour expiry), so this is cheap on repeated visits
    LaunchedEffect(Unit) {
        versionListManager.fetchVersions()
    }

    Column {
        // Version Information
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    "Version Information",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Current Version: v$currentVersion",
                    fontSize = 14.sp,
                    color = BossTheme.colors.textPrimary,
                )

                lastCheckTime?.let { checkTime ->
                    Text(
                        "Last checked: ${formatTime(checkTime)}",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Update Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    "Update Settings",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Automatic Update Check Toggle
                var autoCheckEnabled by remember { mutableStateOf(UpdateSettings.autoCheckEnabled) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Automatic Update Checks",
                            fontSize = 14.sp,
                            color = BossTheme.colors.textPrimary,
                        )
                        Text(
                            "Check for updates every 6 hours",
                            fontSize = 12.sp,
                            color = BossTheme.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = autoCheckEnabled,
                        onCheckedChange = { enabled ->
                            autoCheckEnabled = enabled
                            UpdateSettings.autoCheckEnabled = enabled
                            coroutineScope.launch {
                                UpdateSettingsManager.saveSettings()
                                // Apply change immediately, through the owner of the
                                // periodic loop rather than the singleton.
                                updateCoordinator.setPeriodicChecksEnabled(enabled)
                            }
                        },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = BossTheme.colors.signal,
                                checkedTrackColor = BossTheme.colors.signal.copy(alpha = 0.5f),
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Include Pre-release Versions Toggle
                var includePreReleases by remember { mutableStateOf(UpdateSettings.includePreReleases) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Include Pre-release Versions",
                            fontSize = 14.sp,
                            color = BossTheme.colors.textPrimary,
                        )
                        Text(
                            "Receive alpha, beta, and RC updates",
                            fontSize = 12.sp,
                            color = BossTheme.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = includePreReleases,
                        onCheckedChange = { enabled ->
                            includePreReleases = enabled
                            UpdateSettings.includePreReleases = enabled
                            coroutineScope.launch {
                                UpdateSettingsManager.saveSettings()
                            }
                        },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = BossTheme.colors.signal,
                                checkedTrackColor = BossTheme.colors.signal.copy(alpha = 0.5f),
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Update action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Check for Updates Button
                    TextButton(
                        onClick = {
                            // Manual check: bypass per-version dismissal. On the
                            // updater's scope, so closing Settings can't cancel it.
                            updateCoordinator.checkForUpdatesInBackground(force = true)
                        },
                        enabled = updateState !is UpdateState.CheckingForUpdates,
                        colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
                    ) {
                        if (updateState is UpdateState.CheckingForUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossTheme.colors.signal,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            if (updateState is UpdateState.CheckingForUpdates) "Checking..." else "Check for Updates",
                            fontSize = 13.sp,
                        )
                    }

                    // Select Version Button
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                versionListManager.fetchVersions()
                                showVersionDialog = true
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
                    ) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Select Version", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Update Status
                when (val currentState = updateState) {
                    is UpdateState.UpToDate -> {
                        Text(
                            "You're running the latest version",
                            color = BossTheme.colors.ok,
                            fontSize = 14.sp,
                        )
                    }

                    is UpdateState.UpdateAvailable -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Update available: v${currentState.updateInfo.latestVersion}",
                                color = BossTheme.colors.warn,
                                fontSize = 14.sp,
                            )
                            TextButton(
                                onClick = {
                                    // Manager-owned scope: survives the settings window closing
                                    updateCoordinator.downloadUpdateInBackground(currentState.updateInfo)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.ok),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download Update", fontSize = 13.sp)
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        Column {
                            Text(
                                "Downloading update...",
                                color = BossTheme.colors.signalText,
                                fontSize = 14.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = currentState.progress,
                                modifier = Modifier.fillMaxWidth(),
                                color = BossTheme.colors.signal,
                            )
                            Text(
                                "${(currentState.progress * 100).toInt()}%",
                                color = BossTheme.colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }

                    is UpdateState.ReadyToInstall -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Update downloaded successfully",
                                color = BossTheme.colors.ok,
                                fontSize = 14.sp,
                            )
                            TextButton(
                                onClick = {
                                    // Manager-owned scope: an install must not be
                                    // cancelled by the Settings window closing.
                                    updateCoordinator.installUpdateInBackground(currentState.downloadPath)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.warn),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Install Now", fontSize = 13.sp)
                            }
                        }
                    }

                    is UpdateState.Installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossTheme.colors.warn,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Installing update...",
                                color = BossTheme.colors.warn,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    is UpdateState.RestartRequired -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Update installed! Restart required.",
                                color = BossTheme.colors.ok,
                                fontSize = 14.sp,
                            )
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        restartApplication()
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restart Application", fontSize = 13.sp)
                            }
                        }
                    }

                    is UpdateState.Error -> {
                        Text(
                            currentState.message,
                            color = BossTheme.colors.alert,
                            fontSize = 14.sp,
                        )
                    }

                    else -> { /* Show nothing for other states */ }
                }
            }
        }

        // Version selection dialog
        if (showVersionDialog) {
            VersionSelectionDialog(
                currentVersion = currentVersion,
                versions = versions,
                isLoading = isLoadingVersions,
                error = versionError,
                onVersionSelected = { versionInfo ->
                    selectedVersion = versionInfo
                    showVersionDialog = false

                    // Check if downgrade
                    // Note: Version comparison treats stable > prerelease for same version numbers
                    // (e.g., 8.11.4 > 8.11.4-beta), so going from beta to stable is treated
                    // as an upgrade and won't show the downgrade warning. This is intentional.
                    if (versionInfo.version < currentVersion) {
                        showDowngradeWarning = true
                    } else {
                        // Direct download for upgrades or same version
                        updateCoordinator.downloadSpecificVersionInBackground(versionInfo)
                    }
                },
                onDismiss = { showVersionDialog = false },
            )
        }

        // Downgrade warning dialog
        if (showDowngradeWarning && selectedVersion != null) {
            DowngradeWarningDialog(
                currentVersion = currentVersion,
                targetVersion = selectedVersion!!.version,
                onConfirm = {
                    updateCoordinator.downloadSpecificVersionInBackground(selectedVersion!!)
                    showDowngradeWarning = false
                    selectedVersion = null
                },
                onDismiss = {
                    showDowngradeWarning = false
                    selectedVersion = null
                },
            )
        }
    }
}

/**
 * Format a timestamp as a human-readable "time ago" string
 *
 * Calculates the duration between now and the given instant, then formats it
 * as a relative time string (e.g., "Just now", "2 hours ago", "3 days ago")
 *
 * @param instant The point in time to format
 * @return Human-readable relative time string
 */
private fun formatTime(instant: kotlin.time.Instant): String {
    val now = Clock.System.now()
    val duration = now - instant
    val days = duration.inWholeDays
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes

    return when {
        days == 0L && hours == 0L && minutes < 5 -> "Just now"
        days == 0L && hours == 0L -> "$minutes minutes ago"
        days == 0L && hours == 1L -> "1 hour ago"
        days == 0L -> "$hours hours ago"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} ${if (days / 7 == 1L) "week" else "weeks"} ago"
        days < 365 -> "${days / 30} ${if (days / 30 == 1L) "month" else "months"} ago"
        else -> "${days / 365} ${if (days / 365 == 1L) "year" else "years"} ago"
    }
}

// Platform-specific restart function
expect fun restartApplication()

/** A banner's own padding, both axes. */
private val PAD_X = 12.dp
private val PAD_Y = 6.dp

/**
 * A banner's own padding, with room at the start for the macOS traffic lights.
 *
 * One helper rather than the same padding line in five variants: the inset has to reach every one
 * of them, and a banner that forgot it would put the lights over its text only in whichever state
 * that banner represents - an update error, say, which is the hardest of the five to reproduce.
 */
private fun Modifier.bannerPad(inset: Dp) =
    // An inset means this banner is carrying the lights, and carrying them means being at least
    // as tall as they are. Its natural height happens to clear 28dp today, which is not the same
    // as being guaranteed to: a shorter string, a smaller icon or a denser theme would spill them
    // onto the bar below, where nothing is reserving space any more.
    then(if (inset > 0.dp) Modifier.heightIn(min = TRAFFIC_LIGHT_HEIGHT) else Modifier)
        .padding(start = PAD_X + inset, end = PAD_X, top = PAD_Y, bottom = PAD_Y)
