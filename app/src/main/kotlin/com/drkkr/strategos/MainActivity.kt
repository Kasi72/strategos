package com.drkkr.strategos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.drkkr.strategos.domain.model.NavigationTarget
import com.drkkr.strategos.domain.model.WorkflowStage
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.tabs.*
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens
import com.drkkr.strategos.ui.theme.StrategosTheme
import com.drkkr.strategos.ui.theme.glowBehind
import com.drkkr.strategos.ui.theme.ledStripBelow

private enum class Tab(val label: String, val icon: ImageVector) {
    SCENARIOS("Scenarios", Icons.AutoMirrored.Filled.List),
    MODEL("Model", Icons.Default.Edit),
    ANALYSIS("Analyse", Icons.Default.Analytics),
    REPORT("Report", Icons.Default.Assessment),
    MORE("More", Icons.Default.MoreHoriz)
}

internal enum class MoreSubTab(val label: String, val icon: ImageVector) {
    MAP("Map", Icons.Default.AccountTree),
    DASHBOARD("Implement", Icons.Default.Dashboard)
}

internal enum class ModelSubTab(val label: String, val icon: ImageVector) {
    FRAME("Frame", Icons.Default.Layers),
    MODEL("Model", Icons.Default.Edit)
}

class MainActivity : ComponentActivity() {
    private val vm by viewModels<StrategosViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrategosTheme {
                StrategosApp(vm)
            }
        }
    }
}

private fun WorkflowStage.toTab(): Tab = when (this) {
    WorkflowStage.FRAME, WorkflowStage.MODEL, WorkflowStage.CHALLENGE -> Tab.MODEL
    WorkflowStage.SIMULATE -> Tab.ANALYSIS
    WorkflowStage.DECIDE -> Tab.REPORT
    WorkflowStage.LEARN -> Tab.MORE
}

private fun NavigationTarget.toTab(): Tab = when (this.tabId) {
    "model" -> Tab.MODEL
    "analysis" -> Tab.ANALYSIS
    "report" -> Tab.REPORT
    "implementation" -> Tab.MORE
    else -> Tab.SCENARIOS
}

// Within the Model tab, route to the correct sub-tab: the Frame page for the
// framing target, and the Model editor (stakeholders, actions, constraints,
// evidence) for everything else. Without this, a "model" navigation lands on
// whatever sub-tab was last shown — usually Frame — so blockers like
// "Add evidence source" appeared to do nothing.
private fun NavigationTarget.toModelSubTab(): ModelSubTab? = when (this.tabId) {
    "model" -> if (this.section == "frame") ModelSubTab.FRAME else ModelSubTab.MODEL
    else -> null
}

