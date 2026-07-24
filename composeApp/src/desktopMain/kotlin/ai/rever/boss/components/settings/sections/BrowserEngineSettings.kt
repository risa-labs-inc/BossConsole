package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.config.ChromiumReleaseSource
import ai.rever.boss.config.EngineVersionListing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.rever.boss.config.BrowserEngineSettings as BrowserEngineSettingsData

/** Wrapper distinguishing "not read yet" (null state) from "read, not installed" (null version). */
private data class InstalledVersion(
    val version: String?,
)

/**
 * Settings section for the embedded Chromium engine: shows the installed/default
 * versions and lets the user pick and install a specific published engine version
 * (Supabase primary, GitHub backup). Installs are staged and applied on restart,
 * because the running engine's files can't be replaced in place.
 *
 * The dropdown selection is local UI state; the version pin is persisted only when
 * a staged install succeeds, so browsing the dropdown never changes what the next
 * launch downloads.
 */
@Composable
fun BrowserEngineSettings() {
    val settings by BrowserEngineSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val defaultVersion = ChromiumAutoDownloader.defaultVersion
    val defaultLabel = "Default ($defaultVersion)"

    // Read once off the UI thread; static for the lifetime of the window on
    // purpose — staged installs don't change the live engine until restart,
    // so re-reading it would not change. null = still loading.
    val installedVersionState =
        produceState<InstalledVersion?>(initialValue = null) {
            value =
                withContext(Dispatchers.IO) {
                    InstalledVersion(ChromiumAutoDownloader.installedVersion())
                }
        }
    val installedVersion = installedVersionState.value?.version

    // null = follow app default. Seeded from the persisted pin, then local-only
    // until an install succeeds.
    var selectedOverride by remember(settings.selectedVersion) {
        mutableStateOf(settings.selectedVersion)
    }
    var versionListing by remember { mutableStateOf<EngineVersionListing?>(null) }
    var versionsError by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableStateOf<ChromiumAutoDownloader.DownloadProgress?>(null) }
    var installStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            versionListing = ChromiumReleaseSource.availableVersions()
        } catch (e: Exception) {
            versionListing = EngineVersionListing(emptyList())
            versionsError = e.message ?: "Could not load version list"
        }
    }

    val selectedVersion = selectedOverride ?: defaultVersion
    val installing = installProgress != null

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Current Engine") {
            SettingsInfoRow(
                label = "Installed version",
                value =
                    when {
                        installedVersionState.value == null -> "…"
                        installedVersion == null -> "Not installed"
                        else -> installedVersion
                    },
                description = "BOSS-branded Chromium in ~/.boss/boss-chromium",
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                label = "App default version",
                value = defaultVersion,
                description = "The JxBrowser version this build of BOSS was made for",
            )
        }

        SettingsSection(
            title = "Engine Version",
            description =
                "The engine version must match the app's JxBrowser version " +
                    "($defaultVersion). Pinning a different version is intended for recovery " +
                    "and testing only — the browser may fail to start with a mismatched engine. " +
                    "Nothing changes until you click Install.",
        ) {
            // The current selection is always appended if the listing doesn't contain
            // it (still loading, listing failed, or the pinned version was delisted) —
            // the dropdown must never display a value missing from its options.
            val currentOverride = selectedOverride
            val versionOptions =
                buildList {
                    add(defaultLabel)
                    (versionListing?.versions ?: emptyList())
                        .filter { it != defaultVersion }
                        .forEach { add(it) }
                    if (currentOverride != null && currentOverride !in this) {
                        add(currentOverride)
                    }
                }

            SettingsDropdown(
                label = "Engine version",
                options = versionOptions,
                selectedOption = selectedOverride ?: defaultLabel,
                onOptionSelected = { selection ->
                    selectedOverride = if (selection == defaultLabel) null else selection
                },
                description =
                    when {
                        versionListing == null -> {
                            "Loading published versions…"
                        }

                        versionsError != null -> {
                            "Could not load published versions: $versionsError"
                        }

                        versionListing?.failedSources?.isNotEmpty() == true -> {
                            "Published engine versions — list may be incomplete " +
                                "(${versionListing?.failedSources?.joinToString()} unavailable)"
                        }

                        else -> {
                            "Published engine versions from Supabase and GitHub"
                        }
                    },
                enabled = !installing,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (installing) {
                val progress = installProgress
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceColor)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text =
                            when {
                                progress?.isExtracting == true -> {
                                    "Extracting engine…"
                                }

                                progress != null && progress.totalBytes > 0 -> {
                                    "Downloading engine… ${progress.downloadedMB}MB / ${progress.totalMB}MB"
                                }

                                else -> {
                                    "Connecting to download server…"
                                }
                            },
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = (progress?.progressFraction ?: 0f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = AccentColor,
                        backgroundColor = TextMuted.copy(alpha = 0.2f),
                    )
                }
            } else {
                SettingsButtonRow(
                    label = "Download and stage the selected version",
                    buttonText = if (selectedVersion == installedVersion) "Reinstall" else "Install",
                    onClick = {
                        installStatus = null
                        installProgress = ChromiumAutoDownloader.DownloadProgress(0, 0)
                        val versionToInstall = selectedVersion
                        val overrideToPersist = selectedOverride
                        coroutineScope.launch {
                            val result =
                                ChromiumAutoDownloader.downloadChromium(
                                    version = versionToInstall,
                                    staged = true,
                                ) { progress ->
                                    if (!progress.isComplete && progress.error == null) {
                                        installProgress = progress
                                    }
                                }
                            installProgress = null
                            installStatus =
                                result.fold(
                                    onSuccess = {
                                        // Persist the pin only now, so an abandoned dropdown
                                        // selection never changes what the next launch boots.
                                        BrowserEngineSettingsManager.updateSettings(
                                            BrowserEngineSettingsData(selectedVersion = overrideToPersist),
                                        )
                                        "Engine $versionToInstall downloaded — restart BOSS to apply."
                                    },
                                    onFailure = { e ->
                                        "Install failed: ${e.message ?: "unknown error"}"
                                    },
                                )
                        }
                    },
                    description =
                        "The engine is staged now and swapped in on the next launch. " +
                            "Reinstalling repairs a corrupted engine directory.",
                    enabled = !installing,
                )
            }

            installStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                SettingsInfoRow(
                    label = "Status",
                    value = "",
                    description = status,
                )
            }
        }
    }
}
