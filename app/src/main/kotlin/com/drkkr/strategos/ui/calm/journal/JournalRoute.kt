package com.drkkr.strategos.ui.calm.journal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.domain.model.ScenarioStatus
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.calm.projects.ProjectsUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Journal wiring: the shared Counsel controller records entries; the repository answers link suggestions. */
@Composable
fun JournalRoute(onOpenProject: (String) -> Unit) {
    val vm: StrategosViewModel = viewModel()
    val app by vm.state.collectAsState()
    val ui by vm.counselState.collectAsState()
    val counsel = vm.counsel
    val scope = rememberCoroutineScope()
    val repo = (LocalContext.current.applicationContext as StrategosApplication).container.journalRepository

    val active = app.scenarios.filter { it.status != ScenarioStatus.ARCHIVED }
    val titles = active.associate { it.id to ProjectsUi.displayName(it.title) }

    // entryId -> first project the screener thinks it belongs to. Refreshed when entries or projects change.
    var suggestions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(ui.journalEntries.size, active.map { it.id }, refresh) {
        val map = mutableMapOf<String, String>()
        if (repo != null) for (s in active) repo.linkSuggestions(s.id).forEach { map.putIfAbsent(it.entryId, it.scenarioId) }
        suggestions = map
    }

    var toast by remember { mutableStateOf(false) }
    LaunchedEffect(toast) { if (toast) { delay(2200); toast = false } }

    val entries = JournalUi.build(ui.journalEntries, titles, suggestions, System.currentTimeMillis())
    JournalScreen(
        JournalUiState(entries = entries, loading = false, savedToast = toast),
        JournalActions(
            onSave = { text, mood ->
                scope.launch { counsel?.recordJournalEntry(text, mood?.tag ?: "NEUTRAL", null); toast = true }
            },
            onLink = { eid, pid -> scope.launch { repo?.link(eid, pid); refresh++ } },
            onDismissSuggestion = { eid, pid -> scope.launch { repo?.dismissLink(eid, pid); refresh++ } },
            onOpenProject = onOpenProject
        )
    )
}
