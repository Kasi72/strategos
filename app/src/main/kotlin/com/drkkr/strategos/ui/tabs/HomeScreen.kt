package com.drkkr.strategos.ui.tabs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.model.ApprovalStatus
import com.drkkr.strategos.domain.model.LifecycleStage
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.WorkflowOrchestrator
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.theme.GradientPillButton
import com.drkkr.strategos.ui.theme.NeoElevatedCard
import com.drkkr.strategos.ui.theme.NeoIconSquircle
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.NeoGlassCard
import com.drkkr.strategos.ui.theme.glowBehind
import com.drkkr.strategos.ui.theme.StratTokens

@Composable
fun HomeScreen(
    vm: StrategosViewModel,
    onNewScenario: () -> Unit,
    onOpenScenario: (Scenario) -> Unit,
    onNavigateModel: () -> Unit,
    onStartCounsel: (toCounsel: Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val isDark = isSystemInDarkTheme()

    val canvas = if (isDark) StratTokens.canvasDark else StratTokens.canvasLight
    val surface = if (isDark) StratTokens.surfaceDark else StratTokens.surfaceLight
    val raised = if (isDark) StratTokens.raisedDark else StratTokens.raisedLight
    val ink = if (isDark) StratTokens.inkDark else StratTokens.inkLight
    val accent = if (isDark) StratTokens.accentDark else StratTokens.accentLight
    val borderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)

    val activeScenario = state.activeScenario

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(canvas),
        contentPadding = PaddingValues(
            top = 0.dp,
            bottom = StratTokens.dockTotalBottomPad
        )
    ) {
        item {
            HomeHeaderRow(
                isDark = isDark,
                ink = ink,
                accent = accent,
                raised = raised,
                borderColor = borderColor,
                onNewScenario = onNewScenario
            )
        }

        item {
            com.drkkr.strategos.ui.counsel.HomeComposer(
                vm, expert = true, onStarted = onStartCounsel, onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(horizontal = StratTokens.screenPad, vertical = StratTokens.itemGap)
            )
        }

        if (activeScenario != null) {
            item {
                ActiveDecisionCard(
                    scenario = activeScenario,
                    isDark = isDark,
                    surface = surface,
                    raised = raised,
                    ink = ink,
                    accent = accent,
                    borderColor = borderColor,
                    onClick = { onOpenScenario(activeScenario) }
                )
                Spacer(Modifier.height(StratTokens.itemGap))
            }

            item {
                ContinueWorkflowCard(
                    scenario = activeScenario,
                    isDark = isDark,
                    accent = accent,
                    ink = ink,
                    onClick = onNavigateModel
                )
                Spacer(Modifier.height(StratTokens.sectionGap))
            }

            item {
                DecisionHealthGrid(
                    scenario = activeScenario,
                    isDark = isDark,
                    surface = surface,
                    raised = raised,
                    ink = ink,
                    borderColor = borderColor
                )
                Spacer(Modifier.height(StratTokens.sectionGap))
            }
        }

        val recentScenarios = state.scenarios
            .sortedByDescending { it.updatedAt }
            .take(if (activeScenario != null) 3 else 5)

        if (recentScenarios.isEmpty()) {
            item {
                EmptyHomeState(
                    isDark = isDark,
                    ink = ink,
                    accent = accent,
                    onNewScenario = onNewScenario
                )
            }
        } else {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = StratTokens.screenPad),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Decisions",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ink
                        )
                    )
                    Text(
                        "${state.scenarios.size} total",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = ink.copy(alpha = 0.45f)
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            items(recentScenarios) { s ->
                ScenarioRow(
                    scenario = s,
                    isDark = isDark,
                    surface = surface,
                    raised = raised,
                    ink = ink,
                    accent = accent,
                    borderColor = borderColor,
                    onClick = { onOpenScenario(s) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Header row ─────────────────────────────────────────────────────────────────
@Composable
private fun HomeHeaderRow(
    isDark: Boolean,
    ink: Color,
    accent: Color,
    raised: Color,
    borderColor: Color,
    onNewScenario: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(220), label = "hdr")
    val ty by animateFloatAsState(if (entered) 0f else 10f, tween(220), label = "hdr_ty")

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha; translationY = ty }
            .padding(
                start = StratTokens.screenPad,
                end = StratTokens.screenPad,
                top = 28.dp,
                bottom = 20.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Strategos",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    letterSpacing = (-0.8).sp,
                    color = ink
                ),
                maxLines = 1
            )
            Text(
                "Dr KKR",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = ink.copy(alpha = 0.40f),
                    letterSpacing = 0.8.sp
                )
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isDark) {
                NeoIconSquircle(
                    icon = Icons.Default.Add,
                    tint = NeoTokens.blueCore,
                    onClick = onNewScenario,
                    size = 40.dp,
                    contentDesc = "New scenario",
                    glowColor = NeoTokens.blueCore
                )
            } else {
                IconSquircle(
                    icon = Icons.Default.Add,
                    tint = accent,
                    background = accent.copy(alpha = 0.10f),
                    borderColor = borderColor,
                    contentDesc = "New scenario",
                    onClick = onNewScenario
                )
            }
        }
    }
}

