package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.dao.WhatIfDao
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.SettingsRepository
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
    val dispatcher: ToolDispatcher = ToolDispatcher.default()
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

    private var scenarioId: String? = null
    private var observeJob: Job? = null
    private var turnJob: Job? = null

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

    private fun startObserving(cid: String, sid: String) {
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

    suspend fun refreshAiStatus() {
        val s = deps.settings
        val hasKey = deps.hasKey()
        _state.update {
            it.copy(
                aiReady = s.aiReady(), hasKey = hasKey,
                needsConsent = hasKey && s.consentVersion() != AgentConfig.CONSENT_VERSION
            )
        }
    }

    /** No-op if blank, a turn is running, or AI isn't ready (I7: no network call when AI is off). */
    fun send(text: String): Job? {
        if (text.isBlank() || turnJob?.isActive == true) return null
        return startTurn(TurnTrigger.User(text.trim()))
    }

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
                val result = orchestrator.runTurn(cid, sid, trigger, { turnId -> toolContext(cid, sid, turnId) }) { label ->
                    _state.update { it.copy(activity = label) }
                }
                if (result is TurnResult.Failed) _state.update { it.copy(error = result.userMessage, canRetry = result.retryable) }
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
        userTexts = deps.conversations.userTexts(cid)
    )

    fun applyChangeSet(id: String, decisions: List<OpDecision>): Job = scope.launch {
        val cs = deps.changeSets.get(id)?.takeIf { it.status == ChangeSetStatus.PENDING.name } ?: return@launch
        val scenario = gateway.activeScenario()?.takeIf { it.id == cs.scenarioId } ?: return@launch
        val ops = MessageMapper.decodeOps(cs.opsJson)
        val refIds = runCatching { Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cs.refIdsJson) }.getOrDefault(emptyMap())
        val userTexts = deps.conversations.userTexts(cs.conversationId)

        val result = withContext(Dispatchers.Default) { ChangeSetApplier.apply(scenario, ops, decisions, refIds, userTexts, cs.summary) }
        val saved = when (val commit = gateway.commitAgentChange(result.scenario)) {
            is SaveResult.Success -> commit.saved
            is SaveResult.Conflict -> return@launch _state.update { it.copy(error = "The model changed elsewhere. Reload it and review the changes again.") }
            SaveResult.StorageUnavailable -> return@launch _state.update { it.copy(error = "Storage unavailable. The changes were not applied.") }
        }

        val status = when {
            result.applied == ops.size -> ChangeSetStatus.APPLIED
            result.applied > 0 -> ChangeSetStatus.PARTIAL
            else -> ChangeSetStatus.REJECTED
        }
        val skipped = result.skipped.joinToString("; ") { (i, reason) ->
            val text = ops.getOrNull(i)?.let { ChangeOpDescriber.describe(it, scenario, ops) } ?: "change ${i + 1}"
            "$text ($reason)"
        }.ifEmpty { "none" }
        deps.changeSets.markDecided(
            id, status, decisionsJson(decisions),
            Json.encodeToString(ListSerializer(String.serializer()), result.skipped.map { "${it.first}:${it.second}" }), saved.version
        )
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
