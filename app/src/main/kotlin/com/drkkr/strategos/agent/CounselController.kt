package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.dao.WhatIfDao
import kotlinx.coroutines.flow.first
import com.drkkr.strategos.domain.model.ChangeRecord
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.ApplyResult
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.db.entities.ChangeSetEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.JournalRecordResult
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeOpDescriber
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.ProvenanceStats
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.assistantText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** The scenario side the controller needs. Implemented by StrategosViewModel (§10.5). */
interface CounselGateway : ScenarioGateway {
    suspend fun commitAgentChange(updated: Scenario): SaveResult
}

/** Everything the controller uses, built by AppContainer (or by a test). */
class CounselDeps(
    val llm: LlmClient,
    val systemPrompt: () -> String,
    val tools: () -> JsonArray,
    val agentVersion: () -> String,
    val conversations: ConversationRepository,
    val changeSets: ChangeSetRepository,
    val memory: MemoryRepository,
    val settings: SettingsRepository,
    val whatIfs: WhatIfDao,
    val hasKey: suspend () -> Boolean,
    val pipeline: AnalysisPipeline = AnalysisPipeline(),
    val dispatcher: ToolDispatcher = ToolDispatcher.default(),
    /** SIMPLE PROFILE: true runs the one-shot flow (the app); false keeps the agent loop (tool calls), used by its tests. */
    val oneShot: Boolean = false,
    /** COMPANION REPLY: the one-shot flow adds a second request that writes the answer from the engine's result. */
    val coach: Boolean = false,
    /** Argon: with a [trajectory] repository the one-shot flow uses the coach prompt, the 4-layer context and records trajectory points. */
    val trajectory: TrajectoryRepository? = null,
    val journal: JournalRepository? = null,
    val journalScreener: JournalScreenerService? = null
)

/**
 * Chat logic owned by StrategosViewModel (blueprint §10.5). Plain class: no Android types.
 * BLUEPRINT-QUESTION: §10.5 passes the AppContainer; an explicit [CounselDeps] keeps it testable without the Keystore.
 * Actions return their Job so tests (and callers that care) can wait for them.
 */
