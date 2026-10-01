package com.drkkr.strategos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.model.ActionDirective
import com.drkkr.strategos.domain.model.NavigationTarget
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.StageStatus
import com.drkkr.strategos.domain.model.WorkflowOrchestrator
import com.drkkr.strategos.domain.model.WorkflowStage

@Composable
fun WorkflowStageCard(
    scenario: Scenario?,
    modifier: Modifier = Modifier,
    onNavigate: ((NavigationTarget) -> Unit)? = null
) {
    val directive: ActionDirective? = scenario?.let { WorkflowOrchestrator.nextAction(it) }
    val stageStates = scenario?.let { WorkflowOrchestrator.stageStates(it) }
        ?: WorkflowStage.values().map { com.drkkr.strategos.domain.model.WorkflowStageState(it, StageStatus.FUTURE) }
    val currentStage = scenario?.lifecycleStage?.toWorkflowStage() ?: WorkflowStage.FRAME
    val isBlocked = directive?.isBlocked == true

    StratCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    "Decision Workflow",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (scenario != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = stageColor(currentStage, isBlocked).copy(alpha = 0.15f)
                    ) {
                        Text(
                            currentStage.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = stageColor(currentStage, isBlocked),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Stage strip — driven by orchestrator stage states
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                stageStates.forEachIndexed { idx, state ->
                    val nodeColor = when (state.status) {
                        StageStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        StageStatus.CURRENT_BLOCKED -> Color(0xFFFF9500)
                        StageStatus.CURRENT -> MaterialTheme.colorScheme.primaryContainer
                        StageStatus.FUTURE -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val nodeAlpha = if (state.status == StageStatus.FUTURE) 0.4f else 1.0f
                    val isClickable = state.status in setOf(StageStatus.COMPLETED, StageStatus.CURRENT, StageStatus.CURRENT_BLOCKED)

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(nodeColor.copy(alpha = nodeAlpha))
                            .then(
                                if (onNavigate != null && isClickable)
                                    Modifier.clickable { onNavigate(state.stage.nodeTarget()) }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state.status) {
                            StageStatus.COMPLETED -> Icon(
                                Icons.Default.Check, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            StageStatus.CURRENT_BLOCKED -> Text(
                                "!", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            else -> Text(
                                "${idx + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (state.status == StageStatus.CURRENT)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = nodeAlpha)
                            )
                        }
                    }

                    if (idx < stageStates.lastIndex) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .background(
                                    if (state.status == StageStatus.COMPLETED) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
            }

            // Labels
            Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                stageStates.forEachIndexed { idx, state ->
                    val isCurrent = state.status == StageStatus.CURRENT || state.status == StageStatus.CURRENT_BLOCKED
                    Text(
                        state.stage.shortLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = when (state.status) {
                            StageStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            StageStatus.CURRENT_BLOCKED -> Color(0xFFFF9500)
                            StageStatus.CURRENT -> MaterialTheme.colorScheme.onSurface
                            StageStatus.FUTURE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        }
                    )
                }
            }

            if (scenario != null && directive != null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(10.dp))

                // Orchestrator description
                Text(
                    directive.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                // Blockers from orchestrator
                val currentStateBlockers = stageStates
                    .find { it.status == StageStatus.CURRENT_BLOCKED }?.blockers ?: emptyList()
                val allBlockers = (directive.blockers + currentStateBlockers).distinct()
                if (allBlockers.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFF9500).copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning, null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFFFF9500)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Blocked",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFFF9500)
                                )
                            }
                            allBlockers.forEach { blocker ->
                                Text(
                                    "• $blocker",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 3.dp, start = 20.dp)
                                )
                            }
                        }
                    }
                }

                // Required actions hint
                if (directive.requiredActions.isNotEmpty()) {
                    directive.requiredActions.forEach { action ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowForward, null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                action,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // CTA from orchestrator
                if (onNavigate != null) {
                    Button(
                        onClick = { onNavigate(directive.target) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBlocked)
                                MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            directive.ctaLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        "Next: ${directive.ctaLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun WorkflowStage.nodeTarget(): NavigationTarget = when (this) {
    WorkflowStage.FRAME    -> NavigationTarget.FRAME
    WorkflowStage.MODEL    -> NavigationTarget.MODEL_ACTIONS
    WorkflowStage.CHALLENGE -> NavigationTarget.MODEL_CONSTRAINTS
    WorkflowStage.SIMULATE -> NavigationTarget.ANALYSIS
    WorkflowStage.DECIDE   -> NavigationTarget.REPORT_POLICY
    WorkflowStage.LEARN    -> NavigationTarget.IMPLEMENTATION_MONITORING
}

private fun stageColor(stage: WorkflowStage, blocked: Boolean): Color = when {
    blocked -> Color(0xFFFF9500)
    stage == WorkflowStage.LEARN -> Color(0xFF34C759)
    else -> Color(0xFF007AFF)
}
