package ai.rever.boss.ui.sdk

data class WidgetTree(
    val rootId: String,
    val nodes: Map<String, WidgetNode>,
    val version: Long = 0,
)

data class WidgetNode(
    val id: String,
    val type: WidgetType,
    val properties: Map<String, String> = emptyMap(),
    val childIds: List<String> = emptyList(),
    val modifier: WidgetModifier = WidgetModifier(),
)

data class WidgetModifier(
    val width: Int = 0,
    val height: Int = 0,
    val paddingStart: Int = 0,
    val paddingTop: Int = 0,
    val paddingEnd: Int = 0,
    val paddingBottom: Int = 0,
    val backgroundColor: String = "",
    val alpha: Float = 1f,
    val clickable: Boolean = false,
    val clickEventId: String = "",
)

enum class WidgetType {
    COLUMN,
    ROW,
    BOX,
    SCROLL,
    TEXT,
    ICON,
    IMAGE,
    DIVIDER,
    SPACER,
    PROGRESS,
    BUTTON,
    TEXT_FIELD,
    CHECKBOX,
    DROPDOWN,
    TOGGLE,
    LIST,
    TREE,
    TABLE,
    TAB_ROW,
    CODE_EDITOR,
    TERMINAL,
    BROWSER,
    CANVAS,
}
