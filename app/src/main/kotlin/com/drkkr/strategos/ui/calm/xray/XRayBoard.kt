package com.drkkr.strategos.ui.calm.xray

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.drkkr.strategos.domain.agent.MoveKind
import com.drkkr.strategos.domain.agent.XRayRow
import com.drkkr.strategos.ui.calm.theme.CalmFonts

/** Gap between blocks, in tile units (1.0 = touching). */
private const val GAP = 1.16f
private const val COLS = 3
/** Canvas height in tile widths: room above for the tallest block, the pawn and its label; labels below. */
private const val TOP_ROOM = 2.0f
private const val BOTTOM_ROOM = 0.9f
private const val SIDE_ROOM = 0.6f

private class Faces(val top: Color, val left: Color, val right: Color)

private val LO = Color(0xFF762C2A)
private val MID = Color(0xFF383846)
private val HI = Color(0xFF247656)

/** Block colour by score: dark coral (0) → slate (50) → green (100). The number is always printed too. */
private fun shade(score: Float): Color {
    val t = score.coerceIn(0f, 100f)
    return if (t < 50f) lerp(LO, MID, t / 50f) else lerp(MID, HI, (t - 50f) / 50f)
}

private fun faces(base: Color) = Faces(lerp(base, Color.White, .2f), base, lerp(base, Color.Black, .32f))
private val BLEND = Color(0xFF4C4028)
private val AMBER_SIDE = Color(0xFFA3711F)
private val CRIMSON_SIDE = Color(0xFF962626)

/**
 * The payoff grid as a landscape (board 07, slide 2): your moves A–C run front-right, the counterpart's two
 * replies and your blended score run front-left, and each block's height is its score out of 100.
 *
 * Drawn in one Canvas with a 2:1 projection (no graphicsLayer rotation, so the printed scores stay upright):
 * screen x = (x − y) · w, screen y = (x + y) · w/2 − z, painted back to front.
 *
 * [blend] is each row's exact score at the current odds, so the amber column moves smoothly under the slider.
 * [coach] is the engine's row; when the lead has moved away from it, it keeps a dashed outline.
 */
