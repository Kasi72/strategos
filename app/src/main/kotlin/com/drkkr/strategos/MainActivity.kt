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
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.model.NavigationTarget
import com.drkkr.strategos.domain.model.WorkflowStage
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.counsel.CounselTab
import com.drkkr.strategos.ui.journal.JournalTab
import com.drkkr.strategos.ui.overview.ProjectOverviewTab
import com.drkkr.strategos.ui.review.ReviewInferredScreen
import com.drkkr.strategos.ui.settings.AiSettingsScreen
import com.drkkr.strategos.ui.tabs.*
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens
import com.drkkr.strategos.ui.theme.StrategosTheme
import com.drkkr.strategos.ui.theme.glowBehind
import com.drkkr.strategos.ui.theme.ledStripBelow

private enum class Tab(val label: String, val icon: ImageVector) {
    SCENARIOS("Projects", Icons.Default.Home),
    OVERVIEW("Overview", Icons.Default.Timeline),
    COUNSEL("Talk", Icons.Default.Forum),
    JOURNAL("Journal", Icons.Default.Book),
    SUMMARY("Summary", Icons.Default.Description),
    SETTINGS("Settings", Icons.Default.Tune),
    MODEL("Model", Icons.Default.Edit),
    REPORT("Report", Icons.Default.Assessment),
    MORE("More", Icons.Default.MoreHoriz)
}

/** Guided users get a short dock; Expert ("Show details") gets the full one. Both have exactly as many items as `ledColors`. */
// Summary is no longer in the dock; Overview links to it.
private val GUIDED_TABS = listOf(Tab.SCENARIOS, Tab.OVERVIEW, Tab.COUNSEL, Tab.JOURNAL, Tab.SETTINGS)
private val EXPERT_TABS = listOf(Tab.SCENARIOS, Tab.COUNSEL, Tab.MODEL, Tab.REPORT, Tab.MORE)

internal enum class ReportSubTab(val label: String, val icon: ImageVector) {
    DECISION("Decision", Icons.Default.Assessment),
    FRAMEWORKS("Frameworks", Icons.Default.Analytics)
}

internal enum class MoreSubTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Default.Timeline),
    JOURNAL("Journal", Icons.Default.Book),
    MAP("Map", Icons.Default.AccountTree),
    DASHBOARD("Implement", Icons.Default.Dashboard),
    SETTINGS("Settings", Icons.Default.Tune)
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
            // Redesigned "calm" UI (ui/calm.CalmApp) is built but not yet the entry point: it launches
            // blank on device (nav/VM wiring bug to fix) so the old working UI stays wired for now.
            // To preview the new UI: adb am start …/.ui.calm.CalmPreviewActivity (debug build).
            StrategosTheme {
                StrategosApp(vm)
            }
        }
    }
}

private fun WorkflowStage.toTab(): Tab = when (this) {
    WorkflowStage.FRAME, WorkflowStage.MODEL, WorkflowStage.CHALLENGE -> Tab.MODEL
    WorkflowStage.SIMULATE -> Tab.REPORT                 // Report ▸ Frameworks
    WorkflowStage.DECIDE -> Tab.REPORT
    WorkflowStage.LEARN -> Tab.MORE
}

