package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import BossTheme as HostTheme
import ai.rever.boss.plugin.ui.BossTheme as Tokens

/** Where a browser view sits on screen, in AWT logical units, and the BOSS window hosting it. */
internal data class AutofillAnchor(
    val window: Window,
    val viewOnScreen: Rectangle,
    val windowId: String?,
)

internal enum class SuggestionKind { PASSWORD, CARD, ADDRESS, OTHER }

internal data class SuggestionRow(
    val primary: String,
    val secondary: String,
    val kind: SuggestionKind,
)

/** Margin around the card inside the transparent window, so the shadow has room. */
private const val SHADOW_PAD = 16

private fun clampToScreen(
    p: Point,
    w: Int,
    h: Int,
): Point {
    val screen =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(p) } ?: return p
    return Point(
        p.x.coerceIn(screen.x, (screen.x + screen.width - w).coerceAtLeast(screen.x)),
        p.y.coerceIn(screen.y, (screen.y + screen.height - h).coerceAtLeast(screen.y)),
    )
}

/**
 * An undecorated, transparent, MODELESS dialog owned by the BOSS window that hosts the browser.
 * Never always-on-top: it orders with its owner only. Visible only while that window (or the
 * overlay itself) is active and the anchor is still showing; [tick] re-checks that every 200 ms.
 */
private class OwnedOverlay(
    private val owner: Window,
    focusable: Boolean,
) {
    val dialog =
        androidx.compose.ui.awt.ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isTransparent = true
            focusableWindowState = focusable
            isAlwaysOnTop = false
            isAutoRequestFocus = false
            isResizable = false
            background = java.awt.Color(0, 0, 0, 0)
        }

    val ownerActive: Boolean
        get() =
            (owner.isActive || dialog.isActive) &&
                owner.isShowing &&
                ((owner as? Frame)?.extendedState?.and(Frame.ICONIFIED) ?: 0) == 0

    fun place(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val at = clampToScreen(Point(x - SHADOW_PAD, y - SHADOW_PAD), w + 2 * SHADOW_PAD, h + 2 * SHADOW_PAD)
        dialog.setBounds(at.x, at.y, w + 2 * SHADOW_PAD, h + 2 * SHADOW_PAD)
    }

    fun dispose() {
        dialog.isVisible = false
        dialog.dispose()
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = Tokens.radius.dialogShape
    Box(Modifier.fillMaxSize().padding(SHADOW_PAD.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(6.dp, shape)
                .clip(shape)
                .background(Tokens.colors.raised)
                .border(1.dp, Tokens.colors.line, shape),
        ) { content() }
    }
}

private fun iconFor(kind: SuggestionKind): ImageVector =
    when (kind) {
        SuggestionKind.CARD -> Icons.Outlined.CreditCard
        SuggestionKind.ADDRESS -> Icons.Outlined.Home
        else -> Icons.Outlined.Key
    }

/**
 * Chromium's autofill dropdown, drawn over the native browser surface (jxbrowser-compose draws
 * its own popup underneath it in HARDWARE_ACCELERATED). Never focusable, so the page field keeps
 * focus and typing; keys arrive via [moveSelection]/[acceptSelected]. EDT only.
 */
