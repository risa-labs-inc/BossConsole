package ai.rever.boss.cli

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Diffs two saved Space files.
 *
 * A Space is the saved layout the user drops into: panels, tabs, split tree,
 * breadcrumb config. Two `boss workspace <a>` calls each load one; this one
 * reads both and answers "what's actually different" without dragging BOSS
 * itself up.
 *
 * The diff is structural, not byte-level. A tab whose URL is the same in
 * both is "kept" even if its title changed; a tab whose URL changed is
 * "modified". Pinning changes surface in their own bucket because moving
 * a tab from one panel to another is a state change the operator usually
 * wants to know about, not a no-op.
 *
 * Usage:
 *   boss workspace-diff <file-a.json> <file-b.json> [--json]
 *
 * Exit codes: 0 always (the diff is informational). 2 if either file
 * cannot be read or parsed.
 */
class BossWorkspaceDiffCommand : CliktCommand(name = "workspace-diff") {
    override fun help(context: Context) = "Diffs two saved Space files"

    private val left by argument(help = "Path to the first Space file (typically the older one)")
    private val right by argument(help = "Path to the second Space file (typically the newer one)")
    val json by option("--json", help = "Output the diff as JSON").flag(default = false)

    override fun run() {
        val leftFile = File(left).absoluteFile
        val rightFile = File(right).absoluteFile
        if (!leftFile.isFile) {
            echo("Error: not a file: $left", err = true)
            throw ProgramResult(2)
        }
        if (!rightFile.isFile) {
            echo("Error: not a file: $right", err = true)
            throw ProgramResult(2)
        }
        val leftSpace = readOrFail(leftFile, "left")
        val rightSpace = readOrFail(rightFile, "right")
        val diff = WorkspaceDiffer.diff(leftSpace, rightSpace)
        renderAndExit(diff, json)
    }

