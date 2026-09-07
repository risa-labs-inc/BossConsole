package ai.rever.boss.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A Space: its panes, seen from a corner.
 *
 * `Icons.Outlined.SpaceDashboard` face on - a big pane beside two stacked ones - tipped onto an
 * isometric plane and given a little thickness. The division is the same one, because that is the
 * shape people learned; only the angle is new.
 *
 * **The geometry is one projection, written once.** `at(wx, wy)` maps the unit square a Space's
 * panes live in to the plate, and every point below is a call to it - so the three panes cannot
 * disagree about where the plate is, which is what happens when isometric points are typed out by
 * hand. The projection is the same one the Top of Mind floors view uses, and the plugin has a copy
 * of this file for its own footer button; see the note at the bottom.
 *
 * The plate and its divisions are STROKES, unlike most Material icons: the divisions are the
 * content here, and a filled plate would need a second colour to show them, which an `Icon` tint
 * cannot give it. The thickness underneath is a fill at low alpha, which costs no lines - see the
 * note on it, and the render that made the difference obvious.
 */
val SpaceIcon: ImageVector by lazy {
    // Half-extents of the plate on screen, and where its back corner sits. Width comes out
    // 2.5..21.5 and height 6..18.1 in the 24 viewport, so the glyph is centred on both axes with
    // the same air Material's own icons leave.
    val halfWidth = 9.5f
    val halfDepth = 4.75f
    val centreX = 12f
    val backY = 6.1f

    /** The plate's thickness. A HINT, not a slab: more line in a 20dp box turns into mush. */
    val rise = 2.2f

    /**
     * Where a point of the unit square lands on the plate.
     *
     * x runs to the lower right and y to the lower left, so (0,0) is the back corner and (1,1) the
     * front one. Depth is half of width, which is the flattened isometric the whole app draws.
     */
    fun at(
        wx: Float,
        wy: Float,
    ) = Pair(centreX + (wx - wy) * halfWidth, backY + (wx + wy) * halfDepth)

    ImageVector
        .Builder(
            name = "SpaceIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val ink = SolidColor(Color.Black)

            /** One open polyline. Every coordinate goes through [at], never typed directly. */
            fun line(vararg points: Pair<Float, Float>) {
                path(
                    stroke = ink,
                    strokeLineWidth = 1.4f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    val (x, y) = points.first()
                    moveTo(x, y)
                    points.drop(1).forEach { (px, py) -> lineTo(px, py) }
                }
            }

            fun dropped(point: Pair<Float, Float>) = point.first to point.second + rise

            val back = at(0f, 0f)
            val right = at(1f, 0f)
            val front = at(1f, 1f)
            val left = at(0f, 1f)

            // THE THICKNESS, as one filled band rather than three drops and a bottom edge.
            //
            // Rendered at the 20dp this actually ships at, the stroked version was eight polylines
            // in a 20px box: the divisions closed up into a blob and the corner drops grew spurs
            // where their round caps met the bottom edge. A fill has no caps and adds no lines, so
            // the plate keeps the whole line budget. Only the two FRONT faces exist from this
            // corner; the back corner has no drop, which is what stops the glyph reading as a
            // wireframe of a box rather than a solid seen from outside.
            path(fill = ink, fillAlpha = 0.35f) {
                moveTo(left.first, left.second)
                lineTo(front.first, front.second)
                lineTo(right.first, right.second)
                dropped(right).let { lineTo(it.first, it.second) }
                dropped(front).let { lineTo(it.first, it.second) }
                dropped(left).let { lineTo(it.first, it.second) }
                close()
            }

            // The plate, and the divisions that make it a Space rather than a tile: the long one
            // halves it, the short one splits the far half in two.
            line(back, right, front, left, back)
            line(at(0.5f, 0f), at(0.5f, 1f))
            line(at(0.5f, 0.5f), at(1f, 0.5f))
        }.build()
}

// The Top of Mind plugin has its own copy of this file, because a plugin cannot import a host
// internal and the shared surface it COULD live in - `ai.rever.boss.plugin.ui` in the api jar - is
// served parent-first, so putting it there would need an api release and a `minApiVersion` gate for
// an icon. The two copies must be changed together; if a third consumer ever appears, that is the
// point at which the api release is worth it.
