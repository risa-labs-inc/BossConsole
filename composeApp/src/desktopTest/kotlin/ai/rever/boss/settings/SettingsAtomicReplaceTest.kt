package ai.rever.boss.settings

import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.config.ResourceModeSettings
import ai.rever.boss.filetypes.DefaultAppsSettingsManager
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.html.HtmlFileSettingsStore
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStateManager
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.browser.BrowserSettingsManager
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.updater.UpdateSettingsFiles
import ai.rever.boss.updater.UpdateSettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each settings and state writer REPLACES its file on save rather than truncating and rewriting it:
 * one case per writer #1022 converted to `atomicWriteText` (#1011, #1659). A writer that falls back
 * to `writeText` fails its own case.
 *
 * `writeText` truncates on open, so between the open and the last byte the file is empty or partial.
 * A load that reads in that window fails to decode, keeps defaults in memory, and the next save then
 * writes those defaults over the user's real settings; a crash in that window leaves the file
 * truncated for good. #1032's mutex orders writers but cannot close either window, because the
 * reader does not take it and a crash does not respect it.
 *
 * Racing a reader against a writer would make this timing-dependent. File IDENTITY does not: a file
 * rewritten in place keeps its inode across a save, and one replaced by an atomic rename gets a new
 * one. So this asserts the identity changes, which is true exactly when the save cannot expose a
 * truncated file. Windows exposes no identity through `fileKey()`, so the check is skipped there
 * and made on the POSIX legs, where the property is the same.
 *
 * Two things make that comparison exact rather than likely, and each was a real failure:
 *
 * - **The old inode is pinned with a hard link before the measured save.** An inode NUMBER is only
 *   unique among live files. ext4 hands a just-freed number to the next file created in the
 *   directory, so if anything replaces the file between the two reads, the measured save's temp
 *   file can be given the first file's number back and a correct atomic replace compares equal.
 *   The runner and terminal-link managers do exactly that: they load on a background thread and,
 *   finding no file, write a default one under the save lock. That failed ubuntu CI once while
 *   macOS, whose APFS does not recycle numbers like this, passed. While the link holds the old
 *   inode, nothing can be given its number.
 * - **The file is created before the manager is first touched, if it is absent.** Those two
 *   background loads then read instead of writing, so no replace of the manager's own can land
 *   between the reads. Without this a save reverted to `writeText` could still pass, because
 *   the background write would change the identity for it.
 *
 * **The measured save is assumed to write unconditionally**, as all thirteen writers do today: all
 * but the installed-plugins case save the bytes already on disk. A writer that learns to skip a
 * write whose content has not changed would fail here as "not written at all"; give its case a real
 * change to save, as the installed-plugins case does, rather than weakening the check.
 */
class SettingsAtomicReplaceTest {
    private fun identity(file: File): Any? {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return attributes.fileKey()
    }

    private fun assertReplacedOnSave(
        fileName: String,
        save: suspend () -> Unit,
    ) = assertReplacedOnSave(BossDirectories.resolve(fileName), save)

    private fun assertReplacedOnSave(
        file: File,
        save: suspend () -> Unit,
    ) = runBlocking {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("{}")
        }
        save()
        assertTrue(file.exists(), "${file.name} was never written; the managers log a failed save and swallow it")
        val exposesIdentity = identity(file) != null
        Assumptions.assumeTrue(exposesIdentity, "this filesystem exposes no file identity; checked on POSIX instead")

