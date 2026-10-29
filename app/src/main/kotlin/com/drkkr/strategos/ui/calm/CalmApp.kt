package com.drkkr.strategos.ui.calm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.counsel.CounselTab
import com.drkkr.strategos.ui.journal.JournalTab
import com.drkkr.strategos.ui.settings.AiSettingsScreen
import com.drkkr.strategos.ui.calm.xray.XRayViewModel
import com.drkkr.strategos.ui.calm.xray.XRayContent
import com.drkkr.strategos.ui.calm.overview.OverviewActions
import com.drkkr.strategos.ui.calm.overview.OverviewScreen
import com.drkkr.strategos.ui.calm.overview.OverviewViewModel
import com.drkkr.strategos.ui.calm.projects.ProjectsActions
import com.drkkr.strategos.ui.calm.projects.ProjectsScreen
import com.drkkr.strategos.ui.calm.projects.ProjectsViewModel
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.groundBrush
import kotlinx.coroutines.launch

/** The root of the calm UI: the sky ground, the shared dock, and whichever route is current. */
@Composable
fun CalmApp() {
    val nav = rememberCalmNav()
    BackHandler(enabled = nav.canBack) { nav.back() }
    val prefs = LocalContext.current.getSharedPreferences("calm_ui", android.content.Context.MODE_PRIVATE)
    var onboardedFlag by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("onboarded", false)) }
    val projectsVm: ProjectsViewModel = viewModel()
    val projectsUi by projectsVm.state.collectAsState()
    val onboarding = !onboardedFlag && projectsUi.isFirstRun && nav.current is CalmRoute.Projects
    val c = Calm.colors
    // The ground is painted once, behind everything including the system bars (edge-to-edge).
    Box(Modifier.fillMaxSize().background(c.groundBrush)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { if (nav.current !is CalmRoute.Talk && !onboarding) StrategosDock(nav.tab) { nav.selectTab(it) } }
        ) { pad ->
            // Talk draws edge to edge (its text fades into the status bar); every other screen keeps the system padding.
            val inTalk = nav.current is CalmRoute.Talk
            Box(Modifier.fillMaxSize().padding(if (inTalk) androidx.compose.foundation.layout.PaddingValues() else pad)) {
                when (val route = nav.current) {
                    CalmRoute.Projects -> if (onboarding) OnboardingRoute(nav, projectsVm) { prefs.edit().putBoolean("onboarded", true).apply(); onboardedFlag = true } else ProjectsRoute(nav)
                    is CalmRoute.Overview -> OverviewRoute(nav, route.projectId)
                    is CalmRoute.Talk -> TalkRoutePane(nav, route.projectId)
                    CalmRoute.Archived -> ArchivedRoute(nav, projectsVm)
                    CalmRoute.Journal -> com.drkkr.strategos.ui.calm.journal.JournalRoute(onOpenProject = { nav.go(CalmRoute.Overview(it)) })
                    CalmRoute.Settings -> com.drkkr.strategos.ui.calm.settings.SettingsRoute(viewModel())
                }
            }
        }
    }
}

@Composable
private fun ProjectsRoute(nav: CalmNav) {
    val vm: ProjectsViewModel = viewModel()
    val listUi by vm.state.collectAsState()
    val draftText by vm.draftText.collectAsState()
    val ui = listUi.copy(draft = draftText)
    val scope = rememberCoroutineScope()
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var deleting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val nameOf = { id: String? -> ui.projects.firstOrNull { it.id == id }?.name.orEmpty() }

    renaming?.let { id ->
        var text by androidx.compose.runtime.remember(id) { androidx.compose.runtime.mutableStateOf(nameOf(id)) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { Text("Save", color = Calm.colors.action, modifier = Modifier.clickable { vm.rename(id, text); renaming = null }.then(Modifier.padding(12.dp))) },
            dismissButton = { Text("Cancel", color = Calm.colors.ink2, modifier = Modifier.clickable { renaming = null }.then(Modifier.padding(12.dp))) }
        )
    }
    deleting?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${nameOf(id)}?") },
            text = { Text("Its memory and topics go with it. Your journal entries stay.") },
            confirmButton = { Text("Delete project", color = Calm.colors.tension, modifier = Modifier.clickable { vm.delete(id); deleting = null }.then(Modifier.padding(12.dp))) },
            dismissButton = { Text("Cancel", color = Calm.colors.ink2, modifier = Modifier.clickable { deleting = null }.then(Modifier.padding(12.dp))) }
        )
    }
    ProjectsScreen(ui, ProjectsActions(
        onDraft = vm::setDraft,
        onSend = { scope.launch {
            if (!vm.keyReady()) { com.drkkr.strategos.ui.calm.settings.SettingsNudge.on = true; nav.selectTab(DockTab.SETTINGS); return@launch }   // the draft stays in the box
            val text = ui.draft.trim(); vm.createProject()?.let { id -> com.drkkr.strategos.ui.calm.talk.PendingFirstMessage.set(id, text); nav.go(CalmRoute.Talk(id)) } } },
        onPickExample = vm::setDraft,
        onOpenProject = { nav.go(CalmRoute.Overview(it)) },
        onRename = { renaming = it },
        onArchive = vm::archive,
        onDelete = { deleting = it },
        onOpenArchived = { nav.go(CalmRoute.Archived) },
        onAddKey = { nav.selectTab(DockTab.SETTINGS) },
        onOpenJournal = { nav.selectTab(DockTab.JOURNAL) },
        onOpenSettings = { nav.selectTab(DockTab.SETTINGS) }
    ))
}

