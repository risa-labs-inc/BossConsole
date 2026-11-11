package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.plugin.browser.FluckEngine
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Where a browser view sits on screen, in AWT logical units. */
internal data class AutofillAnchor(
    val window: Window,
    val viewOnScreen: Rectangle,
)

internal data class SuggestionRow(
    val primary: String,
    val secondary: String,
)

private object Palette {
    val dark get() = FluckEngine.isColorSchemeDark
    val bg get() = if (dark) Color(0x24, 0x26, 0x2B) else Color(0xFF, 0xFF, 0xFF)
    val fg get() = if (dark) Color(0xE6, 0xE7, 0xEA) else Color(0x1F, 0x23, 0x28)
    val muted get() = if (dark) Color(0x9A, 0x9E, 0xA6) else Color(0x65, 0x6D, 0x76)
    val border get() = if (dark) Color(0x3A, 0x3D, 0x44) else Color(0xD0, 0xD7, 0xDE)
    val selection get() = if (dark) Color(0x2F, 0x4A, 0x6E) else Color(0xDD, 0xEB, 0xFB)
    val accent = Color(0x2F, 0x81, 0xF7)
}

private fun clampToScreen(
    p: Point,
    size: Dimension,
): Point {
    val screen =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(p) } ?: return p
    return Point(
        p.x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x)),
        p.y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y)),
    )
}

/**
 * Chromium's autofill dropdown, drawn as an owned heavyweight window over the native browser
 * surface (the jxbrowser-compose popup renders underneath it in HARDWARE_ACCELERATED). Never
 * focusable, so the page field keeps focus and typing; keys arrive via [moveSelection]/[acceptSelected].
 * EDT only.
 */
internal class SuggestionsWindow(
    private val anchor: AutofillAnchor,
    private val onAccept: (Int) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val model = DefaultListModel<SuggestionRow>()
    private val list = JList(model)
    private val window = JWindow(anchor.window)
    private var origin = Point()
    private var closed = false

    /** Read from the key callback thread. */
    @Volatile var hasSelection = false
        private set

    private val ownerFocus =
        object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) = cancel()

            override fun windowDeactivated(e: WindowEvent) = cancel()
        }
    private val ownerMoved =
        object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = cancel()

            override fun componentResized(e: ComponentEvent) = cancel()
        }

    init {
        window.focusableWindowState = false
        window.type = Window.Type.POPUP
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener { hasSelection = list.selectedIndex >= 0 }
        list.background = Palette.bg
        list.cellRenderer = Renderer()
        list.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val i = list.locationToIndex(e.point)
                    if (i >= 0 && list.getCellBounds(i, i)?.contains(e.point) == true) list.selectedIndex = i
                }
            },
        )
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val i = list.locationToIndex(e.point)
                    if (i >= 0 && list.getCellBounds(i, i)?.contains(e.point) == true) accept(i)
                }
            },
        )
        list.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createLineBorder(Palette.border)
        panel.background = Palette.bg
        panel.add(list, BorderLayout.CENTER)
        window.contentPane = panel
        anchor.window.addWindowFocusListener(ownerFocus)
        anchor.window.addWindowListener(ownerFocus)
        anchor.window.addComponentListener(ownerMoved)
    }

    val isOpen get() = !closed

    /** [locationInView] is Chromium's popup point in DIP relative to the browser view. */
    fun show(
        rows: List<SuggestionRow>,
        locationInView: Point,
    ) {
        origin = Point(anchor.viewOnScreen.x + locationInView.x, anchor.viewOnScreen.y + locationInView.y)
        update(rows)
        window.isVisible = true
    }

    fun update(rows: List<SuggestionRow>) {
        if (closed) return
        if (rows.isEmpty()) return cancel()
        model.clear()
        rows.forEach(model::addElement)
        list.selectedIndex = -1
        val visible = rows.size.coerceAtMost(MAX_ROWS)
        val size = Dimension(WIDTH, visible * ROW_HEIGHT + 2)
        window.size = size
        window.location = clampToScreen(origin, size)
    }

    fun moveSelection(delta: Int) {
        if (model.isEmpty) return
        val n = model.size()
        val cur = list.selectedIndex
        list.selectedIndex =
            when {
                cur < 0 && delta > 0 -> 0
                cur < 0 -> n - 1
                else -> (cur + delta + n) % n
            }
    }

    /** True when a row was selected and accepted. */
    fun acceptSelected(): Boolean {
        val i = list.selectedIndex
        if (i < 0) return false
        accept(i)
        return true
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
        anchor.window.removeWindowFocusListener(ownerFocus)
        anchor.window.removeWindowListener(ownerFocus)
        anchor.window.removeComponentListener(ownerMoved)
        window.isVisible = false
        window.dispose()
    }

    private class Renderer : ListCellRenderer<SuggestionRow> {
        private val panel = JPanel()
        private val primary = JLabel()
        private val secondary = JLabel()

        init {
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.border = BorderFactory.createEmptyBorder(3, 10, 3, 10)
            primary.font = primary.font.deriveFont(Font.PLAIN, 13f)
            secondary.font = secondary.font.deriveFont(Font.PLAIN, 11f)
            panel.add(primary)
            panel.add(secondary)
            panel.preferredSize = Dimension(WIDTH, ROW_HEIGHT)
        }

        override fun getListCellRendererComponent(
            list: JList<out SuggestionRow>,
            value: SuggestionRow,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            panel.background = if (isSelected) Palette.selection else Palette.bg
            primary.foreground = Palette.fg
            secondary.foreground = Palette.muted
            primary.text = value.primary
            secondary.text = value.secondary
            secondary.isVisible = value.secondary.isNotEmpty()
            return panel
        }
    }

    companion object {
        const val WIDTH = 300
        const val ROW_HEIGHT = 40
        const val MAX_ROWS = 8
    }
}

