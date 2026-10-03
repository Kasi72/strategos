package com.drkkr.strategos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.framework.behavioral.RepairInputs
import com.drkkr.strategos.framework.behavioral.RepairOutputs
import com.drkkr.strategos.framework.behavioral.computeRepair
import kotlin.math.min

private val GreenBond   = Color(0xFF16A34A)
private val AmberAlert  = Color(0xFFD97706)
private val RedRisk     = Color(0xFFDC2626)
private val Teal        = Color(0xFF0891B2)

@Composable
fun RelationalRepairCard(
    result: AnalysisResult,
    modifier: Modifier = Modifier
) {
    @Suppress("UNCHECKED_CAST")
    val left     = (result.calculations["left_now"]  as? Double) ?: return
    val right    = (result.calculations["right_now"] as? Double) ?: return
    val survives = (result.calculations["survives_now"] as? Boolean) ?: return
    val tStar    = (result.calculations["t_star"] as? Int) ?: -1
    val horizon  = (result.calculations["horizon"] as? Int) ?: 12
    val pStar    = result.calculations["trust_threshold_p_star"] as? Double
    val qT       = (result.calculations["renewal_survival_qT"] as? Double) ?: 0.0
    val halflife = (result.calculations["renewal_halflife_periods"] as? Double) ?: Double.POSITIVE_INFINITY
    @Suppress("UNCHECKED_CAST")
    val historyPath = (result.calculations["history_trajectory"] as? List<Double>) ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val termsRaw = result.calculations["terms"] as? Map<String, Double> ?: emptyMap()

    val outcomeColor = when {
        survives && (tStar == -1 || tStar > horizon / 2) -> GreenBond
        survives -> AmberAlert
        else -> RedRisk
    }
    val outcomeLabel = when {
        survives && (tStar == -1 || tStar > horizon / 2) -> "Bond Holds"
        survives -> "Window Closing"
        else -> "Bond at Risk"
    }
    val verdictLine = when {
        survives && (tStar == -1 || tStar > horizon / 2) ->
            "The relationship has a positive maintenance incentive — shared history outweighs the pull to exit."
        survives && tStar != -1 ->
            "Reconnection is currently rational but the prize decays: act before period $tStar or the incentive inverts."
        else ->
            "The alternative currently dominates: reducing friction (Fe) or boosting shared history (H) is needed now."
    }

    var termsExpanded by remember { mutableStateOf(false) }
    var whatIfExpanded by remember { mutableStateOf(false) }

    // what-if slider state (initialised from engine terms)
    var sliderS   by remember { mutableFloatStateOf(termsRaw["S"]?.toFloat()           ?: 0.5f) }
    var sliderQ   by remember { mutableFloatStateOf(termsRaw["q"]?.toFloat()           ?: 0.65f) }
    var sliderH   by remember { mutableFloatStateOf(termsRaw["H"]?.toFloat()           ?: 0.5f) }
    var sliderR   by remember { mutableFloatStateOf(termsRaw["R"]?.toFloat()           ?: 0.30f) }
    var sliderA   by remember { mutableFloatStateOf(termsRaw["A"]?.toFloat()           ?: 0.40f) }
    var sliderDecay by remember { mutableFloatStateOf(termsRaw["prize_decay"]?.toFloat() ?: 0.08f) }

    val liveInputs = RepairInputs(
        S = sliderS.toDouble(),
        q = sliderQ.toDouble(),
        H = sliderH.toDouble(),
        eH = sliderH.toDouble() * 0.15,
        R = sliderR.toDouble(),
        A = sliderA.toDouble(),
        Fe = termsRaw["Fe"]?.toFloat()?.toDouble() ?: 0.0,
        historyGrowth = termsRaw["history_growth"] ?: 0.12,
        prizeDecay = sliderDecay.toDouble()
    )
    val liveOut = computeRepair(liveInputs, horizon, pStar)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, outcomeColor.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp)) {

            // ── Header ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Relational Repair Game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Analysed as a repeated repair game",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = outcomeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        outcomeLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = outcomeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Verdict line ────────────────────────────────────────
            Text(
                verdictLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            // ── Maintenance inequality bar ──────────────────────────
            Text(
                "Maintenance Inequality",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            MaintenanceInequalityBar(left = left, right = right, outcomeColor = outcomeColor)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("S·q·[H+E(H)−R] = ${"%.2f".format(left)}", style = MaterialTheme.typography.labelSmall, color = outcomeColor)
                Text("A−Fe = ${"%.2f".format(right)}", style = MaterialTheme.typography.labelSmall, color = Teal)
            }

            Spacer(Modifier.height(14.dp))

            // ── Melting-prize chart ─────────────────────────────────
            if (historyPath.size > 1) {
                Text(
                    "Melting-Prize Trajectory",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                MeltingPrizeChart(
                    path = historyPath,
                    tStar = tStar,
                    horizon = horizon,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── Stat tiles ──────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Trust threshold p*",
                    value = if (pStar != null) "${(pStar * 100).toInt()}%" else "N/A",
                    color = if (pStar != null && pStar > 0.7) AmberAlert else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Renewal survival q^T",
                    value = "${(qT * 100).toInt()}%",
                    color = if (qT < 0.5) RedRisk else GreenBond,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Prize half-life",
                    value = if (halflife.isInfinite()) "∞" else "${halflife.toInt()} per",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Expandable terms ────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { termsExpanded = !termsExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Model terms", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Icon(
                    imageVector = if (termsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(
                visible = termsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TermRow("S (self-authority)",          termsRaw["S"])
                    TermRow("q (renewal probability)",     termsRaw["q"])
                    TermRow("H (shared history)",          termsRaw["H"])
                    TermRow("E(H) (expected gain)",        termsRaw["E(H)"])
                    TermRow("R (renewal cost)",             termsRaw["R"])
                    TermRow("A (alternative pull)",         termsRaw["A"])
                    TermRow("Fe (reconnection friction)",   termsRaw["Fe"])
                    TermRow("prize_decay / period",         termsRaw["prize_decay"])
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── What-if sliders ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { whatIfExpanded = !whatIfExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("What-if explorer", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (whatIfExpanded) {
                        val liveSurvives = liveOut.survives
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = (if (liveSurvives) GreenBond else RedRisk).copy(alpha = 0.15f)
                        ) {
                            Text(
                                if (liveSurvives) "Holds" else "At Risk",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (liveSurvives) GreenBond else RedRisk
                            )
                        }
                    }
                    Icon(
                        imageVector = if (whatIfExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = whatIfExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WhatIfSlider("S", sliderS, 0.1f, 1f) { sliderS = it }
                    WhatIfSlider("q", sliderQ, 0.1f, 0.99f) { sliderQ = it }
                    WhatIfSlider("H", sliderH, 0.0f, 1f) { sliderH = it }
                    WhatIfSlider("R", sliderR, 0.0f, 0.8f) { sliderR = it }
                    WhatIfSlider("A", sliderA, 0.1f, 0.9f) { sliderA = it }
                    WhatIfSlider("prize_decay", sliderDecay, 0.01f, 0.30f) { sliderDecay = it }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "S·q·[H+E(H)−R] = ${"%.2f".format(liveOut.left)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (liveOut.survives) GreenBond else RedRisk
                        )
                        Text(
                            "A−Fe = ${"%.2f".format(liveOut.right)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Teal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceInequalityBar(left: Double, right: Double, outcomeColor: Color) {
    val total = (left + right).coerceAtLeast(0.001)
    val leftFraction = (left / total).coerceIn(0.0, 1.0).toFloat()
    val rightFraction = 1f - leftFraction

    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(leftFraction)
                    .background(outcomeColor.copy(alpha = 0.85f))
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(Teal.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun MeltingPrizeChart(
    path: List<Double>,
    tStar: Int,
    horizon: Int,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val tStarColor = AmberAlert

    Canvas(modifier = modifier) {
        if (path.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val max = path.max().coerceAtLeast(0.001)
        val n = path.size

        val pts = path.mapIndexed { i, v ->
            Offset(
                x = w * i / (n - 1).coerceAtLeast(1),
                y = h - (h * (v / max)).toFloat()
            )
        }

        // Filled area under curve
        val pathFill = Path().apply {
            moveTo(pts.first().x, h)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(pts.last().x, h)
            close()
        }
        drawPath(pathFill, Brush.verticalGradient(
            listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
        ))

        // Line
        for (i in 0 until pts.size - 1) {
            drawLine(lineColor, pts[i], pts[i + 1], strokeWidth = 2.dp.toPx())
        }

        // t* marker
        if (tStar in 1 until n) {
            val tx = w * tStar / (n - 1).coerceAtLeast(1)
            drawLine(
                color = tStarColor,
                start = Offset(tx, 0f),
                end = Offset(tx, h),
                strokeWidth = 1.5f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
            // t* dot
            drawCircle(tStarColor, radius = 4.dp.toPx(), center = pts[tStar])
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TermRow(label: String, value: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (value != null) "%.3f".format(value) else "—",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WhatIfSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )
    }
}