    private fun readOrFail(
        file: File,
        label: String,
    ): LayoutWorkspace =
        try {
            WorkspaceSerializer.deserialize(file.readText(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            echo("Error: failed to parse $label space file: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        } catch (e: kotlinx.serialization.SerializationException) {
            echo("Error: failed to parse $label space file: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        } catch (e: IllegalArgumentException) {
            echo("Error: failed to parse $label space file: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        }

    private fun renderAndExit(
        diff: WorkspaceDiff,
        json: Boolean,
    ) {
        if (json) {
            echo(WorkspaceDiffJson.encode(diff))
        } else {
            renderHuman(diff)
        }
    }

    private fun renderHuman(diff: WorkspaceDiff) {
        val left = diff.leftId.ifEmpty { "<no-id>" }
        val right = diff.rightId.ifEmpty { "<no-id>" }
        echo("Space diff: $left → $right")
        diff.nameDelta?.let { echo("  name: $it") }
        diff.descriptionDelta?.let { echo("  description: $it") }
        diff.projectPathDelta?.let { echo("  project path: $it") }
        diff.breadcrumbDelta?.let { echo("  breadcrumb config changed: $it") }
        diff.layoutShapeDelta?.let { echo("  layout shape: $it") }
        renderPanelBuckets(diff)
        renderTabBuckets(diff)
        renderPinningChanges(diff)
        if (!diff.hasChanges()) echo("  no changes detected")
    }

    private fun renderPanelBuckets(diff: WorkspaceDiff) {
        if (diff.panelsAdded.isNotEmpty()) {
            echo("  panels added (${diff.panelsAdded.size}):")
            for (p in diff.panelsAdded) echo("    + $p")
        }
        if (diff.panelsRemoved.isNotEmpty()) {
            echo("  panels removed (${diff.panelsRemoved.size}):")
            for (p in diff.panelsRemoved) echo("    - $p")
        }
        if (diff.panelsKept.isNotEmpty()) {
            echo("  panels kept (${diff.panelsKept.size}):")
            for (p in diff.panelsKept) echo("    = $p")
        }
    }

    private fun renderTabBuckets(diff: WorkspaceDiff) {
        if (diff.tabsAdded.isNotEmpty()) {
            echo("  tabs added (${diff.tabsAdded.size}):")
            for (t in diff.tabsAdded) echo("    + $t")
        }
        if (diff.tabsRemoved.isNotEmpty()) {
            echo("  tabs removed (${diff.tabsRemoved.size}):")
            for (t in diff.tabsRemoved) echo("    - $t")
        }
        if (diff.tabsModified.isNotEmpty()) {
            echo("  tabs modified (${diff.tabsModified.size}):")
            for (t in diff.tabsModified) echo("    ~ $t")
        }
    }

    private fun renderPinningChanges(diff: WorkspaceDiff) {
        if (diff.pinningChanges.isNotEmpty()) {
            echo("  pinning changes:")
            for (p in diff.pinningChanges) echo("    $p")
        }
    }
}

data class WorkspaceDiff(
    val leftId: String,
    val rightId: String,
    val nameDelta: String?,
    val descriptionDelta: String?,
    val projectPathDelta: String?,
    val breadcrumbDelta: String?,
    val layoutShapeDelta: String?,
    val panelsAdded: List<String>,
    val panelsRemoved: List<String>,
    val panelsKept: List<String>,
    val tabsAdded: List<String>,
    val tabsRemoved: List<String>,
    val tabsModified: List<String>,
    val pinningChanges: List<String>,
) {
    fun hasChanges(): Boolean =
        nameDelta != null ||
            descriptionDelta != null ||
            projectPathDelta != null ||
            breadcrumbDelta != null ||
            layoutShapeDelta != null ||
            panelsAdded.isNotEmpty() ||
            panelsRemoved.isNotEmpty() ||
            tabsAdded.isNotEmpty() ||
            tabsRemoved.isNotEmpty() ||
            tabsModified.isNotEmpty() ||
            pinningChanges.isNotEmpty()
}

/**
 * Pure diff logic. Panels are matched by id; tabs are matched by an
 * "identity" tuple of (type, url-or-filePath-or-command), so a tab whose
 * URL changed is "modified" rather than "removed + added".
 */
object WorkspaceDiffer {
    fun diff(
        left: LayoutWorkspace,
        right: LayoutWorkspace,
    ): WorkspaceDiff {
        val leftById = extractPanels(left.layout).associateBy { it.id }
        val rightById = extractPanels(right.layout).associateBy { it.id }

        val added = (rightById.keys - leftById.keys).sorted()
        val removed = (leftById.keys - rightById.keys).sorted()
        val kept = (leftById.keys intersect rightById.keys).sorted()

        // Tabs identity, by panel: a tab is "the same" if its content (URL /
        // filePath / command) matches. Title and pinning live in their own
        // buckets so a URL-only rename shows up as a single line, not a
        // remove+add pair.
        val tabsAdded = mutableListOf<String>()
        val tabsRemoved = mutableListOf<String>()
        val tabsModified = mutableListOf<String>()
        val pinningChanges = mutableListOf<String>()
        for (panelId in kept) {
            collectPanelTabChanges(
                panelId = panelId,
                leftPanel = leftById.getValue(panelId),
                rightPanel = rightById.getValue(panelId),
                tabsAdded = tabsAdded,
                tabsRemoved = tabsRemoved,
                tabsModified = tabsModified,
                pinningChanges = pinningChanges,
            )
        }

        // The set of (panel id) is the flat structure; the SHAPE is the tree
        // ordering. Two Spaces with the same panels in different positions
        // are different shapes.
        val leftShape = shapeOf(left.layout)
        val rightShape = shapeOf(right.layout)
        val shapeDelta =
            if (leftShape != rightShape) {
                "tree differs (${describeShape(leftShape)} → ${describeShape(rightShape)})"
            } else {
                null
            }

        return WorkspaceDiff(
            leftId = left.id,
            rightId = right.id,
            nameDelta = if (left.name != right.name) "${left.name} → ${right.name}" else null,
            descriptionDelta = if (left.description != right.description) "changed" else null,
            projectPathDelta = projectPathDelta(left, right),
            breadcrumbDelta = breadcrumbDelta(left.breadcrumbConfig, right.breadcrumbConfig),
            layoutShapeDelta = shapeDelta,
            panelsAdded = added,
            panelsRemoved = removed,
            panelsKept = kept,
            tabsAdded = tabsAdded.sorted(),
            tabsRemoved = tabsRemoved.sorted(),
            tabsModified = tabsModified.sorted(),
            pinningChanges = pinningChanges.sorted(),
        )
    }

    @Suppress("LongParameterList")
    private fun collectPanelTabChanges(
        panelId: String,
        leftPanel: PanelConfig,
        rightPanel: PanelConfig,
        tabsAdded: MutableList<String>,
        tabsRemoved: MutableList<String>,
        tabsModified: MutableList<String>,
        pinningChanges: MutableList<String>,
    ) {
        val leftByKey = leftPanel.tabs.associateBy { tabKey(it) }
        val rightByKey = rightPanel.tabs.associateBy { tabKey(it) }
        for ((k, lt) in leftByKey) {
            if (k !in rightByKey) {
                tabsRemoved += "$panelId: ${describeTab(lt)}"
            }
        }
        for ((k, rt) in rightByKey) {
            if (k !in leftByKey) {
                tabsAdded += "$panelId: ${describeTab(rt)}"
            }
        }
        for ((k, rt) in rightByKey) {
            val lt = leftByKey[k] ?: continue
            if (rt.title != lt.title) {
                tabsModified += "$panelId: ${describeTab(lt)} → ${describeTab(rt)} (title)"
            }
        }
        if (rightPanel.pinnedCount != leftPanel.pinnedCount) {
            pinningChanges += "$panelId: pinnedCount ${leftPanel.pinnedCount} → ${rightPanel.pinnedCount}"
        }
    }

    private fun projectPathDelta(
        left: LayoutWorkspace,
        right: LayoutWorkspace,
    ): String? =
        if (left.projectPath != right.projectPath) {
            "${left.projectPath ?: "<null>"} → ${right.projectPath ?: "<null>"}"
        } else {
            null
        }

    private fun tabKey(tab: TabConfig): String {
        val content =
            when (tab.type) {
                "browser" -> tab.url.orEmpty()
                "editor" -> tab.filePath.orEmpty()
                "terminal" -> (tab.initialCommand.orEmpty() + "@" + tab.workingDirectory.orEmpty())
                else -> ""
            }
        return "${tab.type}::$content"
    }

    private fun describeTab(tab: TabConfig): String {
        val tail =
            when (tab.type) {
                "browser" -> tab.url
                "editor" -> tab.filePath
                "terminal" -> tab.initialCommand
                else -> null
            }
        return if (tail.isNullOrEmpty()) tab.type else "${tab.type}:$tail"
    }

    private fun shapeOf(layout: SplitConfig): String =
        when (layout) {
            is SplitConfig.SinglePanel -> "P(${layout.panel.id})"
            is SplitConfig.VerticalSplit -> "V(${shapeOf(layout.left)},${shapeOf(layout.right)})"
            is SplitConfig.HorizontalSplit -> "H(${shapeOf(layout.top)},${shapeOf(layout.bottom)})"
        }

    private fun describeShape(s: String): String =
        when {
            s.startsWith("P(") -> "single panel"
            s.startsWith("V(") -> "vertical split"
            s.startsWith("H(") -> "horizontal split"
            else -> s
        }

    private fun extractPanels(layout: SplitConfig): List<PanelConfig> {
        val out = mutableListOf<PanelConfig>()
        walk(layout) { panel -> out += panel }
        return out
    }

    private fun walk(
        layout: SplitConfig,
        visit: (PanelConfig) -> Unit,
    ) {
        when (layout) {
            is SplitConfig.SinglePanel -> {
                visit(layout.panel)
            }

            is SplitConfig.VerticalSplit -> {
                walk(layout.left, visit)
                walk(layout.right, visit)
            }

            is SplitConfig.HorizontalSplit -> {
                walk(layout.top, visit)
                walk(layout.bottom, visit)
            }
        }
    }

    private fun breadcrumbDelta(
        left: ai.rever.boss.plugin.workspace.BreadcrumbConfig,
        right: ai.rever.boss.plugin.workspace.BreadcrumbConfig,
    ): String? {
        if (left == right) return null
        val parts = mutableListOf<String>()
        if (left.enabled != right.enabled) parts += "enabled ${left.enabled} → ${right.enabled}"
        if (left.showWorkspacePath != right.showWorkspacePath) {
            parts += "showWorkspacePath ${left.showWorkspacePath} → ${right.showWorkspacePath}"
        }
        if (left.showTabPath != right.showTabPath) {
            parts += "showTabPath ${left.showTabPath} → ${right.showTabPath}"
        }
        if (left.maxLength != right.maxLength) parts += "maxLength ${left.maxLength} → ${right.maxLength}"
        if (left.separator != right.separator) parts += "separator changed"
        return parts.joinToString("; ")
    }
}

private object WorkspaceDiffJson {
    fun encode(diff: WorkspaceDiff): String =
        buildJsonObject {
            put("leftId", diff.leftId)
            put("rightId", diff.rightId)
            diff.nameDelta?.let { put("nameDelta", it) }
            diff.descriptionDelta?.let { put("descriptionDelta", it) }
            diff.projectPathDelta?.let { put("projectPathDelta", it) }
            diff.breadcrumbDelta?.let { put("breadcrumbDelta", it) }
            diff.layoutShapeDelta?.let { put("layoutShapeDelta", it) }
            put("panelsAdded", buildJsonArray { diff.panelsAdded.forEach { add(it) } })
            put("panelsRemoved", buildJsonArray { diff.panelsRemoved.forEach { add(it) } })
            put("panelsKept", buildJsonArray { diff.panelsKept.forEach { add(it) } })
            put("tabsAdded", buildJsonArray { diff.tabsAdded.forEach { add(it) } })
            put("tabsRemoved", buildJsonArray { diff.tabsRemoved.forEach { add(it) } })
            put("tabsModified", buildJsonArray { diff.tabsModified.forEach { add(it) } })
            put("pinningChanges", buildJsonArray { diff.pinningChanges.forEach { add(it) } })
            put("hasChanges", diff.hasChanges())
        }.toString()
}
