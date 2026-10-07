package ai.rever.boss.app

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.utils.extractFileName
import kotlin.random.Random

/** Choose the file's renderer before the dialog places it in the active pane or requested split. */
internal fun newTabFileInfo(path: String): TabInfo {
    val fileName = path.extractFileName()
    if (SplitViewState.shouldOpenInBrowser(fileName)) {
        return FluckTabInfo(
            id = "browser-${Random.nextLong()}",
            typeId = FluckTabType.typeId,
            _title = fileName,
            url = SplitViewState.toFileUrl(path),
        )
    }

    val fileIconInfo = FileIcons.forFile(fileName)
    return EditorTabInfo(
        id = "editor-${Random.nextLong()}",
        typeId = CodeEditorTabType.typeId,
        title = fileName,
        icon = fileIconInfo.icon,
        tabIcon = TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
        filePath = path,
    )
}