@Suppress("TooManyFunctions")
internal class SuggestionsWindow(
    private val anchor: AutofillAnchor,
    private val anchorNow: () -> AutofillAnchor?,
    private val manageLabel: String?,
    private val onAccept: (Int) -> Unit,
    private val onManage: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val overlay = OwnedOverlay(anchor.window, focusable = false)
    private var rows by mutableStateOf(emptyList<SuggestionRow>())
    private var selected by mutableIntStateOf(-1)
    private var location = Point()
    private var closed = false
    private val watcher = Timer(200) { tick() }
    private val ownerListener =
        object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) = cancel()

            override fun windowIconified(e: WindowEvent) = cancel()
        }
    private val moveListener =
        object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = cancel()

            override fun componentResized(e: ComponentEvent) = cancel()
        }

    /** Read from the key callback thread. */
    @Volatile var hasSelection = false
        private set

    val isOpen get() = !closed

    init {
        overlay.dialog.setContent {
            HostTheme {
                Card {
                    Column(Modifier.padding(vertical = Tokens.space.xs)) {
                        rows.forEachIndexed { i, row -> RowItem(row, i == selected, { select(i) }) { accept(i) } }
                        if (manageLabel != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Tokens.space.xs)
                                    .height(1.dp)
                                    .background(Tokens.colors.line),
                            )
                            FooterItem(manageLabel) {
                                dispose()
                                onCancel()
                                onManage()
                            }
                        }
                    }
                }
            }
        }
        anchor.window.addWindowListener(ownerListener)
        anchor.window.addComponentListener(moveListener)
    }

    @Composable
    private fun RowItem(
        row: SuggestionRow,
        highlighted: Boolean,
        onHover: () -> Unit,
        onClick: () -> Unit,
    ) {
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        androidx.compose.runtime.LaunchedEffect(hovered) { if (hovered) onHover() }
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT.dp)
                .padding(horizontal = Tokens.space.xs)
                .clip(Tokens.radius.cardShape)
                .background(if (highlighted) Tokens.colors.signalWash else Tokens.colors.raised)
                .hoverable(hover)
                .clickable(onClick = onClick)
                .padding(horizontal = Tokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconFor(row.kind),
                contentDescription = null,
                tint = Tokens.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Tokens.space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary,
                    color = Tokens.colors.textPrimary,
                    style = Tokens.type.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.secondary.isNotEmpty()) {
                    Text(
                        row.secondary,
                        color = Tokens.colors.textMuted,
                        style = Tokens.type.body.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun FooterItem(
        label: String,
        onClick: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(FOOTER_HEIGHT.dp)
                .padding(horizontal = Tokens.space.xs)
                .clip(Tokens.radius.cardShape)
                .clickable(onClick = onClick)
                .padding(horizontal = Tokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Tokens.colors.signalText, style = Tokens.type.body)
        }
    }

    /** [locationInView] is Chromium's popup point in DIP relative to the browser view. */

    /** False when it could not be shown (its window is not active); Chromium has been answered. */
    @Suppress("ReturnCount")
    fun show(
        newRows: List<SuggestionRow>,
        locationInView: Point,
    ): Boolean {
        location = locationInView
        update(newRows)
        if (closed) return false
        if (!overlay.ownerActive) {
            cancel()
            return false
        }
        overlay.dialog.isVisible = true
        watcher.start()
        return true
    }

    fun update(newRows: List<SuggestionRow>) {
        if (closed) return
        if (newRows.isEmpty()) return cancel()
        rows = newRows
        select(-1)
        val footer = if (manageLabel != null) FOOTER_HEIGHT + 9 else 0
        val h = 2 * 4 + newRows.size.coerceAtMost(MAX_ROWS) * ROW_HEIGHT + footer
        overlay.place(anchor.viewOnScreen.x + location.x, anchor.viewOnScreen.y + location.y + 2, WIDTH, h)
    }

    private fun select(i: Int) {
        selected = i
        hasSelection = i >= 0
    }

    fun moveSelection(delta: Int) {
        val n = rows.size
        if (n == 0) return
        select(
            when {
                selected < 0 && delta > 0 -> 0
                selected < 0 -> n - 1
                else -> (selected + delta + n) % n
            },
        )
    }

    /** True when a row was highlighted and accepted. */
    fun acceptSelected(): Boolean {
        if (selected < 0) return false
        accept(selected)
        return true
    }

    private fun tick() {
        val now = anchorNow()
        val unchanged = now != null && now.window === anchor.window && now.viewOnScreen == anchor.viewOnScreen
        if (!overlay.ownerActive || !unchanged) cancel()
    }

    private fun accept(i: Int) {
        if (closed) return
        dispose()
        onAccept(i)
    }

    fun cancel() {
        if (closed) return
        dispose()
        onCancel()
    }

    /** Close without answering Chromium (it already hid the popup). */
    fun dispose() {
        if (closed) return
        closed = true
        watcher.stop()
        anchor.window.removeWindowListener(ownerListener)
        anchor.window.removeComponentListener(moveListener)
        overlay.dispose()
    }

    companion object {
        const val WIDTH = 320
        const val ROW_HEIGHT = 44
        const val FOOTER_HEIGHT = 34
        const val MAX_ROWS = 8
    }
}

internal enum class SaveChoice { SAVE, NEVER, NOT_NOW }

internal enum class SaveKind { PASSWORD, CARD, ADDRESS }

/** What the save card shows. [password] is only ever rendered masked unless the user reveals it. */
internal data class SaveRequest(
    val kind: SaveKind,
    val title: String,
    val saveLabel: String,
    val allowNever: Boolean,
    val username: String = "",
    val password: String = "",
    val lines: List<String> = emptyList(),
) {
    override fun toString() = "SaveRequest($kind)"
}

/**
 * Chrome-style save card anchored to the browser's top-right. Answers exactly once; Esc and the
 * password timeout answer [SaveChoice.NOT_NOW]. Hidden (not answered) while its BOSS window is inactive.
 * EDT only.
 */
