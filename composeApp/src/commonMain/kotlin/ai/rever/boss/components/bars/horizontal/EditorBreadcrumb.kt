package ai.rever.boss.components.bars.horizontal

/**
 * The editor breadcrumb's segments: [filePath] relative to [projectPath] when the file is inside
 * the project, otherwise every segment of [filePath].
 *
 * Split out and internal so it is testable without a Compose harness. Both `/` and `\` are
 * separators on every host, the display-label rule `plugin-path-utils` documents for
 * [ai.rever.boss.utils.extractFileName]. The previous inline version appended `/` to the project
 * path and split on `/` alone, so on Windows, where the project path is `C:\…`, the prefix never
 * matched and the whole absolute path rendered as a single segment. A path the git panel builds as
 * `C:\repo/src/App.kt` mixes both, which is why neither separator can be preferred.
 *
 * The containment test compares whole segments, so a project at `/repo` does not claim
 * `/repo2/App.kt`, and trailing or repeated separators on either side do not change the result.
 */
internal fun editorBreadcrumbSegments(
    filePath: String,
    projectPath: String,
): List<String> {
    val fileSegments = filePath.split('/', '\\').filter { it.isNotEmpty() }
    val projectSegments = projectPath.split('/', '\\').filter { it.isNotEmpty() }
    val inProject =
        projectSegments.isNotEmpty() &&
            fileSegments.size > projectSegments.size &&
            fileSegments.subList(0, projectSegments.size) == projectSegments
    return if (inProject) fileSegments.drop(projectSegments.size) else fileSegments
}
