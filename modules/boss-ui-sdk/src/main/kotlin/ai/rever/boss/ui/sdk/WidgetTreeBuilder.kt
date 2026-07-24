package ai.rever.boss.ui.sdk

import java.util.UUID

fun widgetTree(block: WidgetTreeBuilder.() -> Unit): WidgetTree {
    val builder = WidgetTreeBuilder()
    builder.block()
    return builder.buildTree()
}

class WidgetTreeBuilder {
    private val allNodes = mutableMapOf<String, WidgetNode>()
    private val childrenOf = mutableMapOf<String, MutableList<String>>()
    private val parentStack = ArrayDeque<String>()
    private var rootId: String? = null

    private fun genId(): String = UUID.randomUUID().toString()

    private fun container(
        type: WidgetType,
        properties: Map<String, String> = emptyMap(),
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String {
        val id = genId()
        childrenOf[id] = mutableListOf()
        parentStack.lastOrNull()?.let { childrenOf[it]?.add(id) }
        if (rootId == null) rootId = id
        parentStack.addLast(id)
        block()
        parentStack.removeLast()
        allNodes[id] = WidgetNode(id, type, properties, childrenOf[id] ?: emptyList(), modifier)
        return id
    }

    private fun leaf(
        type: WidgetType,
        properties: Map<String, String> = emptyMap(),
        modifier: WidgetModifier = WidgetModifier(),
    ): String {
        val id = genId()
        parentStack.lastOrNull()?.let { childrenOf[it]?.add(id) }
        if (rootId == null) rootId = id
        allNodes[id] = WidgetNode(id, type, properties, emptyList(), modifier)
        return id
    }

    fun column(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.COLUMN, emptyMap(), modifier, block)

    fun row(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.ROW, emptyMap(), modifier, block)

    fun box(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.BOX, emptyMap(), modifier, block)

    fun scroll(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.SCROLL, emptyMap(), modifier, block)

    fun text(
        value: String,
        style: String = "",
    ): String =
        leaf(
            WidgetType.TEXT,
            buildMap {
                put("value", value)
                if (style.isNotEmpty()) put("style", style)
            },
        )

    fun button(
        label: String,
        onClickEvent: String,
    ): String = leaf(WidgetType.BUTTON, mapOf("label" to label, "onClickEvent" to onClickEvent))

    fun textField(
        value: String,
        onChangeEvent: String,
        placeholder: String = "",
    ): String =
        leaf(
            WidgetType.TEXT_FIELD,
            buildMap {
                put("value", value)
                put("onChangeEvent", onChangeEvent)
                if (placeholder.isNotEmpty()) put("placeholder", placeholder)
            },
        )

    fun icon(
        name: String,
        size: Int = 24,
    ): String = leaf(WidgetType.ICON, mapOf("name" to name, "size" to size.toString()))

    fun checkbox(
        checked: Boolean,
        onToggleEvent: String,
        label: String = "",
    ): String =
        leaf(
            WidgetType.CHECKBOX,
            buildMap {
                put("checked", checked.toString())
                put("onToggleEvent", onToggleEvent)
                if (label.isNotEmpty()) put("label", label)
            },
        )

    fun dropdown(
        selected: String,
        options: List<String>,
        onSelectEvent: String,
    ): String =
        leaf(
            WidgetType.DROPDOWN,
            mapOf(
                "selected" to selected,
                "options" to options.joinToString(","),
                "onSelectEvent" to onSelectEvent,
            ),
        )

    fun progress(
        value: Float = 0f,
        indeterminate: Boolean = false,
    ): String =
        leaf(
            WidgetType.PROGRESS,
            mapOf(
                "value" to value.toString(),
                "indeterminate" to indeterminate.toString(),
            ),
        )

    fun spacer(height: Int = 0): String = leaf(WidgetType.SPACER, mapOf("height" to height.toString()))

    fun divider(): String = leaf(WidgetType.DIVIDER)

    fun list(items: List<String>): String = leaf(WidgetType.LIST, mapOf("items" to items.joinToString(",")))

    internal fun buildTree(): WidgetTree {
        val root = rootId ?: throw IllegalStateException("No root widget defined")
        return WidgetTree(root, allNodes.toMap())
    }
}
