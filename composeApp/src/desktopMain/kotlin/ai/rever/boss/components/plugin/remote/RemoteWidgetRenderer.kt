package ai.rever.boss.components.plugin.remote

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.ui.sdk.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a [WidgetTree] from an out-of-process plugin as Compose components.
 *
 * This is the kernel-side renderer for Phase 4/7: plugins in separate JVM processes
 * send declarative widget trees over IPC, which the kernel renders using this component.
 *
 * @param tree      The widget tree to render
 * @param onEvent   Callback for UI events: (nodeId, eventType, eventData) -> Unit
 */
@Composable
fun RemoteWidgetRenderer(
    tree: WidgetTree,
    onEvent: (nodeId: String, eventType: String, eventData: String) -> Unit = { _, _, _ -> },
) {
    val root = tree.nodes[tree.rootId] ?: return
    RenderNode(node = root, tree = tree, onEvent = onEvent)
}

@Composable
private fun RenderNode(
    node: WidgetNode,
    tree: WidgetTree,
    onEvent: (nodeId: String, eventType: String, eventData: String) -> Unit,
) {
    val modifier = node.modifier.toComposeModifier(node, onEvent)

    when (node.type) {
        WidgetType.COLUMN -> {
            Column(modifier = modifier) {
                node.childIds.forEach { childId ->
                    tree.nodes[childId]?.let { child ->
                        RenderNode(node = child, tree = tree, onEvent = onEvent)
                    }
                }
            }
        }
        WidgetType.ROW -> {
            Row(modifier = modifier) {
                node.childIds.forEach { childId ->
                    tree.nodes[childId]?.let { child ->
                        RenderNode(node = child, tree = tree, onEvent = onEvent)
                    }
                }
            }
        }
        WidgetType.BOX -> {
            Box(modifier = modifier) {
                node.childIds.forEach { childId ->
                    tree.nodes[childId]?.let { child ->
                        RenderNode(node = child, tree = tree, onEvent = onEvent)
                    }
                }
            }
        }
        WidgetType.SCROLL -> {
            val scrollState = rememberScrollState()
            Column(modifier = modifier.verticalScroll(scrollState)) {
                node.childIds.forEach { childId ->
                    tree.nodes[childId]?.let { child ->
                        RenderNode(node = child, tree = tree, onEvent = onEvent)
                    }
                }
            }
        }
        WidgetType.TEXT -> {
            val value = node.properties["value"] ?: ""
            val fontSize = node.properties["fontSize"]?.toFloatOrNull() ?: 14f
            Text(
                text = value,
                fontSize = fontSize.sp,
                modifier = modifier,
            )
        }
        WidgetType.BUTTON -> {
            val label = node.properties["label"] ?: ""
            val clickEventId = node.properties["clickEventId"] ?: node.modifier.clickEventId
            Button(
                onClick = { onEvent(node.id, "click", clickEventId) },
                modifier = modifier,
            ) {
                Text(label)
            }
        }
        WidgetType.TEXT_FIELD -> {
            var value by remember { mutableStateOf(node.properties["value"] ?: "") }
            val placeholder = node.properties["placeholder"] ?: ""
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    value = newValue
                    onEvent(node.id, "textChange", newValue)
                },
                placeholder = { Text(placeholder) },
                modifier = modifier,
            )
        }
        WidgetType.CHECKBOX -> {
            var checked by remember { mutableStateOf(node.properties["checked"]?.toBoolean() ?: false) }
            val label = node.properties["label"] ?: ""
            Row(modifier = modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { newChecked ->
                        checked = newChecked
                        onEvent(node.id, "toggle", newChecked.toString())
                    },
                )
                if (label.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label)
                }
            }
        }
        WidgetType.TOGGLE -> {
            var checked by remember { mutableStateOf(node.properties["checked"]?.toBoolean() ?: false) }
            Switch(
                checked = checked,
                onCheckedChange = { newChecked ->
                    checked = newChecked
                    onEvent(node.id, "toggle", newChecked.toString())
                },
                modifier = modifier,
            )
        }
        WidgetType.PROGRESS -> {
            val value = node.properties["value"]?.toFloatOrNull() ?: 0f
            val indeterminate = node.properties["indeterminate"]?.toBoolean() ?: false
            if (indeterminate) {
                LinearProgressIndicator(modifier = modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = value, modifier = modifier.fillMaxWidth())
            }
        }
        WidgetType.SPACER -> {
            val height = node.properties["height"]?.toIntOrNull() ?: 8
            Spacer(modifier = Modifier.height(height.dp))
        }
        WidgetType.DIVIDER -> {
            Divider(modifier = modifier)
        }
        WidgetType.LIST -> {
            val items = node.childIds.mapNotNull { tree.nodes[it] }
            LazyColumn(modifier = modifier) {
                items(items) { item ->
                    RenderNode(node = item, tree = tree, onEvent = onEvent)
                }
            }
        }
        WidgetType.ICON -> {
            // Icon rendering — use a Text placeholder for now
            // Full icon mapping from BossEditor icon set to be wired in Phase 7
            val name = node.properties["name"] ?: "?"
            val size = node.properties["size"]?.toIntOrNull() ?: 16
            Text(
                text = "[$name]",
                fontSize = size.sp,
                modifier = modifier,
            )
        }
        WidgetType.DROPDOWN -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = node.properties["selected"] ?: ""
            val options = node.properties["options"]?.split(",") ?: emptyList()
            Box(modifier = modifier) {
                Text(
                    text = selected,
                    modifier = Modifier.clickable { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(onClick = {
                            expanded = false
                            onEvent(node.id, "selection", option)
                        }) {
                            Text(option)
                        }
                    }
                }
            }
        }
        // Complex widgets (CODE_EDITOR, TERMINAL, BROWSER) delegate to host composites
        // These are rendered in-process using the host's actual implementations
        WidgetType.CODE_EDITOR, WidgetType.TERMINAL, WidgetType.BROWSER -> {
            val message = when (node.type) {
                WidgetType.CODE_EDITOR -> "Editor (host-rendered)"
                WidgetType.TERMINAL -> "Terminal (host-rendered)"
                else -> "Browser (host-rendered)"
            }
            Text(
                text = message,
                modifier = modifier.background(BossTheme.colors.raised).padding(8.dp),
                color = BossTheme.colors.textPrimary,
            )
        }
        // Remaining types render as placeholders
        else -> {
            val typeName = node.type.name
            Box(modifier = modifier) {
                Text(text = "[$typeName]", fontSize = 10.sp, color = BossTheme.colors.textMuted)
            }
        }
    }
}

