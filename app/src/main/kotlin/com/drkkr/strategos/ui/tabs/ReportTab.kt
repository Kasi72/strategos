package com.drkkr.strategos.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.framework.BlockerSeverity
import com.drkkr.strategos.domain.framework.CalculationStatus
import com.drkkr.strategos.domain.framework.DecisionReadinessResult
import com.drkkr.strategos.domain.framework.FrameworkCategory
import com.drkkr.strategos.domain.framework.RecommendedAction
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.domain.recommendation.*
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val reportDateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun Long.toReportDateStr(): String = if (this == 0L) "—" else reportDateFmt.format(Date(this))

private enum class ReportTabLevel { EXECUTIVE, STRATEGIC, TECHNICAL, CONSOLE }

@Composable
fun ReportTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val report = state.report
    var tab by remember { mutableStateOf(ReportTabLevel.EXECUTIVE) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Executive", "Strategic", "Technical", "Console").forEachIndexed { idx, label ->
                Tab(
                    selected = tab.ordinal == idx,
                    onClick = { tab = ReportTabLevel.entries[idx] },
                    text = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        when (tab) {
            ReportTabLevel.EXECUTIVE -> if (report != null) ExecutiveView(report, vm) else ReportEmpty()
            ReportTabLevel.STRATEGIC -> if (report != null) StrategicView(report, vm) else ReportEmpty()
            ReportTabLevel.TECHNICAL -> if (report != null) TechnicalView(report, vm) else ReportEmpty()
            ReportTabLevel.CONSOLE -> DecisionConsoleView(vm)
        }
    }
}

