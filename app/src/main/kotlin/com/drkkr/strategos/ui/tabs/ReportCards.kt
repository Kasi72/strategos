package com.drkkr.strategos.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.framework.CalculationStatus
import com.drkkr.strategos.domain.framework.RecommendedAction
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.domain.recommendation.*
import com.drkkr.strategos.ui.components.*

// Presentational leaf cards for the Report tab. Extracted from ReportTab.kt to
// keep that file focused on the tab's structure and the three decision views.

@Composable
fun ConfidenceProfileCard(profile: com.drkkr.strategos.domain.framework.ConfidenceProfile) {
    StratCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            val label = profile.label()
            val labelColor = when (label) {
                "High" -> MaterialTheme.colorScheme.secondary
                "Conditional" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            SemanticChip(label, labelColor)
            Spacer(Modifier.width(8.dp))
            Text("Decision Confidence Profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ConfidenceSignalRow("Evidence quality", profile.evidenceQuality, profile.explanations["evidenceQuality"])
        ConfidenceSignalRow("Model completeness", profile.modelCompleteness, profile.explanations["modelCompleteness"])
        ConfidenceSignalRow("Analytical robustness", profile.analyticalRobustness, profile.explanations["analyticalRobustness"])
        ConfidenceSignalRow("Implementation readiness", profile.implementationReadiness, profile.explanations["implementationReadiness"])
    }
}

@Composable
fun ConfidenceSignalRow(label: String, score: Double, explanation: String?) {
    val pct = (score * 100).toInt()
    val color = when {
        score >= 0.7 -> MaterialTheme.colorScheme.secondary
        score >= 0.45 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("$pct%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
        LinearProgressIndicator(
            progress = { score.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 3.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        if (!explanation.isNullOrBlank()) {
            Text(explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun MiniConfidenceBar(label: String, score: Double) {
    val pct = (score * 100).toInt()
    val color = when {
        score >= 0.7 -> MaterialTheme.colorScheme.secondary
        score >= 0.45 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { score.toFloat() },
            modifier = Modifier.weight(1f).height(4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text("$pct%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = color, modifier = Modifier.width(32.dp))
    }
}

@Composable
fun PathwayCard(pathway: DecisionPathway) {
    val (chipLabel, chipColor) = when {
        pathway.excluded -> "Excluded" to MaterialTheme.colorScheme.error
        pathway.deferReason != null -> "Deferred" to MaterialTheme.colorScheme.tertiary
        pathway.fragile -> "Fragile" to MaterialTheme.colorScheme.tertiary
        pathway.label == "Recommended" -> "Recommended" to MaterialTheme.colorScheme.secondary
        pathway.label == "Safe Fallback" -> "Safe Fallback" to MaterialTheme.colorScheme.primary
        else -> pathway.label to MaterialTheme.colorScheme.onSurfaceVariant
    }
    var expanded by remember { mutableStateOf(pathway.label == "Recommended") }

    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pathway.action, style = MaterialTheme.typography.titleSmall)
                    if (!expanded) Text(pathway.rationale.take(80) + "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SemanticChip(chipLabel, chipColor)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(pathway.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pathway.fragile) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                            Text("Fragile: verify critical assumptions before committing to this pathway.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (pathway.deferReason != null) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                            Text(pathway.deferReason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    if (pathway.excluded) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                            Text("This action violates a hard constraint (legal/safety/ethical) and must not be pursued.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (!pathway.excluded) {
                        Spacer(Modifier.height(6.dp))
                        val strengthLabel = if (pathway.strength <= 0.0) "Score withheld — add evidence sources" else "Pathway strength: ${(pathway.strength * 100).toInt()}%"
                        Text(strengthLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun StakeholderForecastCard(forecast: StakeholderForecast) {
    var expanded by remember { mutableStateOf(false) }
    val responseColor = when {
        forecast.likelyResponse.contains("cooperation", ignoreCase = true) ||
        forecast.likelyResponse.contains("support", ignoreCase = true) -> MaterialTheme.colorScheme.secondary
        forecast.likelyResponse.contains("delay", ignoreCase = true) ||
        forecast.likelyResponse.contains("resistance", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
        forecast.likelyResponse.contains("veto", ignoreCase = true) ||
        forecast.likelyResponse.contains("block", ignoreCase = true) ||
        forecast.likelyResponse.contains("opposition", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(forecast.stakeholderName, style = MaterialTheme.typography.titleSmall)
                    Text(forecast.likelyResponse.take(60), style = MaterialTheme.typography.labelSmall, color = responseColor)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(8.dp))
                    ForecastRow("Main concern", forecast.mainConcern)
                    ForecastRow("Evidence basis", forecast.evidenceBasis)
                    ForecastRow("Risk if ignored", forecast.riskIfIgnored)
                    ForecastRow("Recommended engagement", forecast.bestEngagement)
                }
            }
        }
    }
}

@Composable
fun RiskRegisterCard(risk: RiskRegisterItem) {
    var expanded by remember { mutableStateOf(false) }
    val likelihoodColor = when (risk.likelihood) {
        "High" -> MaterialTheme.colorScheme.error
        "Medium" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(risk.failureMode, style = MaterialTheme.typography.titleSmall)
                    Text(risk.actor, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SemanticChip(risk.likelihood, likelihoodColor)
                Spacer(Modifier.width(4.dp))
                SemanticChip("Impact: ${risk.impact}", if (risk.impact == "High") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(8.dp))
                    ForecastRow("Evidence type", risk.evidenceType)
                    ForecastRow("Early signal", risk.earlySignal)
                    ForecastRow("Mitigation", risk.mitigation)
                    ForecastRow("Owner", risk.owner)
                    ForecastRow("Stop condition", risk.stopCondition)
                }
            }
        }
    }
}

@Composable
fun VoiCard(voi: VoiItem) {
    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Text(voi.missingFact, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Text(voi.whyItMatters.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (voi.percentageScenariosChanged > 0) {
                SemanticChip("${voi.percentageScenariosChanged}% decision impact", MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Cost to obtain: ${voi.costToObtain}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).padding(8.dp)) {
            Text(voi.recommendation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun LensCard(rec: LensRecommendation) {
    val (icon, color) = when (rec.lens) {
        RecommendedAction.Lens.EXPECTED -> Icons.AutoMirrored.Filled.TrendingUp to MaterialTheme.colorScheme.primary
        RecommendedAction.Lens.RISK_AVERSE -> Icons.Default.Security to MaterialTheme.colorScheme.secondary
        RecommendedAction.Lens.ETHICAL_LEGAL -> Icons.Default.Gavel to MaterialTheme.colorScheme.error
        RecommendedAction.Lens.RELATIONSHIP -> Icons.Default.People to MaterialTheme.colorScheme.tertiary
        RecommendedAction.Lens.LONG_TERM -> Icons.Default.Timeline to MaterialTheme.colorScheme.primary
        RecommendedAction.Lens.LOW_REGRET -> Icons.Default.FilterList to MaterialTheme.colorScheme.secondary
    }
    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Box(Modifier.size(36.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.lensLabel.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp), color = color)
                Text(rec.action, style = MaterialTheme.typography.titleSmall)
            }
        }
        Text(rec.rationale.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rec.contingency?.let {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("If they deviate: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun CalcStatusChip(status: CalculationStatus) {
    val (label, color) = when (status) {
        CalculationStatus.CALCULATED -> "Calculated" to MaterialTheme.colorScheme.secondary
        CalculationStatus.PARTIALLY_CALCULATED -> "Partial" to MaterialTheme.colorScheme.tertiary
        CalculationStatus.PROXY_ESTIMATE -> "Proxy" to Color(0xFFFF9500)
        CalculationStatus.NOT_RUN -> "Not run" to MaterialTheme.colorScheme.outline
        CalculationStatus.EXCLUDED -> "Excluded" to MaterialTheme.colorScheme.error
    }
    SemanticChip(label, color)
}

@Composable
fun ReportConfidenceBadge(confidence: Double) {
    val color = when {
        confidence >= 0.75 -> MaterialTheme.colorScheme.secondary
        confidence >= 0.5 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    SemanticChip("${"%.0f".format(confidence * 100)}%", color)
}

@Composable
fun ImmediateActionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TechRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ForecastRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    StratDivider()
}