/**
 * Parse a CSS hex color string (#RGB, #RRGGBB, or #AARRGGBB) into a Compose [Color].
 */
private fun parseHexColor(hex: String): Color? = runCatching {
    val clean = hex.trimStart('#')
    when (clean.length) {
        6 -> Color(
            red = clean.substring(0, 2).toInt(16) / 255f,
            green = clean.substring(2, 4).toInt(16) / 255f,
            blue = clean.substring(4, 6).toInt(16) / 255f,
        )
        8 -> Color(
            alpha = clean.substring(0, 2).toInt(16) / 255f,
            red = clean.substring(2, 4).toInt(16) / 255f,
            green = clean.substring(4, 6).toInt(16) / 255f,
            blue = clean.substring(6, 8).toInt(16) / 255f,
        )
        else -> null
    }
}.getOrNull()

/**
 * Convert [WidgetModifier] to a Compose [Modifier].
 */
private fun WidgetModifier.toComposeModifier(
    node: WidgetNode,
    onEvent: (nodeId: String, eventType: String, eventData: String) -> Unit,
): Modifier {
    var m: Modifier = Modifier

    if (width > 0) m = m.width(width.dp)
    else if (width == -1) m = m.fillMaxWidth()

    if (height > 0) m = m.height(height.dp)
    else if (height == -1) m = m.fillMaxHeight()

    val hasPadding = paddingStart > 0 || paddingTop > 0 || paddingEnd > 0 || paddingBottom > 0
    if (hasPadding) {
        m = m.padding(
            start = paddingStart.dp,
            top = paddingTop.dp,
            end = paddingEnd.dp,
            bottom = paddingBottom.dp,
        )
    }

    if (backgroundColor.isNotEmpty()) {
        parseHexColor(backgroundColor)?.let { color ->
            m = m.background(color)
        }
    }

    if (clickable && clickEventId.isNotEmpty()) {
        m = m.clickable { onEvent(node.id, "click", clickEventId) }
    }

    return m
}
