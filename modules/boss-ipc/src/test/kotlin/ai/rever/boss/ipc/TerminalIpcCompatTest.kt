package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.services.CellAttr
import ai.rever.boss.ipc.proto.services.CellRun
import ai.rever.boss.ipc.proto.services.CellStyle
import ai.rever.boss.ipc.proto.services.CursorShape
import ai.rever.boss.ipc.proto.services.CursorState
import ai.rever.boss.ipc.proto.services.ShellEvent
import ai.rever.boss.ipc.proto.services.ShellEventType
import ai.rever.boss.ipc.proto.services.TerminalGridDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the terminal-proto scaffolding and the 1.0.x ↔ 1.1.x compatibility
 * envelope.
 *
 * Two responsibilities:
 *  1. Pin the cross-version compatibility behaviour at the 1.0.x ↔ 1.1.x
 *     boundary so a future minor bump cannot silently break plugin loading.
 *  2. Round-trip the reserved terminal grid / cursor / shell-event proto
 *     messages to confirm `services/terminal.proto` still generates
 *     correctly. The host does not implement the matching RPCs today
 *     (see issue #743) — `IpcVersion.CURRENT` is intentionally 1.0.0 —
 *     but the bindings remain so re-enabling the streaming surface in a
 *     future minor bump is a one-line change.
 */
class TerminalIpcCompatTest {

    @Test
    fun `legacy terminal plugin built against 1_0_0 still loads on 1_1_x host`() {
        val r = IpcVersion.isCompatible(
            runtimeMinIpcVersion = "1.0.0",
            hostIpcVersion = "1.1.0",
        )
        assertEquals(IpcVersion.CompatResult.Compatible, r)
    }

    @Test
    fun `new terminal plugin requiring 1_1_0 is rejected by 1_0_x host with actionable message`() {
        val r = IpcVersion.isCompatible(
            runtimeMinIpcVersion = "1.1.0",
            hostIpcVersion = "1.0.5",
        )
        val inc = r as? IpcVersion.CompatResult.Incompatible
            ?: error("expected Incompatible, got $r")
        assertTrue(inc.reason.contains("Update BossConsole"), "got: ${inc.reason}")
    }

    @Test
    fun `TerminalGridDelta round-trips with grapheme starts and style attrs`() {
        val original = TerminalGridDelta.newBuilder()
            .setSessionId("sess-1")
            .setRevision(42)
            .setCols(80)
            .setRows(24)
            .setIsFullRedraw(false)
            .setIsAlternateBuffer(false)
            .addRowsChanged(
                CellRun.newBuilder()
                    .setRow(3)
                    .setCol(0)
                    .setText("héllo 🌍")
                    .addAllGraphemeStarts(listOf(0, 1, 3, 4, 5, 6, 7))
                    .setStyle(
                        CellStyle.newBuilder()
                            .setFgRgba(0xFFFFFFFFu.toInt())
                            .setBgRgba(0x000000FFu.toInt())
                            .setAttrs(
                                CellAttr.CELL_ATTR_BOLD_VALUE or
                                    CellAttr.CELL_ATTR_UNDERLINE_VALUE,
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        val roundTripped = TerminalGridDelta.parseFrom(original.toByteArray())

        assertEquals("sess-1", roundTripped.sessionId)
        assertEquals(42L, roundTripped.revision)
        assertEquals(80, roundTripped.cols)
        assertEquals(1, roundTripped.rowsChangedCount)
        val run = roundTripped.getRowsChanged(0)
        assertEquals("héllo 🌍", run.text)
        assertEquals(listOf(0, 1, 3, 4, 5, 6, 7), run.graphemeStartsList)
        val expectedAttrs = CellAttr.CELL_ATTR_BOLD_VALUE or CellAttr.CELL_ATTR_UNDERLINE_VALUE
        assertEquals(expectedAttrs, run.style.attrs)
    }

    @Test
    fun `CursorState carries shape and blink hints`() {
        val original = CursorState.newBuilder()
            .setSessionId("sess-1")
            .setRow(5)
            .setCol(12)
            .setVisible(true)
            .setShape(CursorShape.CURSOR_SHAPE_BAR)
            .setBlink(true)
            .build()

        val roundTripped = CursorState.parseFrom(original.toByteArray())
        assertEquals(CursorShape.CURSOR_SHAPE_BAR, roundTripped.shape)
        assertTrue(roundTripped.blink)
        assertEquals(5, roundTripped.row)
        assertEquals(12, roundTripped.col)
    }

    @Test
    fun `ShellEvent encodes prompt-and-command lifecycle`() {
        val finished = ShellEvent.newBuilder()
            .setSessionId("sess-1")
            .setType(ShellEventType.SHELL_EVENT_TYPE_COMMAND_FINISHED)
            .setExitCode(0)
            .setTimestampMs(1_234_567L)
            .build()

        val roundTripped = ShellEvent.parseFrom(finished.toByteArray())
        assertEquals(ShellEventType.SHELL_EVENT_TYPE_COMMAND_FINISHED, roundTripped.type)
        assertEquals(0, roundTripped.exitCode)
        assertEquals(1_234_567L, roundTripped.timestampMs)
    }
}
