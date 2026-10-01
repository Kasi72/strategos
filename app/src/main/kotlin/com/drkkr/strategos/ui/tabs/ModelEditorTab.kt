package com.drkkr.strategos.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorTab(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    val scenario = state.activeScenario
    if (scenario == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("No active scenario.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Create or load one from Scenarios.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Scenario metadata
        item {
            SectionHeader("Scenario")
            StratCard(modifier = Modifier.padding(bottom = 16.dp)) {
                OutlinedTextField(
                    value = scenario.title,
                    onValueChange = { vm.updateActiveScenario(scenario.copy(title = it)) },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = scenario.description,
                    onValueChange = { vm.updateActiveScenario(scenario.copy(description = it)) },
                    label = { Text("Description / Context") },
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = scenario.decisionOwner,
                    onValueChange = { vm.updateActiveScenario(scenario.copy(decisionOwner = it)) },
                    label = { Text("Decision owner") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )
            }
        }

        // Governance
        item {
            SectionHeader("Decision Governance")
            GovernanceCard(scenario = scenario, onUpdate = { vm.updateActiveScenario(it) })
        }

        // Stakeholders
        item { SectionHeader("Stakeholders") }
        itemsIndexed(scenario.stakeholders) { idx, sh ->
            StakeholderEditorCard(
                stakeholder = sh,
                allStakeholders = scenario.stakeholders,
                onUpdate = { updated ->
                    vm.updateActiveScenario(scenario.copy(stakeholders = scenario.stakeholders.toMutableList().also { it[idx] = updated }))
                },
                onDelete = {
                    vm.updateActiveScenario(scenario.copy(stakeholders = scenario.stakeholders.filterIndexed { i, _ -> i != idx }))
                }
            )
        }
        item {
            TextButton(
                onClick = {
                    val newSh = Stakeholder(name = "Stakeholder ${scenario.stakeholders.size + 1}")
                    vm.updateActiveScenario(scenario.copy(stakeholders = scenario.stakeholders + newSh))
                },
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Stakeholder")
            }
        }

        // Actions
        item { SectionHeader("Actions") }
        itemsIndexed(scenario.actions) { idx, action ->
            ActionEditorCard(
                action = action,
                stakeholders = scenario.stakeholders,
                onUpdate = { updated ->
                    vm.updateActiveScenario(scenario.copy(actions = scenario.actions.toMutableList().also { it[idx] = updated }))
                },
                onDelete = {
                    vm.updateActiveScenario(scenario.copy(actions = scenario.actions.filterIndexed { i, _ -> i != idx }))
                }
            )
        }
        item {
            if (scenario.stakeholders.isNotEmpty()) {
                TextButton(
                    onClick = {
                        val newAction = Action(stakeholderId = scenario.stakeholders.first().id, label = "Action ${scenario.actions.size + 1}")
                        vm.updateActiveScenario(scenario.copy(actions = scenario.actions + newAction))
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Action")
                }
            }
        }

        // Constraints + Assessments
        if (scenario.constraints.isNotEmpty()) {
            item { SectionHeader("Constraints & Assessments") }
            itemsIndexed(scenario.constraints) { _, con ->
                ConstraintAssessmentCard(
                    constraint = con,
                    scenario = scenario,
                    onUpsert = { vm.upsertConstraintAssessment(it) },
                    onDelete = { vm.deleteConstraintAssessment(it) }
                )
            }
        }

        // Payoff weights
        item { SectionHeader("Payoff Dimension Weights") }
        item {
            StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
                PayoffDimension.values().forEach { dim ->
                    val w = scenario.weights[dim] ?: 0.0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(dim.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = w.toFloat(),
                            onValueChange = { v ->
                                vm.updateActiveScenario(scenario.copy(weights = scenario.weights.toMutableMap().also { it[dim] = v.toDouble() }))
                            },
                            valueRange = 0f..5f,
                            steps = 9,
                            modifier = Modifier.width(150.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("%.1f".format(w), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(24.dp))
                    }
                }
            }
        }

        // Assumption log
        if (scenario.assumptionLog.isNotEmpty()) {
            item { SectionHeader("Assumption Log") }
            itemsIndexed(scenario.assumptionLog) { _, entry ->
                StratCard(modifier = Modifier.padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssumptionChip(entry.status)
                        Spacer(Modifier.width(10.dp))
                        Text(entry.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── Governance Card ───────────────────────────────────────────────────────────

@Composable
fun GovernanceCard(scenario: com.drkkr.strategos.domain.model.Scenario, onUpdate: (com.drkkr.strategos.domain.model.Scenario) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    StratCard(modifier = Modifier.padding(bottom = 16.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Gavel, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Decision Rule", style = MaterialTheme.typography.titleSmall)
                    Text(decisionRuleLabel(scenario.decisionRule), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("Select the rule that governs coalition formation and veto rights:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    DecisionRule.entries.forEach { rule ->
                        val selected = scenario.decisionRule == rule
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onUpdate(scenario.copy(decisionRule = rule)) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = { onUpdate(scenario.copy(decisionRule = rule)) })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(decisionRuleLabel(rule), style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(decisionRuleExplanation(rule), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Stakeholder Editor Card ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StakeholderEditorCard(
    stakeholder: Stakeholder,
    allStakeholders: List<Stakeholder>,
    onUpdate: (Stakeholder) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(0) }

    StratCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column {
            Row(modifier = Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stakeholder.name.ifBlank { "Unnamed" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            if (stakeholder.role.isNotBlank()) append(stakeholder.role)
                            if (stakeholder.canVeto) append(" · Veto")
                            if (stakeholder.approvalRole.isNotBlank()) append(" · ${stakeholder.approvalRole}")
                        }.ifBlank { "No role set" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    SemanticChip("A:${stakeholder.authority}", MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    SemanticChip("I:${stakeholder.influence}", MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(10.dp))
                    // Section tabs
                    val sections = listOf("Identity", "Interests", "Relationships", "Behavior")
                    ScrollableTabRow(
                        selectedTabIndex = activeSection,
                        containerColor = MaterialTheme.colorScheme.surface,
                        edgePadding = 0.dp
                    ) {
                        sections.forEachIndexed { idx, label ->
                            Tab(
                                selected = activeSection == idx,
                                onClick = { activeSection = idx },
                                text = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (activeSection) {
                        0 -> StakeholderIdentitySection(stakeholder, onUpdate)
                        1 -> StakeholderInterestsSection(stakeholder, onUpdate)
                        2 -> StakeholderRelationshipsSection(stakeholder, allStakeholders, onUpdate)
                        3 -> StakeholderBehaviorSection(stakeholder, onUpdate)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove stakeholder", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StakeholderIdentitySection(sh: Stakeholder, onUpdate: (Stakeholder) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = sh.name,
            onValueChange = { onUpdate(sh.copy(name = it)) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = sh.role,
            onValueChange = { onUpdate(sh.copy(role = it)) },
            label = { Text("Role / title") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = sh.approvalRole,
            onValueChange = { onUpdate(sh.copy(approvalRole = it)) },
            label = { Text("Approval role (e.g. Final sign-off, Recommender)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        // Type selector
        Text("Stakeholder type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StakeholderType.entries.forEach { type ->
                FilterChip(
                    selected = sh.type == type,
                    onClick = { onUpdate(sh.copy(type = type)) },
                    label = { Text(type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        // Authority and influence sliders
        SliderField("Authority (formal decision rights)", sh.authority.toFloat(), 0f, 10f) {
            onUpdate(sh.copy(authority = it.toInt()))
        }
        SliderField("Influence (informal)", sh.influence.toFloat(), 0f, 10f) {
            onUpdate(sh.copy(influence = it.toInt()))
        }
        SliderField("Commitment credibility", sh.credibility.toFloat(), 0f, 10f) {
            onUpdate(sh.copy(credibility = it.toInt()))
        }
        // Flags
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sh.canVeto, onClick = { onUpdate(sh.copy(canVeto = !sh.canVeto)) }, label = { Text("Can veto", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = sh.canDelay, onClick = { onUpdate(sh.copy(canDelay = !sh.canDelay)) }, label = { Text("Can delay", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = sh.canExit, onClick = { onUpdate(sh.copy(canExit = !sh.canExit)) }, label = { Text("Can exit", style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StakeholderInterestsSection(sh: Stakeholder, onUpdate: (Stakeholder) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StringListEditor("Goals (public)", sh.goals) { onUpdate(sh.copy(goals = it)) }
        StringListEditor("Private goals", sh.privateGoals) { onUpdate(sh.copy(privateGoals = it)) }
        StringListEditor("Fears (losses/risks they avoid)", sh.fears) { onUpdate(sh.copy(fears = it)) }
        StringListEditor("Resources controlled", sh.resourcesControlled) { onUpdate(sh.copy(resourcesControlled = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StakeholderRelationshipsSection(
    sh: Stakeholder,
    allStakeholders: List<Stakeholder>,
    onUpdate: (Stakeholder) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val otherStakeholders = allStakeholders.filter { it.id != sh.id }
        if (otherStakeholders.isNotEmpty()) {
            Text("Trusts (select stakeholders they trust)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                otherStakeholders.forEach { other ->
                    FilterChip(
                        selected = sh.trustRelationships.contains(other.id),
                        onClick = {
                            val updated = if (sh.trustRelationships.contains(other.id))
                                sh.trustRelationships - other.id
                            else
                                sh.trustRelationships + other.id
                            onUpdate(sh.copy(trustRelationships = updated))
                        },
                        label = { Text(other.name.take(20), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Coalitions with", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                otherStakeholders.forEach { other ->
                    FilterChip(
                        selected = sh.coalitionsWith.contains(other.id),
                        onClick = {
                            val updated = if (sh.coalitionsWith.contains(other.id))
                                sh.coalitionsWith - other.id
                            else
                                sh.coalitionsWith + other.id
                            onUpdate(sh.copy(coalitionsWith = updated))
                        },
                        label = { Text(other.name.take(20), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        } else {
            Text("Add more stakeholders to map relationships.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StringListEditor("Dependencies (what they need from others)", sh.dependencies) { onUpdate(sh.copy(dependencies = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StakeholderBehaviorSection(sh: Stakeholder, onUpdate: (Stakeholder) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Behavioral assumptions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sh.behavioralAssumptions.forEachIndexed { idx, assump ->
            StratCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = assump.text,
                        onValueChange = { newText ->
                            val updated = sh.behavioralAssumptions.toMutableList()
                            updated[idx] = assump.copy(text = newText)
                            onUpdate(sh.copy(behavioralAssumptions = updated))
                        },
                        label = { Text("Assumption") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 3
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FactStatus.entries.forEach { status ->
                            FilterChip(
                                selected = assump.status == status,
                                onClick = {
                                    val updated = sh.behavioralAssumptions.toMutableList()
                                    updated[idx] = assump.copy(status = status)
                                    onUpdate(sh.copy(behavioralAssumptions = updated))
                                },
                                label = { Text(status.name.replace("_", " ").lowercase(), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = assump.evidenceNote,
                        onValueChange = { note ->
                            val updated = sh.behavioralAssumptions.toMutableList()
                            updated[idx] = assump.copy(evidenceNote = note)
                            onUpdate(sh.copy(behavioralAssumptions = updated))
                        },
                        label = { Text("Evidence note") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            onUpdate(sh.copy(behavioralAssumptions = sh.behavioralAssumptions.filterIndexed { i, _ -> i != idx }))
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        TextButton(onClick = {
            onUpdate(sh.copy(behavioralAssumptions = sh.behavioralAssumptions + BehavioralAssumption(text = "")))
        }) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add assumption", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Action Editor Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionEditorCard(
    action: Action,
    stakeholders: List<Stakeholder>,
    onUpdate: (Action) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val owner = stakeholders.firstOrNull { it.id == action.stakeholderId }?.name ?: "?"

    StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
        Column {
            Row(modifier = Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.label.ifBlank { "Unnamed action" }, style = MaterialTheme.typography.titleSmall)
                    Text("[$owner]${if (action.description.isNotBlank()) " · ${action.description.take(60)}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StratDivider()
                    Spacer(Modifier.height(2.dp))
                    OutlinedTextField(
                        value = action.label,
                        onValueChange = { onUpdate(action.copy(label = it)) },
                        label = { Text("Label") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = action.description,
                        onValueChange = { onUpdate(action.copy(description = it)) },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        maxLines = 3
                    )
                    if (stakeholders.isNotEmpty()) {
                        Text("Owner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            stakeholders.forEach { sh ->
                                FilterChip(
                                    selected = action.stakeholderId == sh.id,
                                    onClick = { onUpdate(action.copy(stakeholderId = sh.id)) },
                                    label = { Text(sh.name.take(20), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                    // Legal status — defaults UNCLEAR, requires explicit confirmation
                    val legalColor = when (action.legalStatus) {
                        LegalStatus.PERMITTED -> MaterialTheme.colorScheme.secondary
                        LegalStatus.RESTRICTED -> MaterialTheme.colorScheme.tertiary
                        LegalStatus.PROHIBITED -> MaterialTheme.colorScheme.error
                        LegalStatus.UNCLEAR -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text("Legal status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LegalStatus.entries.forEach { status ->
                            FilterChip(
                                selected = action.legalStatus == status,
                                onClick = { onUpdate(action.copy(legalStatus = status)) },
                                label = { Text(status.name.replace("_", " "), style = MaterialTheme.typography.labelSmall,
                                    color = if (action.legalStatus == status) legalColor else MaterialTheme.colorScheme.onSurface) }
                            )
                        }
                    }
                    if (action.legalStatus == LegalStatus.UNCLEAR) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp)) {
                            Text("⚠ Legal status not confirmed — this action cannot be fully ranked.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp))
                        }
                    }
                    // Difficulty and reversibility
                    SliderField("Implementation difficulty", action.implementationDifficulty.toFloat(), 1f, 5f) {
                        onUpdate(action.copy(implementationDifficulty = it.toInt()))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = action.reversible, onClick = { onUpdate(action.copy(reversible = !action.reversible)) }, label = { Text("Reversible", style = MaterialTheme.typography.labelSmall) })
                        FilterChip(selected = action.publicVisibility, onClick = { onUpdate(action.copy(publicVisibility = !action.publicVisibility)) }, label = { Text("Public", style = MaterialTheme.typography.labelSmall) })
                    }
                    // Cost and time
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (action.cost == 0.0) "" else action.cost.toString(),
                            onValueChange = { v -> onUpdate(action.copy(cost = v.toDoubleOrNull() ?: 0.0)) },
                            label = { Text("Cost") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = if (action.timeCost == 0) "" else action.timeCost.toString(),
                            onValueChange = { v -> onUpdate(action.copy(timeCost = v.toIntOrNull() ?: 0)) },
                            label = { Text("Days") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                    }
                    // Preconditions
                    StringListEditor("Preconditions", action.preconditions) { onUpdate(action.copy(preconditions = it)) }
                    // Stakeholder reactions
                    if (stakeholders.isNotEmpty()) {
                        Text("Expected stakeholder reactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stakeholders.forEach { sh ->
                            var reactionText by remember(action.id, sh.id) { mutableStateOf(action.likelyReactions[sh.id] ?: "") }
                            OutlinedTextField(
                                value = reactionText,
                                onValueChange = { v ->
                                    reactionText = v
                                    val updated = action.likelyReactions.toMutableMap()
                                    if (v.isBlank()) updated.remove(sh.id) else updated[sh.id] = v
                                    onUpdate(action.copy(likelyReactions = updated))
                                },
                                label = { Text(sh.name.take(20)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                maxLines = 2
                            )
                        }
                    }
                    // Incomplete field warning
                    val missingFields = buildList {
                        if (action.label.isBlank()) add("label")
                        if (action.stakeholderId.isBlank()) add("owner")
                        if (action.legalStatus == LegalStatus.UNCLEAR) add("legal status")
                    }
                    if (missingFields.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                            Text("Incomplete: ${missingFields.joinToString(", ")} not confirmed.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp))
                        }
                    }
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove action", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ── Utility Composables ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringListEditor(
    label: String,
    items: List<String>,
    onUpdate: (List<String>) -> Unit
) {
    var newItem by remember { mutableStateOf("") }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { idx, item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("• $item", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onUpdate(items.filterIndexed { i, _ -> i != idx }) },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                placeholder = { Text("Add item…", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (newItem.isNotBlank()) {
                        onUpdate(items + newItem.trim())
                        newItem = ""
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun SliderField(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = (max - min).toInt() - 1,
            modifier = Modifier.width(130.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("${value.toInt()}", style = MaterialTheme.typography.labelSmall.copy(), modifier = Modifier.width(20.dp))
    }
}

@Composable
fun AssumptionChip(status: FactStatus) {
    val (label, color) = when (status) {
        FactStatus.CONFIRMED -> "Confirmed" to MaterialTheme.colorScheme.secondary
        FactStatus.USER_ESTIMATE -> "Estimate" to MaterialTheme.colorScheme.tertiary
        FactStatus.AI_INFERRED -> "AI Inferred" to MaterialTheme.colorScheme.primary
        FactStatus.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.error
    }
    SemanticChip(label = label, color = color)
}

// ── Decision rule helpers ─────────────────────────────────────────────────────

private fun decisionRuleLabel(rule: DecisionRule): String = when (rule) {
    DecisionRule.SIMPLE_MAJORITY -> "Simple majority"
    DecisionRule.SUPERMAJORITY -> "Supermajority (2/3)"
    DecisionRule.CONSENSUS -> "Full consensus"
    DecisionRule.SINGLE_EXECUTIVE -> "Single executive"
    DecisionRule.BOARD_APPROVAL -> "Board approval"
    DecisionRule.REGULATOR_APPROVAL -> "Regulator approval"
    DecisionRule.SEQUENTIAL_APPROVAL -> "Sequential approval"
    DecisionRule.FORMAL_VETO -> "Formal veto"
    DecisionRule.CUSTOM_WEIGHTED -> "Custom weighted"
    DecisionRule.MULTI_STAGE_GOVERNANCE -> "Multi-stage governance"
}

private fun decisionRuleExplanation(rule: DecisionRule): String = when (rule) {
    DecisionRule.SIMPLE_MAJORITY -> "More than half of voting stakeholders must approve."
    DecisionRule.SUPERMAJORITY -> "Two-thirds or more must approve; protects against thin majorities."
    DecisionRule.CONSENSUS -> "All stakeholders must agree; any objection blocks the decision."
    DecisionRule.SINGLE_EXECUTIVE -> "One designated authority makes the call unilaterally."
    DecisionRule.BOARD_APPROVAL -> "A defined board or committee must ratify the decision."
    DecisionRule.REGULATOR_APPROVAL -> "An external regulator must formally approve before action."
    DecisionRule.SEQUENTIAL_APPROVAL -> "Each authority approves in sequence; any stage can block."
    DecisionRule.FORMAL_VETO -> "Specific stakeholders hold formal veto rights over the outcome."
    DecisionRule.CUSTOM_WEIGHTED -> "Weighted votes; stakeholder authority scores determine outcome."
    DecisionRule.MULTI_STAGE_GOVERNANCE -> "Multiple governance bodies each approve at defined stages."
}

// ── Constraint Assessment Card ────────────────────────────────────────────────

private val assessmentDateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun Long.toAssessmentDateStr(): String = if (this == 0L) "" else assessmentDateFmt.format(Date(this))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConstraintAssessmentCard(
    constraint: Constraint,
    scenario: Scenario,
    onUpsert: (ConstraintAssessment) -> Unit,
    onDelete: (String) -> Unit
) {
    val hardKinds = setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD)
    val isHard = constraint.kind in hardKinds
    val selectedActionId = scenario.selectedPolicyOption?.actionId ?: scenario.actions.firstOrNull()?.id ?: ""
    val selectedPolicyId = scenario.selectedPolicyOption?.id ?: ""

    val existingAssessments = scenario.constraintAssessments.filter {
        it.constraintId == constraint.id &&
        (it.actionId == selectedActionId || it.actionId.isEmpty())
    }

    var expanded by remember { mutableStateOf(isHard && existingAssessments.isEmpty()) }
    var showAddDialog by remember { mutableStateOf(false) }

    val tint = when {
        constraint.kind == Constraint.Kind.SAFETY || constraint.kind == Constraint.Kind.LEGAL ->
            MaterialTheme.colorScheme.error
        constraint.kind == Constraint.Kind.ETHICAL || constraint.kind == Constraint.Kind.USER_HARD ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val assessmentStatusColor = when {
        existingAssessments.any { it.status == ConstraintStatus.VIOLATED } -> MaterialTheme.colorScheme.error
        existingAssessments.any { it.status == ConstraintStatus.SATISFIED } -> MaterialTheme.colorScheme.primary
        existingAssessments.isNotEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
        isHard -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    StratCard(modifier = Modifier.padding(vertical = 3.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(constraint.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(constraint.kind.name, style = MaterialTheme.typography.labelSmall,
                            color = tint)
                        if (isHard) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                                Text("HARD", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                Text(
                    when {
                        existingAssessments.any { it.status == ConstraintStatus.VIOLATED } -> "VIOLATED"
                        existingAssessments.any { it.status == ConstraintStatus.SATISFIED } -> "Satisfied"
                        existingAssessments.any { it.status == ConstraintStatus.NOT_APPLICABLE } -> "N/A"
                        existingAssessments.isNotEmpty() -> "Unknown"
                        isHard -> "No assessment"
                        else -> "—"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = assessmentStatusColor
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))

                    if (selectedActionId.isBlank()) {
                        Text(
                            "Select a policy option or add actions to record assessments.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val actionLabel = scenario.actions.find { it.id == selectedActionId }?.label ?: selectedActionId
                        Text(
                            "Assessments for: $actionLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        if (existingAssessments.isEmpty()) {
                            Text("No assessments recorded yet.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            existingAssessments.forEach { a ->
                                AssessmentRow(a, constraint, onDelete)
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add assessment", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAssessmentDialog(
            constraint = constraint,
            actionId = selectedActionId,
            policyOptionId = selectedPolicyId,
            onDismiss = { showAddDialog = false },
            onSave = { a -> onUpsert(a); showAddDialog = false }
        )
    }
}

@Composable
private fun AssessmentRow(
    a: ConstraintAssessment,
    constraint: Constraint,
    onDelete: (String) -> Unit
) {
    val statusColor = when (a.status) {
        ConstraintStatus.SATISFIED -> MaterialTheme.colorScheme.primary
        ConstraintStatus.VIOLATED -> MaterialTheme.colorScheme.error
        ConstraintStatus.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        ConstraintStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    StratCard(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.12f)) {
                        Text(a.status.name, style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    if (a.assessor.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text("by ${a.assessor}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (a.expiryDate > 0L) {
                        val expired = System.currentTimeMillis() > a.expiryDate
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (expired) "Expired ${a.expiryDate.toAssessmentDateStr()}"
                            else "Expires ${a.expiryDate.toAssessmentDateStr()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (expired) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (a.rationale.isNotBlank()) {
                    Text(a.rationale, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            IconButton(onClick = { onDelete(a.id) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAssessmentDialog(
    constraint: Constraint,
    actionId: String,
    policyOptionId: String,
    onDismiss: () -> Unit,
    onSave: (ConstraintAssessment) -> Unit
) {
    var status by remember { mutableStateOf(ConstraintStatus.UNKNOWN) }
    var rationale by remember { mutableStateOf("") }
    var assessor by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Assess: ${constraint.label}", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Status", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConstraintStatus.entries.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { status = s },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = status == s, onClick = { status = s })
                        Spacer(Modifier.width(4.dp))
                        Text(s.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = rationale, onValueChange = { rationale = it },
                    label = { Text("Rationale / evidence") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4
                )
                OutlinedTextField(
                    value = assessor, onValueChange = { assessor = it },
                    label = { Text("Assessor name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = expiryDays, onValueChange = { expiryDays = it.filter { c -> c.isDigit() } },
                    label = { Text("Expires in days (0 = never)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val days = expiryDays.toLongOrNull() ?: 0L
                    val expiry = if (days > 0) System.currentTimeMillis() + days * 86_400_000L else 0L
                    onSave(
                        ConstraintAssessment(
                            id = UUID.randomUUID().toString(),
                            constraintId = constraint.id,
                            actionId = actionId,
                            policyOptionId = policyOptionId,
                            status = status,
                            rationale = rationale.trim(),
                            assessor = assessor.trim(),
                            expiryDate = expiry
                        )
                    )
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
