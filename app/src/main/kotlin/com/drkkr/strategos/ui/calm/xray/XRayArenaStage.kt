package com.drkkr.strategos.ui.calm.xray

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.drkkr.strategos.domain.agent.ArenaLine
import com.drkkr.strategos.domain.agent.XRayArena
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The stage is authored in a 340 × 270 unit box and scaled to the width it is given. */
private const val W = 340f
private const val H = 236f

/**
 * Slide 1, "who is in the game": three isometric platforms (you, the counterpart, the strongest outside
 * voice), a tether between you and them, and a dashed pull from the outsider.
 *
 * The tether carries no text. Trust and tension used to sit in one pill on top of the moving line, where
 * they were hard to read; they now have their own two gauges under the stage (see [TrustTension]), and the
 * tether only shows the direction: green at your end, coral at theirs.
 */
@Composable
internal fun ArenaSlide(a: XRayArena, still: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArenaStage(a, still)
        if (a.trust != null || a.tension != null) TrustTension(a.trust, a.tension, a.them.name, still)
        PersonCard(XR.iris, a.you.name, a.you.role, a.you.lines)
        PersonCard(XR.tension, a.them.name, a.them.role, a.them.lines)
        a.influencer?.let { f ->
            PersonCard(XR.amber, f.name, f.role, listOf(ArenaLine("Pull", "${f.relation} · sway ${f.sway}", f.inferred)))
        }
    }
}

