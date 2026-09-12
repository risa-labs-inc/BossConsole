package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Keeps [SettingsSearchIndex] honest against the sections it claims to describe, in both directions.
 *
 * A hand-declared index is only as good as the thing that notices when it stops matching, and the
 * two failures are not symmetrical:
 *
 *  - A **missing** entry costs discoverability. The setting is there, search cannot find it.
 *  - A **stale** entry is worse, and is what a rename produces. The result still appears, still
 *    navigates, and then highlights nothing - the feature visibly lying about having found
 *    something. Nothing in a rename diff looks wrong, which is exactly why this is a test.
 *
 * A convention test in the style of `DialogCardWidthConventionTest`, and heuristic for the same
 * reason: it cannot parse Kotlin. Its one real limitation is worth stating plainly - it pairs a
 * label with the last `SettingsSection(title = ...)` seen **above it in the same file**, which is
 * textual order, not call order. That happens to be right for all 12 labels currently emitted from
 * a helper function rather than from the function holding the group (the `PlatformPerformanceRows`,
 * `SandboxRow`, `DevToolsPortRows` and `RepairModelFields` rows), each verified by hand against its
 * call site. If someone moves such a helper under a different group, this test keeps passing and
 * `SettingsSearchHighlightTest` is what catches it.
 */
class SettingsSearchIndexDriftTest {
    /**
     * Labels that are read-only status rows rather than settings, and deliberately unindexed.
     *
     * `SettingsInfoRow` serves both jobs in this tree, so `label =` on its own does not mean "this
     * is a setting". Each of these appears only in a transient state - mid-download, pending a
     * restart, or reporting what the running engine resolved to - and offering them as search
     * results would send someone to a row that answers nothing and cannot be changed.
     */
    private val notSettings =
        setOf(
            // BrowserEngineSettings: staging progress, not controls.
            "Staged - restart to apply",
            "Status",
            "Installed version",
            "Target version",
        )

    /**
     * Files under the scanned roots that hold no settings at all.
     *
     * Both are modals reached *from* a settings page rather than part of one, and their `label =`
     * calls are filter chips over test results. Skipped whole rather than listed label by label,
     * because every one of those labels is templated (`"All (${stats.total})"`).
     */
    private val skippedFiles =
        setOf(
            "ShortcutTestDialog.kt",
            "KeyCaptureDialog.kt",
        )

    /** The one templated label, which the scanner sees as a literal it cannot evaluate. */
    private val templatedLiteral = "Show \${bar.displayName()}"

    /**
     * What [templatedLiteral] expands to over `ChromeBar.entries`.
     *
     * Pinned separately by [SettingsSearchIndexShapeTest], which recomputes the family from the enum
     * rather than restating it, so adding a fifth bar fails there rather than silently here.
     */
    private val templatedExpansions =
        setOf(
            "Show Top Bar",
            "Show Bottom Bar",
            "Show Left Strip",
            "Show Right Strip",
        )