internal enum class SaveChoice { SAVE, NEVER, NOT_NOW }

/**
 * "Save password?" bubble at the top-right of the browser view. Answers exactly once; times out
 * as [SaveChoice.NOT_NOW]. EDT only.
 */
internal class SavePrompt(
    anchor: AutofillAnchor,
    title: String,
    detail: String,
    saveLabel: String,
    allowNever: Boolean,
    private val onChoice: (SaveChoice) -> Unit,
) {
    private val window = JWindow(anchor.window)
    private var answered = false
    private val timer = Timer(TIMEOUT_MS) { answer(SaveChoice.NOT_NOW) }

    init {
        window.focusableWindowState = false
        val panel = JPanel(BorderLayout(0, 8))
        panel.background = Palette.bg
        panel.border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.border),
                BorderFactory.createEmptyBorder(12, 14, 10, 14),
            )
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(
            JLabel(title).apply {
                foreground = Palette.fg
                font = font.deriveFont(Font.BOLD, 13f)
            },
        )
        if (detail.isNotEmpty()) {
            text.add(
                JLabel(detail).apply {
                    foreground = Palette.muted
                    font = font.deriveFont(Font.PLAIN, 12f)
                },
            )
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        buttons.isOpaque = false
        if (allowNever) buttons.add(button("Never for this site", primary = false) { answer(SaveChoice.NEVER) })
        buttons.add(button("Not now", primary = false) { answer(SaveChoice.NOT_NOW) })
        buttons.add(button(saveLabel, primary = true) { answer(SaveChoice.SAVE) })
        panel.add(text, BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.SOUTH)
        window.contentPane = panel
        window.pack()
        val size = Dimension(window.width.coerceAtLeast(320), window.height)
        window.size = size
        val view = anchor.viewOnScreen
        window.location = clampToScreen(Point(view.x + view.width - size.width - 12, view.y + 8), size)
    }

    fun show() {
        window.isVisible = true
        timer.isRepeats = false
        timer.start()
    }

    fun dismiss() = answer(SaveChoice.NOT_NOW)

    private fun answer(choice: SaveChoice) {
        if (answered) return
        answered = true
        timer.stop()
        window.isVisible = false
        window.dispose()
        onChoice(choice)
    }

    private fun button(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
    ) = JButton(label).apply {
        isFocusable = false
        isFocusPainted = false
        isContentAreaFilled = true
        isOpaque = true
        background = if (primary) Palette.accent else Palette.bg
        foreground = if (primary) Color.WHITE else Palette.fg
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (primary) Palette.accent else Palette.border),
                BorderFactory.createEmptyBorder(4, 10, 4, 10),
            )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
    }

    companion object {
        const val TIMEOUT_MS = 45_000
    }
}

internal fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}