        val pin = File(file.parentFile, "${file.name}.pin")
        pin.delete()
        Files.createLink(pin.toPath(), file.toPath())
        try {
            val before = identity(pin)
            val modifiedBefore = Files.getLastModifiedTime(pin.toPath())

            save()

            if (identity(file) == before) {
                // Tell the two ways to keep an identity apart: these writers log a failed save and
                // swallow it, so "not written at all" must not send a reader hunting for a writeText.
                val rewritten = Files.getLastModifiedTime(file.toPath()) != modifiedBefore
                fail(
                    if (rewritten) {
                        "${file.name} was truncated and rewritten in place (identity $before kept, modified time " +
                            "changed); it must be replaced by an atomic rename"
                    } else {
                        "${file.name} was not written at all by the measured save (identity $before and modified " +
                            "time unchanged): look for a logged, swallowed save failure, or a write the writer skipped"
                    },
                )
            }
        } finally {
            pin.delete()
        }
    }

    @Test
    fun `performance settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("performance-settings.json") { PerformanceSettingsManager.saveSettings() }

    @Test
    fun `focus-mode settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("focus-mode-settings.json") { FocusModeSettingsManager.saveSettings() }

    @Test
    fun `runner settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("runner-settings.json") { RunnerSettingsManager.saveSettings() }

    @Test
    fun `terminal-link settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("terminal-link-settings.json") { TerminalLinkSettingsManager.saveSettings() }

    /**
     * Not one of the four #1659 names, but the same hazard: its load reads outside the write lock,
     * and what a torn read resets is the user's own choices (auto-check, the dismissed version).
     * Resolved through [UpdateSettingsFiles.settingsFile], the manager's own path, since other tests
     * point that at a file of their own.
     */
    @Test
    fun `update settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave(UpdateSettingsFiles.settingsFile) { UpdateSettingsManager.saveSettings() }

    // -----------------------------------------------------------------
    // The rest of #1022's writers. Each saves through the same API the app uses.
    // -----------------------------------------------------------------

    @Test
    fun `sidebar visibility settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("sidebar-visibility-settings.json") {
            SidebarVisibilitySettingsManager.updateSettings(SidebarVisibilitySettingsManager.currentSettings.value)
        }

    @Test
    fun `browser engine settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("browser-engine-settings.json") {
            BrowserEngineSettingsManager.updateSettings(BrowserEngineSettingsManager.currentSettings.value)
        }

    @Test
    fun `resource mode settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("resource-mode.json") { ResourceModeSettings.update { it } }

    /** Resolved through the manager's own path, which other tests point at a file of their own. */
    @Test
    fun `default apps settings are replaced on save, never rewritten in place`() {
        // The one case that changes shared state rather than re-saving it, so it puts both back:
        // markPromptShown sets the process-global flag, and the test-home file records it.
        val file = DefaultAppsSettingsManager.settingsFile
        val original = file.takeIf { it.exists() }?.readBytes()
        try {
            assertReplacedOnSave(file) { DefaultAppsSettingsManager.markPromptShown() }
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
            DefaultAppsSettingsManager.resetForTest()
        }
    }

    /** Resolved through the manager's own path, which other tests point at a file of their own. */
    @Test
    fun `browser settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave(BrowserSettingsManager.settingsFile) { BrowserSettingsManager.saveSettings() }

    @Test
    fun `html file settings are replaced on save, never rewritten in place`() =
        withTempDir { dir ->
            val file = File(dir, "html-file-settings.json")
            val store = HtmlFileSettingsStore(file) { throw it }
            assertReplacedOnSave(file) { store.update() }
        }

    @Test
    fun `plugin states are replaced on save, never rewritten in place`() =
        withTempDir { dir ->
            val manager = PluginStateManager(configDir = dir)
            assertReplacedOnSave(File(dir, "plugin-states.json")) { manager.saveStates(emptyMap()) }
        }

    /**
     * installed.json is shared with other test classes, so this adds an entry of its own, saves by
     * changing it, and removes it again rather than clearing the file.
     */
    @Test
    fun `installed plugins are replaced on save, never rewritten in place`() {
        val id = "test.settings-atomic-replace"
        PluginPersistence.addInstalledPlugin(id, jarPath = "/nonexistent/$id.jar")
        try {
            var saves = 0
            assertReplacedOnSave(File(PluginStoreSetup.getPluginDir(), "installed.json")) {
                PluginPersistence.updateSourceUrl(id, "https://example.invalid/${++saves}")
            }
        } finally {
            PluginPersistence.removeInstalledPlugin(id)
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("settings-atomic-replace-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
