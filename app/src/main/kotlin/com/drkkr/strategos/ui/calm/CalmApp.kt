package com.drkkr.strategos.ui.calm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.counsel.CounselTab
import com.drkkr.strategos.ui.journal.JournalTab
import com.drkkr.strategos.ui.settings.AiSettingsScreen
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
    val c = Calm.colors
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { if (nav.current !is CalmRoute.Talk) StrategosDock(nav.tab) { nav.selectTab(it) } }
    ) { pad ->
        Box(Modifier.fillMaxSize().background(c.groundBrush).padding(pad)) {
            when (val route = nav.current) {
                CalmRoute.Projects -> ProjectsRoute(nav)
                is CalmRoute.Overview -> OverviewRoute(nav, route.projectId)
                is CalmRoute.Talk -> LegacyTalkRoute(nav, route.projectId)
                CalmRoute.Journal -> LegacyHost { JournalTab(it) }
                CalmRoute.Settings -> LegacyHost { AiSettingsScreen(it) }
            }
        }
    }
}

@Composable
private fun ProjectsRoute(nav: CalmNav) {
    val vm: ProjectsViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    ProjectsScreen(ui, ProjectsActions(
        onDraft = vm::setDraft,
        onSend = { scope.launch { vm.createProject()?.let { nav.go(CalmRoute.Talk(it)) } } },
        onPickExample = vm::setDraft,
        onOpenProject = { nav.go(CalmRoute.Overview(it)) },
        onArchive = vm::archive,
        onDelete = vm::delete,
        onOpenArchived = {},
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
    OverviewScreen(ui, OverviewActions(
        onBack = { nav.back() },
        onOpenChat = { nav.go(CalmRoute.Talk(projectId)) },
        onOpenThread = { nav.go(CalmRoute.Talk(projectId)) },
        onNewTopic = { nav.go(CalmRoute.Talk(projectId)) },
        onSaveQuickCheck = { did, landed -> vm.saveQuickCheck(did, landed) },
        onOpenReply = { nav.go(CalmRoute.Talk(projectId)) },
        onReadMore = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onOpenXRay = {},
        onReview = {},
        onAddFact = {},
        onApplyJournal = { vm.review(it, apply = true) },
        onDismissJournal = { vm.review(it, apply = false) },
        onOpenJournalTab = {}
    ))
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