@Composable
private fun ReportEmpty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Assessment, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text("No report yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Run analysis to generate a decision report.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Decision Console ──────────────────────────────────────────────────────────

@Composable
fun DecisionConsoleView(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario
    val report = state.report

    if (scenario == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active scenario.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var showAddStageDialog by remember { mutableStateOf(false) }
    var showRecordDecisionDialog by remember { mutableStateOf<String?>(null) }
    val safetyResult = remember(scenario) { PolicySafetyEvaluator.evaluate(scenario) }

    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {

        // ── DECISION POLICY ──────────────────────────────────────────────────
        item {
            Text("Decision Policy", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        }

        // PolicySafetyEvaluator status
        item {
            val safeColor = if (safetyResult.eligible) Color(0xFF34C759) else Color(0xFFFF3B30)
            StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Icon(
                        if (safetyResult.eligible) Icons.Default.CheckCircle else Icons.Default.Block,
                        null, modifier = Modifier.size(16.dp), tint = safeColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (safetyResult.eligible) "Policy constraints satisfied" else "Policy blocked",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = safeColor
                    )
                }
                safetyResult.blockers.forEach { b ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 2.dp)) {
                        Icon(Icons.Default.Block, null, modifier = Modifier.size(11.dp).padding(top = 2.dp),
                            tint = Color(0xFFFF3B30))
                        Spacer(Modifier.width(5.dp))
                        Text(b, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF3B30))
                    }
                }
                safetyResult.warnings.forEach { w ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 2.dp)) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(11.dp).padding(top = 2.dp),
                            tint = Color(0xFFFF9500))
                        Spacer(Modifier.width(5.dp))
                        Text(w, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
                    }
                }
            }
        }

        // Decision readiness checklist
        val readiness = state.decisionReadiness
        if (readiness != null && readiness.blockers.isNotEmpty()) {
            item {
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "Requirements",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    readiness.blockers.forEach { blocker ->
                        val isRed = blocker.severity == BlockerSeverity.CRITICAL
                        val rowColor = if (isRed) Color(0xFFDC2626) else Color(0xFFD97706)
                        val rowIcon = if (isRed) Icons.Default.Error else Icons.Default.Warning
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 3.dp)
                        ) {
                            Icon(rowIcon, null,
                                modifier = Modifier.size(14.dp).padding(top = 1.dp),
                                tint = rowColor)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(blocker.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold, color = rowColor)
                                Text(blocker.impact,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Now: ${blocker.currentState}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { vm.navigateTo(blocker.target) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(blocker.fixLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = rowColor)
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }

        // Pathway cards from report — with select/fallback/defer actions
        if (report != null && report.pathways.isNotEmpty()) {
            item { SectionHeader("Pathways") }
            items(report.pathways) { pathway ->
                PolicyPathwayCard(pathway, scenario, vm, safetyResult)
            }
        } else {
            item {
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Run analysis first to see decision pathways.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Selected policy option editor
        item {
            SectionHeader("Selected Policy Option")
            val option = scenario.selectedPolicyOption
            StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                if (option == null) {
                    Text("No policy option selected. Select a pathway above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp),
                                tint = Color(0xFF34C759))
                            Spacer(Modifier.width(8.dp))
                            Text(option.actionLabel, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        if (option.rationale.isNotBlank()) {
                            Text(option.rationale, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        if (option.selectedSnapshotId.isNotBlank()) {
                            Text("Snapshot: ${option.selectedSnapshotId.take(8)}…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        // Risk controls
                        if (option.riskControls.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Risk controls", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            option.riskControls.forEach { rc ->
                                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                    Icon(Icons.Default.Shield, null, modifier = Modifier.size(11.dp).padding(top = 2.dp),
                                        tint = Color(0xFF007AFF))
                                    Spacer(Modifier.width(4.dp))
                                    Text(rc, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        // Stop condition
                        if (option.stopCondition.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Stop condition: ${option.stopCondition}", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF3B30))
                        }
                        // Reversal plan
                        if (option.reversalPlan.isNotBlank()) {
                            Text("Reversal: ${option.reversalPlan}", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF9500))
                        }
                        // Hard constraint status matrix
                        val hardStatuses = PolicySafetyEvaluator.hardConstraintStatuses(scenario, option.actionId)
                        if (hardStatuses.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(Modifier.height(6.dp))
                            Text("Hard constraint status", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hardStatuses.forEach { (c, a) ->
                                val (statusColor, statusLabel) = when {
                                    a == null -> Color(0xFFFF9500) to "Not assessed"
                                    a.status == ConstraintStatus.SATISFIED -> Color(0xFF34C759) to "Satisfied"
                                    a.status == ConstraintStatus.VIOLATED -> Color(0xFFFF3B30) to "Violated"
                                    else -> Color(0xFF8E8E93) to "Unknown"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(c.label, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f))
                                    Surface(shape = RoundedCornerShape(4.dp),
                                        color = statusColor.copy(alpha = 0.12f)) {
                                        Text(statusLabel, style = MaterialTheme.typography.labelSmall,
                                            color = statusColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Submission blockers
        if (!safetyResult.eligible) {
            item {
                Surface(shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF3B30).copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Submission blocked", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, color = Color(0xFFFF3B30))
                        safetyResult.blockers.forEach { b ->
                            Text("• $b", style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF3B30), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }

        // ── APPROVAL WORKFLOW ────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            Text("Approval Workflow", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        }

        if (scenario.approvalStages.isEmpty()) {
            item {
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("No approval stages defined. Add at least one stage before submitting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(scenario.approvalStages) { stage ->
                ApprovalStageEditCard(stage, scenario, vm,
                    onRecordDecision = { showRecordDecisionDialog = stage.stageId })
            }
        }

        // Add stage button
        item {
            OutlinedButton(
                onClick = { showAddStageDialog = true },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add approval stage", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Submit for approval button
        item {
            val canSubmit = safetyResult.eligible &&
                scenario.approvalStages.isNotEmpty() &&
                scenario.selectedPolicyOption != null &&
                scenario.lifecycleStage == LifecycleStage.ANALYSED
            Button(
                onClick = { vm.submitForApproval() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Submit for approval", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            if (state.lifecycleTransitionError != null) {
                Text(state.lifecycleTransitionError ?: "", style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF3B30), modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    // ── Add Stage Dialog ─────────────────────────────────────────────────────
    if (showAddStageDialog) {
        AddApprovalStageDialog(
            stakeholders = scenario.stakeholders,
            onDismiss = { showAddStageDialog = false },
            onAdd = { stage ->
                vm.addApprovalStage(stage)
                showAddStageDialog = false
            }
        )
    }

    // ── Record Decision Dialog ────────────────────────────────────────────────
    showRecordDecisionDialog?.let { stageId ->
        RecordApprovalDecisionDialog(
            stageId = stageId,
            onDismiss = { showRecordDecisionDialog = null },
            onRecord = { decision ->
                vm.recordApprovalDecisionFull(stageId, decision)
                showRecordDecisionDialog = null
            }
        )
    }
}

@Composable
private fun PolicyPathwayCard(
    pathway: DecisionPathway,
    scenario: Scenario,
    vm: StrategosViewModel,
    safetyResult: PolicySafetyResult
) {
    val isSelected = scenario.selectedPolicyOption?.actionId == (pathway.actionId ?: pathway.label)
    var expanded by remember { mutableStateOf(isSelected) }

    // Derive explicit pathway state — never show "0% strength" as a real result
    val pathwayState: String = when {
        pathway.excluded -> "Excluded by hard constraint"
        pathway.deferReason != null -> "Deferred — ${pathway.deferReason}"
        pathway.actionId == null -> "Insufficient payoff data"
        pathway.calculationBasis.isBlank() && pathway.strength == 0.0 -> "Not calculated"
        pathway.strength == 0.0 -> "Insufficient evidence"
        pathway.fragile -> "Eligible (fragile)"
        else -> "Eligible"
    }
    val canSelect = !pathway.excluded && pathway.deferReason == null &&
        pathway.actionId != null && pathway.strength > 0.0

    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pathway.action, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    val stateColor = when {
                        pathway.excluded -> Color(0xFFDC2626)
                        pathway.deferReason != null -> MaterialTheme.colorScheme.tertiary
                        pathway.strength == 0.0 -> Color(0xFFD97706)
                        pathway.fragile -> Color(0xFFD97706)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(pathwayState, style = MaterialTheme.typography.labelSmall, color = stateColor)
                }
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(pathway.rationale, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canSelect) {
                            Button(
                                onClick = {
                                    val opt = PolicyOption(
                                        actionId = pathway.actionId!!,
                                        actionLabel = pathway.action,
                                        rationale = pathway.rationale,
                                        selectedSnapshotId = scenario.snapshotId ?: "",
                                        selectedBy = scenario.decisionOwner
                                    )
                                    vm.selectPolicyOption(opt)
                                },
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(if (isSelected) "Selected" else "Select policy",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (!canSelect && !pathway.excluded) {
                            Surface(shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFD97706).copy(alpha = 0.1f),
                                modifier = Modifier.padding(top = 2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(12.dp),
                                        tint = Color(0xFFD97706))
                                    Spacer(Modifier.width(4.dp))
                                    Text(pathwayState, style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFD97706))
                                }
                            }
                        }
                        if (pathway.excluded) {
                            Surface(shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFDC2626).copy(alpha = 0.1f),
                                modifier = Modifier.padding(top = 2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Block, null, modifier = Modifier.size(12.dp),
                                        tint = Color(0xFFDC2626))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Excluded — hard constraint violation",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFDC2626))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalStageEditCard(
    stage: ApprovalStage,
    scenario: Scenario,
    vm: StrategosViewModel,
    onRecordDecision: () -> Unit
) {
    val (color, icon) = when {
        stage.invalidatedAt > 0L -> Color(0xFFFF3B30) to Icons.Default.Cancel
        stage.status == ApprovalStatus.APPROVED -> Color(0xFF34C759) to Icons.Default.CheckCircle
        stage.status == ApprovalStatus.REJECTED -> Color(0xFFFF3B30) to Icons.Default.Cancel
        stage.status == ApprovalStatus.EXPIRED -> Color(0xFF8E8E93) to Icons.Default.Timer
        else -> Color(0xFFFF9500) to Icons.Default.HourglassEmpty
    }
    val effectiveStatus = stage.effectiveStatus()
    val isDuplicate = scenario.approvalStages.count { it.stageId == stage.stageId } > 1

    StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                Icon(icon, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stage.name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text(stage.threshold.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val approverNames = stage.requiredStakeholderIds.mapNotNull { id ->
                        scenario.stakeholders.find { it.id == id }?.name
                    }
                    if (approverNames.isNotEmpty()) {
                        Text("Approvers: ${approverNames.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (stage.expiryDate > 0L) {
                        val expired = System.currentTimeMillis() > stage.expiryDate
                        Text("Expires ${stage.expiryDate.toReportDateStr()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (expired) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Decisions recorded
                    stage.decisions.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(
                                if (d.decision == ApprovalStatus.APPROVED) Icons.Default.ThumbUp else Icons.Default.ThumbDown,
                                null, modifier = Modifier.size(11.dp),
                                tint = if (d.decision == ApprovalStatus.APPROVED) Color(0xFF34C759) else Color(0xFFFF3B30)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${d.approverName}: ${if (d.decision == ApprovalStatus.APPROVED) "Approved" else "Rejected"}" +
                                if (d.rationale.isNotBlank()) " — ${d.rationale.take(60)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (effectiveStatus == ApprovalStatus.APPROVED) {
                        Text("Threshold met: ${stage.threshold.name.replace('_', ' ')}",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF34C759),
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
                    Text(stage.statusLabel(), style = MaterialTheme.typography.labelSmall, color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (stage.status == ApprovalStatus.PENDING) {
                    TextButton(
                        onClick = onRecordDecision,
                        modifier = Modifier.heightIn(min = 32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Record decision", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(
                    onClick = { vm.removeApprovalStage(stage.stageId) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AddApprovalStageDialog(
    stakeholders: List<Stakeholder>,
    onDismiss: () -> Unit,
    onAdd: (ApprovalStage) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf(ApprovalStage.Threshold.SIMPLE_MAJORITY) }
    var selectedStakeholderIds by remember { mutableStateOf(setOf<String>()) }
    var expiryDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Approval Stage", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Stage name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                // Threshold selector
                Text("Decision threshold", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ApprovalStage.Threshold.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { threshold = t }) {
                        RadioButton(selected = threshold == t, onClick = { threshold = t })
                        Spacer(Modifier.width(6.dp))
                        Text(t.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Stakeholder selection (required approvers)
                if (stakeholders.isNotEmpty()) {
                    Text("Required approvers", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    stakeholders.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                selectedStakeholderIds = if (s.id in selectedStakeholderIds)
                                    selectedStakeholderIds - s.id
                                else selectedStakeholderIds + s.id
                            }) {
                            Checkbox(checked = s.id in selectedStakeholderIds,
                                onCheckedChange = { checked ->
                                    selectedStakeholderIds = if (checked) selectedStakeholderIds + s.id
                                    else selectedStakeholderIds - s.id
                                })
                            Spacer(Modifier.width(6.dp))
                            Text(s.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = expiryDays, onValueChange = { expiryDays = it },
                    label = { Text("Expiry (days, optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val expiryMs = expiryDays.toLongOrNull()?.let { System.currentTimeMillis() + it * 86400000L } ?: 0L
                    val stage = ApprovalStage(
                        name = name.ifBlank { "Approval stage" },
                        threshold = threshold,
                        requiredStakeholderIds = selectedStakeholderIds.toList(),
                        expiryDate = expiryMs
                    )
                    onAdd(stage)
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RecordApprovalDecisionDialog(
    stageId: String,
    onDismiss: () -> Unit,
    onRecord: (ApprovalDecision) -> Unit
) {
    var approverName by remember { mutableStateOf("") }
    var approved by remember { mutableStateOf(true) }
    var rationale by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Record Approval Decision", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = approverName, onValueChange = { approverName = it },
                    label = { Text("Approver name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(true to "Approved", false to "Rejected").forEach { (v, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { approved = v }) {
                            RadioButton(selected = approved == v, onClick = { approved = v })
                            Spacer(Modifier.width(4.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = rationale, onValueChange = { rationale = it },
                    label = { Text("Rationale (optional)") },
                    modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val decision = ApprovalDecision(
                        stageId = stageId,
                        approverId = "",
                        approverName = approverName,
                        decision = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
                        rationale = rationale,
                        recordedAt = System.currentTimeMillis()
                    )
                    onRecord(decision)
                },
                enabled = approverName.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Executive View ────────────────────────────────────────────────────────────

@Composable
fun ExecutiveView(report: DecisionReport, vm: StrategosViewModel) {
    val recommended = report.pathways.firstOrNull { it.label == "Recommended" }
    val fallback = report.pathways.firstOrNull { it.label == "Safe Fallback" }
    val topRisk = report.riskRegister.firstOrNull()
    val topVoi = report.voiItems.firstOrNull()
    val profile = report.confidenceProfile

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text("Decision Report", style = MaterialTheme.typography.displayLarge)
            Text(report.scenario.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        }

        // 1. Decision status
        item {
            val (statusLabel, statusColor) = decisionStatus(report)
            FeatureCard(modifier = Modifier.padding(bottom = 12.dp)) {
                Text("DECISION STATUS", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(statusColor, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(statusLabel, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = statusColor)
                }
                Spacer(Modifier.height(8.dp))
                Text(decisionStatusSentence(report), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile != null) {
                    Spacer(Modifier.height(14.dp))
                    Text("Overall confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    MiniConfidenceBar("Evidence", profile.evidenceQuality)
                    MiniConfidenceBar("Completeness", profile.modelCompleteness)
                    MiniConfidenceBar("Robustness", profile.analyticalRobustness)
                    MiniConfidenceBar("Readiness", profile.implementationReadiness)
                }
                // Show blocking reasons for hold/blocked statuses
                val reason = report.executiveStatusReason
                if (report.executiveStatus.requiresHold() && reason != null && reason.hasBlockers()) {
                    Spacer(Modifier.height(14.dp))
                    Text("BLOCKERS", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    reason.blockingReasons.forEach { blocker ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                            Icon(Icons.Default.Error, null,
                                modifier = Modifier.size(13.dp).padding(top = 2.dp),
                                tint = Color(0xFFDC2626))
                            Spacer(Modifier.width(6.dp))
                            Text(blocker, style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFDC2626))
                        }
                    }
                    if (reason.requiredActions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("REQUIRED ACTIONS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        reason.requiredActions.forEach { action ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Default.Warning, null,
                                    modifier = Modifier.size(13.dp).padding(top = 2.dp),
                                    tint = Color(0xFFD97706))
                                Spacer(Modifier.width(6.dp))
                                Text(action, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                // Lifecycle stage badge
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timeline, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Stage: ${report.scenario.lifecycleStage.displayLabel()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 2. Recommended pathway
        if (recommended != null) {
            item {
                SectionHeader("Recommended Pathway")
                FeatureCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(recommended.action, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(8.dp))
                    Text(recommended.rationale, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (recommended.strength <= 0.0) {
                            SemanticChip("Score withheld — add evidence", MaterialTheme.colorScheme.tertiary)
                        } else {
                            SemanticChip(
                                "${(recommended.strength * 100).toInt()}% strength",
                                if (recommended.strength >= 0.65) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (recommended.fragile) SemanticChip("Fragile", MaterialTheme.colorScheme.error)
                        if (recommended.deferReason != null) SemanticChip("Deferred", MaterialTheme.colorScheme.tertiary)
                    }
                    if (recommended.deferReason != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(recommended.deferReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("Decision owner: ${report.scenario.decisionOwner.ifBlank { "Not specified" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 3. Why preferred
        item {
            SectionHeader("Why this pathway is preferred")
            StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                val reasons = extractWhyReasons(report, recommended)
                reasons.forEach { reason ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (reasons.isEmpty()) {
                    Text("Run analysis to generate rationale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 4. Main risk
        if (topRisk != null) {
            item {
                SectionHeader("Main Risk")
                StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val riskColor = when (topRisk.likelihood) {
                            "High" -> MaterialTheme.colorScheme.error
                            "Medium" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        SemanticChip(topRisk.likelihood, riskColor)
                        Spacer(Modifier.width(8.dp))
                        Text(topRisk.failureMode, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Actor: ${topRisk.actor}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Early signal: ${topRisk.earlySignal}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Mitigation: ${topRisk.mitigation}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // 5. What could change the decision
        item {
            SectionHeader("What could change the recommendation")
            StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                if (topVoi != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(topVoi.missingFact, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(topVoi.whyItMatters, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (topVoi.percentageScenariosChanged > 0) {
                        Spacer(Modifier.height(6.dp))
                        SemanticChip("Changes decision in ${topVoi.percentageScenariosChanged}% of scenarios", MaterialTheme.colorScheme.tertiary)
                    }
                } else {
                    val topAssumption = report.sensitiveAssumptions.firstOrNull()
                    if (topAssumption != null) {
                        Text(topAssumption, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No high-impact changing factors identified.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 6. Safe fallback
        if (fallback != null) {
            item {
                SectionHeader("Safe Fallback")
                StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(fallback.action, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(fallback.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 7. Immediate action plan
        item {
            SectionHeader("Immediate Action Plan")
            StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                val nextAction = report.missingInfo.firstOrNull()
                ImmediateActionRow("Owner", report.scenario.decisionOwner.ifBlank { "Not assigned" })
                ImmediateActionRow("First action", recommended?.action?.take(80) ?: "Define pathway first")
                ImmediateActionRow("Required approval", report.scenario.decisionRule.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
                ImmediateActionRow("Key condition", report.pathways.firstOrNull { it.label == "Recommended" }?.rationale ?: "—")
                if (nextAction != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text("Evidence gap: ${nextAction.description}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.saveScenario() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save Scenario", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ── Strategic View ────────────────────────────────────────────────────────────

@Composable
fun StrategicView(report: DecisionReport, vm: StrategosViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 4-signal confidence profile
        report.confidenceProfile?.let { profile ->
            item {
                SectionHeader("Decision Confidence Profile")
                ConfidenceProfileCard(profile)
                Spacer(Modifier.height(8.dp))
            }
        }

        // Framework disagreements
        if (report.frameworkDisagreements.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                            Text("Framework Disagreements", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                        report.frameworkDisagreements.forEach { dis ->
                            Text(dis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Decision pathways
        if (report.pathways.isNotEmpty()) {
            item { SectionHeader("Decision Pathways") }
            items(report.pathways) { pathway -> PathwayCard(pathway) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Stakeholder forecasts
        if (report.stakeholderForecasts.isNotEmpty()) {
            item { SectionHeader("Stakeholder Response Forecast") }
            items(report.stakeholderForecasts) { forecast -> StakeholderForecastCard(forecast) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Risk register
        if (report.riskRegister.isNotEmpty()) {
            item { SectionHeader("Risk Register") }
            items(report.riskRegister) { risk -> RiskRegisterCard(risk) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // VOI items
        if (report.voiItems.isNotEmpty()) {
            item { SectionHeader("Highest-Value Information") }
            items(report.voiItems) { voi -> VoiCard(voi) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Missing info
        if (report.missingInfo.isNotEmpty()) {
            item { SectionHeader("Missing Information") }
            items(report.missingInfo) { mi ->
                StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(mi.description, style = MaterialTheme.typography.bodySmall)
                    Text("Impact: ${mi.expectedImpact}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Lens recommendations
        if (report.combinedRecommendations.isNotEmpty()) {
            item { SectionHeader("Framework Recommendations by Lens") }
            items(report.combinedRecommendations) { rec -> LensCard(rec) }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── Technical View ────────────────────────────────────────────────────────────

@Composable
fun TechnicalView(report: DecisionReport, vm: StrategosViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Scenario summary
        item {
            SectionHeader("Scenario Input Summary")
            StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                TechRow("Title", report.scenario.title)
                TechRow("Decision owner", report.scenario.decisionOwner.ifBlank { "Not set" })
                TechRow("Decision rule", report.scenario.decisionRule.name.replace("_", " "))
                TechRow("Stakeholders", "${report.scenario.stakeholders.size}")
                TechRow("Actions", "${report.scenario.actions.size}")
                TechRow("Payoff entries", "${report.scenario.payoffs.size}")
                TechRow("Evidence sources", "${report.scenario.evidenceSources.size}")
                TechRow("Beliefs", "${report.scenario.beliefs.size}")
                TechRow("Constraints", "${report.scenario.constraints.size}")
                TechRow("Completeness", "${report.scenario.completenessScore()}%")
                TechRow("Evidence quality", "${report.scenario.evidenceQuality()}%")
            }
        }

        // Analysis plan
        report.analysisPlan?.let { plan ->
            item {
                SectionHeader("Framework Selection")
                StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(plan.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Framework results
        if (report.moduleResults.isNotEmpty()) {
            item { SectionHeader("Framework Results (${report.moduleResults.size})") }
            items(report.moduleResults) { result ->
                var expanded by remember { mutableStateOf(false) }
                StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
                    Column(modifier = Modifier.clickable { expanded = !expanded }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(result.moduleName, style = MaterialTheme.typography.labelMedium)
                                Text("${result.frameworkCategory.name.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CalcStatusChip(result.calculationStatus)
                            Spacer(Modifier.width(4.dp))
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                StratDivider()
                                Spacer(Modifier.height(6.dp))
                                TechRow("Applicability", "${"%.0f".format(result.applicability.score * 100)}%")
                                if (result.calculationStatus == CalculationStatus.PROXY_ESTIMATE || result.calculationStatus == CalculationStatus.PARTIALLY_CALCULATED) {
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                                        Text("This result uses surrogate assumptions because required payoff or evidence data is incomplete. Use it to guide information collection, not as a validated prediction.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                                if (result.explanation.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(result.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                result.findings.take(3).forEach { f ->
                                    Spacer(Modifier.height(3.dp))
                                    Text("• ${f.title}", style = MaterialTheme.typography.labelSmall)
                                }
                                result.assumptions.take(3).forEach { a ->
                                    Spacer(Modifier.height(2.dp))
                                    Text("Assumption: ${a.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Evidence sources
        if (report.scenario.evidenceSources.isNotEmpty()) {
            item {
                SectionHeader("Evidence Sources")
                StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    report.scenario.evidenceSources.forEachIndexed { i, src ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("${i+1}. ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column {
                                Text(src.label, style = MaterialTheme.typography.bodySmall)
                                Text("${src.kind.name.replace("_", " ")} · Quality ${src.quality}/5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (i < report.scenario.evidenceSources.lastIndex) StratDivider()
                    }
                }
            }
        }

        // Sensitive assumptions audit
        if (report.sensitiveAssumptions.isNotEmpty()) {
            item {
                SectionHeader("Sensitive Assumptions")
                StratCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    report.sensitiveAssumptions.forEachIndexed { i, a ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Icon(Icons.Default.Info, null, modifier = Modifier.size(12.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(a, style = MaterialTheme.typography.bodySmall)
                        }
                        if (i < report.sensitiveAssumptions.lastIndex) StratDivider()
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── Components ────────────────────────────────────────────────────────────────

// Presentational leaf cards (PathwayCard, StakeholderForecastCard, RiskRegisterCard,
// VoiCard, LensCard, confidence cards, chips and rows) now live in ReportCards.kt.

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun decisionStatus(report: DecisionReport): Pair<String, Color> {
    return when (report.executiveStatus) {
        com.drkkr.strategos.domain.framework.ExecutiveStatus.PROCEED -> "Proceed" to Color(0xFF34C759)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.CONDITIONAL_TRIAL -> "Conditional Trial" to Color(0xFF34AEEA)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.HOLD_FOR_EVIDENCE -> "Hold — Collect Evidence" to Color(0xFFFF3B30)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.HOLD_FOR_VERIFICATION -> "Hold — Verify Constraints" to Color(0xFFFF3B30)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.EXPLORATORY_ONLY -> "Exploratory Only" to Color(0xFFFF9500)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.INDETERMINATE -> "Indeterminate" to Color(0xFFFF9500)
        com.drkkr.strategos.domain.framework.ExecutiveStatus.BLOCKED_CONSTRAINT -> "Blocked" to Color(0xFFFF3B30)
    }
}

private fun decisionStatusSentence(report: DecisionReport): String {
    val reason = report.executiveStatusReason
    val blocker = reason?.primaryBlocker()
    return when (report.executiveStatus) {
        com.drkkr.strategos.domain.framework.ExecutiveStatus.PROCEED ->
            "Decision model complete and constraints satisfied. Proceed with the recommended pathway."
        com.drkkr.strategos.domain.framework.ExecutiveStatus.CONDITIONAL_TRIAL ->
            "Action is reversible and low-impact. Proceed with a limited trial and monitor outcomes."
        com.drkkr.strategos.domain.framework.ExecutiveStatus.HOLD_FOR_EVIDENCE ->
            blocker?.let { if (it.startsWith("No evidence")) it else "No evidence sources recorded. $it" }
                ?: "No evidence sources recorded. Collect evidence before any recommendation is valid."
        com.drkkr.strategos.domain.framework.ExecutiveStatus.HOLD_FOR_VERIFICATION ->
            "Unverified constraints on irreversible or high-impact actions. ${blocker ?: "Assess all hard constraints before proceeding."}"
        com.drkkr.strategos.domain.framework.ExecutiveStatus.EXPLORATORY_ONLY ->
            "Insufficient model data for a formal recommendation. Expand the payoff matrix and evidence base."
        com.drkkr.strategos.domain.framework.ExecutiveStatus.INDETERMINATE ->
            "Options cannot be distinguished. ${blocker ?: "Add distinguishing evidence or refine payoff entries."}"
        com.drkkr.strategos.domain.framework.ExecutiveStatus.BLOCKED_CONSTRAINT ->
            "All candidate actions violate hard constraints. ${blocker ?: "Resolve constraint violations before analysis."}"
    }
}

private fun extractWhyReasons(report: DecisionReport, pathway: DecisionPathway?): List<String> {
    val reasons = mutableListOf<String>()
    if (pathway == null) return reasons
    val profile = report.confidenceProfile
    if (profile != null && profile.analyticalRobustness >= 0.6) {
        reasons += "Performs best across tested stakeholder-response scenarios."
    }
    val safetyConstraint = report.scenario.constraints.any { it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.SAFETY || it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.LEGAL }
    if (safetyConstraint) {
        reasons += "Preserves safety and legal constraints while keeping the decision pathway open."
    }
    val vetoStakeholders = report.scenario.stakeholders.filter { it.canVeto }
    if (vetoStakeholders.isNotEmpty()) {
        reasons += "Reduces likely resistance from key veto actor(s): ${vetoStakeholders.joinToString(", ") { it.name }.take(80)}."
    }
    if (reasons.isEmpty()) {
        reasons += pathway.rationale.take(120)
    }
    return reasons.take(3)
}
