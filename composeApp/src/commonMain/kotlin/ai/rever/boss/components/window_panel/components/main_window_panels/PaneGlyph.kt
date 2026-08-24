package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.window_panel.PanelBounds

/** Everything within this of an edge counts as touching it - dividers are a pixel or two wide. */
private const val EDGE_TOLERANCE = 0.03f

/**
 * Where a pane sits inside the split area, as fractions of it.
 *
 * A window-level tab bar lists several panes' tabs in one column, and a rule between two groups
 * says only that they are different panes - not which is which. This is what a group header draws
 * to answer that, and it is taken from the panes' MEASURED rectangles rather than from the split
 * tree, so it stays true for any arrangement, nested ones included, and follows a divider as it is
 * dragged.
 */
internal data class PaneGlyph(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val touchesLeft: Boolean get() = left <= EDGE_TOLERANCE
    val touchesRight: Boolean get() = right >= 1f - EDGE_TOLERANCE
    val touchesTop: Boolean get() = top <= EDGE_TOLERANCE
    val touchesBottom: Boolean get() = bottom >= 1f - EDGE_TOLERANCE

    /** Reaches both side edges, so no horizontal word describes it. */
    val spansWidth: Boolean get() = touchesLeft && touchesRight

    /** Reaches both top and bottom, so no vertical word describes it. */
    val spansHeight: Boolean get() = touchesTop && touchesBottom
}

/**
 * [bounds] as a fraction of the area every pane in [all] covers between them.
 *
 * Null when that area is degenerate, which is what an unmeasured layout looks like - and a caller
 * that draws nothing then is right, where one drawing a glyph from garbage is not.
 */
internal fun paneGlyphFor(
    bounds: PanelBounds,
    all: Collection<PanelBounds>,
): PaneGlyph? {
    val area = boundingArea(all) ?: return null
    return PaneGlyph(
        left = ((bounds.left - area.left) / area.width).coerceIn(0f, 1f),
        top = ((bounds.top - area.top) / area.height).coerceIn(0f, 1f),
        right = ((bounds.right - area.left) / area.width).coerceIn(0f, 1f),
        bottom = ((bounds.bottom - area.top) / area.height).coerceIn(0f, 1f),
    )
}

/**
 * The rectangle every pane sits inside, or null when it is not a real area yet.
 *
 * Not the window and not the split area's own measured bounds: the panes' union is the one
 * rectangle that is guaranteed to be measured whenever the panes are, and it excludes the tab
 * bar itself, which is what makes a full-height pane read as full height.
 */
private fun boundingArea(all: Collection<PanelBounds>): PanelBounds? {
    if (all.isEmpty()) return null
    val left = all.minOf { it.left }
    val top = all.minOf { it.top }
    val width = all.maxOf { it.right } - left
    val height = all.maxOf { it.bottom } - top
    return if (isUsable(width) && isUsable(height)) PanelBounds(left, top, width, height) else null
}

private fun isUsable(extent: Float): Boolean = extent > 0f && extent.isFinite()

/**
 * A name for a pane's position, or its number when no honest name fits.
 *
 * Two kinds of name, and a pane qualifies for at most one. A pane that runs the full height of
 * the split area and touches one side is "Left" or "Right"; one that runs the full width is "Top"
 * or "Bottom". A pane in a nested split spans neither axis, but it still sits in a corner of the
 * area, so "Top right" says exactly where it is.
 *
 * A pane that touches nothing useful - the middle column of a three-way split, a pane nested two
 * deep - is numbered. Inventing a name for it would be worse than a number beside a glyph that
 * shows precisely where it is, which is why the glyph is the part that always renders.
 *
 * @param index the pane's place in visual order, zero-based.
 */
internal fun paneLabel(
    index: Int,
    glyph: PaneGlyph?,
): String {
    val named = glyph?.let { edgeName(it) ?: cornerName(it) }
    return named ?: "Pane ${index + 1}"
}

/**
 * A name for a pane that runs the full length of one axis. Null when none applies.
 *
 * The first case is a pane covering the whole area. With more than one pane that cannot happen,
 * and with one there is no header to label - but a name there would be a claim about a split that
 * does not exist.
 */
private fun edgeName(glyph: PaneGlyph): String? =
    when {
        glyph.spansWidth && glyph.spansHeight -> null
        glyph.spansHeight && glyph.touchesLeft -> "Left"
        glyph.spansHeight && glyph.touchesRight -> "Right"
        glyph.spansWidth && glyph.touchesTop -> "Top"
        glyph.spansWidth && glyph.touchesBottom -> "Bottom"
        else -> null
    }

/** A name for a pane that spans neither axis but sits in a corner. Null when none applies. */
private fun cornerName(glyph: PaneGlyph): String? =
    when {
        glyph.spansWidth || glyph.spansHeight -> null
        glyph.touchesTop && glyph.touchesLeft -> "Top left"
        glyph.touchesTop && glyph.touchesRight -> "Top right"
        glyph.touchesBottom && glyph.touchesLeft -> "Bottom left"
        glyph.touchesBottom && glyph.touchesRight -> "Bottom right"
        else -> null
    }
