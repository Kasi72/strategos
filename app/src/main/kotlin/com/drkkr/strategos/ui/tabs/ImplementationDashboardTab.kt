package com.drkkr.strategos.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.SectionHeader
import com.drkkr.strategos.ui.components.SemanticChip
import com.drkkr.strategos.ui.components.StratCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun Long.toDateStr(): String = if (this == 0L) "—" else dateFmt.format(Date(this))

@Composable
fun ImplementationDashboardTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario

    if (scenario == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Dashboard, null, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("Load a scenario first.", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val gateResult = remember(scenario) { ImplementationGate.evaluate(scenario) }
    val plan = scenario.implementationPlan
    var showCreatePlanDialog by remember { mutableStateOf(false) }
    var showAddMonitoringDialog by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var closeReason by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        item {
            Text(scenario.title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                scenario.lifecycleStage.displayLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // ── IMPLEMENTATION GATE ──────────────────────────────────────────────
        item {
            ImplementationGateCard(gateResult)
            Spacer(Modifier.height(8.dp))
        }

        // ── LIFECYCLE CONTROLS ───────────────────────────────────────────────
        item {
            LifecycleControlsRow(
                scenario = scenario,
                onBeginImplementation = { vm.beginImplementation() },
                onPause = { vm.triggerStopSignal("User-triggered pause") },
                onResume = { vm.resumeFromPause() },
                onRevert = { vm.revertFromPause() },
                onClose = { showCloseDialog = true }
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── SELECTED POLICY OPTION ───────────────────────────────────────────
        item {
            SectionHeader("Selected Policy Option")
            PolicyOptionCard(scenario)
            Spacer(Modifier.height(8.dp))
        }

        // ── APPROVAL ROUTE ───────────────────────────────────────────────────
        if (scenario.approvalStages.isNotEmpty()) {
            item { SectionHeader("Approval Route (${scenario.approvalStages.size})") }
            items(scenario.approvalStages) { stage ->
                ApprovalStageCard(stage, scenario)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── IMPLEMENTATION PLAN ──────────────────────────────────────────────
        item {
            SectionHeader("Implementation Plan")
            if (plan != null) {
                ImplementationPlanCard(plan, scenario)
            } else {
                StratCard {
                    Column {
                        Text(
                            "No implementation plan recorded.\nRequired before implementation can begin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { showCreatePlanDialog = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Create implementation plan")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── MONITORING ───────────────────────────────────────────────────────
        val entries = plan?.monitoringEntries ?: emptyList()
        item {
            SectionHeader("Monitoring (${entries.size} entries)")
            if (scenario.lifecycleStage.isImplementationPhase() || scenario.lifecycleStage == LifecycleStage.MONITORING) {
                TextButton(
                    onClick = { showAddMonitoringDialog = true },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add monitoring entry", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                StratCard {
                    Text(
                        "No monitoring entries recorded. Add entries to track observed outcomes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            items(entries.sortedByDescending { it.date }) { entry ->
                MonitoringEntryCard(entry)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── REVISION HISTORY ─────────────────────────────────────────────────
        item {
            SectionHeader("Revision History")
            RevisionHistoryPanel(scenario)
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showCreatePlanDialog) {
        CreatePlanDialog(
            scenario = scenario,
            onDismiss = { showCreatePlanDialog = false },
            onSave = { p -> vm.createPlan(p); showCreatePlanDialog = false }
        )
    }
    if (showAddMonitoringDialog) {
        AddMonitoringEntryDialog(
            scenario = scenario,
            onDismiss = { showAddMonitoringDialog = false },
            onSave = { e -> vm.addMonitoringEntry(e); showAddMonitoringDialog = false }
        )
    }
    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Close Scenario") },
            text = {
                Column {
                    Text("Record a reason for closing this scenario:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = closeReason, onValueChange = { closeReason = it },
                        label = { Text("Reason") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.closeScenario(closeReason.ifBlank { "Closed by user" }); showCloseDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { showCloseDialog = false }) { Text("Cancel") } }
        )
    }
}

// ── Lifecycle Controls Row ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LifecycleControlsRow(
    scenario: Scenario,
    onBeginImplementation: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRevert: () -> Unit,
    onClose: () -> Unit
) {
    val stage = scenario.lifecycleStage
    val canBegin = ScenarioCapabilities.canStartImplementation(scenario).allowed
    val canPause = ScenarioCapabilities.canPause(scenario).allowed
    val canResume = ScenarioCapabilities.canResume(scenario).allowed
    val canClose = ScenarioCapabilities.canClose(scenario).allowed

    if (!canBegin && !canPause && !canResume && !canClose) return

    StratCard {
        Column {
            Text("Lifecycle Actions", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (canBegin) {
                    Button(
                        onClick = onBeginImplementation,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Begin", style = MaterialTheme.typography.labelSmall) }
                }
                if (canPause) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9500)),
                        border = BorderStroke(1.dp, Color(0xFFFF9500))
                    ) { Text("Pause", style = MaterialTheme.typography.labelSmall) }
                }
                if (canResume) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                    ) { Text("Resume", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = onRevert,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) { Text("Revert", style = MaterialTheme.typography.labelSmall) }
                }
                if (canClose) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) { Text("Close", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

// ── Create Plan Dialog ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePlanDialog(
    scenario: Scenario,
    onDismiss: () -> Unit,
    onSave: (ImplementationPlan) -> Unit
) {
    var owner by remember { mutableStateOf("") }
    var approver by remember { mutableStateOf("") }
    var stopCondition by remember { mutableStateOf("") }
    var reversalPlan by remember { mutableStateOf("") }
    var budgetText by remember { mutableStateOf("") }
    var durationDaysText by remember { mutableStateOf("") }
    val selectedActionId = scenario.selectedPolicyOption?.actionId ?: scenario.actions.firstOrNull()?.id ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Create Implementation Plan", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = owner, onValueChange = { owner = it },
                    label = { Text("Plan owner *") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = approver, onValueChange = { approver = it },
                    label = { Text("Accountable approver") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = stopCondition, onValueChange = { stopCondition = it },
                    label = { Text("Stop condition *") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )
                OutlinedTextField(
                    value = reversalPlan, onValueChange = { reversalPlan = it },
                    label = { Text("Reversal plan") },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = budgetText, onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Budget") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = durationDaysText, onValueChange = { durationDaysText = it.filter { c -> c.isDigit() } },
                        label = { Text("Days") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (owner.isNotBlank() && stopCondition.isNotBlank()) {
                        onSave(ImplementationPlan(
                            owner = owner.trim(),
                            accountableApprover = approver.trim(),
                            selectedActionId = selectedActionId,
                            stopCondition = stopCondition.trim(),
                            reversalPlan = reversalPlan.trim(),
                            budget = budgetText.toDoubleOrNull() ?: 0.0,
                            estimatedDurationDays = durationDaysText.toIntOrNull() ?: 0
                        ))
                    }
                },
                shape = RoundedCornerShape(12.dp),
                enabled = owner.isNotBlank() && stopCondition.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Add Monitoring Entry Dialog ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMonitoringEntryDialog(
    scenario: Scenario,
    onDismiss: () -> Unit,
    onSave: (MonitoringEntry) -> Unit
) {
    var observedOutcome by remember { mutableStateOf("") }
    var expectedOutcome by remember { mutableStateOf("") }
    var correctiveAction by remember { mutableStateOf("") }
    var signal by remember { mutableStateOf(MonitoringSignal.ON_TRACK) }
    var costVarianceText by remember { mutableStateOf("") }
    var timeVarianceText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Add Monitoring Entry", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Signal status", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonitoringSignal.entries.forEach { s ->
                        val isSelected = signal == s
                        val color = when (s) {
                            MonitoringSignal.ON_TRACK -> Color(0xFF34C759)
                            MonitoringSignal.WATCH -> Color(0xFFFF9500)
                            MonitoringSignal.ALERT, MonitoringSignal.STOP_TRIGGERED -> Color(0xFFFF3B30)
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { signal = s },
                            label = { Text(s.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.15f),
                                selectedLabelColor = color
                            )
                        )
                    }
                }
                OutlinedTextField(
                    value = observedOutcome, onValueChange = { observedOutcome = it },
                    label = { Text("Observed outcome *") },
                    modifier = Modifier.fillMaxWidth().height(70.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )
                OutlinedTextField(
                    value = expectedOutcome, onValueChange = { expectedOutcome = it },
                    label = { Text("Expected outcome") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = correctiveAction, onValueChange = { correctiveAction = it },
                    label = { Text("Corrective action (if any)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = costVarianceText,
                        onValueChange = { costVarianceText = it.filter { c -> c.isDigit() || c == '-' || c == '.' } },
                        label = { Text("Cost variance") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = timeVarianceText,
                        onValueChange = { timeVarianceText = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Time (days)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (observedOutcome.isNotBlank()) {
                        onSave(MonitoringEntry(
                            observedOutcome = observedOutcome.trim(),
                            expectedOutcome = expectedOutcome.trim(),
                            correctiveAction = correctiveAction.trim(),
                            signalStatus = signal,
                            costVariance = costVarianceText.toDoubleOrNull() ?: 0.0,
                            timeVarianceDays = timeVarianceText.toIntOrNull() ?: 0
                        ))
                    }
                },
                shape = RoundedCornerShape(12.dp),
                enabled = observedOutcome.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Implementation Gate Card ─────────────────────────────────────────────────

@Composable
private fun ImplementationGateCard(result: ImplementationGateResult) {
    val (statusColor, statusIcon) = when (result.overallStatus) {
        ImplementationGateOverallStatus.BLOCKED -> Color(0xFFFF3B30) to Icons.Default.Block
        ImplementationGateOverallStatus.HOLD_FOR_VERIFICATION -> Color(0xFFFF9500) to Icons.Default.HourglassEmpty
        ImplementationGateOverallStatus.READY_FOR_APPROVAL -> Color(0xFF007AFF) to Icons.Default.FolderOpen
        ImplementationGateOverallStatus.APPROVED_NOT_STARTED -> Color(0xFF34C759) to Icons.Default.CheckCircle
        ImplementationGateOverallStatus.IN_IMPLEMENTATION -> Color(0xFF007AFF) to Icons.Default.PlayArrow
        ImplementationGateOverallStatus.MONITORING -> Color(0xFF5856D6) to Icons.Default.TrackChanges
        ImplementationGateOverallStatus.PAUSED -> Color(0xFFFF3B30) to Icons.Default.Pause
        ImplementationGateOverallStatus.CLOSED -> Color(0xFF8E8E93) to Icons.Default.Archive
    }

    StratCard {
        // Status header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(statusIcon, null, modifier = Modifier.size(16.dp), tint = statusColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        result.overallStatus.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
        }

        // Blocked by
        if (result.blockingReasons.isNotEmpty()) {
            Text(
                "Blocked by:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF3B30),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            result.blockingReasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 3.dp)) {
                    Icon(Icons.Default.Block, null, modifier = Modifier.size(12.dp).padding(top = 2.dp),
                        tint = Color(0xFFFF3B30))
                    Spacer(Modifier.width(6.dp))
                    Text(reason, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Next action
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(14.dp).padding(top = 1.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                result.nextRequiredAction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Gates detail
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Spacer(Modifier.height(8.dp))
        result.gates.forEach { gate ->
            val gateColor = when (gate.status) {
                GateStatus.PASS -> Color(0xFF34C759)
                GateStatus.AMBER -> Color(0xFFFF9500)
                GateStatus.BLOCKED -> Color(0xFFFF3B30)
                GateStatus.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
            val gateIcon = when (gate.status) {
                GateStatus.PASS -> Icons.Default.CheckCircle
                GateStatus.AMBER -> Icons.Default.Warning
                GateStatus.BLOCKED -> Icons.Default.Cancel
                GateStatus.NOT_APPLICABLE -> Icons.Default.RemoveCircleOutline
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(gateIcon, null, modifier = Modifier.size(13.dp), tint = gateColor)
                    Spacer(Modifier.width(6.dp))
                    Text(gate.name, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                if (gate.detail.isNotBlank()) {
                    Text(
                        gate.detail, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

// ── Policy Option Card ───────────────────────────────────────────────────────

@Composable
private fun PolicyOptionCard(scenario: Scenario) {
    val option = scenario.selectedPolicyOption
    StratCard {
        if (option == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RadioButtonUnchecked, null, modifier = Modifier.size(16.dp),
                    tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(8.dp))
                Text("No policy option selected. Required before requesting approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp),
                    tint = Color(0xFF34C759))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(option.actionLabel, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (option.rationale.isNotBlank()) {
                        Text(option.rationale, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (option.selectedBy.isNotBlank()) {
                        Text("Selected by: ${option.selectedBy} · ${option.selectedAt.toDateStr()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// ── Approval Stage Card ──────────────────────────────────────────────────────

@Composable
private fun ApprovalStageCard(stage: ApprovalStage, scenario: Scenario) {
    val (color, icon) = when {
        stage.invalidatedAt > 0L -> Color(0xFFFF3B30) to Icons.Default.Cancel
        stage.status == ApprovalStatus.APPROVED -> Color(0xFF34C759) to Icons.Default.CheckCircle
        stage.status == ApprovalStatus.REJECTED -> Color(0xFFFF3B30) to Icons.Default.Cancel
        stage.status == ApprovalStatus.EXPIRED -> Color(0xFF8E8E93) to Icons.Default.Timer
        else -> Color(0xFFFF9500) to Icons.Default.HourglassEmpty
    }

    StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                Icon(icon, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stage.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stage.threshold.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (stage.requiredStakeholderIds.isNotEmpty()) {
                        val names = stage.requiredStakeholderIds.mapNotNull { id ->
                            scenario.stakeholders.find { it.id == id }?.name
                        }
                        if (names.isNotEmpty()) {
                            Text("Required: ${names.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (stage.rationale.isNotBlank()) {
                        Text(stage.rationale, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
                    Text(stage.statusLabel(), style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (stage.recordedAt > 0L) {
                    Text(stage.recordedAt.toDateStr(), style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (stage.expiryDate > 0L) {
                    Text("Expires ${stage.expiryDate.toDateStr()}",
                        style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        color = if (System.currentTimeMillis() > stage.expiryDate)
                            Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// ── Implementation Plan Card ─────────────────────────────────────────────────

@Composable
private fun ImplementationPlanCard(plan: ImplementationPlan, scenario: Scenario) {
    StratCard {
        // Owner + approver
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Owner", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(plan.owner.ifBlank { "Unassigned" },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = if (plan.owner.isBlank()) Color(0xFFFF3B30)
                    else MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Approver", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(plan.accountableApprover.ifBlank { "Not set" },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // Selected action
        val selectedAction = scenario.actions.find { it.id == plan.selectedActionId }
        if (selectedAction != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            Text("Primary action", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selectedAction.label, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        }

        // Fallback
        val fallbackAction = scenario.actions.find { it.id == plan.fallbackActionId }
        if (fallbackAction != null) {
            Spacer(Modifier.height(4.dp))
            Text("Fallback", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fallbackAction.label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp))
        } else if (plan.fallbackAction.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Fallback", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(plan.fallbackAction, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp))
        }

        // Budget / duration
        if (plan.budget > 0 || plan.estimatedDurationDays > 0) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (plan.budget > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Budget", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.0f".format(plan.budget), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                if (plan.estimatedDurationDays > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Est. duration", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${plan.estimatedDurationDays}d", style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Milestones
        if (plan.milestones.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            Text("Milestones (${plan.milestones.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            plan.milestones.forEach { ms ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                    Icon(
                        if (ms.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null, modifier = Modifier.size(13.dp).padding(top = 1.dp),
                        tint = if (ms.completed) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(ms.description, style = MaterialTheme.typography.labelSmall)
                        if (ms.targetDate > 0L) {
                            Text(ms.targetDate.toDateStr(), style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Stop condition
        if (plan.stopCondition.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(13.dp).padding(top = 1.dp),
                    tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Stop condition", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = Color(0xFFFF3B30))
                    Text(plan.stopCondition, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Reversal plan
        if (plan.reversalPlan.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Undo, null, modifier = Modifier.size(13.dp).padding(top = 1.dp),
                    tint = Color(0xFFFF9500))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Reversal plan", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = Color(0xFFFF9500))
                    Text(plan.reversalPlan, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Early warning indicators
        if (plan.earlyWarningIndicators.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            Text("Early warning indicators",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            plan.earlyWarningIndicators.forEach { indicator ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 1.dp)) {
                    Icon(Icons.Default.NotificationsActive, null,
                        modifier = Modifier.size(11.dp).padding(top = 2.dp),
                        tint = Color(0xFFFF9500))
                    Spacer(Modifier.width(5.dp))
                    Text(indicator, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ── Monitoring Entry Card ────────────────────────────────────────────────────

@Composable
private fun MonitoringEntryCard(entry: MonitoringEntry) {
    val (signalColor, signalLabel, signalIcon) = when (entry.signalStatus) {
        MonitoringSignal.ON_TRACK -> Triple(Color(0xFF34C759), "On Track", Icons.Default.CheckCircle)
        MonitoringSignal.WATCH -> Triple(Color(0xFFFF9500), "Watch", Icons.Default.Visibility)
        MonitoringSignal.ALERT -> Triple(Color(0xFFFF3B30), "Alert", Icons.Default.Warning)
        MonitoringSignal.STOP_TRIGGERED -> Triple(Color(0xFFFF3B30), "STOP TRIGGERED", Icons.Default.Stop)
    }

    StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(entry.date.toDateStr(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon, null, modifier = Modifier.size(12.dp), tint = signalColor)
                Spacer(Modifier.width(4.dp))
                Text(signalLabel, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = signalColor)
            }
        }

        if (entry.observedOutcome.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 3.dp)) {
                Text("Observed: ", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.observedOutcome, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (entry.expectedOutcome.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 3.dp)) {
                Text("Expected: ", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.expectedOutcome, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (entry.costVariance != 0.0 || entry.timeVarianceDays != 0) {
            Row(modifier = Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry.costVariance != 0.0) {
                    val costColor = if (entry.costVariance > 0) Color(0xFFFF3B30) else Color(0xFF34C759)
                    Text("Cost: ${if (entry.costVariance > 0) "+" else ""}${"%.0f".format(entry.costVariance)}",
                        style = MaterialTheme.typography.labelSmall, color = costColor)
                }
                if (entry.timeVarianceDays != 0) {
                    val timeColor = if (entry.timeVarianceDays > 0) Color(0xFFFF3B30) else Color(0xFF34C759)
                    Text("Time: ${if (entry.timeVarianceDays > 0) "+" else ""}${entry.timeVarianceDays}d",
                        style = MaterialTheme.typography.labelSmall, color = timeColor)
                }
            }
        }
        if (entry.correctiveAction.isNotBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(11.dp).padding(top = 1.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(5.dp))
                Text(entry.correctiveAction, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (entry.ownerNote.isNotBlank()) {
            Text(entry.ownerNote, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// ── Revision History ─────────────────────────────────────────────────────────

@Composable
private fun RevisionHistoryPanel(scenario: Scenario) {
    StratCard {
        val history = scenario.changeHistory.sortedByDescending { it.timestamp }
        if (history.isEmpty()) {
            Text("No revision history recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.forEach { record ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.width(6.dp).align(Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(
                                    if (record.invalidatedApproval) Color(0xFFFF3B30)
                                    else MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(record.summary, style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium)
                        if (record.fieldChanged.isNotBlank()) {
                            Text("${record.fieldChanged}: \"${record.oldValue.take(40)}\" → \"${record.newValue.take(40)}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        if (record.reason.isNotBlank()) {
                            Text("Reason: ${record.reason}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(record.timestamp.toDateStr(), style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("v${record.previousVersion}→v${record.previousVersion + 1}",
                                style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (record.invalidatedApproval) {
                                Text("Invalidated approval", style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp, color = Color(0xFFFF3B30))
                            }
                            if (record.invalidatedSnapshot) {
                                Text("Invalidated snapshot", style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp, color = Color(0xFFFF9500))
                            }
                        }
                    }
                }
                if (history.last() != record) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