@Composable
private fun ArenaStage(a: XRayArena, still: Boolean) {
    val measurer = rememberTextMeasurer()
    val enter = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1100, easing = LinearEasing)) }
    val flow = if (still) 0f else rememberInfiniteTransition(label = "tether").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "flow"
    ).value
    val said = buildString {
        append("Arena: you and ${a.them.name}")
        a.influencer?.let { append(", with ${it.name} pulling from outside") }
        append(".")
    }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = min(maxWidth, 380.dp)
        val nameStyle = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = with(LocalDensity.current) { (width * (12.5f / W)).toSp() })
        val monoStyle = TextStyle(fontFamily = CalmFonts.Mono, fontSize = with(LocalDensity.current) { (width * (10f / W)).toSp() })
        Canvas(Modifier.width(width).height(width * (H / W)).semantics { contentDescription = said }) {
            val s = size.width / W
            val tw = 30f; val th = 15f; val ox = 170f; val oy = 66f
            fun p(x: Float, y: Float, z: Float = 0f) = Offset((ox + (x - y) * tw) * s, (oy + (x + y) * th - z) * s)
            val e = enter.value
            // Each actor appears in turn: floor, you, them, the outsider, then the lines draw themselves.
            fun stage(from: Float, to: Float) = ((e - from) / (to - from)).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }

            val floor = stage(0f, .3f)
            for (k in 0..5) {
                drawLine(XR.iris.copy(alpha = .13f * floor), p(k.toFloat(), 0f), p(k.toFloat(), 5f), 1f * s)
                drawLine(XR.iris.copy(alpha = .13f * floor), p(0f, k.toFloat()), p(5f, k.toFloat()), 1f * s)
            }

            val you = Offset(1.0f, 3.6f); val them = Offset(3.6f, 1.0f); val out = Offset(4.4f, 4.0f)
            val aYou = stage(.1f, .4f); val aThem = stage(.25f, .55f); val aOut = stage(.4f, .7f)
            platform(::p, s, you, 1.3f, XR.iris, .28f, aYou)
            platform(::p, s, them, 1.3f, XR.tension, .16f, aThem)
            if (a.influencer != null) platform(::p, s, out, 1.0f, XR.amber, .16f, aOut)

            // Tether: you → them, green to coral, dashes flowing toward them. No text on it.
            val y0 = p(you.x, you.y, 7f); val t0 = p(them.x, them.y, 7f)
            val start = Offset(y0.x + 10f * s, y0.y - 30f * s); val end = Offset(t0.x - 10f * s, t0.y - 30f * s)
            val tether = Path().apply { moveTo(start.x, start.y); quadraticBezierTo((start.x + end.x) / 2f, start.y - 78f * s, end.x, end.y) }
            val drawn = stage(.5f, .9f)
            if (drawn > 0f) {
                val part = Path()
                PathMeasure().apply { setPath(tether, false); getSegment(0f, length * drawn, part, true) }
                val brush = Brush.linearGradient(listOf(XR.trust, XR.tension), start, end)
                drawPath(part, brush, alpha = .22f, style = Stroke(7f * s, cap = StrokeCap.Round))
                drawPath(part, brush, style = Stroke(3f * s, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * s, 6f * s), -flow * 13f * s)))
                drawCircle(XR.trust, 3.5f * s, start)
                if (drawn >= 1f) drawCircle(XR.tension, 3.5f * s, end)
            }

            // The outsider's pull: a thin dashed amber arrow into the counterpart's platform.
            if (a.influencer != null) {
                val o0 = p(out.x, out.y, 7f)
                val from = Offset(o0.x + 9f * s, o0.y - 26f * s); val to = Offset(t0.x - 16f * s, t0.y + 10f * s)
                val ctrl = Offset(from.x + 14f * s, to.y + 6f * s)
                val pull = stage(.7f, 1f)
                if (pull > 0f) {
                    val part = Path()
                    PathMeasure().apply { setPath(Path().apply { moveTo(from.x, from.y); quadraticBezierTo(ctrl.x, ctrl.y, to.x, to.y) }, false); getSegment(0f, length * pull, part, true) }
                    drawPath(part, XR.amber.copy(alpha = .85f), style = Stroke(1.6f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * s, 4f * s))))
                    if (pull >= 1f) {
                        val ang = atan2(to.y - ctrl.y, to.x - ctrl.x)
                        val hd = 7f * s
                        drawPath(Path().apply {
                            moveTo(to.x, to.y)
                            lineTo(to.x - hd * cos(ang - .45f), to.y - hd * sin(ang - .45f))
                            lineTo(to.x - hd * cos(ang + .45f), to.y - hd * sin(ang + .45f)); close()
                        }, XR.amber)
                        val l = measurer.measure("pull", monoStyle)
                        drawText(l, XR.amber, Offset(ctrl.x + 10f * s, ctrl.y + 2f * s))
                    }
                }
            }

            pawn(s, y0, XR.iris, XR.irisSoft, aYou)
            pawn(s, t0, Color(0xFFC9625C), Color(0xFFFFD0CB), aThem)
            if (a.influencer != null) pawn(s, p(out.x, out.y, 7f), Color(0xFFB88A3A), Color(0xFFFBE3B4), aOut)

            // Names sit under each platform, clear of the tether and the pawns.
            fun name(text: String, at: Offset, sz: Float, alpha: Float) {
                val l = measurer.measure(text, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (96f * s).toInt()))
                val c = p(at.x + sz / 2f, at.y + sz / 2f)
                val w = l.size.width + 14f * s; val h = l.size.height + 4f * s
                val x = (c.x - w / 2f).coerceIn(0f, size.width - w); val y = c.y + 5f * s
                drawRoundRect(XR.panel.copy(alpha = .86f * alpha), Offset(x, y), Size(w, h), CornerRadius(h / 2f))
                drawText(l, XR.ink.copy(alpha = alpha), Offset(x + 7f * s, y + 2f * s))
            }
            name(a.you.name, you, 1.3f, aYou)
            name(a.them.name, them, 1.3f, aThem)
            a.influencer?.let { name(it.name, out, 1.0f, aOut) }
        }
    }
}

private fun DrawScope.platform(p: (Float, Float, Float) -> Offset, s: Float, c: Offset, sz: Float, edge: Color, fill: Float, alpha: Float) {
    if (alpha <= 0f) return
    val a = c.x - sz / 2f; val b = c.y - sz / 2f; val cc = c.x + sz / 2f; val d = c.y + sz / 2f
    // Rises 8 units into place as it fades in.
    val h = 7f; val sinkBy = (1f - alpha) * 8f
    fun q(x: Float, y: Float, z: Float) = p(x, y, z - sinkBy)
    fun path(o: List<Offset>) = Path().apply { moveTo(o[0].x, o[0].y); for (i in 1 until o.size) lineTo(o[i].x, o[i].y); close() }
    val top = path(listOf(q(a, b, h), q(cc, b, h), q(cc, d, h), q(a, d, h)))
    val mid = q(c.x, c.y, h)
    drawCircle(Brush.radialGradient(listOf(edge.copy(alpha = .22f * alpha), Color.Transparent), mid, sz * 34f * s), sz * 34f * s, mid)
    drawPath(path(listOf(q(cc, b, 0f), q(cc, d, 0f), q(cc, d, h), q(cc, b, h))), Color(0xFF141418).copy(alpha = alpha))
    drawPath(path(listOf(q(a, d, 0f), q(cc, d, 0f), q(cc, d, h), q(a, d, h))), Color(0xFF1B1B21).copy(alpha = alpha))
    drawPath(top, XR.panel.copy(alpha = alpha))
    drawPath(top, edge.copy(alpha = fill * alpha))
    drawPath(top, edge.copy(alpha = .7f * alpha), style = Stroke(1.5f * s))
}

