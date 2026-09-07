package ai.rever.boss.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A Space: its panes, on a card that stands a little off the ground.
 *
 * `Icons.Outlined.SpaceDashboard`'s division - a big pane beside two stacked ones - drawn face on,
 * with a thin shade wrapping its bottom and right edges. The division is the same one, because
 * that is the shape people learned; the depth is a hint, not a projection.
 *
 * **The geometry is one projection, written once.** `at(wx, wy)` maps the unit square a Space's
 * panes live in to the card's face, and every point below is a call to it - so the three panes and
 * the shade under them cannot disagree about where the card is, which is what happens when the
 * corners are typed out by hand. The Top of Mind plugin has a copy of this file for its own footer
 * button; see the note at the bottom.
 *
 * **This was an isometric plate first, and the reason it is not one any more is worth keeping.**
 * The division used to be tipped onto the 2:1 plane the floors view draws. Reading it back, that is
 * too much staging for a 16dp button, and it cannot be dialled down: a plate has no face, so
 * shallowing the tip flattens the whole glyph towards a lozenge rather than making it less
 * isometric - at a fifth of the depth the panes are unreadable slivers. Depth had to come off a
 * card that faces the reader, which is what this is.
 *
 * Three things the renders settled, none of which was obvious on paper:
 *
 * - **The shade wraps the corner.** A band under the bottom edge alone - "thickness, no rotation",
 *   the most restrained version there is - reads as an underline under the card rather than as the
 *   card having a bottom, and leaves the glyph bottom-heavy. Turning it up the right edge as one
 *   L is what makes it read as a solid.
 * - **It goes down and right, never up.** The floors view puts a workspace's thin faces receding
 *   back-and-up off its front face, and copying that here fails: a dim band ABOVE the outline
 *   reads as a glow or a smudge, on a dark ground and on a light one, because the eye only takes
 *   an offset shade for depth when it falls where a shadow would.
 * - **It is a FILL, and it sits outside the stroke.** A fill costs no polylines and has no caps to
 *   grow spurs where they meet, which is what sank the first version of the isometric one (eight
 *   stroked lines in a 20px box, divisions closed into a blob). Starting the band at the stroke's
 *   OUTER edge rather than its centre line is what makes [LIFT] the thickness you actually see.
 *
 * The plate and its divisions stay STROKES, unlike most Material icons: the divisions are the
 * content here, and a filled card would need a second colour to show them, which an `Icon` tint
 * cannot give it.
 *
 * **Judged at 13dp to 20dp, which is every size it ships at.** The plugin's footer draws it at
 * 20dp and its tab menu smaller again; the host's Space button is a `BossActionButton` `leftIcon`,
 * which is 16dp, or 13dp compact. The empty state is the only large one, and large always looks
 * fine. [STROKE] is 1.6 rather than the 1.4 the tipped version used because at these sizes the
 * glyph sits in a row of Material outlined icons (Save, Delete, Search) whose strokes are 2 units,
 * and 1.4 read as a lighter class of thing beside them.
 */
val SpaceIcon: ImageVector by lazy {
    // The card's face. Its position puts the WHOLE glyph - stroke and shade included - centred in
    // the 24 viewport, spanning 2.9..21.1 across and 3.4..20.6 down, which is the air Material's
    // own icons leave.
    val faceWidth = 15f
    val faceHeight = 14f
    val faceLeft = 12f - (faceWidth + STROKE + LIFT) / 2f + STROKE / 2f
    val faceTop = 12f - (faceHeight + STROKE + LIFT) / 2f + STROKE / 2f

    /**
     * Where a point of the unit square lands on the card's face.
     *
     * x runs right and y runs down, so (0,0) is the top left corner of the face and (1,1) the
     * bottom right one. There is no projection: a pane is the rectangle it looks like, which is
     * the whole point of the shape.
     */
    fun at(
        wx: Float,
        wy: Float,
    ) = Pair(faceLeft + wx * faceWidth, faceTop + wy * faceHeight)

    ImageVector
        .Builder(
            name = "SpaceIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val ink = SolidColor(Color.Black)

            /** One polyline over points from [at], never a coordinate typed directly. */
            fun line(
                closed: Boolean,
                vararg points: Pair<Float, Float>,
            ) {
                path(
                    stroke = ink,
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    val (x, y) = points.first()
                    moveTo(x, y)
                    points.drop(1).forEach { (px, py) -> lineTo(px, py) }
                    if (closed) close()
                }
            }

            // The three corners the shade touches, on the OUTER edge of the stroke - half a stroke
            // out from the geometry, so the band's visible width is exactly LIFT.
            val half = STROKE / 2f
            val outerTopRight = at(1f, 0f).let { (x, y) -> (x + half) to (y - half) }
            val outerBottomRight = at(1f, 1f).let { (x, y) -> (x + half) to (y + half) }
            val outerBottomLeft = at(0f, 1f).let { (x, y) -> (x - half) to (y + half) }

            fun lifted(point: Pair<Float, Float>) = (point.first + LIFT) to (point.second + LIFT)

            // THE DEPTH: one L-shaped band down the right edge and along the bottom, as a single
            // fill. Only these two edges exist, because a card lit from the top left casts on its
            // bottom and right and nowhere else - shading all four would be an outline, not a
            // solid.
            path(fill = ink, fillAlpha = 0.4f) {
                moveTo(outerTopRight.first, outerTopRight.second)
                lifted(outerTopRight).let { lineTo(it.first, it.second) }
                lifted(outerBottomRight).let { lineTo(it.first, it.second) }
                lifted(outerBottomLeft).let { lineTo(it.first, it.second) }
                lineTo(outerBottomLeft.first, outerBottomLeft.second)
                lineTo(outerBottomRight.first, outerBottomRight.second)
                close()
            }

            // The card, and the divisions that make it a Space rather than a tile: the long one
            // halves it, the short one splits the far half in two.
            line(true, at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f))
            line(false, at(0.5f, 0f), at(0.5f, 1f))
            line(false, at(0.5f, 0.5f), at(1f, 0.5f))
        }.build()
}

/** The card's line weight, chosen against the Material outlined icons it sits beside. */
private const val STROKE = 1.6f

/** How far the card stands off the ground. A HINT: a unit and a half, not a projection. */
private const val LIFT = 1.5f

// The Top of Mind plugin has its own copy of this file, because a plugin cannot import a host
// internal and the shared surface it COULD live in - `ai.rever.boss.plugin.ui` in the api jar - is
// served parent-first, so putting it there would need an api release and a `minApiVersion` gate for
// an icon. The two copies must be changed together; if a third consumer ever appears, that is the
// point at which the api release is worth it.
