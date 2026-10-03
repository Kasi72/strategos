package com.drkkr.strategos.ui.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.drkkr.strategos.BuildConfig
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.framework.BlockerSeverity
import com.drkkr.strategos.domain.framework.CalculationStatus
import com.drkkr.strategos.domain.framework.DecisionReadinessResult
import com.drkkr.strategos.domain.framework.Finding
import com.drkkr.strategos.domain.framework.FrameworkCategory
import com.drkkr.strategos.domain.framework.ModuleRunResult
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.domain.model.NavigationTarget
import com.drkkr.strategos.ui.components.BlockerActionCard
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.AnalysisPlan
import com.drkkr.strategos.framework.behavioral.ScenarioClassifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import com.drkkr.strategos.ui.DebugEntry
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.*

@Composable
fun AnalysisTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario

    if (scenario == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Analytics, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("Load a scenario first.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text("Analysis", style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(bottom = 4.dp))
            Text(scenario.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        }

        item {
            GameTypeClassificationCard(scenario)
            Spacer(Modifier.height(8.dp))
        }

        item {
            ScenarioHealthCard(scenario)
            Spacer(Modifier.height(8.dp))
        }

        // Snapshot invalidated warning
        if (state.snapshotInvalidated) {
            item {
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = Color(0xFFD97706))
                        Spacer(Modifier.width(8.dp))
                        Text("Model inputs changed — re-run analysis required.",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706))
                    }
                }
            }
        }

        // Actionable blockers from canonical DecisionReadinessResult
        val readiness = state.decisionReadiness
        if (readiness != null && readiness.blockers.isNotEmpty()) {
            item { SectionHeader("Blockers (${readiness.blockers.size})") }
            items(readiness.blockers) { blocker ->
                BlockerActionCard(
                    blocker = blocker,
                    onFix = { b -> vm.navigateTo(b.target) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        item {
            Button(
                onClick = { vm.analyze() },
                enabled = !state.isAnalyzing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (state.isAnalyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Analysing…", style = MaterialTheme.typography.titleSmall)
                } else {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run Analysis", style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        state.errorMessage?.let { err ->
            item {
                StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        state.analysisPlan?.let { plan ->
            item {
                AnalysisPlanCard(plan)
                Spacer(Modifier.height(8.dp))
            }
        }

        // Trust Dynamics card — shown when that module ran successfully
        val trustResult = state.moduleRunResults
            .firstOrNull { it.moduleId == "stakeholder-trust-dynamics" && it.status == ModuleRunStatus.SUCCESS }
            ?.result
            ?: state.analysisResults.firstOrNull { it.moduleId == "stakeholder-trust-dynamics" }
        trustResult?.let { tr ->
            item {
                com.drkkr.strategos.ui.components.TrustConditionsCard(
                    result = tr,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        // Relational Repair card — shown when that module ran successfully
        val repairResult = state.moduleRunResults
            .firstOrNull { it.moduleId == "relational-repair" && it.status == ModuleRunStatus.SUCCESS }
            ?.result
            ?: state.analysisResults.firstOrNull { it.moduleId == "relational-repair" }
        repairResult?.let { rr ->
            item {
                com.drkkr.strategos.ui.components.RelationalRepairCard(
                    result = rr,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        if (state.moduleRunResults.isNotEmpty()) {
            item { SectionHeader("Framework Results") }
            items(state.moduleRunResults) { run ->
                when (run.status) {
                    ModuleRunStatus.SUCCESS -> run.result?.let { FrameworkResultCard(it) }
                    ModuleRunStatus.FAILED_RECOVERABLY -> FailedModuleCard(run, onRetry = { vm.analyze() })
                    ModuleRunStatus.SKIPPED_NOT_APPLICABLE,
                    ModuleRunStatus.SKIPPED_INSUFFICIENT_INPUT -> { /* not shown */ }
                }
            }
        } else if (state.analysisResults.isNotEmpty()) {
            item { SectionHeader("Framework Results") }
            items(state.analysisResults) { result ->
                FrameworkResultCard(result)
            }
        }

        if (state.debugEntries.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                DebugLogButton(state.debugEntries)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisPlanCard(plan: AnalysisPlan) {
    val coreColor = MaterialTheme.colorScheme.primary
    val supportColor = MaterialTheme.colorScheme.secondary
    val exploratoryColor = MaterialTheme.colorScheme.tertiary
    val excludedColor = MaterialTheme.colorScheme.outline

    StratCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Analysis Plan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (plan.core.isNotEmpty()) {
            Text("CORE", style = MaterialTheme.typography.labelSmall, color = coreColor, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                plan.core.forEach { sel -> SemanticChip(sel.moduleName, coreColor) }
            }
        }
        if (plan.supporting.isNotEmpty()) {
            Text("SUPPORTING", style = MaterialTheme.typography.labelSmall, color = supportColor, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                plan.supporting.forEach { sel -> SemanticChip(sel.moduleName, supportColor) }
            }
        }
        if (plan.exploratory.isNotEmpty()) {
            Text("EXPLORATORY", style = MaterialTheme.typography.labelSmall, color = exploratoryColor, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                plan.exploratory.forEach { sel -> SemanticChip(sel.moduleName, exploratoryColor) }
            }
        }
        if (plan.excluded.isNotEmpty()) {
            Text("EXCLUDED (${plan.excluded.size})", style = MaterialTheme.typography.labelSmall, color = excludedColor, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun CalculationStatusChip(status: CalculationStatus) {
    val (label, color) = when (status) {
        CalculationStatus.CALCULATED -> "Calculated" to MaterialTheme.colorScheme.secondary
        CalculationStatus.PARTIALLY_CALCULATED -> "Partial" to MaterialTheme.colorScheme.tertiary
        CalculationStatus.PROXY_ESTIMATE -> "Proxy" to MaterialTheme.colorScheme.outline
        CalculationStatus.NOT_RUN -> "Not Run" to MaterialTheme.colorScheme.error
        CalculationStatus.EXCLUDED -> "Excluded" to MaterialTheme.colorScheme.error
    }
    SemanticChip(label, color)
}

@Composable
fun FrameworkResultCard(result: AnalysisResult) {
    var expanded by remember { mutableStateOf(false) }
    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.moduleName, style = MaterialTheme.typography.titleSmall)
                    if (result.frameworkCategory == FrameworkCategory.CORE) {
                        SemanticChip("CORE", MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "Applicability: ${"%.0f".format(result.applicability.score * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CalculationStatusChip(result.calculationStatus)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Expand")
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    thickness = 0.5.dp
                )
                result.applicability.reasons.forEach { r ->
                    Text("• $r", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
                }
                Spacer(Modifier.height(6.dp))
                result.findings.forEach { f -> FindingRow(f) }
                if (result.explanation.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Explanation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(result.explanation, style = MaterialTheme.typography.bodySmall)
                }
                if (result.recommendedActions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Recommended Actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.recommendedActions.forEach { rec ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(rec.label, style = MaterialTheme.typography.bodySmall)
                                Text(rec.rationale, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FailedModuleCard(run: ModuleRunResult, onRetry: () -> Unit) {
    var diagnosticExpanded by remember { mutableStateOf(false) }
    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.ErrorOutline, null,
                modifier = Modifier.size(18.dp).padding(top = 1.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${run.moduleName} could not run.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Other compatible analyses may still be available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (BuildConfig.DEBUG && run.diagnosticId != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { diagnosticExpanded = !diagnosticExpanded },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            if (diagnosticExpanded) "Hide diagnostics" else "Diagnostics [${run.diagnosticId}]",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    AnimatedVisibility(diagnosticExpanded) {
                        Column(modifier = Modifier.padding(top = 2.dp)) {
                            Text(
                                "ID: ${run.diagnosticId}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            run.userMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onRetry) {
                Text("Retry", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun FindingRow(finding: Finding) {
    val (icon, color) = when (finding.severity) {
        Finding.Severity.CRITICAL -> Icons.Default.Error to MaterialTheme.colorScheme.error
        Finding.Severity.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        Finding.Severity.NOTE -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        Finding.Severity.INFO -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
    }
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, null, modifier = Modifier.size(14.dp).padding(top = 2.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(finding.title, style = MaterialTheme.typography.bodySmall, color = color)
            Text(finding.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GameTypeClassificationCard(scenario: com.drkkr.strategos.domain.model.Scenario) {
    val classification = remember(scenario.id, scenario.payoffs, scenario.title, scenario.description) {
        ScenarioClassifier.classify(scenario)
    }
    if (classification.scores.isEmpty() && classification.primary == ScenarioClassifier.GameType.GENERAL) return

    val gameTypeLabel = when (classification.primary) {
        ScenarioClassifier.GameType.PRISONERS_DILEMMA -> "Prisoner's Dilemma"
        ScenarioClassifier.GameType.STAG_HUNT -> "Stag Hunt / Coordination"
        ScenarioClassifier.GameType.ULTIMATUM -> "Ultimatum Game"
        ScenarioClassifier.GameType.TRUST_GAME -> "Trust Game"
        ScenarioClassifier.GameType.PUBLIC_GOODS -> "Public Goods Game"
        ScenarioClassifier.GameType.BARGAINING -> "Bargaining Game"
        ScenarioClassifier.GameType.ZERO_SUM -> "Zero-Sum Game"
        ScenarioClassifier.GameType.AUCTION -> "Auction / Mechanism Design"
        ScenarioClassifier.GameType.SIGNALLING -> "Signalling / Credibility"
        ScenarioClassifier.GameType.FIRST_MOVER -> "Stackelberg / First-Mover"
        ScenarioClassifier.GameType.GENERAL -> "General Strategic Form"
    }

    StratCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Default.Psychology, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Detected Game Structure", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SemanticChip(gameTypeLabel, MaterialTheme.colorScheme.primary)
        if (classification.evidence.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            classification.evidence.forEach { ev ->
                Text("• $ev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        if (classification.scores.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                classification.scores.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .forEach { (type, score) ->
                        val label = type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                        SemanticChip("$label ${"%.0f".format(score * 100)}%", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
        }
    }
}

@Composable
fun ConfidenceBadge(confidence: Double) {
    val color = when {
        confidence >= 0.75 -> MaterialTheme.colorScheme.secondary
        confidence >= 0.5 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    SemanticChip(label = "${"%.0f".format(confidence * 100)}%", color = color)
}

@Composable
fun ScenarioHealthCard(scenario: Scenario) {
    val completeness = scenario.completenessScore()
    val evidenceQ = scenario.evidenceQuality()

    val missingItems = buildList {
        if (scenario.title.isBlank()) add("Title missing")
        if (scenario.description.length <= 30) add("Description too short")
        if (scenario.decisionOwner.isBlank()) add("Decision owner not set")
        if (scenario.stakeholders.size < 2) add("Need ≥2 stakeholders")
        if (scenario.actions.size < 2) add("Need ≥2 actions")
        if (scenario.payoffs.isEmpty()) add("No payoffs defined")
        if (scenario.constraints.isEmpty()) add("No constraints defined")
        if (scenario.evidenceSources.isEmpty()) add("No evidence sources")
        if (scenario.beliefs.isEmpty()) add("No beliefs/probabilities")
        if (scenario.stakes.isBlank()) add("Stakes not described")
    }

    // Structural completeness: blue/neutral (fields filled ≠ healthy decision)
    val completenessColor = when {
        completeness >= 75 -> Color(0xFF2367D1)
        completeness >= 45 -> Color(0xFFD97706)
        else -> Color(0xFFDC2626)
    }
    // Evidence quality: red at 0% — never misleadingly positive
    val evColor = when {
        evidenceQ == 0 -> Color(0xFFDC2626)
        evidenceQ >= 70 -> Color(0xFF16A34A)
        evidenceQ >= 40 -> Color(0xFFD97706)
        else -> Color(0xFFDC2626)
    }
    // Decision readiness: only green when both completeness AND evidence adequate
    val readinessColor = when {
        evidenceQ == 0 || completeness < 45 -> Color(0xFFDC2626)
        evidenceQ < 40 || completeness < 75 -> Color(0xFFD97706)
        else -> Color(0xFF16A34A)
    }

    StratCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Default.HealthAndSafety, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Structural Completeness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            SemanticChip("$completeness%", completenessColor)
        }

        LinearProgressIndicator(
            progress = { completeness / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = completenessColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Evidence quality:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SemanticChip(if (evidenceQ == 0) "None" else "$evidenceQ%", evColor)
            if (evidenceQ == 0) {
                Text("— no sources recorded", style = MaterialTheme.typography.labelSmall, color = evColor)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Decision readiness:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val readinessLabel = when {
                evidenceQ == 0 -> "Not ready"
                evidenceQ < 40 || completeness < 75 -> "Partial"
                else -> "Ready"
            }
            SemanticChip(readinessLabel, readinessColor)
        }

        if (missingItems.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("To improve model quality:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            missingItems.take(5).forEach { item ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Icon(Icons.Default.Warning, null, modifier = Modifier.size(12.dp).padding(top = 2.dp), tint = Color(0xFFFF9500))
                    Spacer(Modifier.width(4.dp))
                    Text(item, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogButton(entries: List<DebugEntry>) {
    var showSheet by remember { mutableStateOf(false) }
    val errorCount = entries.count { it.level == DebugEntry.Level.ERROR }

    val errorBorder = androidx.compose.foundation.BorderStroke(
        1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    )
    OutlinedButton(
        onClick = { showSheet = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = errorBorder
    ) {
        Icon(Icons.Default.BugReport, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (errorCount > 0) "Debug Log — $errorCount error${if (errorCount > 1) "s" else ""}"
            else "Debug Log",
            style = MaterialTheme.typography.labelMedium
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            DebugSheet(entries, onDismiss = { showSheet = false })
        }
    }
}

@Composable
fun DebugSheet(entries: List<DebugEntry>, onDismiss: () -> Unit) {
    var expandedIndex by remember { mutableStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Debug Log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "${entries.size} entr${if (entries.size == 1) "y" else "ies"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        entries.forEachIndexed { idx, entry ->
            val levelColor = when (entry.level) {
                DebugEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                DebugEntry.Level.WARNING -> MaterialTheme.colorScheme.tertiary
                DebugEntry.Level.INFO -> MaterialTheme.colorScheme.primary
            }
            val levelLabel = when (entry.level) {
                DebugEntry.Level.ERROR -> "ERROR"
                DebugEntry.Level.WARNING -> "WARN"
                DebugEntry.Level.INFO -> "INFO"
            }
            val isExpanded = expandedIndex == idx

            StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    SemanticChip(levelLabel, levelColor)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = levelColor)
                    }
                    if (entry.detail.isNotBlank()) {
                        IconButton(
                            onClick = { expandedIndex = if (isExpanded) -1 else idx },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                "Toggle detail",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (isExpanded && entry.detail.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