private fun NavigationTarget.toTab(): Tab = when (this.tabId) {
    "model" -> Tab.MODEL
    "analysis" -> Tab.REPORT                             // Report ▸ Frameworks
    "report" -> Tab.REPORT
    "implementation" -> Tab.MORE
    else -> Tab.SCENARIOS
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
    var selectedMoreTab by remember { mutableStateOf(MoreSubTab.OVERVIEW) }
    var selectedModelSubTab by remember { mutableStateOf(ModelSubTab.FRAME) }
    var selectedReportTab by remember { mutableStateOf(ReportSubTab.DECISION) }
    var showReview by remember { mutableStateOf(false) }
    val mode by vm.uiMode.collectAsState()
    val expert = mode == UiMode.EXPERT
    val dock = if (expert) EXPERT_TABS else GUIDED_TABS
    val shownTab = if (selectedTab in dock || (selectedTab == Tab.SUMMARY && !expert)) selectedTab else Tab.SCENARIOS
    val openSettings = {
        if (expert) { selectedTab = Tab.MORE; selectedMoreTab = MoreSubTab.SETTINGS } else selectedTab = Tab.SETTINGS
    }

    // Switching Guided/Expert from Settings keeps you in Settings (its place differs between the two docks).
    LaunchedEffect(expert) {
        if (expert && selectedTab == Tab.SETTINGS) { selectedTab = Tab.MORE; selectedMoreTab = MoreSubTab.SETTINGS }
        else if (!expert && selectedTab == Tab.MORE && selectedMoreTab == MoreSubTab.SETTINGS) selectedTab = Tab.SETTINGS
    }

    LaunchedEffect(state.pendingNavigationTarget) {
        state.pendingNavigationTarget?.let { target ->
            selectedTab = target.toTab()
            if (target.tabId == "analysis") selectedReportTab = ReportSubTab.FRAMEWORKS
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
            when (shownTab) {
                Tab.SCENARIOS -> if (expert) HomeScreen(
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
                    },
                    onStartCounsel = { toCounsel -> selectedTab = if (toCounsel) Tab.COUNSEL else Tab.MODEL },
                    onOpenSettings = openSettings
                ) else GuidedHome(
                    vm = vm,
                    onStarted = { selectedTab = Tab.COUNSEL },
                    onOpenScenario = { s -> vm.setActiveScenario(s); selectedTab = Tab.COUNSEL },
                    onOpenSettings = openSettings
                )
                Tab.OVERVIEW -> ProjectOverviewTab(vm, onOpenThread = { selectedTab = Tab.COUNSEL }, onOpenSummary = { selectedTab = Tab.SUMMARY }, onGoHome = { selectedTab = Tab.SCENARIOS })
                Tab.JOURNAL -> JournalTab(vm)
                Tab.COUNSEL -> CounselTab(vm, expert, onOpenSettings = openSettings, onGoHome = { selectedTab = Tab.SCENARIOS }, onReviewAi = { if (expert) showReview = true })
                Tab.SUMMARY -> SummaryScreen(vm)
                Tab.SETTINGS -> AiSettingsScreen(vm)
                Tab.MODEL -> ModelTab(
                    vm = vm,
                    selectedSubTab = selectedModelSubTab,
                    onSubTabSelected = { selectedModelSubTab = it }
                )
                Tab.REPORT -> ReportWithSubTabs(vm, selectedReportTab, onReviewAi = { showReview = true }) { selectedReportTab = it }
                Tab.MORE -> MoreTab(
                    vm = vm,
                    selectedSubTab = selectedMoreTab,
                    onSubTabSelected = { selectedMoreTab = it },
                    onOpenTalk = { selectedTab = Tab.COUNSEL }
                )
            }
        }

        // Floating nav dock
        if (showReview) ReviewInferredScreen(vm, onClose = { showReview = false })

        FloatingNavDock(
            tabs = dock,
            selectedTab = shownTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FloatingNavDock(
    tabs: List<Tab>,
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
            tabs.forEachIndexed { index, tab ->
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
    onSubTabSelected: (MoreSubTab) -> Unit,
    onOpenTalk: () -> Unit = {}
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
                MoreSubTab.OVERVIEW -> ProjectOverviewTab(vm, onOpenThread = onOpenTalk, onOpenSummary = {}, onGoHome = {})
                MoreSubTab.JOURNAL -> JournalTab(vm)
                MoreSubTab.MAP -> StakeholderMapTab(vm)
                MoreSubTab.DASHBOARD -> ImplementationDashboardTab(vm)
                MoreSubTab.SETTINGS -> AiSettingsScreen(vm)
            }
        }
    }
}

@Composable
internal fun ReportWithSubTabs(vm: StrategosViewModel, selected: ReportSubTab, onReviewAi: () -> Unit, onSelect: (ReportSubTab) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NeoSubTabBar(
            entries = ReportSubTab.entries, selected = selected, label = { it.label }, icon = { it.icon },
            onSelect = onSelect, accentColor = NeoTokens.amber
        )
        Box(Modifier.fillMaxSize()) {
            when (selected) {
                ReportSubTab.DECISION -> ReportTab(vm, onReviewAi)
                ReportSubTab.FRAMEWORKS -> AnalysisTab(vm)
            }
        }
    }
}