private fun NavigationTarget.toMoreSubTab(): MoreSubTab? = when (this) {
    NavigationTarget.IMPLEMENTATION_PLAN,
    NavigationTarget.IMPLEMENTATION_MONITORING,
    NavigationTarget.IMPLEMENTATION_APPROVALS -> MoreSubTab.DASHBOARD
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategosApp(vm: StrategosViewModel) {
    val state by vm.state.collectAsState()
    var selectedTab by remember { mutableStateOf(Tab.SCENARIOS) }
    var selectedMoreTab by remember { mutableStateOf(MoreSubTab.MAP) }
    var selectedModelSubTab by remember { mutableStateOf(ModelSubTab.FRAME) }

    LaunchedEffect(state.pendingNavigationTarget) {
        state.pendingNavigationTarget?.let { target ->
            selectedTab = target.toTab()
            target.toModelSubTab()?.let { sub -> selectedModelSubTab = sub }
            target.toMoreSubTab()?.let { sub -> selectedMoreTab = sub }
            vm.clearNavigation()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Content area — padded so content doesn't hide behind dock
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = StratTokens.dockTotalBottomPad)
        ) {
            when (selectedTab) {
                Tab.SCENARIOS -> HomeScreen(
                    vm = vm,
                    onNewScenario = {
                        vm.newBlankScenario()
                        selectedTab = Tab.MODEL
                        selectedModelSubTab = ModelSubTab.FRAME
                    },
                    onOpenScenario = { s ->
                        vm.setActiveScenario(s)
                        selectedTab = Tab.MODEL
                    },
                    onNavigateModel = {
                        selectedTab = Tab.MODEL
                    }
                )
                Tab.MODEL -> ModelTab(
                    vm = vm,
                    selectedSubTab = selectedModelSubTab,
                    onSubTabSelected = { selectedModelSubTab = it }
                )
                Tab.ANALYSIS -> AnalysisTab(vm)
                Tab.REPORT -> ReportTab(vm)
                Tab.MORE -> MoreTab(
                    vm = vm,
                    selectedSubTab = selectedMoreTab,
                    onSubTabSelected = { selectedMoreTab = it }
                )
            }
        }

        // Floating nav dock
        FloatingNavDock(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FloatingNavDock(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val dockShape = RoundedCornerShape(StratTokens.radiusDock)

    val dockModifier = if (isDark) {
        modifier
            .padding(horizontal = StratTokens.dockSideMargin, vertical = StratTokens.dockBottomMargin)
            .height(StratTokens.dockHeight)
            .fillMaxWidth()
            .shadow(20.dp, dockShape, spotColor = Color.Black.copy(0.7f))
            .clip(dockShape)
            .background(
                Brush.verticalGradient(listOf(NeoTokens.dark3.copy(0.92f), NeoTokens.dark2.copy(0.80f)))
            )
            .border(1.dp, NeoTokens.glassBorder, dockShape)
    } else {
        modifier
            .padding(horizontal = StratTokens.dockSideMargin, vertical = StratTokens.dockBottomMargin)
            .height(StratTokens.dockHeight)
            .fillMaxWidth()
            .shadow(8.dp, dockShape, spotColor = Color.Black.copy(0.12f))
            .clip(dockShape)
            .background(StratTokens.raisedLight)
            .border(1.dp, Color.Black.copy(0.08f), dockShape)
    }

    Box(dockModifier) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEachIndexed { index, tab ->
                DockItem(
                    tab = tab,
                    selected = tab == selectedTab,
                    tabIndex = index,
                    isDark = isDark,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: Tab,
    selected: Boolean,
    tabIndex: Int,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, tween(100), label = "di")

    val ledColor = if (isDark && tabIndex < NeoTokens.ledColors.size)
        NeoTokens.ledColors[tabIndex]
    else
        if (isDark) NeoTokens.blueCore else StratTokens.accentLight

    val iconTint = when {
        isDark && selected -> ledColor
        isDark -> Color.White.copy(alpha = 0.38f)
        selected -> StratTokens.accentLight
        else -> StratTokens.inkLight.copy(alpha = 0.45f)
    }

    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .ledStripBelow(ledColor, selected)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                Modifier
                    .then(if (isDark && selected) Modifier.glowBehind(ledColor.copy(0.22f), 14.dp) else Modifier)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) ledColor.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    tab.icon,
                    tab.label,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall.copy(color = iconTint)
            )
        }
    }
}

@Composable
private fun <T : Enum<T>> NeoSubTabBar(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
    accentColor: Color = NeoTokens.blueCore
) {
    val isDark = isSystemInDarkTheme()
    if (isDark) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(NeoTokens.dark2)
                .border(1.dp, NeoTokens.glassBorder, RoundedCornerShape(50.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entries.forEach { sub ->
                val sel = sub == selected
                val interSrc = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50.dp))
                        .background(
                            if (sel) Brush.horizontalGradient(listOf(accentColor, accentColor.copy(0.7f)))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable(interSrc, null) { onSelect(sub) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon(sub), label(sub),
                        tint = if (sel) Color.White else Color.White.copy(0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label(sub),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (sel) Color.White else Color.White.copy(0.45f),
                            fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold
                            else androidx.compose.ui.text.font.FontWeight.Normal
                        )
                    )
                }
            }
        }
    } else {
        TabRow(
            selectedTabIndex = entries.indexOf(selected),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            entries.forEach { sub ->
                Tab(
                    selected = sub == selected,
                    onClick = { onSelect(sub) },
                    text = { Text(label(sub), style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(icon(sub), label(sub), Modifier.size(18.dp)) }
                )
            }
        }
    }
}

@Composable
internal fun ModelTab(
    vm: StrategosViewModel,
    selectedSubTab: ModelSubTab,
    onSubTabSelected: (ModelSubTab) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NeoSubTabBar(
            entries = ModelSubTab.entries,
            selected = selectedSubTab,
            label = { it.label },
            icon = { it.icon },
            onSelect = onSubTabSelected,
            accentColor = NeoTokens.blueCore
        )
        Box(Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                ModelSubTab.FRAME -> com.drkkr.strategos.ui.tabs.FrameTab(vm)
                ModelSubTab.MODEL -> ModelEditorTab(vm)
            }
        }
    }
}

@Composable
internal fun MoreTab(
    vm: StrategosViewModel,
    selectedSubTab: MoreSubTab,
    onSubTabSelected: (MoreSubTab) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NeoSubTabBar(
            entries = MoreSubTab.entries,
            selected = selectedSubTab,
            label = { it.label },
            icon = { it.icon },
            onSelect = onSubTabSelected,
            accentColor = NeoTokens.purple
        )
        Box(Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                MoreSubTab.MAP -> StakeholderMapTab(vm)
                MoreSubTab.DASHBOARD -> ImplementationDashboardTab(vm)
            }
        }
    }
}
