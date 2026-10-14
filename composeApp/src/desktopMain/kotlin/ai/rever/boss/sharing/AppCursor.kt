package ai.rever.boss.sharing

import java.awt.Cursor

/** Only standard shapes cross the wire. Custom cursor images never become CSS URLs. */
internal fun appCursorCss(cursor: Cursor?): String =
    when (cursor?.type) {
        Cursor.HAND_CURSOR -> "pointer"
        Cursor.TEXT_CURSOR -> "text"
        Cursor.CROSSHAIR_CURSOR -> "crosshair"
        Cursor.WAIT_CURSOR -> "wait"
        Cursor.MOVE_CURSOR -> "move"
        Cursor.E_RESIZE_CURSOR, Cursor.W_RESIZE_CURSOR -> "ew-resize"
        Cursor.N_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR -> "ns-resize"
        Cursor.NE_RESIZE_CURSOR, Cursor.SW_RESIZE_CURSOR -> "nesw-resize"
        Cursor.NW_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR -> "nwse-resize"
        else -> "default"
    }
