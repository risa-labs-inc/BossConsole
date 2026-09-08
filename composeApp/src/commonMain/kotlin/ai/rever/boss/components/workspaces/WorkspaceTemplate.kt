package ai.rever.boss.components.workspaces

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.dashboard.WorkspacePlaceholders
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val logger = BossLogger.forComponent("WorkspaceTemplate")

/*
 * The built-in layouts are TEMPLATES, not Spaces, and picking one MATERIALISES it.
 *
 * `PredefinedWorkspaces.allWorkspaces` is a list of parameterised layouts: `{projectPath}`,
 * `{gitRemoteUrl}` and friends stand in for a project nobody has chosen yet. Applying one
 * resolves those placeholders on the way to building the tabs and throws the answers away, so
 * the entry in the Space list still says `{projectPath}` afterwards and the layout on screen
 * belongs to no Space at all.
 *
 * The predicate is `requiresProject()`, and there is deliberately no `isTemplate` field.
 * Carrying an unsubstituted project placeholder is exactly what makes something a template, and a
 * saved Space is written from live state by `WorkspaceExtractor` with real paths in it, so a
 * saved Space can never satisfy it. `LayoutWorkspace` is the plugin api type - a typealias onto
 * `plugin-workspace-types`, consumed by 33 plugin repos and member-checked by
 * `BinaryCompatibilityValidator` - and a new constructor parameter on that data class rejects
 * every already-built plugin (see the `@JvmOverloads` note on `PanelConfig`).
 *
 * So a template is answered from the layout, and a materialised Space is an ordinary Space from
 * then on: it has real paths, so `requiresProject()` is false for it and nothing lists it as a
 * template again.
 */

/**
 * The name a materialised template is saved under.
 *
 * Template plus project, because that is what the Space is: "Claude Code" against `Boss` and
 * "Claude Code" against `BossTerm` are two Spaces with two split trees and two sets of live
 * terminals, and a user who materialises the same template against a second project must not have
 * the first one silently overwritten. `WorkspaceManager` keys saved Spaces by NAME - it saves to
 * `generateFileName(name)` and replaces the list entry whose name matches - so the name is the
 * only thing standing between those two.
 */
internal fun materialisedTemplateName(
    templateName: String,
    projectName: String,
): String = "$templateName ($projectName)"

/**
 * A project path's display name, the way `applyWorkspace` derives it when it restores a project.
 */
internal fun projectNameFor(projectPath: String): String =
    projectPath
        .trimEnd('/')
        .trimEnd('\\')
        .extractFileName()
        .ifEmpty { "Project" }

/**
 * [template] with its placeholders resolved, under a fresh identity.
 *
 * Pure, and takes [substitute] rather than reaching for [WorkspacePlaceholders] itself, because
 * the real substitution forks `git remote get-url origin` and lists `~/.claude/projects` - see
 * [materialiseTemplateForProject], which supplies it.
 *
 * **The per-field rule is `createTabFromWorkspaceConfig`'s, field for field**, because the
 * materialised Space is then byte-identical to what applying the template would have built:
 * `initialCommand` is shell command content, so `{projectPath}` is substituted SHELL-QUOTED
 * there and raw everywhere else. Getting that backwards would either break a project path with a
 * space in it (`cd /Users/me/My Project && claude` is two arguments) or write a quoted path into
 * a `filePath`, which is not shell-parsed and would be opened with the quotes in its name.
 *
 * [id] and [now] are parameters so this is testable: [LayoutWorkspace.generateId] is a clock
 * read, and two calls in one millisecond return the same id. The project NAME is not a parameter -
 * it is [projectNameFor] of the path, so a caller cannot hand in a name that disagrees with the
 * project the placeholders were resolved against.
 */
internal fun materialiseTemplate(
    template: LayoutWorkspace,
    id: String,
    projectPath: String,
    now: Long,
    substitute: (content: String, quote: Boolean) -> String,
): LayoutWorkspace =
    template.copy(
        id = id,
        name = materialisedTemplateName(template.name, projectNameFor(projectPath)),
        layout = template.layout.substituted(substitute),
        timestamp = now,
        projectPath = projectPath,
    )

