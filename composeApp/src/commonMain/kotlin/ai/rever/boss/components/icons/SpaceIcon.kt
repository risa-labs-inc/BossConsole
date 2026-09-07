package ai.rever.boss.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A Space: its panes, on a card tipped back a few degrees.
 *
 * `Icons.Outlined.SpaceDashboard`'s division - a big pane beside two stacked ones - on a face whose
 * top edge is narrower than its bottom, with a thin shade under the bottom edge. The division is
 * the same one, because that is the shape people learned; the tip is a few degrees of perspective,
 * not a projection.
 *
 * **The geometry is one projection, written once.** `at(wx, wy)` maps the unit square a Space's
 * panes live in to the card's face, and every point below is a call to it - so the three panes and
 * the shade under them cannot disagree about where the card is, which is what happens when the
 * corners are typed out by hand. All the tip lives in that one function: the face's width is
 * interpolated from [TOP_WIDTH] to [BOTTOM_WIDTH] with `wy`, so the panes inherit it for free and
 * nothing else in the file knows the card is tipped. The Top of Mind plugin has a copy of this file
 * for its own footer button; see the note at the bottom.
 *
 * Two things fall out of that interpolation that are worth knowing before changing the numbers:
 *
 * - **The long division stays vertical**, because it sits at `wx = 0.5` and the face is symmetric
 *   about x = 12, so its width term multiplies by zero at every depth. Only the outer edges lean.
 *   That is most of why this reads as a tip rather than as a wonky rectangle.
 * - **The lean is 6.4 degrees**, from 1.4 units of horizontal run over 12.4 of drop per side.
 *   Enough to see, not enough to stop a pane reading as a pane.
 *
 * **This was a full isometric plate first, and the reason it is not one any more is worth keeping.**
 * The division used to be tipped onto the 2:1 plane the floors view draws. That read as a diagram
 * rather than an icon at the sizes it ships at, and it cannot be dialled down: a plate has no face,
 * so shallowing the tip flattens the whole glyph towards a lozenge rather than making it less
 * isometric, and at a fifth of the depth the panes are unreadable slivers. The depth had to come
 * off a card that faces the reader, which is what this is.
 *
 * What else was rendered and lost, so nobody re-walks it:
 *
 * - **A shade wrapping the bottom AND right edges**, which reads as the most solid of the lot and
 *   keeps every pane a true rectangle. It is the stronger glyph at 13dp; the tip was preferred.
 * - **A band under the bottom edge with no tip at all.** Reads as an underline under the card
 *   rather than as the card having a bottom, and leaves the glyph bottom-heavy.
 * - **The floors view's own model, thin faces receding back and up.** A dim band ABOVE the outline
 *   reads as a glow or a smudge, on a dark ground and on a light one alike: the eye only takes an
 *   offset shade for depth where a shadow would fall. The floors view keeps it because a storey is
 *   a 26dp bar with room for the faces to be faces.
 * - **A shear**, which leans the verticals and reads as italic rather than as depth.
 *
 * **The shade is a FILL, and it is welded to the bottom edge.** A fill costs no polylines and has
 * no caps to grow spurs where they meet, which is what sank the first version of the isometric one
 * (eight stroked lines in a 20px box, divisions closed into a blob). It starts at the bottom
 * stroke's CENTRE line rather than outside it, so [RISE] of 2.2 buys 1.4 units of visible band -
 * that is the render that was chosen, and moving the band outside the stroke would thicken it.
 *
 * The plate and its divisions stay STROKES, unlike most Material icons: the divisions are the
 * content here, and a filled card would need a second colour to show them, which an `Icon` tint
 * cannot give it.
 *
 * **Judged at 13dp to 20dp, which is every size it ships at.** The plugin's footer draws it at
 * 20dp and its tab menu smaller again; the host's Space button is a `BossActionButton` `leftIcon`,
 * which is 16dp, or 13dp compact. The empty state is the only large one, and large always looks
 * fine. [STROKE] is 1.6 rather than the 1.4 the tipped plate used because at these sizes the glyph
 * sits in a row of Material outlined icons (Save, Delete, Search) whose strokes are 2 units, and
 * 1.4 read as a lighter class of thing beside them.
 *
 * **Known: the ink sits 0.4 of a unit above the viewport's centre.** Measured off a 10x render
 * rather than derived - the glyph spans 2.60..21.40 across, which is centred, and 3.90..19.30 down,
 * which is not, because the top edge contributes half a stroke where the bottom contributes the
 * whole band. That is 0.33dp at 20dp, under one physical pixel on a retina display, and it is the
 * placement of the render that was approved. Recentring is [FACE_TOP] plus 0.4 and nothing else,
 * if it ever looks wrong beside another icon.
 */
val SpaceIcon: ImageVector by lazy {
    /**
     * Where a point of the unit square lands on the card's face.
     *
     * x runs right and y runs down, so (0,0) is the top left corner of the face and (1,1) the
     * bottom right one. The face's WIDTH is a function of y, which is the whole tip: a pane keeps
     * horizontal top and bottom edges and leans its outer one, so it still reads as a pane.
     */
    fun at(
        wx: Float,
        wy: Float,
    ) = Pair(
        12f + (wx - 0.5f) * (TOP_WIDTH + wy * (BOTTOM_WIDTH - TOP_WIDTH)),
        FACE_TOP + wy * FACE_HEIGHT,
    )

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

            // THE DEPTH: the card's own bottom, as one fill under the bottom edge. It is the near
            // face of a card tipped away from the reader, so it belongs on that edge alone -
            // shading the sides as well would be an outline rather than a solid, and shading the
            // top would put a shadow where the light is coming from.
            path(fill = ink, fillAlpha = 0.35f) {
                val bottomLeft = at(0f, 1f)
                val bottomRight = at(1f, 1f)
                moveTo(bottomLeft.first, bottomLeft.second)
                lineTo(bottomRight.first, bottomRight.second)
                lineTo(bottomRight.first, bottomRight.second + RISE)
                lineTo(bottomLeft.first, bottomLeft.second + RISE)
                close()
            }

            // The card, and the divisions that make it a Space rather than a tile: the long one
            // halves it, the short one splits the far half in two.
            line(true, at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f))
            line(false, at(0.5f, 0f), at(0.5f, 1f))
            line(false, at(0.5f, 0.5f), at(1f, 0.5f))
        }.build()
}

/** The face's top edge, and its bottom one. The difference between them is the whole tip. */
private const val TOP_WIDTH = 14.2f
private const val BOTTOM_WIDTH = 17f

private const val FACE_HEIGHT = 12.4f
private const val FACE_TOP = 4.7f

/** The card's line weight, chosen against the Material outlined icons it sits beside. */
private const val STROKE = 1.6f

/** How deep the card's near face is. See the note on it: 2.2 here is 1.4 units you can see. */
private const val RISE = 2.2f

// The Top of Mind plugin has its own copy of this file, because a plugin cannot import a host
// internal and the shared surface it COULD live in - `ai.rever.boss.plugin.ui` in the api jar - is
// served parent-first, so putting it there would need an api release and a `minApiVersion` gate for
// an icon. The two copies must be changed together; if a third consumer ever appears, that is the
// point at which the api release is worth it.