@Composable
private fun OverviewRoute(nav: CalmNav, projectId: String) {
    val app = LocalContext.current.applicationContext as Application
    val ctx = LocalContext.current
    val vm: OverviewViewModel = viewModel(
        key = "overview-$projectId",
        factory = viewModelFactory { initializer { OverviewViewModel(app, projectId) } }
    )
    val ui by vm.state.collectAsState()
    var showXRay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showReview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAdd by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    renaming?.let { start ->
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Calm.colors.paper, title = { Text("Rename project", color = Calm.colors.ink) },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { Text("Save", color = Calm.colors.action, modifier = Modifier.clickable { vm.rename(text); renaming = null }.padding(12.dp)) },
            dismissButton = { Text("Cancel", color = Calm.colors.ink2, modifier = Modifier.clickable { renaming = null }.padding(12.dp)) }
        )
    }
    if (showReview) com.drkkr.strategos.ui.calm.overview.ReviewAiInputsSheet(ui.reviewItems, vm::review, vm::acceptAll) { showReview = false }
    if (showAdd) com.drkkr.strategos.ui.calm.overview.AddFactDialog(onAdd = vm::addFact, onDismiss = { showAdd = false })
    if (showXRay) XRayRoute(projectId, onDismiss = { showXRay = false }, onReadMore = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
    OverviewScreen(ui, journalPage = { ProjectJournalTab(projectId, ui.projectName) }, a = OverviewActions(
        onBack = { nav.back() },
        onOpenChat = { nav.go(CalmRoute.Talk(projectId)) },
        onOpenThread = { id -> com.drkkr.strategos.ui.calm.talk.PendingThread.set(projectId, id); nav.go(CalmRoute.Talk(projectId)) },
        onNewTopic = { nav.go(CalmRoute.Talk(projectId)) },
        onSaveQuickCheck = { did, landed -> vm.saveQuickCheck(did, landed) },
        onOpenReply = { nav.go(CalmRoute.Talk(projectId)) },
        onReadMore = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onOpenXRay = { showXRay = true },
        onReview = { showReview = true },
        onAddFact = { showAdd = true },
        onApplyJournal = { vm.review(it, apply = true) },
        onDismissJournal = { vm.review(it, apply = false) },
        onOpenJournalTab = {},
        onRename = { renaming = ui.projectName }
    ))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun XRayRoute(projectId: String, onDismiss: () -> Unit, onReadMore: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: XRayViewModel = viewModel(key = "xray-$projectId", factory = viewModelFactory { initializer { XRayViewModel(app, projectId) } })
    val ui by vm.state.collectAsState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = Calm.dimens.sheetTopRadius, topEnd = Calm.dimens.sheetTopRadius),
        containerColor = Color.Transparent,
        scrimColor = Color(0xFF101E40).copy(alpha = 0.4f)
    ) {
        androidx.compose.foundation.layout.Box(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            XRayContent(ui, onOdds = vm::setOdds, onKeep = { vm.keep() }, onReset = vm::reset, onReadMore = onReadMore, onClose = onDismiss)
        }
    }
}

/**
 * Interim hosts: Talk, Journal and Settings still render the proven screens from the old UI inside the
 * calm shell (old theme, shared StrategosViewModel) until their redesigned versions land, so the app
 * stays fully usable (chat, AI key, journal) while migrating.
 */
@Composable
private fun LegacyHost(content: @Composable (StrategosViewModel) -> Unit) {
    val vm: StrategosViewModel = viewModel()
    OldTheme { Box(Modifier.fillMaxSize().background(OldTheme.surface())) { content(vm) } }
}