class CounselController(
    private val scope: CoroutineScope,
    private val deps: CounselDeps,
    private val gateway: CounselGateway
) {
    private val _state = MutableStateFlow(CounselUiState())
    val state: StateFlow<CounselUiState> = _state.asStateFlow()

    private val summarizer = ConversationSummarizer(deps.llm, deps.conversations, deps.settings)
    private val assembler = ContextAssembler(
        deps.systemPrompt, deps.conversations, deps.changeSets, deps.memory, deps.settings,
        scenarioFor = { id -> gateway.activeScenario()?.takeIf { it.id == id } }
    )
    private val orchestrator = AgentOrchestrator(
        deps.llm, deps.tools, deps.dispatcher, assembler, deps.conversations, deps.settings,
        afterTurn = summarizer::maybeSummarize, backgroundScope = scope
    )

    private val argonAssembler = deps.trajectory?.let { t ->
        ContextAssembler(
            { ArgonPrompts.EXTRACTION_PROMPT }, deps.conversations, deps.changeSets, deps.memory, deps.settings,
            scenarioFor = { id -> gateway.activeScenario()?.takeIf { it.id == id } },
            journal = deps.journal, trajectory = t
        )
    }

    private val oneShotTurn = OneShotTurn(
        deps.llm, deps.conversations, deps.changeSets, deps.settings, gateway, autoApply = ::autoApply,
        onSupport = { showModelSupport() }, afterTurn = summarizer::maybeSummarize, coach = deps.coach,
        argon = argonAssembler?.let { OneShotTurn.ArgonTurn(it, deps.trajectory!!, deps.journal) }
    )

    init {
        // §11.5.1: screens update the moment a key, consent or the toggle changes; no restart.
        scope.launch { deps.settings.observeAiFlags().collect { refreshAiStatus() } }
        // Space 2 is global: the whole journal, whichever project is open.
        deps.journal?.let { j -> scope.launch { j.observeEntries(null).collect { list -> _state.update { it.copy(journalEntries = list) } } } }
    }

    private var scenarioId: String? = null
    private var observeJob: Job? = null
    private var turnJob: Job? = null
    private val undoStack = ArrayDeque<Scenario>()

    fun bindScenario(scenarioId: String?): Job = scope.launch {
        observeJob?.cancel()
        this@CounselController.scenarioId = scenarioId
        _state.value = CounselUiState()
        refreshAiStatus()
        if (scenarioId == null) return@launch
        val cid = runCatching {
            deps.conversations.getOrCreateActive(scenarioId, deps.settings.model(), deps.agentVersion()).id
        }.getOrNull() ?: return@launch          // the scenario row isn't saved yet; send() retries
        startObserving(cid, scenarioId)
    }

    /** Per-project flows: the thread list, the trajectory series and the journal notes. Restarted when the project changes. */
    private var extrasJob: Job? = null

    private fun observeProject(sid: String) {
        extrasJob?.cancel()
        extrasJob = scope.launch {
            launch { deps.conversations.observeThreads(sid).collect { list ->
                _state.update { st -> st.copy(threads = list.map { ThreadUi(it.id, it.title, it.topicTag, it.isPinned, it.updatedAt) }) }
            } }
            deps.trajectory?.let { t ->
                launch { t.observePoints(sid).collect { points ->
                    val snap = runCatching { t.current(sid) }.getOrNull()
                    _state.update { it.copy(trajectoryPoints = points, trajectory = snap) }
                } }
            }
            deps.journal?.let { j ->
                launch { j.observeActivePostNotes(sid).collect { notes -> _state.update { it.copy(postNotes = notes.filter { n -> !n.applied }) } } }
            }
        }
    }

    private fun startObserving(cid: String, sid: String) {
        observeProject(sid)
        _state.update { it.copy(conversationId = cid) }
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                deps.conversations.observeMessages(cid),
                deps.changeSets.observe(cid),
                deps.memory.observeOpen(sid)
            ) { messages, sets, memory ->
                val scenario = gateway.activeScenario()?.takeIf { it.id == sid }
                Triple(MessageMapper.toChatItems(messages, sets, scenario), memory.map { MemoryItemUi(it.id, it.kind, it.text, it.factStatus) },
                    scenario?.let { strip(it, sets.count { s -> s.status == ChangeSetStatus.PENDING.name }) })
            }.collect { (items, memory, strip) ->
                _state.update { it.copy(items = items, memory = memory, strip = strip) }
            }
        }
    }

    // ── Argon: named threads (Space 1) ────────────────────────────────────────

    /** Starts a new named thread in the open project and switches to it. */
    fun createNamedThread(title: String, topicTag: String = "GENERAL"): Job = scope.launch {
        val sid = scenarioId ?: return@launch
        if (turnJob?.isActive == true) return@launch
        val thread = runCatching { deps.conversations.createNamedThread(sid, title, topicTag, deps.settings.model(), deps.agentVersion()) }.getOrNull()
            ?: return@launch
        startObserving(thread.id, sid)
    }

    /** Shows another thread of the same project. The project model is shared; only the messages change. */
    fun selectThread(threadId: String): Job = scope.launch {
        val sid = scenarioId ?: return@launch
        if (turnJob?.isActive == true || _state.value.conversationId == threadId) return@launch
        val thread = deps.conversations.get(threadId)?.takeIf { it.scenarioId == sid } ?: return@launch
        deps.conversations.switchThread(thread.id)
        startObserving(thread.id, sid)
    }

    fun renameThread(threadId: String, title: String, topicTag: String): Job = scope.launch { deps.conversations.renameThread(threadId, title, topicTag) }

    fun setThreadPinned(threadId: String, pinned: Boolean): Job = scope.launch { deps.conversations.setPinned(threadId, pinned) }

    // ── Argon: journal (Space 2) and quick-check / post-notes (Space 3) ───────

    /**
     * Saves the entry locally and returns at once (I11); the screener runs in the background. A crisis phrase shows the
     * support card here, before any network call. Null when the journal is not available.
     */
    suspend fun recordJournalEntry(rawText: String, moodTag: String, linkedScenarioId: String?): JournalRecordResult? {
        val journal = deps.journal ?: return null
        if (rawText.isBlank()) return null
        val result = journal.recordEntry(rawText.trim(), moodTag, linkedScenarioId)
        if (result.safetyTriggered) showSupport()
        _state.update { it.copy(journalAck = result.ack) }
        deps.journalScreener?.screenAsync(result.entryId)
        return result
    }

    /**
     * A 2-4 word chat-style title for a new project, from its first message. One small request (about 150 tokens in, 16 out).
     * Null on any failure or an unusable answer, so the placeholder name simply stays.
     */
    suspend fun suggestProjectTitle(firstMessage: String): String? {
        val input = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("role", "system")
                put("content", "Write a title of 2 to 4 words for this situation, like a chat app's conversation title. Plain words, no quotes, no trailing punctuation, no names unless essential. Reply with the title only.")
            })
            add(kotlinx.serialization.json.buildJsonObject { put("role", "user"); put("content", firstMessage.take(500)) })
        }
        val r = deps.llm.create(com.drkkr.strategos.llm.LlmRequest(deps.settings.model(), input, tools = null, maxOutputTokens = 16, temperature = 0.3, purpose = "TITLE")) as? com.drkkr.strategos.llm.LlmResult.Ok ?: return null
        val title = r.output.assistantText()
        return title.trim().lineSequence().firstOrNull().orEmpty().trim().trim('"', '\'', '“', '”', '.', ' ')
            .takeIf { it.isNotBlank() && it.length <= 40 && it.split(' ').size in 1..6 }
    }

    fun clearJournalAck() = _state.update { it.copy(journalAck = null) }

    /** Three taps. Deterministic: no model call (I12). */
    fun submitQuickCheck(executionChoice: String, counterpartReaction: String, selfFeeling: String, note: String = "", recommendedLabel: String = ""): Job = scope.launch {
        val sid = scenarioId ?: return@launch
        val t = deps.trajectory ?: return@launch
        val snap = t.submitQuickCheck(
            sid, _state.value.conversationId, recommendedLabel, executionChoice, counterpartReaction, selfFeeling, note,
            scenario = gateway.activeScenario()?.takeIf { it.id == sid }, commit = { gateway.commitAgentChange(it) }
        )
        _state.update { it.copy(trajectory = snap) }
    }

    fun applyJournalPostNote(noteId: String): Job = scope.launch { deps.journal?.applyPostNote(noteId) }

    fun dismissJournalPostNote(noteId: String): Job = scope.launch { deps.journal?.dismissPostNote(noteId) }

    suspend fun refreshAiStatus() {
        val s = deps.settings
        val hasKey = deps.hasKey()
        _state.update {
            it.copy(
                statusLoaded = true, aiReady = s.aiReady(), hasKey = hasKey, keyInvalid = s.keyInvalid(),
                needsConsent = hasKey && s.consentVersion() != AgentConfig.CONSENT_VERSION
            )
        }
    }

    /**
     * Screens the text for crisis phrases first (§17.4: the card shows even offline or with AI off), then sends.
     * Sending is a no-op if blank, a turn is running, or AI isn't ready (I7: no network call when AI is off).
     */
    fun send(text: String): Job? {
        if (text.isBlank()) return null
        if (SafetyKeywordScreen.check(text)) showSupport()
        if (turnJob?.isActive == true) return null
        return startTurn(TurnTrigger.User(text.trim()))
    }

    /** Conversations where the support card has already been shown. */
    private val supportShownIn = mutableSetOf<String>()

    /**
     * The model's own `support` flag shows the card once per conversation. After that only a new message that matches
     * [SafetyKeywordScreen] shows it again (see [send]), so it does not come back on every turn after being dismissed.
     */
    private fun showModelSupport() {
        val cid = _state.value.conversationId
        if (cid != null && !supportShownIn.add(cid)) return
        showSupport()
    }

    private fun showSupport() {
        _state.value.conversationId?.let { supportShownIn += it }
        _state.update { it.copy(supportVisible = true) }
        scope.launch { _state.update { it.copy(supportText = deps.settings.supportText()) } }
    }

    fun dismissSupport() = _state.update { it.copy(supportVisible = false) }

    /** "Looks right" on a recap (§17.3): confirms what was added since the previous recap, then tells the agent. */
    fun confirmUnderstanding(): Job = scope.launch {
        promoteSinceLastRecap()
        send(LOOKS_RIGHT)?.join()
    }

    fun fixSomething(text: String): Job? = send("$NOT_RIGHT_PREFIX$text")

    /** Restores the scenario from before the last applied change set (kept in memory, last 5). */
    fun undoLastChange(): Job = scope.launch {
        val previous = undoStack.removeLastOrNull() ?: return@launch
        val current = gateway.activeScenario()?.takeIf { it.id == previous.id } ?: return@launch
        val record = ChangeRecord(
            changedBy = "user", summary = "Undid the last AI change", previousVersion = current.version,
            previousLifecycleStage = current.lifecycleStage, reason = "Undo"
        )
        val restored = previous.copy(version = current.version, changeHistory = current.changeHistory + record)
        if (gateway.commitAgentChange(restored) !is SaveResult.Success) {
            _state.update { it.copy(error = "Couldn't undo. The model was not changed.") }
        }
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun answerQuestion(questionId: String, questionText: String, option: String): Job? = send("Re: $questionText → $option")

    fun retryLastTurn(): Job? = if (turnJob?.isActive == true) null else startTurn(TurnTrigger.Retry)

    fun stop() {
        turnJob?.cancel()
    }

    suspend fun awaitIdle() {
        turnJob?.join()
    }

    private fun startTurn(trigger: TurnTrigger): Job {
        val job = scope.launch {
            if (!deps.settings.aiReady()) {
                refreshAiStatus()
                return@launch
            }
            val sid = scenarioId ?: return@launch
            val cid = _state.value.conversationId ?: runCatching {
                deps.conversations.getOrCreateActive(sid, deps.settings.model(), deps.agentVersion()).id
            }.getOrNull()?.also { startObserving(it, sid) } ?: run {
                _state.update { it.copy(error = "Save the scenario first.") }
                return@launch
            }
            _state.update { it.copy(running = true, activity = "Thinking…", error = null, canRetry = false) }
            try {
                val onActivity = { label: String -> _state.update { it.copy(activity = label) } }
                val result = if (deps.oneShot) oneShotTurn.run(cid, sid, trigger, onActivity)
                else orchestrator.runTurn(cid, sid, trigger, { turnId -> toolContext(cid, sid, turnId) }, onActivity)
                if (result is TurnResult.Failed) _state.update { it.copy(error = result.userMessage, canRetry = result.retryable) }
                else deps.journalScreener?.let { svc -> scope.launch { svc.retryPendingScreens() } }   // online again: screen what was queued
            } finally {
                _state.update { it.copy(running = false, activity = null) }
            }
        }
        turnJob = job
        return job
    }

    private suspend fun toolContext(cid: String, sid: String, turnId: String) = ToolContext(
        conversationId = cid, turnId = turnId, scenarioId = sid, gateway = gateway, pipeline = deps.pipeline,
        conversations = deps.conversations, changeSets = deps.changeSets, memory = deps.memory, whatIfs = deps.whatIfs,
        userTexts = deps.conversations.userTexts(cid),
        uiMode = deps.settings.uiMode(),
        autoApply = ::autoApply
    )

    /**
     * Guided mode (§17.3): the same path as [applyChangeSet] with every op accepted, but no EVENT and no auto-continue,
     * because the agent is still mid-turn. Returns null when the change couldn't be saved.
     */
    suspend fun autoApply(changeSetId: String): ApplyResult? {
        val cs = deps.changeSets.get(changeSetId)?.takeIf { it.status == ChangeSetStatus.PENDING.name } ?: return null
        val ops = MessageMapper.decodeOps(cs.opsJson)
        return applyInternal(cs, ops.indices.map { OpDecision(it, Decision.ACCEPT) })?.first
    }

    /** Applies, commits and marks the set decided. Returns the result and the saved scenario, or null on failure. */
    private suspend fun applyInternal(cs: ChangeSetEntity, decisions: List<OpDecision>): Pair<ApplyResult, Scenario>? {
        val scenario = gateway.activeScenario()?.takeIf { it.id == cs.scenarioId } ?: return null
        val ops = MessageMapper.decodeOps(cs.opsJson)
        val refIds = runCatching { Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cs.refIdsJson) }.getOrDefault(emptyMap())
        val userTexts = deps.conversations.userTexts(cs.conversationId)

        val result = withContext(Dispatchers.Default) { ChangeSetApplier.apply(scenario, ops, decisions, refIds, userTexts, cs.summary) }
        val saved = when (val commit = gateway.commitAgentChange(result.scenario)) {
            is SaveResult.Success -> commit.saved
            is SaveResult.Conflict -> {
                _state.update { it.copy(error = "The model changed elsewhere. Reload it and review the changes again.") }
                return null
            }
            SaveResult.StorageUnavailable -> {
                _state.update { it.copy(error = "Storage unavailable. The changes were not applied.") }
                return null
            }
        }
        if (result.applied > 0) {
            undoStack.addLast(scenario)
            while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        val status = when {
            result.applied == ops.size -> ChangeSetStatus.APPLIED
            result.applied > 0 -> ChangeSetStatus.PARTIAL
            else -> ChangeSetStatus.REJECTED
        }
        deps.changeSets.markDecided(
            cs.id, status, decisionsJson(decisions),
            Json.encodeToString(ListSerializer(String.serializer()), result.skipped.map { "${it.first}:${it.second}" }), saved.version
        )
        return result to saved
    }

    /** AI_INFERRED stakeholders, actions and constraints created since the previous recap become USER_ESTIMATE. Payoffs stay. */
    private suspend fun promoteSinceLastRecap() {
        val cid = _state.value.conversationId ?: return
        val scenario = gateway.activeScenario() ?: return
        val recaps = deps.conversations.messages(cid).filter { it.uiKind == UiKind.UNDERSTANDING.name }
        val since = recaps.dropLast(1).lastOrNull()?.createdAt ?: 0L
        val created = deps.changeSets.observe(cid).first()
            .filter { (it.decidedAt ?: 0L) > since && (it.status == ChangeSetStatus.APPLIED.name || it.status == ChangeSetStatus.PARTIAL.name) }
            .flatMap { cs -> runCatching { Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cs.refIdsJson).values }.getOrDefault(emptyList()) }
            .toSet()
        fun FactStatus.promoted(id: String) = if (this == FactStatus.AI_INFERRED && id in created) FactStatus.USER_ESTIMATE else this
        val updated = scenario.copy(
            stakeholders = scenario.stakeholders.map { it.copy(factStatus = it.factStatus.promoted(it.id)) },
            actions = scenario.actions.map { it.copy(factStatus = it.factStatus.promoted(it.id)) },
            constraints = scenario.constraints.map { it.copy(factStatus = it.factStatus.promoted(it.id)) }
        )
        if (updated != scenario) gateway.commitAgentChange(updated)
    }

    fun applyChangeSet(id: String, decisions: List<OpDecision>): Job = scope.launch {
        val cs = deps.changeSets.get(id)?.takeIf { it.status == ChangeSetStatus.PENDING.name } ?: return@launch
        val before = gateway.activeScenario() ?: return@launch
        val ops = MessageMapper.decodeOps(cs.opsJson)
        val (result, saved) = applyInternal(cs, decisions) ?: return@launch
        val skipped = result.skipped.joinToString("; ") { (i, reason) ->
            val text = ops.getOrNull(i)?.let { ChangeOpDescriber.describe(it, before, ops) } ?: "change ${i + 1}"
            "$text ($reason)"
        }.ifEmpty { "none" }
        reportDecision(
            cs.conversationId,
            "User applied ${result.applied} of ${ops.size} proposed changes (\"${cs.summary}\"). " +
                "Rejected or skipped: $skipped. Scenario is now version ${saved.version}."
        )
    }

    fun rejectChangeSet(id: String): Job = scope.launch {
        val cs = deps.changeSets.get(id)?.takeIf { it.status == ChangeSetStatus.PENDING.name } ?: return@launch
        deps.changeSets.markDecided(id, ChangeSetStatus.REJECTED, null, null, null)
        reportDecision(cs.conversationId, "User rejected the proposed changes (\"${cs.summary}\").")
    }

    /** Appends the EVENT; with auto-continue on and no turn running, the EVENT starts the next turn instead (§10.5 steps 5–6). */
    private suspend fun reportDecision(cid: String, text: String) {
        if (deps.settings.autoContinue() && turnJob?.isActive != true && deps.settings.aiReady()) {
            startTurn(TurnTrigger.Event(text))
        } else {
            deps.conversations.append(cid, "decision", MessageRole.EVENT, text)
        }
    }

    fun clearConversation(): Job = scope.launch {
        _state.value.conversationId?.let { deps.conversations.clear(it) }
    }

    fun confirmMemory(id: String): Job = scope.launch { deps.memory.setStatus(id, FactStatus.USER_ESTIMATE) }

    fun deleteMemory(id: String): Job = scope.launch { deps.memory.delete(id) }

    /** Creates a PENDING change set from a what-if's ops, re-validated against the current model. */
    fun promoteWhatIf(whatIfId: String): Job = scope.launch {
        val w = deps.whatIfs.get(whatIfId) ?: return@launch
        val scenario = gateway.activeScenario()?.takeIf { it.id == w.scenarioId } ?: return@launch
        val v = ChangeSetValidator.validate(scenario, MessageMapper.decodeOps(w.opsJson))
        if (v.accepted.isEmpty()) return@launch _state.update { it.copy(error = "None of these changes fit the current model.") }
        val cs = deps.changeSets.create(
            scenarioId = w.scenarioId, conversationId = w.conversationId, summary = w.label,
            opsJson = Json.encodeToString(ListSerializer(ChangeOp.serializer()), v.accepted.map { it.op }),
            refIdsJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), v.refIds),
            baseScenarioVersion = scenario.version
        )
        deps.whatIfs.update(w.copy(promotedChangeSetId = cs.id))
    }

    companion object {
        const val LOOKS_RIGHT = "Looks right"
        const val NOT_RIGHT_PREFIX = "Something's not right: "
        private const val MAX_UNDO = 5
    }

    private fun decisionsJson(decisions: List<OpDecision>) =
        Json.encodeToString(ListSerializer(OpDecision.serializer()), decisions)

    private fun strip(s: Scenario, pending: Int): LiveModelStripUi {
        val coverage = PayoffCoverage.of(s)
        val players = s.players()
        return LiveModelStripUi(
            stage = s.lifecycleStage.toWorkflowStage(), players = players.size, influencers = s.stakeholders.size - players.size,
            actions = s.actions.size, payoffsFilled = coverage.filled, payoffsExpected = coverage.expected,
            aiSharePct = (ProvenanceStats.of(s).aiPayoffShare * 100).roundToInt(), pendingReviews = pending,
            completeness = s.completenessScore()
        )
    }
}
