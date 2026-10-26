package com.drkkr.strategos.ui.calm.xray

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.agent.XRayRow
import com.drkkr.strategos.ui.calm.theme.Calm
import kotlin.math.roundToInt

private val MONO = FontFamily.Monospace
private val CORAL = Color(0xFFF4B8A4)
private val MIST = Color(0xFFEEF1F6)
private val TEAL = Color(0xFF8EC9B5)
private val CELL_INK = Color(0xFF0F1B2D)

/** Cell fill by score, interpolated coral (20) -> mist (50) -> teal (80); the number is always printed too. */
internal fun cellColor(score: Int): Color {
    val x = score.coerceIn(20, 80).toFloat()
    return if (x <= 50f) lerp(CORAL, MIST, (x - 20f) / 30f) else lerp(MIST, TEAL, (x - 50f) / 30f)
}

/** The sheet's content: a light graph-paper page, deliberately a different material from the calm surface. */
@Composable
fun XRayContent(ui: XRayUiState, onOdds: (Float) -> Unit, onKeep: () -> Unit, onReset: () -> Unit, onReadMore: (String) -> Unit, onClose: () -> Unit) {
    val c = Calm.colors
    val paper = if (c.isDark) Color(0xFF14203A) else Color(0xFFF8FBFF)
    val grid = if (c.isDark) Color(0xFF3A5FA0).copy(alpha = .30f) else Color(0xFF3C64B4).copy(alpha = .14f)
    Column(
        Modifier.fillMaxWidth().background(paper)
            .drawBehind {
                val step = 16.dp.toPx()
                var x = 0f; while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f; while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            }
            .padding(Calm.dimens.gutter),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val g = ui.grid
        if (ui.loading) { Text("Working it out…", style = Calm.type.body, color = c.ink2); return@Column }
        if (g == null) {
            Text("Not enough to rank yet", style = Calm.type.title, color = c.ink)
            Text(ui.notEnough?.question.orEmpty(), style = Calm.type.body, color = c.ink2)
            return@Column
        }
        Text(if (ui.flipped) "WITH YOUR ODDS" else "THE COACH'S MOVE", style = Calm.type.caption.copy(fontFamily = MONO),
            color = if (ui.flipped) c.rigor else c.ink3)
        Text(g.best.label, style = Calm.type.title, color = c.ink)
        // At the coach's odds the verdict is the engine's own; once the user drags, it is the grid's.
        val robust = if (!ui.changed) (ui.engineRobust ?: g.robust) else g.robust
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (robust) "ROBUST" else "FRAGILE", style = Calm.type.pill.copy(fontFamily = MONO, fontWeight = FontWeight.SemiBold),
                color = if (robust) Color(0xFF0F5A45) else Color(0xFF9A3412),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (robust) Color(0xFFC6E6DB) else Color(0xFFFBD9CC)).padding(horizontal = 8.dp, vertical = 2.dp))
            Text(if (robust) "Holds up even if your guesses are a bit off." else "Only holds if your guesses are right.", style = Calm.type.caption, color = c.ink2)
        }
        Text(if (ui.flipped) "Flipped · the coach said ${ui.coachBestLabel.lowercase()}" else "At the coach's odds", style = Calm.type.caption, color = if (ui.flipped) c.rigor else c.ink3)

        if (!ui.changed && g.rows.any { it.expected > g.best.expected })
            Text("The coach picks the move that holds up, not only the highest score.", style = Calm.type.caption, color = c.ink2)
        PayoffGrid(g.rows, g.bestIndex, g.wellLabel, g.badLabel, (g.odds * 100).roundToInt())

        Text("TRY DIFFERENT ODDS", style = Calm.type.caption.copy(fontFamily = MONO), color = c.ink3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.oddsLabel, style = Calm.type.body, color = c.ink, modifier = Modifier.weight(1f))
            Text("${(ui.odds * 100).roundToInt()}%", style = Calm.type.subtitle.copy(fontFamily = MONO), color = c.ink)
        }
        Slider(
            value = ui.odds, onValueChange = onOdds, valueRange = 0f..1f, steps = 99,
            modifier = Modifier.semantics {
                stateDescription = "Chance ${ui.oddsLabel}, ${(ui.odds * 100).roundToInt()} percent. Best move: ${g.best.label}."
            }
        )
        Text("coach's guess ${(ui.coachOdds * 100).roundToInt()}%", style = Calm.type.caption.copy(fontFamily = MONO), color = c.ink3)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reset", style = Calm.type.subtitle, color = if (ui.changed) c.action else c.ink3,
                modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = ui.changed) { onReset() }.padding(horizontal = 8.dp, vertical = 12.dp))
            Text("Keep as my estimate", style = Calm.type.subtitle, color = c.actionText,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(if (ui.changed) c.action else c.ink3.copy(alpha = .45f))
                    .clickable(enabled = ui.changed) { onKeep() }.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp))
        }

        ui.pattern?.let { p ->
            Text("WHY THIS WORKS", style = Calm.type.caption.copy(fontFamily = MONO), color = c.ink3)
            Text(p.name.uppercase(), style = Calm.type.subtitle, color = c.ink)
            Text(p.summary, style = Calm.type.body, color = c.ink2)
            Text("Read more: ${p.name}", style = Calm.type.subtitle, color = c.action, modifier = Modifier.heightIn(min = 48.dp).clickable { onReadMore(p.wikiUrl) })
        }
        if (ui.modelStrip.isNotBlank()) Text(ui.modelStrip, style = Calm.type.caption.copy(fontFamily = MONO), color = c.rigor)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PayoffGrid(rows: List<XRayRow>, best: Int, wellLabel: String, badLabel: String, wellPct: Int) {
    val c = Calm.colors
    Column(Modifier.semantics { contentDescription = "Scores for each of your moves if he takes it well or badly" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("YOUR MOVE", style = Calm.type.pill.copy(fontFamily = MONO), color = c.ink3, modifier = Modifier.weight(1.5f))
            Text("WELL $wellPct%", style = Calm.type.pill.copy(fontFamily = MONO), color = c.ink3, modifier = Modifier.weight(1f))
            Text("BADLY ${100 - wellPct}%", style = Calm.type.pill.copy(fontFamily = MONO), color = c.ink3, modifier = Modifier.weight(1f))
            Text("SCORE", style = Calm.type.pill.copy(fontFamily = MONO), color = c.ink3, modifier = Modifier.weight(0.8f))
        }
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().then(if (i == best) Modifier.border(2.dp, c.rigorGraphic, RoundedCornerShape(12.dp)) else Modifier).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text(r.label, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1.5f))
                Cell(r.well, Modifier.weight(1f)); Cell(r.bad, Modifier.weight(1f))
                Text("${r.expected}", style = Calm.type.subtitle.copy(fontFamily = MONO), color = c.ink, modifier = Modifier.weight(0.8f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("hurts", style = Calm.type.pill, color = c.ink3); Text("mixed", style = Calm.type.pill, color = c.ink3); Text("good for you", style = Calm.type.pill, color = c.ink3)
        }
        Text("Well = ${wellLabel.lowercase()} · Badly = ${badLabel.lowercase()}", style = Calm.type.pill, color = c.ink3)
    }
}

@Composable
private fun Cell(score: Int, modifier: Modifier) {
    val bg by animateColorAsState(cellColor(score), tween(200), label = "cell")
    Box(modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text("$score", style = Calm.type.subtitle.copy(fontFamily = MONO), color = CELL_INK)
    }
}
