@file:Suppress("MatchingDeclarationName")

package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.config.ChromiumAutoDownloader
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
    val coroutineScope = rememberCoroutineScope()

    var installedVersion by remember { mutableStateOf<String?>(null) }
    val pendingStagedVersion by produceState<String?>(initialValue = null) {
        value =
            withContext(Dispatchers.IO) {
                val dir = ChromiumAutoDownloader.getPendingChromiumDir()
                if (dir.toFile().exists()) ChromiumAutoDownloader.installedVersionAt(dir) else null
            }
    }
    val defaultVersion = BrowserEngineSettingsManager.effectiveVersion
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<ChromiumAutoDownloader.DownloadProgress?>(null) }
    var outcome by remember { mutableStateOf<StagedInstallOutcome?>(null) }
    var confirmingRestart by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        installedVersion = ChromiumAutoDownloader.installedVersion()
    }

    Column {
        SettingsSection(title = "Embedded Browser Engine") {
            SettingsInfoRow(
                label = "Installed version",
                value = installedVersion ?: "Not installed",
            )

            SettingsInfoRow(
                label = "Target version",
                value = defaultVersion,
                description =
                    """The engine version BOSS will use. Can be overridden for testing
                    |via the boss.browser.engine.version system property.
                    """.trimMargin(),
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
                    label = "Download and stage the target version",
                    buttonText = if (defaultVersion == installedVersion) "Reinstall" else "Install",
                    onClick = {
                        outcome = null
                        confirmingRestart = false
                        installProgress = ChromiumAutoDownloader.DownloadProgress(0, 0)
                        installing = true
                        coroutineScope.launch {
                            val result =
                                ChromiumAutoDownloader.downloadChromium(
                                    version = defaultVersion,
                                    staged = true,
                                ) { progress ->
                                    if (!progress.isComplete && progress.error == null) {
                                        installProgress = progress
                                    }
                                }
                            installProgress = null
                            installing = false
                            outcome = stagedInstallOutcome(defaultVersion, defaultVersion, result)
                        }
                    },
                    description =
                        "The engine is staged now and swapped in on the next launch. " +
                            "Reinstalling repairs a corrupted engine directory.",
                    enabled = !installing,
                )
            }

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
                    SettingsButtonRow(
                        label = "Staged - restart to apply",
                        buttonText = "Restart BOSS",
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

        ChromiumFlagsSections()
    }
}
