package com.drkkr.strategos.ui.calm.talk

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.data.repo.ThreadSummary
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.calm.projects.ProjectsUi
import kotlinx.coroutines.launch

/**
 * Owns the Talk wiring: binds the shared Counsel controller to the project, maps its state into [TalkUiState],
 * and holds messages sent while offline until the connection returns.
 */
@Composable
fun TalkRoute(
    projectId: String,
    onBack: () -> Unit,
    onOpenXRay: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit
) {
    val vm: StrategosViewModel = viewModel()
    val app = vm.state.collectAsState().value
    val counselUi by vm.counselState.collectAsState()
    val reveal by vm.revealMath.collectAsState()
    val counsel = vm.counsel
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bind the controller to this project once the scenario list has loaded.
    LaunchedEffect(projectId, app.scenarios.size) {
        app.scenarios.firstOrNull { it.id == projectId }?.let { if (app.activeScenario?.id != projectId) vm.setActiveScenario(it) }
    }
    val scenario = app.scenarios.firstOrNull { it.id == projectId }

    // Thread summaries (last line, time) come from the repository; the live thread list only triggers a refresh.
    val convo = remember { (ctx.applicationContext as StrategosApplication).container.database?.let { ConversationRepository(it) } }
    var summaries by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    LaunchedEffect(projectId, counselUi.threads, counselUi.items.size, counselUi.conversationId) {
        summaries = convo?.threadSummaries(projectId).orEmpty()
    }

    val online = rememberOnline()
    val queued = remember(projectId) { mutableStateListOf<String>() }
    LaunchedEffect(online, counselUi.running, counselUi.aiReady) {
        if (online && queued.isNotEmpty() && !counselUi.running && counselUi.aiReady) {
            val next = queued.removeAt(0)
            counsel?.send(next)
        }
    }

    val lastError = counselUi.items.lastOrNull() as? ChatItem.Error
    val problem = when {
        counselUi.keyInvalid -> TalkProblem.KEY_REJECTED
        lastError != null -> TalkProblem.FAILED
        counselUi.aiReady -> TalkProblem.NONE
        !counselUi.hasKey || counselUi.needsConsent -> TalkProblem.NO_KEY
        else -> TalkProblem.AI_OFF
    }
    val now = System.currentTimeMillis()
    val state = TalkUiState(
        projectName = scenario?.let { ProjectsUi.displayName(it.title) }.orEmpty(),
        topics = summaries.map { TopicUi(it.id, it.title, it.lastLine, TalkUi.whenLabel(it.lastAt, now), it.id == counselUi.conversationId) },
        turns = TalkUi.turns(counselUi.items.filter { it !is ChatItem.Error }, queued.toList()),
        thinking = counselUi.running,
        activity = counselUi.activity,
        problem = problem,
        offline = !online,
        reveal = reveal,
        canRetry = lastError?.retryable == true || counselUi.canRetry,
        loading = scenario == null
    )
    TalkScreen(state, TalkActions(
        onBack = onBack,
        onSend = { text -> if (!online) queued += text else counsel?.send(text) },
        onStop = { counsel?.stop() },
        onRetry = { counsel?.retryLastTurn() },
        onSelectTopic = { counsel?.selectThread(it) },
        onNewTopic = { text ->
            scope.launch {
                counsel?.createNamedThread(ProjectsUi.displayName(text, 28))?.join()
                counsel?.send(text)
            }
        },
        onOpenXRay = onOpenXRay,
        onOpenSettings = onOpenSettings,
        onOpenMemory = onOpenMemory
    ))
}

@Composable
private fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember { mutableStateOf(cm.activeNetwork != null) }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = cm.activeNetwork != null }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}