    @Test
    fun `every settings control in the sources is in the search index`() {
        val indexed = indexedPairs()
        val missing =
            scanSources()
                .filter { it.label !in notSettings && it.label != templatedLiteral }
                .filter { Pair(it.group, it.label) !in indexed }
                .sortedBy { "${it.file}:${it.line}" }

        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "These settings are not in SettingsSearchIndex, so Settings search cannot " +
                            "find them. Add an entry under the right section and group in " +
                            "SettingsSearchIndex.kt - or, if it is a read-only status row rather " +
                            "than a setting, add it to `notSettings` in this test with a reason.",
                    )
                    missing.forEach {
                        appendLine("  ${it.file}:${it.line}  ${it.label} (group: ${it.group ?: "none"})")
                    }
                },
            )
        }
    }

    @Test
    fun `every indexed entry still exists in the sources`() {
        val scanned = scanSources().map { Pair(it.group, it.label) }.toSet()
        val stale =
            SettingsSearchIndex.builtIn
                .filter { it.section in scannedSections }
                .filter { !it.curated }
                .filter { it.label !in templatedExpansions }
                .filter { Pair(it.group, it.label) !in scanned }
                .map { "${it.section?.name}: ${it.label} (group: ${it.group ?: "none"})" }
                .sorted()

        if (stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "These SettingsSearchIndex entries name a setting that no longer exists in " +
                            "the sources. A result for one of them navigates and then highlights " +
                            "nothing, which reads as the search being broken. Update or remove them.",
                    )
                    stale.forEach { appendLine("  $it") }
                },
            )
        }
    }

    /** The sections whose bodies live under `settings/sections/`, so this scan can see them. */
    private val scannedSections =
        SettingsSection.entries.toSet() -
            setOf(
                // Rendered by BossTerm, BossEditor and editor-tab. (AI providers used to be here
                // too, served by secret-manager. That section now lives in the plugin's own panel
                // and the host has no section for it - only the curated signpost that opens the
                // panel, which carries no section and so never reaches this scan.)
                SettingsSection.TERMINAL,
                SettingsSection.BOSS_EDITOR,
                SettingsSection.LANGUAGE_SERVERS,
                // commonMain UpdateUI.kt, built from raw Text rather than the shared controls.
                SettingsSection.UPDATES,
            )

    private fun indexedPairs(): Set<Pair<String?, String>> =
        SettingsSearchIndex.builtIn
            .filter { it.section in scannedSections }
            .map { Pair(it.group, it.label) }
            .toSet()

    private data class Scanned(
        val file: String,
        val line: Int,
        val group: String?,
        val label: String,
    )

    /**
     * Pulls `label = "..."` and `SettingsSection(title = "...")` out of the section sources.
     *
     * Scans the lines *after* an opening `SettingsSection(` as well as the line itself: 10 of the 51
     * group headers wrap their `title =` onto the next line, and a same-line-only regex would pass
     * while quietly missing a fifth of them. `label = {` (the one Material slot, in
     * `ProfileManagementSection`) never matches, because the pattern requires a quote.
     */
    private fun scanSources(): List<Scanned> {
        val root = repoRoot()
        val files =
            kotlinSourcesUnder(
                root,
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/settings/sections",
                // Shortcuts lives here, in commonMain. Leaving it out is what let the tab-switching
                // controls go unindexed with nothing reporting it.
                "composeApp/src/commonMain/kotlin/ai/rever/boss/components/settings/keymap",
            ).filter { it.name !in skippedFiles }
        check(files.isNotEmpty()) { "no settings section sources found under $root" }

        return files.flatMap { file -> scanFile(file, root) }
    }

    private fun scanFile(
        file: File,
        root: File,
    ): List<Scanned> {
        val relative = file.relativeTo(root).path
        val lines = file.readLines()
        val found = mutableListOf<Scanned>()
        var group: String? = null

        lines.forEachIndexed { index, line ->
            val inlineGroup = INLINE_GROUP.find(line)
            if (inlineGroup != null) {
                group = inlineGroup.groupValues[1]
                return@forEachIndexed
            }
            if (OPEN_GROUP.containsMatchIn(line)) {
                for (ahead in index + 1 until minOf(index + GROUP_LOOKAHEAD, lines.size)) {
                    val title = TITLE.find(lines[ahead])
                    if (title != null) {
                        group = title.groupValues[1]
                        break
                    }
                }
                return@forEachIndexed
            }
            val label = LABEL.find(line) ?: return@forEachIndexed
            found += Scanned(relative, index + 1, group, label.groupValues[1])
        }

        // Group headers are searchable in their own right, and carry no enclosing group.
        val groups =
            lines.mapIndexedNotNull { index, line ->
                INLINE_GROUP.find(line)?.let { Scanned(relative, index + 1, null, it.groupValues[1]) }
                    ?: OPEN_GROUP.find(line)?.let {
                        (index + 1 until minOf(index + GROUP_LOOKAHEAD, lines.size))
                            .firstNotNullOfOrNull { ahead -> TITLE.find(lines[ahead]) }
                            ?.let { title -> Scanned(relative, index + 1, null, title.groupValues[1]) }
                    }
            }

        return found + groups
    }

    private companion object {
        /** How far past `SettingsSection(` to look for a wrapped `title =`. */
        const val GROUP_LOOKAHEAD = 4

        val INLINE_GROUP = Regex("""SettingsSection\(\s*title\s*=\s*"((?:[^"\\]|\\.)*)"""")
        val OPEN_GROUP = Regex("""SettingsSection\(\s*$""")
        val TITLE = Regex("""title\s*=\s*"((?:[^"\\]|\\.)*)"""")
        val LABEL = Regex("""^\s*label\s*=\s*"((?:[^"\\]|\\.)*)"""")
    }
}
