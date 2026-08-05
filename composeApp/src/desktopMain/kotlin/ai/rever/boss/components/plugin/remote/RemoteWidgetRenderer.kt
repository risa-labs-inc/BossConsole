package ai.rever.boss.components.plugin.remote

import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.plugin.ui.BossColorScheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a [WidgetTree] from an out-of-process plugin as Compose components.
 *
 * This is the kernel-side renderer for Phase 4/7: plugins in separate JVM processes
 * send declarative widget trees over IPC, which the kernel renders using this component.
 *
 * How a node's properties and modifier are interpreted lives in `boss-ui-sdk`
 * ([resolveClickEventId], [resolveListItems], [resolveDropdownOptions], [effectiveAlpha],
 * [parseBackgroundColor]) so that this renderer and the native `boss-remote-ui` renderer in the Rust
 * shell agree by construction, and so the rules are testable without a UI toolkit.
 *
 * @param tree      The widget tree to render
 * @param onEvent   Callback for UI events, forwarded to the owning plugin as proto `UIEvent`s
 */
@Composable
fun RemoteWidgetRenderer(
    tree: WidgetTree,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit = { _, _ -> },
) {
    val root = tree.nodes[tree.rootId] ?: return
    // A wrapper only so the surface has one node above the whole tree to tap keys at — see
    // Modifier.forwardUnclaimedKeys for why that node is the right place and why it never consumes.
    // propagateMinConstraints so a root that fills its parent still does; the Box is otherwise
    // transparent to layout.
    Box(
        modifier = Modifier.forwardUnclaimedKeys(onEvent),
        propagateMinConstraints = true,
    ) {
        RenderNode(node = root, tree = tree, onEvent = onEvent)
    }
}