private fun SplitConfig.substituted(substitute: (String, Boolean) -> String): SplitConfig =
    when (this) {
        is SinglePanel -> SinglePanel(panel.copy(tabs = panel.tabs.map { it.substituted(substitute) }))
        is VerticalSplit -> VerticalSplit(left.substituted(substitute), right.substituted(substitute))
        is HorizontalSplit -> HorizontalSplit(top.substituted(substitute), bottom.substituted(substitute))
    }

private fun TabConfig.substituted(substitute: (String, Boolean) -> String): TabConfig =
    copy(
        url = url?.let { substitute(it, false) },
        filePath = filePath?.let { substitute(it, false) },
        // The one quoted field. See the KDoc above.
        initialCommand = initialCommand?.let { substitute(it, true) },
        workingDirectory = workingDirectory?.let { substitute(it, false) },
    )

/**
 * [template] materialised against the project at [projectPath], off the main thread.
 *
 * The substitution is [WorkspacePlaceholders.processPlaceholders] - the app's ONE placeholder
 * resolver, which is also what builds the tabs - rather than a second pass over the same four
 * tokens. Its `{projectPath}` half is `substituteProjectPath`; the other three cost a `git`
 * subprocess, a directory listing and possibly a `mkdir`, which is why this suspends onto IO.
 */
internal suspend fun materialiseTemplateForProject(
    template: LayoutWorkspace,
    projectPath: String,
): LayoutWorkspace =
    withContext(Dispatchers.IO) {
        materialiseTemplate(
            template = template,
            id = LayoutWorkspace.generateId(),
            projectPath = projectPath,
            now = Clock.System.now().toEpochMilliseconds(),
        ) { content, quote ->
            WorkspacePlaceholders.processPlaceholders(content, projectPath, null, quoteProjectPath = quote)
        }
    }

/**
 * The Space to actually open when the user picks [picked], materialising a template on the way.
 *
 * The one door every pick goes through - the Space picker in Top of Mind, the Space button's own
 * menu, the home screen's cards and the startup "which Space" prompt - so a template becomes a
 * Space wherever it is picked from and not only in the picker that grouped it as one.
 *
 * Three outcomes:
 *
 * - **Not a template**: returned unchanged. Nothing is written and nothing is said.
 * - **A template, with a project selected**: substituted, saved under
 *   [materialisedTemplateName], and returned for the caller to load and apply. Saved BEFORE it is
 *   applied, so a Space the user can switch back to exists from the moment its tabs do.
 * - **A template, with NO project**: returned unchanged, so this is exactly today's behaviour -
 *   the placeholders resolve to `~/BossProjects` while the layout stays unowned - and a status
 *   message says so. Deliberately not a project picker: choosing a project mid-switch is a second
 *   dialog on top of the one the user just used, and the message it replaces (the home screen's,
 *   which refused the click outright) is the wording kept here.
 */
suspend fun spaceToOpen(
    picked: LayoutWorkspace,
    projectPath: String,
    manager: WorkspaceManager = workspaceManager,
): LayoutWorkspace {
    val project = DefaultWorkingDirectory.selectedOrNull(projectPath)
    return when {
        !picked.requiresProject() -> {
            picked
        }

        project == null -> {
            StatusMessageManager.showMessage(
                "Open a project first - \"${picked.name}\" builds its tabs from the project you are in",
            )
            picked
        }

        else -> {
            materialisedAndSaved(picked, project, manager)
        }
    }
}

private suspend fun materialisedAndSaved(
    template: LayoutWorkspace,
    projectPath: String,
    manager: WorkspaceManager,
): LayoutWorkspace {
    val materialised = materialiseTemplateForProject(template, projectPath)
    // Load then save, which is how every other save in the app writes a Space:
    // saveCurrentWorkspace() persists whatever the manager holds as current, under its own name.
    manager.loadWorkspace(materialised)
    manager.saveCurrentWorkspace()
    logger.info(
        LogCategory.WORKSPACE,
        "Materialized template into a workspace",
        mapOf("template" to template.name, "workspace" to materialised.name, "id" to materialised.id),
    )
    StatusMessageManager.showMessage("Created Space \"${materialised.name}\"")
    return materialised
}