@Composable
internal fun IsoBoard(
    rows: List<XRayRow>,
    blend: List<Float>,
    best: Int,
    coach: Int,
    selected: Int,
    still: Boolean,
    description: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val n = rows.size
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)

    // Animated state, one value per cell. Kept in composition so the Canvas only reads floats.
    val heights = List(n * COLS) { k ->
        val i = k / COLS; val j = k % COLS
        val r = rows[i]
        val v = when (j) { 0 -> r.well.toFloat(); 1 -> r.bad.toFloat(); else -> blend[i] }
        val sunk = selected == i && r.kind == MoveKind.BACKFIRES
        val target = (0.1f + 1.4f * v / 100f) * (if (sunk) 0.45f else 1f) + (if (j == 2 && i == best) 0.35f else 0f)
        // The score column follows the finger, so it gets a short tween; everything else settles in 240 ms.
        animateFloatAsState(target, tween(if (still) 0 else if (j == 2) 140 else 240, easing = LinearOutSlowInEasing), label = "h$k").value
    }
    val lead = List(n) { i -> animateFloatAsState(if (i == best) 1f else 0f, tween(if (still) 0 else 240), label = "lead$i").value }
    val sink = List(n) { i ->
        animateFloatAsState(if (selected == i && rows[i].kind == MoveKind.BACKFIRES) 1f else 0f, tween(if (still) 0 else 240, easing = FastOutSlowInEasing), label = "sink$i").value
    }
    val dim = List(n) { i -> animateFloatAsState(if (selected >= 0 && selected != i) .3f else 1f, tween(if (still) 0 else 200), label = "dim$i").value }

    // Blocks rise from the floor back-to-front the first time the board appears.
    val intro = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(760, easing = LinearOutSlowInEasing)) }
    // The pawn drops onto the leading block whenever the lead changes.
    val drop = remember { Animatable(0f) }
    LaunchedEffect(best) { if (!still) { drop.snapTo(1f); drop.animateTo(0f, tween(320, delayMillis = 120, easing = LinearOutSlowInEasing)) } }

    val hits = remember { arrayOfNulls<List<Offset>>(9 * COLS) }
    val order = remember { IntArray(9 * COLS) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spanX = (COLS - 1) * GAP + 1f
        val spanY = (n - 1) * GAP + 1f
        val tile = min(36.dp, maxWidth / (spanX + spanY + 2 * SIDE_ROOM))
        val h = tile * (TOP_ROOM + (spanX + spanY) / 2f + BOTTOM_ROOM)
        val density = LocalDensity.current
        val numStyle = TextStyle(fontFamily = CalmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = with(density) { (tile * .36f).toSp() })
        val edgeStyle = TextStyle(fontFamily = CalmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = with(density) { (tile * .30f).toSp() })

        Canvas(
            Modifier.fillMaxWidth().height(h)
                .semantics { contentDescription = description }
                .pointerInput(n) {
                    detectTapGestures { at ->
                        // Front-most block first, so a tap lands on what the eye sees.
                        for (q in n * COLS - 1 downTo 0) {
                            val k = order[q]
                            if (hits[k]?.let { inside(at, it) } == true) { select(k / COLS); break }
                        }
                    }
                }
        ) {
            val tw = tile.toPx(); val th = tw / 2f
            val ox = size.width / 2f + (spanY - spanX) * tw / 2f
            val oy = TOP_ROOM * tw
            fun p(x: Float, y: Float, z: Float) = Offset(ox + (x - y) * tw, oy + (x + y) * th - z)

            // Floor, with the score lane tinted so column S reads as "the answer".
            poly(listOf(p(-.25f, -.25f, 0f), p(spanX + .25f, -.25f, 0f), p(spanX + .25f, spanY + .25f, 0f), p(-.25f, spanY + .25f, 0f)),
                XR.iris.copy(alpha = .05f), XR.iris.copy(alpha = .22f), 1.dp.toPx())
            val sx = (COLS - 1) * GAP
            poly(listOf(p(sx - .08f, -.25f, 0f), p(spanX + .25f, -.25f, 0f), p(spanX + .25f, spanY + .25f, 0f), p(sx - .08f, spanY + .25f, 0f)),
                XR.amber.copy(alpha = .07f))

            // Paint back to front by x + y.
            var q = 0
            for (d in 0..(n - 1 + COLS - 1)) for (i in 0 until n) {
                val j = d - i
                if (j !in 0 until COLS) continue
                val k = i * COLS + j
                order[q++] = k
                val r = rows[i]
                val v = when (j) { 0 -> r.well.toFloat(); 1 -> r.bad.toFloat(); else -> blend[i] }
                // Stagger the rise: far blocks first.
                val rise = ease(((intro.value * 1.7f) - .7f * d / (n + COLS - 2f).coerceAtLeast(1f)).coerceIn(0f, 1f))
                val z = (heights[k] * tw * rise).coerceAtLeast(2f)
                val amber = if (j == 2) lead[i] else 0f
                val crimson = sink[i]
                val base = if (j == 2) BLEND else shade(v)
                val f = faces(base)
                val top = lerp(lerp(f.top, XR.amber, amber), XR.crimson, crimson)
                val left = lerp(lerp(f.left, AMBER_SIDE, amber), CRIMSON_SIDE, crimson)
                val right = lerp(lerp(f.right, lerp(AMBER_SIDE, Color.Black, .32f), amber), lerp(CRIMSON_SIDE, Color.Black, .32f), crimson)
                val a = dim[i]
                val x0 = j * GAP; val y0 = i * GAP; val x1 = x0 + 1f; val y1 = y0 + 1f
                val centre = p(x0 + .5f, y0 + .5f, z)

                val glow = maxOf(amber, crimson) * a
                if (glow > .01f) drawCircle(
                    Brush.radialGradient(listOf((if (crimson > amber) XR.crimson else XR.amber).copy(alpha = .34f * glow), Color.Transparent), centre, tw * 1.5f),
                    tw * 1.5f, centre
                )
                poly(listOf(p(x1, y0, 0f), p(x1, y1, 0f), p(x1, y1, z), p(x1, y0, z)), right.copy(alpha = a))
                poly(listOf(p(x0, y1, 0f), p(x1, y1, 0f), p(x1, y1, z), p(x0, y1, z)), left.copy(alpha = a))
                val face = listOf(p(x0, y0, z), p(x1, y0, z), p(x1, y1, z), p(x0, y1, z))
                poly(face, top.copy(alpha = a), Color.White.copy(alpha = .10f * a), 1.dp.toPx())
                if (i == coach && coach != best) poly(face, null, XR.ink2.copy(alpha = a), 1.5.dp.toPx(), dashed = true)
                hits[k] = listOf(face[0], face[1], p(x1, y0, 0f), p(x1, y1, 0f), p(x0, y1, 0f), face[3])

                val onAmber = amber > .5f && crimson < .5f
                // The leading score block carries the pawn, whose label already says the score.
                val covered = if (j == 2) lead[i] else 0f
                label(measurer, Math.round(v).toString(), numStyle, centre, (if (onAmber) Color(0xFF2A1C05) else XR.ink).copy(alpha = a * rise * (1f - covered)))
            }

            // Edge labels on the two open front edges.
            listOf("1", "2", "S").forEachIndexed { j, t -> label(measurer, t, edgeStyle, p(j * GAP + .5f, spanY + .48f, 0f), if (j == 2) XR.amber else XR.ink3) }
            for (i in 0 until n) label(measurer, ('A' + i).toString(), edgeStyle, p(spanX + .48f, i * GAP + .5f, 0f), if (selected == i || (selected < 0 && i == best)) XR.ink else XR.ink3)

            // The pawn stands on the leading score block, under a label with its score.
            if (best in 0 until n && intro.value > .85f) {
                val z = heights[best * COLS + 2] * tw
                val foot = p(sx + .5f, best * GAP + .5f, z)
                val d = drop.value
                val a = (1f - d) * dim[best]
                val u = tw / 36f
                drawOval(Color.Black.copy(alpha = .4f * a), Offset(foot.x - 9f * u, foot.y - 4.5f * u), Size(18f * u, 9f * u))
                val lift = d * tw * .5f
                drawRoundRect(XR.iris.copy(alpha = a), Offset(foot.x - 6.5f * u, foot.y - 24f * u - lift), Size(13f * u, 20f * u), CornerRadius(6.5f * u))
                drawCircle(XR.irisSoft.copy(alpha = a), 6.5f * u, Offset(foot.x, foot.y - 30f * u - lift))

                val text = measurer.measure("${rows[best].expected} · best", edgeStyle, maxLines = 1)
                val pw = text.size.width + 16f * u; val ph = text.size.height + 8f * u
                val px = (foot.x - pw / 2f).coerceIn(2f, size.width - pw - 2f)
                val py = (foot.y - 40f * u - ph - lift).coerceAtLeast(1f)
                drawRoundRect(XR.card.copy(alpha = a), Offset(px, py), Size(pw, ph), CornerRadius(ph / 2f))
                drawRoundRect(XR.amber.copy(alpha = .55f * a), Offset(px, py), Size(pw, ph), CornerRadius(ph / 2f), style = Stroke(1.dp.toPx()))
                drawText(text, XR.amber.copy(alpha = a), Offset(px + 8f * u, py + 4f * u))
            }
        }
    }
}

private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

private fun DrawScope.poly(pts: List<Offset>, fill: Color?, stroke: Color? = null, width: Float = 0f, dashed: Boolean = false) {
    val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }
    if (fill != null) drawPath(path, fill)
    if (stroke != null) drawPath(path, stroke, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2.4f)) else null))
}

private fun DrawScope.label(m: TextMeasurer, text: String, style: TextStyle, centre: Offset, color: Color) {
    val l = m.measure(text, style, maxLines = 1)
    drawText(l, color, Offset(centre.x - l.size.width / 2f, centre.y - l.size.height / 2f))
}

/** Ray-cast point-in-polygon, for tapping a block's silhouette. */
private fun inside(p: Offset, poly: List<Offset>): Boolean {
    var hit = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) hit = !hit
        j = i
    }
    return hit
}