@Composable
private fun RenderNode(
    node: WidgetNode,
    tree: WidgetTree,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
) {
    val modifier = node.modifier.toComposeModifier(node, onEvent)

    when (node.type) {
        WidgetType.COLUMN -> {
            Column(modifier = modifier) {
                RenderChildren(node, tree, onEvent)
            }
        }

        WidgetType.ROW -> {
            Row(modifier = modifier) {
                RenderChildren(node, tree, onEvent)
            }
        }

        WidgetType.BOX -> {
            Box(modifier = modifier) {
                RenderChildren(node, tree, onEvent)
            }
        }

        WidgetType.SCROLL -> {
            val scrollState = rememberScrollState()
            // Coalesced, not per-frame: an unthrottled scroll is one IPC message every ~16ms for the
            // length of a fling. See ScrollCoalescer for the window and for why the resting position
            // is always delivered.
            ReportScrollPosition(node.id, scrollState, onEvent)
            Column(modifier = modifier.verticalScroll(scrollState)) {
                // Everything below is measured with an unbounded max height — see the LIST branch.
                CompositionLocalProvider(LocalUnboundedHeight provides true) {
                    RenderChildren(node, tree, onEvent)
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
            // Accepts both spellings of the event id — see resolveClickEventId.
            val clickEventId = node.resolveClickEventId()
            Button(
                onClick = { onEvent(node.id, WidgetEvent.Click(clickEventId)) },
                modifier = modifier,
            ) {
                Text(node.properties["label"] ?: "")
            }
        }

        WidgetType.TEXT_FIELD -> {
            val placeholder = node.properties["placeholder"] ?: ""
            var value by rememberPushableState(node.id, node.properties["value"] ?: "")
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    value = newValue
                    onEvent(node.id, WidgetEvent.TextChange(newValue))
                },
                placeholder = { Text(placeholder) },
                modifier = modifier.notifyFocusChanges(node.id, onEvent),
            )
        }

        WidgetType.CHECKBOX -> {
            var checked by rememberPushableState(node.id, node.properties["checked"].toBossBoolean())
            val label = node.properties["label"] ?: ""
            Row(modifier = modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { newChecked ->
                        checked = newChecked
                        onEvent(node.id, WidgetEvent.Toggle(newChecked))
                    },
                )
                if (label.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label)
                }
            }
        }

        WidgetType.TOGGLE -> {
            var checked by rememberPushableState(node.id, node.properties["checked"].toBossBoolean())
            Switch(
                checked = checked,
                onCheckedChange = { newChecked ->
                    checked = newChecked
                    onEvent(node.id, WidgetEvent.Toggle(newChecked))
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
            // A LazyColumn measured with an unbounded max height throws, so a plugin could crash the
            // host surface purely by nesting LIST inside SCROLL (or inside another LIST). Inside such
            // a parent, fall back to a plain Column: no virtualization, but the surface survives
            // whatever tree shape arrives over IPC.
            // Read the ambient value BEFORE providing our own, or every list looks nested.
            val unboundedParent = LocalUnboundedHeight.current
            val children = node.childIds.mapNotNull { tree.nodes[it] }
            // WidgetTreeBuilder.list() carries rows as an `items` property rather than child nodes;
            // walking childIds alone drew an empty list.
            val rows = if (children.isEmpty()) node.resolveListItems() else emptyList()
            CompositionLocalProvider(LocalUnboundedHeight provides true) {
                if (unboundedParent) {
                    Column(modifier = modifier) {
                        children.forEach { child -> key(child.id) { RenderNode(child, tree, onEvent) } }
                        rows.forEach { row -> Text(text = row) }
                    }
                } else {
                    LazyColumn(modifier = modifier) {
                        items(items = children, key = { it.id }) { child ->
                            RenderNode(child, tree, onEvent)
                        }
                        items(rows) { row -> Text(text = row) }
                    }
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
            var expanded by remember(node.id) { mutableStateOf(false) }
            val selected = node.properties["selected"] ?: ""
            val options = node.resolveDropdownOptions()
            Box(modifier = modifier) {
                Text(
                    text = selected,
                    modifier = Modifier.clickable { expanded = true },
                )
                // ContextMenu, not Material's DropdownMenu: this renders in the main window, where
                // under HARDWARE the browser's native surface paints over the Compose scene. A
                // DropdownMenu is a lightweight Popup and opened behind the page; ContextMenu
                // routes through the heavyweight popup window. Material gives no injection point,
                // so the widget has to change rather than the menu.
                if (expanded) {
                    ContextMenu(
                        items =
                            options.mapIndexed { index, option ->
                                ContextMenuItem(
                                    text = option,
                                    onClick = { onEvent(node.id, WidgetEvent.Selection(option, index)) },
                                )
                            },
                        onDismissRequest = { expanded = false },
                    )
                }
            }
        }

        // Complex widgets (CODE_EDITOR, TERMINAL, BROWSER) delegate to host composites
        // These are rendered in-process using the host's actual implementations
        WidgetType.CODE_EDITOR, WidgetType.TERMINAL, WidgetType.BROWSER -> {
            val message =
                when (node.type) {
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
 * Render a container's children, giving each one a Compose identity keyed by its node id.
 *
 * Without the [key], Compose identifies children *positionally*, so inserting or reordering a
 * sibling shifts every `remember`ed state (a text field's buffer, a dropdown's expanded flag) onto
 * the wrong node.
 */
@Composable
private fun RenderChildren(
    node: WidgetNode,
    tree: WidgetTree,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
) {
    node.childIds.forEach { childId ->
        tree.nodes[childId]?.let { child ->
            key(child.id) {
                RenderNode(child, tree, onEvent)
            }
        }
    }
}

/**
 * Whether the surrounding layout measures its children with an unbounded max height.
 *
 * `LazyColumn` throws when measured that way, so a plugin could crash the host surface purely by
 * nesting `LIST` inside `SCROLL` (or inside another `LIST`) — tree *shape* arriving over IPC, not a
 * host bug. Ambient rather than a parameter: it describes the enclosing layout, and threading it
 * through every branch would put it in `RenderNode`'s signature for one consumer.
 */
private val LocalUnboundedHeight = compositionLocalOf { false }

/**
 * Local widget state that the user edits and the **plugin can still push to**.
 *
 * A plain `remember(nodeId) { mutableStateOf(incoming) }` seeds once per node identity and then
 * ignores the property forever, which means a plugin cannot drive its own widgets: no clearing a
 * search box after submit, no echoing back a normalized value, no rejecting a toggle. Tracking the
 * last value seen on the wire separates "the user typed" (leave the buffer alone) from "the plugin
 * sent something new" (the plugin wins). An unchanged property re-sent on every tree update does
 * *not* clobber in-flight typing.
 */
@Composable
private fun <T> rememberPushableState(
    nodeId: String,
    incoming: T,
): MutableState<T> {
    val state = remember(nodeId) { mutableStateOf(incoming) }
    val lastIncoming = remember(nodeId) { mutableStateOf(incoming) }
    LaunchedEffect(nodeId, incoming) {
        if (lastIncoming.value != incoming) {
            lastIncoming.value = incoming
            state.value = incoming
        }
    }
    return state
}

/**
 * Report focus transitions, and only transitions.
 *
 * `onFocusChanged` also fires when a node's focus state is first resolved on attach, so wiring it
 * straight through made every text field announce focus *loss* the moment it rendered — a plugin
 * could not tell that from a real blur. Gate on the previous value, seeded to `false` because a
 * freshly composed (or re-keyed) node genuinely starts unfocused: that attach callback is not a
 * transition, while an auto-focused field's `true` is one and still reports.
 */
@Composable
private fun Modifier.notifyFocusChanges(
    nodeId: String,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
): Modifier {
    val lastFocused = remember(nodeId) { mutableStateOf(false) }
    return onFocusChanged { focusState ->
        val isFocused = focusState.isFocused
        if (lastFocused.value != isFocused) {
            lastFocused.value = isFocused
            onEvent(nodeId, WidgetEvent.Focus(isFocused))
        }
    }
}

/** `String.toBoolean()` semantics for an absent property, matching the SDK's wire conventions. */
private fun String?.toBossBoolean(): Boolean = this?.toBoolean() ?: false

/**
 * Resolve a design-system token to a color from the host's active scheme.
 *
 * A table rather than a `when` so [ThemeToken] coverage is asserted by
 * `RemoteWidgetRendererColorTest` instead of being spread across the renderer.
 */
internal val themeTokenColors: Map<ThemeToken, BossColorScheme.() -> Color> =
    mapOf(
        ThemeToken.INK to { ink },
        ThemeToken.PANEL to { panel },
        ThemeToken.RAISED to { raised },
        ThemeToken.LINE to { line },
        ThemeToken.LINE_STRONG to { lineStrong },
        ThemeToken.TEXT_PRIMARY to { textPrimary },
        ThemeToken.TEXT_SECONDARY to { textSecondary },
        ThemeToken.TEXT_MUTED to { textMuted },
        ThemeToken.SIGNAL to { signal },
        ThemeToken.SIGNAL_DIM to { signalDim },
        ThemeToken.SIGNAL_WASH to { signalWash },
        ThemeToken.SIGNAL_TEXT to { signalText },
        ThemeToken.DATA to { data },
        ThemeToken.OK to { ok },
        ThemeToken.WARN to { warn },
        ThemeToken.ALERT to { alert },
        ThemeToken.ON_SIGNAL to { onSignal },
        ThemeToken.ON_DATA to { onData },
    )

/**
 * Resolve a `WidgetModifier.background_color` spec against [scheme].
 *
 * `ui_protocol.proto` promises "hex color string … or theme token"; only hex was ever parsed, so
 * every token value was silently dropped. Tokens resolve through the host's *active* theme, so a
 * plugin asking for `panel` re-skins with the app.
 */
internal fun resolveBackgroundColor(
    spec: String,
    scheme: BossColorScheme,
): Color? =
    when (val parsed = parseBackgroundColor(spec)) {
        is BackgroundSpec.Token -> themeTokenColors[parsed.token]?.invoke(scheme)
        is BackgroundSpec.Hex -> Color(parsed.argb)
        BackgroundSpec.None -> null
    }

/**
 * Convert [WidgetModifier] to a Compose [Modifier].
 */
@Composable
private fun WidgetModifier.toComposeModifier(
    node: WidgetNode,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
): Modifier {
    var m: Modifier = Modifier

    if (width > 0) {
        m = m.width(width.dp)
    } else if (width == -1) {
        m = m.fillMaxWidth()
    }

    if (height > 0) {
        m = m.height(height.dp)
    } else if (height == -1) {
        m = m.fillMaxHeight()
    }

    val hasPadding = paddingStart > 0 || paddingTop > 0 || paddingEnd > 0 || paddingBottom > 0
    if (hasPadding) {
        m =
            m.padding(
                start = paddingStart.dp,
                top = paddingTop.dp,
                end = paddingEnd.dp,
                bottom = paddingBottom.dp,
            )
    }

    // Memoized: this runs for every node on every recomposition, and resolving a spec allocates
    // (token normalization builds two strings, hex parsing more).
    val colors = BossTheme.colors
    val background = remember(backgroundColor, colors) { resolveBackgroundColor(backgroundColor, colors) }
    if (background != null) {
        m = m.background(background)
    }

    // After background, so a translucent widget keeps its own backdrop crisp — matches the native
    // renderer. `effectiveAlpha()` resolves proto3's "unset is 0.0" trap; see WidgetModifier.alpha.
    effectiveAlpha()?.let { resolved ->
        m = m.alpha(resolved)
    }

    if (clickable && clickEventId.isNotEmpty()) {
        m = m.clickable { onEvent(node.id, WidgetEvent.Click(clickEventId)) }
    }

    return m
}