internal class SavePrompt(
    private val anchor: AutofillAnchor,
    private val anchorNow: () -> AutofillAnchor?,
    private val request: SaveRequest,
    private val onChoice: (SaveChoice, String) -> Unit,
) {
    private val overlay = OwnedOverlay(anchor.window, focusable = request.kind == SaveKind.PASSWORD)
    private var answered = false
    private var username by mutableStateOf(request.username)
    private val timeout = Timer(TIMEOUT_MS) { answer(SaveChoice.NOT_NOW) }
    private val watcher = Timer(200) { tick() }

    init {
        overlay.dialog.setContent {
            HostTheme {
                Card { Content() }
            }
        }
    }

    @Composable
    @Suppress("LongMethod")
    private fun Content() {
        val first = remember { FocusRequester() }
        Column(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        answer(SaveChoice.NOT_NOW)
                        true
                    } else {
                        false
                    }
                }.padding(Tokens.space.lg),
            verticalArrangement = Arrangement.spacedBy(Tokens.space.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(
                        when (request.kind) {
                            SaveKind.CARD -> SuggestionKind.CARD
                            SaveKind.ADDRESS -> SuggestionKind.ADDRESS
                            SaveKind.PASSWORD -> SuggestionKind.PASSWORD
                        },
                    ),
                    contentDescription = null,
                    tint = Tokens.colors.signalText,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Tokens.space.sm))
                Text(
                    request.title,
                    color = Tokens.colors.textPrimary,
                    style = Tokens.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (request.kind == SaveKind.PASSWORD) {
                BossTextField(value = username, onValueChange = {
                    username = it
                }, label = "Username", modifier = Modifier.fillMaxWidth().focusRequester(first))
                PasswordField(request.password)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    request.lines.forEachIndexed { i, line ->
                        Text(
                            line,
                            color = if (i == 0) Tokens.colors.textPrimary else Tokens.colors.textSecondary,
                            style = Tokens.type.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (request.allowNever) TextLink("Never for this site") { answer(SaveChoice.NEVER) }
                Spacer(Modifier.weight(1f))
                QuietButton("Not now") { answer(SaveChoice.NOT_NOW) }
                Spacer(Modifier.width(Tokens.space.sm))
                BossPrimaryButton(text = request.saveLabel, onClick = { answer(SaveChoice.SAVE) })
            }
        }
    }

    @Composable
    private fun PasswordField(value: String) {
        var shown by remember { mutableStateOf(false) }
        Column {
            Text(
                "Password",
                color = Tokens.colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Tokens.radius.buttonShape)
                    .background(Tokens.colors.panel)
                    .border(1.dp, Tokens.colors.line, Tokens.radius.buttonShape)
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    textStyle = TextStyle(color = Tokens.colors.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Tokens.colors.signal),
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                Icon(
                    if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (shown) "Hide password" else "Show password",
                    tint = Tokens.colors.textSecondary,
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(Tokens.radius.buttonShape)
                            .clickable { shown = !shown }
                            .padding(6.dp),
                )
            }
        }
    }

    @Composable
    private fun TextLink(
        label: String,
        onClick: () -> Unit,
    ) {
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Text(
            label,
            color = Tokens.colors.textSecondary,
            style =
                Tokens.type.body.copy(
                    fontSize = 12.sp,
                    textDecoration = if (hovered) TextDecoration.Underline else null,
                ),
            modifier = Modifier.hoverable(hover).clickable(onClick = onClick),
        )
    }

    @Composable
    private fun QuietButton(
        label: String,
        onClick: () -> Unit,
    ) {
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Box(
            Modifier
                .clip(Tokens.radius.buttonShape)
                .background(if (hovered) Tokens.colors.signalWash else Tokens.colors.raised)
                .hoverable(hover)
                .clickable(onClick = onClick)
                .padding(horizontal = Tokens.space.md, vertical = Tokens.space.sm),
        ) { Text(label, color = Tokens.colors.textPrimary, fontSize = 13.sp) }
    }

    private val height =
        when (request.kind) {
            SaveKind.PASSWORD -> 252
            else -> 120 + 18 * request.lines.size
        }

    fun show() {
        place(anchor)
        timeout.isRepeats = false
        // Chromium counts a declined card/address as a strike and stops offering after a few, so
        // only an explicit "Not now" (or a newer prompt) may decline one; passwords just ignore.
        if (request.kind == SaveKind.PASSWORD) timeout.start()
        watcher.start()
        tick()
    }

    private fun place(a: AutofillAnchor) {
        val v = a.viewOnScreen
        overlay.place(v.x + v.width - WIDTH - 12, v.y + 8, WIDTH, height)
    }

    private fun tick() {
        if (answered) return
        val now = anchorNow()
        val visible = overlay.ownerActive && now != null && now.window === anchor.window
        if (visible) place(now!!)
        if (overlay.dialog.isVisible != visible) overlay.dialog.isVisible = visible
    }

    fun dismiss() = answer(SaveChoice.NOT_NOW)

    private fun answer(choice: SaveChoice) {
        if (answered) return
        answered = true
        timeout.stop()
        watcher.stop()
        overlay.dispose()
        onChoice(choice, username.trim())
    }

    companion object {
        const val WIDTH = 360
        const val TIMEOUT_MS = 45_000
    }
}

internal fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}