@Composable
private fun TalkRoutePane(nav: CalmNav, projectId: String) {
    var showXRay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val ctx = LocalContext.current
    BackHandler { nav.back() }
    if (showXRay) XRayRoute(projectId, onDismiss = { showXRay = false }, onReadMore = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
    com.drkkr.strategos.ui.calm.talk.TalkRoute(
        projectId, onBack = { nav.back() }, onOpenXRay = { showXRay = true },
        onOpenSettings = { nav.selectTab(DockTab.SETTINGS) }, onOpenMemory = { nav.back() }
    )
}

@Composable
private fun LegacyTalkRoute(nav: CalmNav, projectId: String) {
    val vm: StrategosViewModel = viewModel()
    val st by vm.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(projectId, st.scenarios.size) {
        st.scenarios.firstOrNull { it.id == projectId }?.let { if (st.activeScenario?.id != projectId) vm.setActiveScenario(it) }
    }
    BackHandler { nav.back() }
    OldTheme {
        Box(Modifier.fillMaxSize().background(OldTheme.surface())) {
            CounselTab(vm, expert = false, onOpenSettings = { nav.selectTab(DockTab.SETTINGS) }, onGoHome = { nav.back() }, onReviewAi = {})
        }
    }
}

private object OldTheme {
    @Composable operator fun invoke(content: @Composable () -> Unit) = com.drkkr.strategos.ui.theme.StrategosTheme(content = content)
    @Composable fun surface() = androidx.compose.material3.MaterialTheme.colorScheme.background
}

@Composable
private fun OnboardingRoute(nav: CalmNav, vm: ProjectsViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    com.drkkr.strategos.ui.calm.onboarding.OnboardingScreen(beforeStart = { proceed ->
        scope.launch {
            if (vm.keyReady()) proceed()
            else { com.drkkr.strategos.ui.calm.settings.SettingsNudge.on = true; onDone(); nav.selectTab(DockTab.SETTINGS) }   // no key yet: set it first
        }
    }, onSend = { text ->
        scope.launch {
            vm.setDraft(text)
            vm.createProject()?.let { id ->
                com.drkkr.strategos.ui.calm.talk.PendingFirstMessage.set(id, text)
                onDone()
                nav.go(CalmRoute.Talk(id))
            }
        }
    })
}

@Composable
private fun ArchivedRoute(nav: CalmNav, vm: ProjectsViewModel) {
    val items by vm.archived.collectAsState()
    var deleting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
    BackHandler { nav.back() }
    deleting?.let { (id, name) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("Its memory and topics go with it. Your journal entries stay.") },
            confirmButton = { Text("Delete project", color = Calm.colors.tension, modifier = Modifier.clickable { vm.delete(id); deleting = null }.padding(12.dp)) },
            dismissButton = { Text("Cancel", color = Calm.colors.ink2, modifier = Modifier.clickable { deleting = null }.padding(12.dp)) }
        )
    }
    com.drkkr.strategos.ui.calm.projects.ArchivedScreen(items, onBack = { nav.back() }, onRestore = vm::restore, onDelete = { id -> deleting = items.firstOrNull { it.first == id } })
}

/** The Journal tab of a project: entries linked to it, the screener's suggestions, and a composer linked to it. */
@Composable
private fun ProjectJournalTab(projectId: String, projectName: String) {
    val vm: StrategosViewModel = viewModel()
    val ui by vm.counselState.collectAsState()
    val scope = rememberCoroutineScope()
    val repo = (LocalContext.current.applicationContext as com.drkkr.strategos.StrategosApplication).container.journalRepository
    var suggestions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap()) }
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(ui.journalEntries.size, projectId, refresh) {
        suggestions = repo?.linkSuggestions(projectId)?.associate { it.entryId to it.scenarioId }.orEmpty()
    }
    val rows = com.drkkr.strategos.ui.calm.journal.JournalUi.build(ui.journalEntries, mapOf(projectId to projectName), suggestions, System.currentTimeMillis())
    com.drkkr.strategos.ui.calm.journal.ProjectJournalPage(
        projectName,
        linked = rows.filter { it.linkedProjectId == projectId },
        suggested = rows.filter { it.suggestedProjectId == projectId },
        onSave = { text -> scope.launch { vm.counsel?.recordJournalEntry(text, "NEUTRAL", projectId); refresh++ } },
        onLink = { id -> scope.launch { repo?.link(id, projectId); refresh++ } },
        onDismiss = { id -> scope.launch { repo?.dismissLink(id, projectId); refresh++ } }
    )
}