@Composable
private fun IconSquircle(
    icon: ImageVector,
    tint: Color,
    background: Color,
    borderColor: Color,
    contentDesc: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDesc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ── Active decision card ────────────────────────────────────────────────────────
@Composable
private fun ActiveDecisionCard(
    scenario: Scenario,
    isDark: Boolean,
    surface: Color,
    raised: Color,
    ink: Color,
    accent: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f, tween(100), label = "sc")

    val cardContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LifecycleChip(stage = scenario.lifecycleStage, accent = accent, isDark = isDark)
            Text(
                "${scenario.completenessScore()}%",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            scenario.title.ifBlank { "Untitled Decision" },
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else ink
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (scenario.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                scenario.description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (isDark) Color.White.copy(0.55f) else ink.copy(alpha = 0.55f)
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetaPill("${scenario.stakeholders.size} players", if (isDark) Color.White else ink)
            MetaPill("${scenario.actions.size} actions", if (isDark) Color.White else ink)
            MetaPill("${scenario.constraints.size} constraints", if (isDark) Color.White else ink)
        }
    }

    val containerModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = StratTokens.screenPad)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)

    if (isDark) {
        NeoElevatedCard(
            modifier = containerModifier,
            cornerRadius = StratTokens.radiusMain,
            accentGlow = NeoTokens.blueCore,
            content = cardContent
        )
    } else {
        Box(
            containerModifier
                .shadow(4.dp, RoundedCornerShape(StratTokens.radiusMain), spotColor = Color.Black.copy(0.12f))
                .clip(RoundedCornerShape(StratTokens.radiusMain))
                .background(raised)
                .border(1.dp, borderColor, RoundedCornerShape(StratTokens.radiusMain))
        ) {
            Column(Modifier.padding(StratTokens.cardPadMain), content = cardContent)
        }
    }
}

@Composable
private fun LifecycleChip(stage: LifecycleStage, accent: Color, isDark: Boolean) {
    val (label, color) = when (stage) {
        LifecycleStage.DRAFT -> "Draft" to Color(0xFF6B7280)
        LifecycleStage.MODELLED -> "Modelled" to Color(0xFF2367D1)
        LifecycleStage.CHALLENGED -> "Challenged" to Color(0xFFD97706)
        LifecycleStage.ANALYSED -> "Analysed" to Color(0xFF7C3AED)
        LifecycleStage.READY_FOR_APPROVAL -> "For Approval" to Color(0xFFD97706)
        LifecycleStage.APPROVED -> "Approved" to Color(0xFF16A34A)
        LifecycleStage.IN_IMPLEMENTATION -> "Implementing" to Color(0xFF059669)
        LifecycleStage.MONITORING -> "Monitoring" to Color(0xFF0284C7)
        LifecycleStage.HOLD -> "On Hold" to Color(0xFF9CA3AF)
        LifecycleStage.PAUSED -> "Paused" to Color(0xFF9CA3AF)
        LifecycleStage.CLOSED -> "Closed" to Color(0xFF6B7280)
        LifecycleStage.ARCHIVED -> "Archived" to Color(0xFF6B7280)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(StratTokens.radiusPill))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        )
    }
}

