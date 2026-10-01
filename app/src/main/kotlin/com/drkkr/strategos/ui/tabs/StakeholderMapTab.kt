package com.drkkr.strategos.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.SectionHeader
import com.drkkr.strategos.ui.components.StratCard
import kotlin.math.*

@Composable
fun StakeholderMapTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario

    if (scenario == null || scenario.stakeholders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No stakeholders defined yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text("Stakeholder Map", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 12.dp)) }

        item {
            StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Influence Diagram", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                InfluenceCanvas(stakeholders = scenario.stakeholders)
            }
        }

        item { SectionHeader("Stakeholder Details") }
        items(scenario.stakeholders) { sh ->
            StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(sh.name, style = MaterialTheme.typography.titleSmall)
                Text("Role: ${sh.role.ifBlank { "—" }} · Authority: ${sh.authority}/10", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sh.incentives.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Incentives: ${sh.incentives.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
                }
                if (sh.goals.isNotEmpty()) {
                    Text("Goals: ${sh.goals.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun InfluenceCanvas(stakeholders: List<Stakeholder>) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    val canvasHeight = maxOf(260, stakeholders.size * 52)
    Canvas(modifier = Modifier.fillMaxWidth().height(canvasHeight.dp)) {
        val cx = size.width / 2; val cy = size.height / 2
        val radius = minOf(cx, cy) * 0.65f
        val n = stakeholders.size
        stakeholders.forEachIndexed { i, sh ->
            val angle = (2 * PI / n * i - PI / 2).toFloat()
            val x = cx + radius * cos(angle); val y = cy + radius * sin(angle)
            val nodeR = (16 + sh.authority * 1.5f)
            drawCircle(primary.copy(alpha = 0.15f), nodeR, Offset(x, y))
            drawCircle(primary, 2f, Offset(x, y))
            // Draw abbreviated name
            val label = sh.name.take(10)
            val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp))
            drawText(measured, onSurface, topLeft = Offset(x - measured.size.width / 2, y + nodeR + 4))
        }
        // Draw connecting lines
        for (i in stakeholders.indices) for (j in i + 1 until stakeholders.size) {
            val ai = (2 * PI / n * i - PI / 2).toFloat()
            val aj = (2 * PI / n * j - PI / 2).toFloat()
            drawLine(outline, Offset(cx + radius * cos(ai), cy + radius * sin(ai)),
                Offset(cx + radius * cos(aj), cy + radius * sin(aj)), strokeWidth = 0.8f)
        }
    }
}
