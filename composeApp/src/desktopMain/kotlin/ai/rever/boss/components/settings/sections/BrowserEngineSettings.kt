package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.dialogs.ConfirmationDialog
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
import ai.rever.boss.utils.ApplicationRestarter
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

/**
 * What Settings shows after a staged engine install.
 *
 * A sealed type rather than a message plus a flag: those were two `mutableStateOf`s
 * encoding one outcome, kept consistent only by convention, so "staged but failed"
 * was representable. Here it isn't.
 *
 * [Staged.appliesOnRestart] is the part that matters. `updateSettings` runs
 * `withoutUnusablePin()`, which drops any `selectedVersion` that isn't the bundled
 * version — so staging a *non-default* engine persists no pin, and the next launch
 * promotes it, finds it doesn't match `effectiveVersion`, and re-downloads the
 * default. Offering "Restart BOSS" there would cost the user their session and a
 * several-hundred-MB download to end up exactly where they started.
 */
internal sealed interface StagedInstallOutcome {
    data class Staged(
        val version: String,
        val appliesOnRestart: Boolean,
    ) : StagedInstallOutcome

    data class Failed(
        val message: String,
    ) : StagedInstallOutcome
}

/** The message for an outcome. Derived, never stored alongside it. */
internal fun StagedInstallOutcome.message(defaultVersion: String): String =
    when (this) {
        is StagedInstallOutcome.Failed -> {
            message
        }

        is StagedInstallOutcome.Staged -> {
            if (appliesOnRestart) {
                "Engine $version is staged. It is not in use until BOSS restarts."
            } else {
                "Engine $version is staged, but this build requires $defaultVersion - " +
                    "it will be replaced on the next launch."
            }
        }
    }

/** Whether this outcome should offer the restart that completes it. */
internal fun StagedInstallOutcome.offersRestart(): Boolean = this is StagedInstallOutcome.Staged && appliesOnRestart

internal fun stagedInstallOutcome(
    version: String,
    defaultVersion: String,
    result: Result<*>,
): StagedInstallOutcome =
    result.fold(
        onSuccess = {
            StagedInstallOutcome.Staged(
                version = version,
                // Only a default-version stage survives to be used.
                appliesOnRestart = version == defaultVersion,
            )
        },
        onFailure = { e ->
            StagedInstallOutcome.Failed(e.message ?: "unknown error")
        },
    )

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
    // One state, so "staged but failed" is unrepresentable.
    var outcome by remember { mutableStateOf<StagedInstallOutcome?>(null) }
    var confirmingRestart by remember { mutableStateOf(false) }

    // Seeded from disk, not just from the install that happened in this composition:
    // both flags used to be plain `remember`, so closing and reopening Settings lost
    // the pending state while boss-chromium.pending still sat on disk — leaving no
    // indication a restart was owed and inviting a second install.
    val pendingStagedVersion =
        produceState<String?>(initialValue = null) {
            value =
                withContext(Dispatchers.IO) {
                    val dir = ChromiumAutoDownloader.getPendingChromiumDir()
                    if (dir.toFile().exists()) ChromiumAutoDownloader.installedVersionAt(dir) else null
                }
        }.value

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
                    "and testing only - the browser may fail to start with a mismatched engine. " +
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
                            "Published engine versions - list may be incomplete " +
                                "(${versionListing?.failedSources?.joinToString()} unavailable)"
                        }

                        else -> {
                            "Published engine versions with catalog checksums for this platform"
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
                        outcome = null
                        confirmingRestart = false
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
                            if (result.isSuccess) {
                                // Persist the pin only now, so an abandoned dropdown
                                // selection never changes what the next launch boots.
                                BrowserEngineSettingsManager.updateSettings(
                                    BrowserEngineSettingsData(selectedVersion = overrideToPersist),
                                )
                            }
                            outcome = stagedInstallOutcome(versionToInstall, defaultVersion, result)
                        }
                    },
                    description =
                        "The engine is staged now and swapped in on the next launch. " +
                            "Reinstalling repairs a corrupted engine directory.",
                    enabled = !installing,
                )
            }

            // Either an outcome from this session, or a stage left pending by an
            // earlier one that this Settings view never saw.
            val effectiveOutcome =
                outcome
                    ?: pendingStagedVersion?.let { staged ->
                        StagedInstallOutcome.Staged(
                            version = staged,
                            appliesOnRestart = staged == defaultVersion,
                        )
                    }

            effectiveOutcome?.let { current ->
                Spacer(modifier = Modifier.height(8.dp))
                if (current.offersRestart()) {
                    // An action, not a note: a staged engine sits in
                    // boss-chromium.pending and does nothing until
                    // promotePendingInstall swaps it in at startup, so a missed note
                    // reads like the install failed and invites a second one.
                    SettingsButtonRow(
                        label = "Staged - restart to apply",
                        buttonText = "Restart BOSS",
                        // Confirmed, not immediate. This quits the app ~500ms later
                        // with no undo, and the repo already handles that this way
                        // (see FluckBrowserSettings' Restart Required dialog); a
                        // single unconfirmed click sitting directly under Install is
                        // too easy to hit by accident.
                        onClick = { confirmingRestart = true },
                        isDestructive = true,
                        description =
                            current.message(defaultVersion) +
                                " BOSS reopens with your tabs restored; running terminal processes end.",
                    )
                } else {
                    SettingsInfoRow(
                        label = "Status",
                        value = "",
                        description = current.message(defaultVersion),
                    )
                }
            }

            if (confirmingRestart) {
                ConfirmationDialog(
                    title = "Restart Required",
                    message =
                        "BOSS will close and reopen to apply the staged browser engine. " +
                            "Your tabs are restored; running terminal processes end.",
                    confirmText = "Restart Now",
                    onConfirm = {
                        confirmingRestart = false
                        ApplicationRestarter.scheduleRestart(delayMillis = 500)
                    },
                    onDismiss = { confirmingRestart = false },
                )
            }
        }

        // The engine's OPTIONS, below its version. Same screen because both answer "what browser
        // am I running", separate composable because they share no state — see ChromiumFlagsSections.
        ChromiumFlagsSections()
    }
}
