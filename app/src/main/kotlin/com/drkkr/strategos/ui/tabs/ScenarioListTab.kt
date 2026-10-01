package com.drkkr.strategos.ui.tabs

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.ScenarioStatus
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.*
import com.drkkr.strategos.ui.components.WorkflowStageCard
import com.drkkr.strategos.ui.theme.StrategosBrandTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioListTab(vm: StrategosViewModel, onOpenScenario: (Scenario) -> Unit) {
    val state by vm.state.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Add, "New Scenario")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = StrategosBrandTokens.pageHorizontalPadding,
                vertical = 0.dp
            )
        ) {
            item {
                StrategosBrandHeader()
                WorkflowStageCard(
                    scenario = state.activeScenario,
                    modifier = Modifier.padding(bottom = 24.dp),
                    onNavigate = { target -> vm.navigateTo(target) }
                )
            }

            item {
                SectionHeader("Templates")
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    IosListRow(
                        title = "Institutional Healthcare — Psychiatry",
                        subtitle = "Conflict resolution · 4 stakeholders",
                        leadingIcon = Icons.Default.LocalHospital,
                        showChevron = true,
                        modifier = Modifier.clickable {
                            vm.loadTemplate()
                            vm.state.value.activeScenario?.let(onOpenScenario)
                        }
                    )
                }
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    IosListRow(
                        title = "Resource Allocation",
                        subtitle = "Common-pool resource · budget competition",
                        leadingIcon = Icons.Default.PieChart,
                        showChevron = true,
                        modifier = Modifier.clickable {
                            vm.loadResourceAllocationTemplate()
                            vm.state.value.activeScenario?.let(onOpenScenario)
                        }
                    )
                }
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    IosListRow(
                        title = "Crisis Management",
                        subtitle = "Chicken/Brinkmanship · escalation scenario",
                        leadingIcon = Icons.Default.Warning,
                        showChevron = true,
                        modifier = Modifier.clickable {
                            vm.loadCrisisTemplate()
                            vm.state.value.activeScenario?.let(onOpenScenario)
                        }
                    )
                }
                StratCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    IosListRow(
                        title = "Procurement / Vendor Selection",
                        subtitle = "Principal-Agent · adverse selection",
                        leadingIcon = Icons.Default.Business,
                        showChevron = true,
                        modifier = Modifier.clickable {
                            vm.loadProcurementTemplate()
                            vm.state.value.activeScenario?.let(onOpenScenario)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search scenarios…") },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }

            val filtered = if (searchQuery.isBlank()) state.scenarios
                else state.scenarios.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true) ||
                    it.tags.any { t -> t.contains(searchQuery, ignoreCase = true) }
                }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Analytics,
                                null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (searchQuery.isBlank()) "No scenarios yet" else "No matches for \"$searchQuery\"",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (searchQuery.isBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Tap + to create one or load a template above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                item { SectionHeader("Saved Scenarios (${filtered.size})") }
                items(filtered) { s ->
                    StratCard(modifier = Modifier.padding(vertical = 4.dp).clickable { onOpenScenario(s) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                IosListRow(
                                    title = s.title,
                                    subtitle = "${s.stakeholders.size} players · ${s.actions.size} actions · ${s.completenessScore()}% complete",
                                    leadingIcon = Icons.Default.Description,
                                    showChevron = false
                                )
                            }
                            StatusBadge(s.status)
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(

            onDismissRequest = { showNewDialog = false; inputText = "" },
            shape = RoundedCornerShape(24.dp),
            title = { Text("New Scenario", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text("Describe the decision situation:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputText, onValueChange = { inputText = it },
                        label = { Text("Situation (Tamil or English)") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            vm.parseAndCreateScenario(inputText)
                            showNewDialog = false
                            inputText = ""
                            vm.state.value.activeScenario?.let(onOpenScenario)
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Analyse") }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.newBlankScenario(); showNewDialog = false; inputText = "" }
                ) { Text("Blank") }
            }
        )
    }
}

@Composable
fun StatusBadge(status: ScenarioStatus) {
    val (label, color) = when (status) {
        ScenarioStatus.DRAFT -> "Draft" to Color(0xFF8E8E93)
        ScenarioStatus.ACTIVE -> "Active" to Color(0xFF34C759)
        ScenarioStatus.ARCHIVED -> "Archived" to Color(0xFFFF9500)
        ScenarioStatus.COMPLETED -> "Done" to Color(0xFF007AFF)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
