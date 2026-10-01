package com.drkkr.strategos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.framework.behavioral.CooperationInertia
import com.drkkr.strategos.framework.behavioral.TrustArchetype
import com.drkkr.strategos.framework.behavioral.TrustDynamicsOutput

// ─────────────────────────────────────────────────────────────────
//  TrustConditionsCard
//  Shows the 3 trust conditions as traffic lights + per-stakeholder
//  archetype chips + cooperation inertia badges.
// ─────────────────────────────────────────────────────────────────
@Composable
fun TrustConditionsCard(
    result: AnalysisResult,
    modifier: Modifier = Modifier
) {
    val output = result.calculations["trustDynamics"] as? TrustDynamicsOutput ?: return

    var expanded by remember { mutableStateOf(false) }

    val overallScore = output.overallTrustScore
    val overallColor = when {
        overallScore >= 0.65 -> Color(0xFF16A34A)
        overallScore >= 0.40 -> Color(0xFFD97706)
        else -> Color(0xFFDC2626)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, overallColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {

            // ── Header ──────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AccountTree,
                    null,
                    tint = overallColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Stakeholder Trust Dynamics",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Axelrod 1984 · Mitchell et al. 1997",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Overall score pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = overallColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, overallColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        "${(overallScore * 100).toInt()}% cooperation potential",
                        style = MaterialTheme.typography.labelSmall,
                        color = overallColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Three trust condition traffic lights ─────────────
            TrustConditionRow(
                label = "Repeat interactions",
                met = output.repeatInteractions,
                detail = if (output.repeatInteractions) "Scenario marked as repeated game — TfT incentives operative."
                else "One-shot game. Cooperation incentive is weak. Frame as an ongoing relationship to activate TfT."
            )
            Spacer(Modifier.height(6.dp))
            TrustConditionRow(
                label = "Win-win potential",
                met = output.winWinPotential,
                detail = if (output.winWinPotential) "No structural zero-sum signals. Mutual gain is achievable."
                else "Structural zero-sum detected: ${output.winWin.signals.firstOrNull() ?: ""}. Redesign incentives before negotiating."
            )
            Spacer(Modifier.height(6.dp))
            TrustConditionRow(
                label = "Low miscommunication risk",
                met = output.lowMiscommunication,
                detail = if (output.lowMiscommunication) "Noise level acceptable. Tit-for-tat strategies can stabilize."
                else "Elevated miscommunication risk. Use Copykitten or GRIT to avoid defection spirals from misread signals."
            )

            if (output.profiles.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))

                // ── Stakeholder archetype chips ──────────────────
                Text(
                    "Stakeholder archetypes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                output.profiles.forEach { profile ->
                    StakeholderTrustRow(profile = profile)
                    Spacer(Modifier.height(6.dp))
                }

                // ── Expand button for win-win signals ───────────
                if (output.winWin.signals.size > 1) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (expanded) "Hide zero-sum signals" else "Show zero-sum signals (${output.winWin.signals.size})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    AnimatedVisibility(expanded) {
                        Column {
                            output.winWin.signals.forEach { signal ->
                                Row(
                                    Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        "·",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 6.dp, top = 1.dp)
                                    )
                                    Text(
                                        signal,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Traffic light row for each trust condition
// ─────────────────────────────────────────────────────────────────
@Composable
private fun TrustConditionRow(label: String, met: Boolean, detail: String) {
    var showDetail by remember { mutableStateOf(false) }
    val dotColor = if (met) Color(0xFF16A34A) else Color(0xFFDC2626)
    val icon: ImageVector = if (met) Icons.Default.CheckCircle else Icons.Default.Cancel

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, tint = dotColor, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showDetail = !showDetail },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        if (showDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(showDetail) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Per-stakeholder row with archetype chip + inertia badge + prescription
// ─────────────────────────────────────────────────────────────────
@Composable
private fun StakeholderTrustRow(
    profile: com.drkkr.strategos.framework.behavioral.StakeholderTrustProfile
) {
    val archetypeColor = archetypeColor(profile.archetype)
    val inertiaColor = when (profile.cooperationInertia) {
        CooperationInertia.LOW -> Color(0xFF16A34A)
        CooperationInertia.MEDIUM -> Color(0xFFD97706)
        CooperationInertia.HIGH -> Color(0xFFDC2626)
        CooperationInertia.PERMANENT -> Color(0xFF7C3AED)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Name
        Text(
            profile.stakeholderName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        // Archetype chip
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = archetypeColor.copy(alpha = 0.12f),
            border = BorderStroke(0.5.dp, archetypeColor.copy(alpha = 0.5f))
        ) {
            Text(
                profile.archetype.name.replace('_', '-').lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = archetypeColor,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        // Cooperation inertia dot
        Surface(
            shape = CircleShape,
            color = inertiaColor.copy(alpha = 0.15f),
            border = BorderStroke(0.5.dp, inertiaColor.copy(alpha = 0.6f)),
            modifier = Modifier.size(22.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    profile.cooperationInertia.name.take(1),
                    style = MaterialTheme.typography.labelSmall,
                    color = inertiaColor
                )
            }
        }
        // High-risk warning dot
        if (profile.isHighRisk) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Warning,
                null,
                tint = Color(0xFFDC2626),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun archetypeColor(archetype: TrustArchetype): Color = when (archetype) {
    TrustArchetype.COPYCAT -> Color(0xFF2563EB)
    TrustArchetype.COPYKITTEN -> Color(0xFF0891B2)
    TrustArchetype.PAVLOV -> Color(0xFF0D9488)
    TrustArchetype.GRUDGER -> Color(0xFFD97706)
    TrustArchetype.DETECTIVE -> Color(0xFF7C3AED)
    TrustArchetype.ALWAYS_COOPERATE -> Color(0xFF16A34A)
    TrustArchetype.ALWAYS_DEFECT -> Color(0xFFDC2626)
    TrustArchetype.EXTORTIONER -> Color(0xFF9F1239)
    TrustArchetype.UNKNOWN -> Color(0xFF6B7280)
}
