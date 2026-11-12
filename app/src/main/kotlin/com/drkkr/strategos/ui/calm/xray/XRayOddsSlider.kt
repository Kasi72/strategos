package com.drkkr.strategos.ui.calm.xray

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import kotlin.math.roundToInt

/**
 * The what-if slider, drawn as a two-way split: the green side is the chance the counterpart takes it well,
 * the coral side the chance they take it badly, and the thumb is the line between them. Both ends are
 * labelled with the reply and its share, so it never reads as a one-way "more / less" control.
 *
 * Below the track: a grey tick at the coach's odds and an amber tick wherever the best move changes.
 */
@Composable
internal fun OddsSlider(
    value: Float,
    coach: Float,
    flips: List<Int>,
    wellLabel: String,
    badLabel: String,
    spoken: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val pct = (value * 100).roundToInt()
    val change by rememberUpdatedState(onChange)
    var pressed by remember { mutableStateOf(false) }
    val thumb by animateFloatAsState(if (pressed) 13f else 10f, tween(120), label = "thumb")
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontFamily = CalmFonts.Mono, fontSize = 10.sp)
    val mono = Calm.type.caption.copy(fontFamily = CalmFonts.Mono, fontWeight = FontWeight.Medium)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            End(wellLabel, "$pct%", XR.trust, mono, Modifier.weight(1f), alignEnd = false)
            End(badLabel, "${100 - pct}%", XR.tension, mono, Modifier.weight(1f), alignEnd = true)
        }
        Canvas(
            Modifier.fillMaxWidth().height(64.dp)
                .semantics {
                    contentDescription = spoken
                    stateDescription = "$pct percent"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, 99)
                    setProgress { v -> change((v.coerceIn(0f, 1f) * 100).roundToInt() / 100f); true }
                }
                .pointerInput(Unit) {
                    val pad = 14.dp.toPx()
                    fun set(x: Float) = change((((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * 100).roundToInt() / 100f)
                    awaitEachGesture {
                        // Take the touch at once (no slop), so the sheet behind never steals a horizontal drag.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume(); pressed = true; set(down.position.x)
                        drag(down.id) { it.consume(); set(it.position.x) }
                        pressed = false
                    }
                }
        ) {
            val pad = 14.dp.toPx()
            val w = size.width - 2 * pad
            val cy = 22.dp.toPx()
            val t = 6.dp.toPx()
            fun x(f: Float) = pad + w * f.coerceIn(0f, 1f)
            val tx = x(value)
            drawRoundRect(XR.tension.copy(alpha = .55f), Offset(pad, cy - t / 2), Size(w, t), CornerRadius(t / 2))
            drawRoundRect(XR.trust, Offset(pad, cy - t / 2), Size(tx - pad, t), CornerRadius(t / 2))

            // Ticks. Flip labels win the space; the coach label drops to a bare tick if it would collide.
            val taken = mutableListOf<Rect>()
            fun tick(f: Float, text: String, color: Color, must: Boolean) {
                val cx = x(f); val top = cy + 12.dp.toPx(); val s = 4.dp.toPx()
                drawPath(Path().apply { moveTo(cx, top); lineTo(cx - s, top + s * 1.25f); lineTo(cx + s, top + s * 1.25f); close() }, color)
                val l = measurer.measure(text, tickStyle, maxLines = 1)
                val gap = 6.dp.toPx()
                fun box(lx: Float) = Rect(lx - gap, 0f, lx + l.size.width + gap, 1f)
                // Centred under the tick if there is room; otherwise hang it to whichever side is free.
                val lx = listOf(cx - l.size.width / 2f, cx - l.size.width + s, cx - s)
                    .map { it.coerceIn(0f, size.width - l.size.width) }
                    .firstOrNull { c -> taken.none { it.overlaps(box(c)) } }
                    ?: if (must) (cx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width) else return
                taken += box(lx)
                drawText(l, color, Offset(lx, top + s * 1.25f + 3.dp.toPx()))
            }
            flips.take(2).forEach { tick(it / 100f, "flips $it%", XR.amber, true) }
            tick(coach, "coach ${(coach * 100).roundToInt()}%", XR.ink3, false)

            drawCircle(Color.Black.copy(alpha = .35f), (thumb + 2f).dp.toPx(), Offset(tx, cy + 1.dp.toPx()))
            drawCircle(XR.ink, thumb.dp.toPx(), Offset(tx, cy))
            drawCircle(XR.panel, 3.dp.toPx(), Offset(tx, cy))
        }
    }
}

@Composable
private fun End(label: String, share: String, color: Color, mono: TextStyle, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
        if (!alignEnd) Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = Calm.type.caption, color = XR.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(1f, fill = false))
        Text(share, style = mono, color = XR.ink, maxLines = 1)
        if (alignEnd) Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}