@Composable
private fun MetaPill(text: String, ink: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(color = ink.copy(alpha = 0.40f))
    )
}

// ── Continue workflow card ──────────────────────────────────────────────────────
@Composable
private fun ContinueWorkflowCard(
    scenario: Scenario,
    isDark: Boolean,
    accent: Color,
    ink: Color,
    onClick: () -> Unit
) {
    val directive = remember(scenario) {
        WorkflowOrchestrator.nextAction(scenario)
    }
    val accentBg = if (isDark) accent.copy(alpha = 0.15f) else accent.copy(alpha = 0.08f)
    val accentBorder = accent.copy(alpha = 0.20f)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f, tween(100), label = "csc")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = StratTokens.screenPad)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(StratTokens.radiusCard))
            .background(accentBg)
            .border(1.dp, accentBorder, RoundedCornerShape(StratTokens.radiusCard))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(StratTokens.cardPadStd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                directive.ctaLabel,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                directive.description,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isDark) accent.copy(alpha = 0.70f) else ink.copy(alpha = 0.55f)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.ArrowForwardIos,
            null,
            tint = accent.copy(alpha = 0.60f),
            modifier = Modifier.size(14.dp)
        )
    }
}

// ── Decision health grid ────────────────────────────────────────────────────────
@Composable
private fun DecisionHealthGrid(
    scenario: Scenario,
    isDark: Boolean,
    surface: Color,
    raised: Color,
    ink: Color,
    borderColor: Color
) {
    val completeness = scenario.completenessScore()
    val evidenceCount = scenario.actions.size + scenario.payoffs.size
    val constraintTotal = scenario.constraints.size
    val constraintSatisfied = scenario.constraintAssessments.count {
        it.status.name == "SATISFIED"
    }
    val approvalCount = scenario.approvalStages.size
    val approvalComplete = scenario.approvalStages.count { stage ->
        stage.decisions.any { it.decision == ApprovalStatus.APPROVED }
    }

    val cells = listOf(
        HealthCell(
            title = "Readiness",
            value = "$completeness%",
            icon = Icons.Default.CheckCircle,
            color = when {
                completeness >= 80 -> StratTokens.healthGreen
                completeness >= 50 -> StratTokens.healthAmber
                else -> StratTokens.healthRed
            }
        ),
        HealthCell(
            title = "Evidence",
            value = "$evidenceCount entries",
            icon = Icons.Default.DataObject,
            color = if (evidenceCount > 0) StratTokens.healthBlue else StratTokens.healthAmber
        ),
        HealthCell(
            title = "Constraints",
            value = if (constraintTotal == 0) "—" else "$constraintSatisfied / $constraintTotal",
            icon = Icons.Default.Shield,
            color = when {
                constraintTotal == 0 -> StratTokens.healthAmber
                constraintSatisfied == constraintTotal -> StratTokens.healthGreen
                else -> StratTokens.healthAmber
            }
        ),
        HealthCell(
            title = "Approval",
            value = if (approvalCount == 0) "—" else "$approvalComplete / $approvalCount",
            icon = Icons.Default.HowToVote,
            color = when {
                approvalCount == 0 -> Color(0xFF6B7280)
                approvalComplete == approvalCount -> StratTokens.healthGreen
                else -> StratTokens.healthAmber
            }
        )
    )

    Column(Modifier.padding(horizontal = StratTokens.screenPad)) {
        Text(
            "Decision Health",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = ink
            )
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            cells.take(2).forEach { cell ->
                HealthCell(
                    cell = cell,
                    isDark = isDark,
                    raised = raised,
                    ink = ink,
                    borderColor = borderColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            cells.drop(2).forEach { cell ->
                HealthCell(
                    cell = cell,
                    isDark = isDark,
                    raised = raised,
                    ink = ink,
                    borderColor = borderColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private data class HealthCell(
    val title: String,
    val value: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
private fun HealthCell(
    cell: HealthCell,
    isDark: Boolean,
    raised: Color,
    ink: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    val cellContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(cell.icon, null, tint = cell.color, modifier = Modifier.size(16.dp))
            Text(
                cell.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isDark) Color.White.copy(0.50f) else ink.copy(0.50f)
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            cell.value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = cell.color
            )
        )
    }

    if (isDark) {
        NeoGlassCard(
            modifier = modifier,
            cornerRadius = StratTokens.radiusCard,
            content = cellContent
        )
    } else {
        Box(
            modifier
                .shadow(2.dp, RoundedCornerShape(StratTokens.radiusCard), spotColor = Color.Black.copy(0.08f))
                .clip(RoundedCornerShape(StratTokens.radiusCard))
                .background(raised)
                .border(1.dp, borderColor, RoundedCornerShape(StratTokens.radiusCard))
                .padding(StratTokens.cardPadStd)
        ) {
            Column(content = cellContent)
        }
    }
}

// ── Scenario row ────────────────────────────────────────────────────────────────
@Composable
private fun ScenarioRow(
    scenario: Scenario,
    isDark: Boolean,
    surface: Color,
    raised: Color,
    ink: Color,
    accent: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f, tween(100), label = "sr")

    val rowClick = Modifier
        .fillMaxWidth()
        .padding(horizontal = StratTokens.screenPad)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)

    val rowContent: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isDark) {
                NeoIconSquircle(
                    icon = Icons.Default.Description,
                    tint = NeoTokens.blueCore,
                    onClick = onClick,
                    size = 40.dp,
                    contentDesc = ""
                )
            } else {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(0.10f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Description, null, tint = accent, modifier = Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    scenario.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color.White else ink
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(scenario.lifecycleStage.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                        append(" · ${scenario.completenessScore()}% complete")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isDark) Color.White.copy(0.45f) else ink.copy(0.45f)
                    ),
                    maxLines = 1
                )
            }
            Icon(Icons.Default.ArrowForwardIos, null, tint = if (isDark) Color.White.copy(0.25f) else ink.copy(0.25f), modifier = Modifier.size(13.dp))
        }
    }

    if (isDark) {
        NeoGlassCard(modifier = rowClick, cornerRadius = StratTokens.radiusCard) { rowContent() }
    } else {
        Box(
            rowClick
                .clip(RoundedCornerShape(StratTokens.radiusCard))
                .background(raised)
                .border(1.dp, borderColor, RoundedCornerShape(StratTokens.radiusCard))
        ) { rowContent() }
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────────
@Composable
private fun EmptyHomeState(
    isDark: Boolean,
    ink: Color,
    accent: Color,
    onNewScenario: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = StratTokens.screenPad)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isDark) {
            NeoIconSquircle(
                icon = Icons.Default.Analytics,
                tint = NeoTokens.blueCore,
                onClick = {},
                size = 72.dp,
                contentDesc = "",
                glowColor = NeoTokens.blueCore
            )
        } else {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(accent.copy(0.10f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Analytics, null, tint = accent, modifier = Modifier.size(36.dp)) }
        }
        Text(
            "No decisions yet",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = ink
            )
        )
        Text(
            "Create your first strategic decision scenario.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = ink.copy(alpha = 0.50f)
            )
        )
        Spacer(Modifier.height(4.dp))
        if (isDark) {
            GradientPillButton(
                text = "New scenario",
                onClick = onNewScenario,
                modifier = Modifier.fillMaxWidth(0.65f),
                startColor = NeoTokens.blueCore,
                endColor = NeoTokens.blueDeep
            )
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(StratTokens.radiusBtn))
                    .background(accent)
                    .clickable(onClick = onNewScenario)
                    .padding(horizontal = 24.dp, vertical = 13.dp)
            ) {
                Text(
                    "New scenario",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
            }
        }
    }
}