private fun DrawScope.pawn(s: Float, foot: Offset, body: Color, head: Color, alpha: Float) {
    if (alpha <= 0f) return
    val lift = (1f - alpha) * 14f * s
    val u = 1.2f * s
    drawOval(Color.Black.copy(alpha = .45f * alpha), Offset(foot.x - 8f * u, foot.y - 4f * u), Size(16f * u, 8f * u))
    drawRoundRect(body.copy(alpha = alpha), Offset(foot.x - 6f * u, foot.y - 22f * u - lift), Size(12f * u, 19f * u), CornerRadius(6f * u))
    drawCircle(head.copy(alpha = alpha), 6f * u, Offset(foot.x, foot.y - 28f * u - lift))
}

/**
 * Trust and tension as two separate gauges. Each has its own colour, its own number and a plain word for
 * the band, so the two readings can never run into each other. They stack when the sheet is narrow or the
 * font is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrustTension(trust: Int?, tension: Int?, name: String, still: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val each = if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) (maxWidth - 8.dp) / 2 else maxWidth
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            trust?.let { Gauge("TRUST", it, band(it, "low", "fair", "strong"), XR.trust, "Trust between you and $name", still, Modifier.width(each)) }
            tension?.let { Gauge("TENSION", it, band(it, "calm", "tight", "strained"), XR.tension, "Tension between you and $name", still, Modifier.width(each)) }
        }
    }
}

private fun band(v: Int, low: String, mid: String, high: String) = if (v < 40) low else if (v < 65) mid else high

@Composable
private fun Gauge(label: String, value: Int, word: String, color: Color, spoken: String, still: Boolean, modifier: Modifier) {
    var shown by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { shown = true }
    val n by animateIntAsState(if (shown) value else 0, tween(if (still) 0 else 800, 250, LinearOutSlowInEasing), label = "n")
    val fill by animateFloatAsState(if (shown) value / 100f else 0f, tween(if (still) 0 else 800, 250, LinearOutSlowInEasing), label = "fill")
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(XR.card.copy(alpha = .86f)).border(1.dp, XR.hair, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = "$spoken: $value out of 100, $word." },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(label, style = Calm.type.pill.copy(fontFamily = CalmFonts.Mono), color = XR.ink3, maxLines = 1, modifier = Modifier.weight(1f))
            Text(word, style = Calm.type.pill, color = color, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$n", style = Calm.type.title.copy(fontFamily = CalmFonts.Mono, fontWeight = FontWeight.Medium), color = XR.ink)
            Text("/ 100", style = Calm.type.pill.copy(fontFamily = CalmFonts.Mono), color = XR.ink3, modifier = Modifier.padding(bottom = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = .07f))) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun PersonCard(dot: Color, name: String, role: String, lines: List<ArenaLine>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(XR.card.copy(alpha = .86f)).border(1.dp, XR.hair, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Text(name, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = XR.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (role.isNotBlank()) Text(role, style = Calm.type.pill, color = XR.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        lines.forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Text(l.label, style = Calm.type.caption, color = XR.ink3)
                Text(l.value, style = Calm.type.caption, color = XR.ink2, modifier = Modifier.weight(1f, fill = false))
                if (l.inferred) Text("INFERRED", style = Calm.type.pill.copy(fontFamily = CalmFonts.Mono, fontSize = Calm.type.pill.fontSize * .85f), color = XR.amber,
                    maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(XR.amber.copy(alpha = .14f)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
}
