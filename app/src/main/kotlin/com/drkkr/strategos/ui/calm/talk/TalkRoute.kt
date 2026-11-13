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
import com.drkkr.strategos.domain.a2ui.A2ui
import com.drkkr.strategos.domain.files.FileBlock
import com.drkkr.strategos.domain.files.FileBrief
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
    onOpenOverview: () -> Unit,
    /** How the project is trending, for the line under the title. Null until its chart has data. */
    status: com.drkkr.strategos.domain.analysis.MomentumStatus? = null,
    /** The pending check-in question, if the user has something to report back on. */
    checkIn: String? = null,
    onSaveCheckIn: (did: String, landed: String) -> Unit = { _, _ -> }
) {
    val vm: StrategosViewModel = viewModel()
    val app = vm.state.collectAsState().value
    val counselUi by vm.counselState.collectAsState()
    val reveal by vm.revealMath.collectAsState()
    val counsel = vm.counsel
    val ctx = LocalContext.current

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

    LaunchedEffect(projectId, app.activeScenario?.id, counselUi.conversationId) {
        if (app.activeScenario?.id == projectId && counselUi.conversationId != null) PendingThread.take(projectId)?.let { counsel?.selectThread(it) }
    }

    // A project started from the Projects composer or onboarding sends its first message once the coach is ready.
    LaunchedEffect(projectId, app.activeScenario?.id, counselUi.conversationId, counselUi.aiReady, counselUi.running) {
        if (app.activeScenario?.id == projectId && counselUi.conversationId != null && counselUi.aiReady && !counselUi.running) {
            PendingFirstMessage.take(projectId)?.let { counsel?.send(it) }
        }
    }

    // ChatGPT-style: once the first reply is in, a project still carrying its placeholder name gets a short title. Renaming later is always the user's.
    val container = remember { (ctx.applicationContext as StrategosApplication).container }
    LaunchedEffect(projectId, counselUi.running, counselUi.items.size) {
        if (counselUi.running || counselUi.items.none { it is ChatItem.AssistantText }) return@LaunchedEffect
        val s = container.scenarioRepository.find(projectId) ?: return@LaunchedEffect
        if (s.description.isBlank() || s.title != ProjectsUi.displayName(s.description) || !AutoNaming.begin(projectId)) return@LaunchedEffect
        counsel?.suggestProjectTitle(s.description)?.let { name ->
            container.scenarioRepository.find(projectId)?.takeIf { it.title == s.title }?.let { container.scenarioRepository.save(it.copy(title = name)) }
        }
    }

    val lastError = counselUi.items.lastOrNull() as? ChatItem.Error
    val problem = when {
        !counselUi.statusLoaded -> TalkProblem.NONE          // still reading the key: say nothing rather than flash "no key"
        counselUi.keyInvalid -> TalkProblem.KEY_REJECTED
        lastError != null -> TalkProblem.FAILED
        counselUi.aiReady -> TalkProblem.NONE
        !counselUi.hasKey || counselUi.needsConsent -> TalkProblem.NO_KEY
        else -> TalkProblem.AI_OFF
    }
    val picked = rememberFilePick(projectId)
    val now = System.currentTimeMillis()
    val state = TalkUiState(
        projectName = scenario?.let { ProjectsUi.displayName(it.title) }.orEmpty(),
        topics = summaries.map { TopicUi(it.id, it.title, FileBlock.words(it.lastLine), TalkUi.whenLabel(it.lastAt, now), it.id == counselUi.conversationId) },
        turns = TalkUi.turns(counselUi.items.filter { it !is ChatItem.Error }, queued.toList()),
        thinking = counselUi.running,
        activity = counselUi.activity,
        problem = problem,
        offline = !online,
        reveal = reveal,
        canRetry = lastError?.retryable == true || counselUi.canRetry,
        loading = scenario == null,
        powerMode = counselUi.powerMode,
        powerAvailable = counselUi.powerModeAvailable && counselUi.conversationId != null,
        powerExplained = counselUi.powerExplained,
        statusLine = when (status) {
            com.drkkr.strategos.domain.analysis.MomentumStatus.IMPROVING -> "Getting better"
            com.drkkr.strategos.domain.analysis.MomentumStatus.TENSE -> "Getting tense"
            com.drkkr.strategos.domain.analysis.MomentumStatus.STEADY -> "Holding steady"
            null -> ""
        },
        statusTone = when (status) {
            com.drkkr.strategos.domain.analysis.MomentumStatus.IMPROVING -> 1
            com.drkkr.strategos.domain.analysis.MomentumStatus.TENSE -> -1
            else -> 0
        },
        // Same rule as the label under a reply: the X-ray is offered once the engine has a move, unless the user hid the math.
        canXRay = reveal != RevealMath.NEVER && counselUi.items.any { it is ChatItem.AssistantText && it.pill != null },
        checkIn = checkIn,
        attachment = picked.ui
    )
    TalkScreen(state, TalkActions(
        onBack = onBack,
        // A file that is ready goes inside the message as a prepared block (see FileBlock); with no words of the user's own it gets a default ask.
        onSend = { text ->
            val full = picked.take()?.let { FileBlock.compose(text, it) } ?: text
            if (full.isNotBlank()) { if (!online) queued += full else counsel?.send(full) }
        },
        // A card's reply goes out exactly like a typed message; its other taps open a place in the app.
        onCardAction = { a ->
            when (a.name) {
                A2ui.REPLY -> if (a.text.isNotBlank()) { if (!online) queued += a.text else counsel?.send(a.text) }
                A2ui.OPEN_OVERVIEW -> onOpenOverview()
            }
        },
        onAttach = picked.open,
        onRemoveAttachment = picked.clear,
        onStop = { counsel?.stop() },
        onRetry = { counsel?.retryLastTurn() },
        onSelectTopic = { counsel?.selectThread(it) },
        onOpenXRay = onOpenXRay,
        onOpenSettings = onOpenSettings,
        onOpenOverview = onOpenOverview,
        onSaveCheckIn = onSaveCheckIn,
        onSetPowerMode = { on -> counsel?.setPowerMode(on) },
        onPowerExplained = { counsel?.markPowerExplained() },
        // The user's word on the Power Read card: saved through the same userFixed path as the Review sheet (I-H8).
        onSetLeverage = { key, value ->
            vm.state.value.activeScenario?.takeIf { it.id == projectId }?.let { s ->
                vm.updateActiveScenario(s.copy(humanContext = (s.humanContext ?: com.drkkr.strategos.domain.human.HumanContext()).setByUser(key, value)))
                vm.saveScenario()
            }
        }
    ))
}

/** Treats an unreadable network state as online, so a missing permission can never take the screen down. */
private fun hasNetwork(cm: ConnectivityManager): Boolean = runCatching { cm.activeNetwork != null }.getOrDefault(true)

@Composable
private fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember { mutableStateOf(hasNetwork(cm)) }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = hasNetwork(cm) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}
