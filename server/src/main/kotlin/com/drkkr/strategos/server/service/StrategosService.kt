package com.drkkr.strategos.server.service

import com.drkkr.strategos.domain.agent.BayesianUpdater
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.IntentDetail
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.server.llm.LlmRouter
import com.drkkr.strategos.server.memory.Episode
import com.drkkr.strategos.server.memory.FactInput
import com.drkkr.strategos.server.memory.MemoryContext
import com.drkkr.strategos.server.memory.MemoryFact
import com.drkkr.strategos.server.memory.MemoryGroups
import com.drkkr.strategos.server.memory.MemoryService
import com.drkkr.strategos.server.persistence.*
import com.drkkr.strategos.server.pipeline.DeterministicPayoffCalculator
import com.drkkr.strategos.server.pipeline.EngineStage
import com.drkkr.strategos.server.pipeline.JournalScreener
import com.drkkr.strategos.server.pipeline.ScoredOption
import com.drkkr.strategos.server.pipeline.TurnPipeline
import com.drkkr.strategos.server.pipeline.TurnResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Priors(val probSoftensOnRepair: Double, val probRespectsBoundary: Double, val probEscalatesOnReactive: Double)

@Serializable
data class ProjectState(
    val project: ProjectSummary,
    /** The engine's view of the scenario (the same digest Call 1 reads; walk-away and private figures stay out). */
    val scenario: JsonObject?,
    val knownFacts: List<String>,
    val priors: Priors?,
    val options: List<ScoredOption>,
    val postNotes: List<PostNote>,
    val threads: List<TopicThread>,
)

@Serializable
data class ProjectSummary(
    val id: String, val title: String, val domainType: DomainType, val trust: Double, val tension: Double,
    val createdAt: Long, val updatedAt: Long, val archived: Boolean,
)

@Serializable
data class ThreadContext(val thread: TopicThread, val recentTurns: List<TurnRecord>, val memory: MemoryContext)

@Serializable
data class QuickCheckResult(val priorsBefore: Priors?, val priorsAfter: Priors?, val recorded: Boolean)

@Serializable
data class JournalSaved(val id: String, val message: String)

/** One conversation in the sidebar: a project with its thread, as ChatGPT lists chats. */
@Serializable
data class ChatSummary(val projectId: String, val threadId: String?, val title: String, val domainType: DomainType, val updatedAt: Long, val turns: Int)

@Serializable
data class Health(val ok: Boolean, val engine: Boolean, val database: Boolean, val memory: String, val memoryHealthy: Boolean, val llmProvider: String, val llmModel: String)

/**
 * The one service layer behind both the REST API and the MCP server (SDD §1, single source of truth). Every read and write of
 * projects, threads, memory and the engine goes through here, so the two interfaces can never disagree.
 */
class StrategosService(
    private val db: Database,
    val projects: ProjectRepository,
    val threads: ThreadRepository,
    val turns: TurnRepository,
    val journal: JournalRepository,
    val memory: MemoryService,
    val llm: LlmRouter,
    private val pipeline: TurnPipeline,
    private val screener: JournalScreener,
    private val background: CoroutineScope,
) {
    private val screening = java.util.concurrent.ConcurrentHashMap<String, Job>()

    suspend fun health(): Health {
        val engine = runCatching { com.drkkr.strategos.domain.analysis.AnalysisPipeline(); true }.getOrDefault(false)
        return Health(engine && db.ping(), engine, db.ping(), memory.backend, runCatching { memory.healthy() }.getOrDefault(false),
            llm.name, llm.model)
    }

    // ── projects ──
    fun listProjects(includeArchived: Boolean = false) = projects.list(includeArchived).map(::summary)
    fun createProject(title: String, domainType: DomainType = DomainType.GENERAL, description: String = ""): ProjectSummary =
        summary(projects.create(title.trim().ifEmpty { "Untitled" }, domainType, description))
    fun patchProject(id: String, title: String?, domainType: DomainType?, archived: Boolean?) = projects.patch(id, title, domainType, archived)?.let(::summary)
    fun deleteProject(id: String) = projects.delete(id)

    fun projectState(id: String): ProjectState? {
        val p = projects.get(id) ?: return null
        val s = p.scenario
        val self = IntakeChecklist.self(s)
        val other = s.players().firstOrNull { it.id != self?.id }
        return ProjectState(
            project = summary(p),
            scenario = if (s.stakeholders.isEmpty()) null else LiveDigest.build(s),
            knownFacts = s.beliefs.filter { it.status == FactStatus.CONFIRMED || it.status == FactStatus.USER_ESTIMATE }.map { it.label },
            priors = other?.let { Priors(it.probSoftensOnRepair ?: BayesianUpdater.DEFAULT_SOFTENS, it.probRespectsBoundary ?: BayesianUpdater.DEFAULT_BOUNDARY,
                it.probEscalatesOnReactive ?: BayesianUpdater.DEFAULT_ESCALATES) },
            options = DeterministicPayoffCalculator.netPayoffs(s),
            postNotes = journal.postNotes(id),
            threads = threads.list(id),
        )
    }

    // ── chats (the web UI's single-thread view) ──
    fun listChats(): List<ChatSummary> = projects.list().map { p ->
        val t = threads.list(p.id).firstOrNull()
        ChatSummary(p.id, t?.id, p.title, p.domainType, maxOf(p.updatedAt, t?.lastActiveAt ?: 0L), t?.turnCount ?: 0)
    }.sortedByDescending { it.updatedAt }

    /** A new chat from its first message: a project titled from the message, one thread, and the first turn. */
    suspend fun startChat(message: String, domainType: DomainType = DomainType.GENERAL): TurnResponse {
        require(message.isNotBlank()) { "message is empty" }
        val p = projects.create(chatTitle(message), domainType)
        val t = threads.create(p.id, "Main")
        return pipeline.run(t.id, message)
    }

    // ── threads ──
    fun listThreads(projectId: String) = threads.list(projectId)
    fun createThread(projectId: String, title: String): TopicThread? = projects.get(projectId)?.let { threads.create(it.id, title.trim().ifEmpty { "Main" }) }
    fun threadTurns(threadId: String) = turns.forThread(threadId)

    suspend fun openThreadContext(threadId: String, query: String? = null): ThreadContext? {
        val t = threads.get(threadId) ?: return null
        val p = projects.get(t.projectId) ?: return null
        val recent = turns.last(threadId, 4)
        val q = query ?: recent.lastOrNull()?.userMessage ?: t.title
        val mem = memory.retrieveContext(listOf(MemoryGroups.project(p.id)), q, p.scenario.stakeholders.map { it.name })
        return ThreadContext(t, recent, mem)
    }

    // ── turns ──
    suspend fun sendMessage(threadId: String, message: String): TurnResponse = pipeline.run(threadId, message)
    suspend fun draftWords(threadId: String): TurnResponse = pipeline.draftWords(threadId)
    suspend fun awaitBackground(threadId: String) = pipeline.awaitBackground(threadId)

    /** MCP: a real-world event or message, analysed and remembered like any turn (on the given thread, or the project's first). */
    suspend fun logInteractionAndAnalyze(projectId: String, event: String, threadId: String? = null): TurnResponse {
        val t = threadId?.let { threads.get(it) } ?: threads.list(projectId).lastOrNull() ?: createThread(projectId, "Logged interactions")
            ?: throw NoSuchElementException("project $projectId")
        return pipeline.run(t.id, event)
    }

    /** A what-if on a copy: all three stages, nothing saved. */
    suspend fun runWhatIf(projectId: String, change: String): TurnResponse {
        val t = threads.list(projectId).lastOrNull() ?: createThread(projectId, "Main") ?: throw NoSuchElementException("project $projectId")
        val text = change.trim().let { if (it.startsWith("what if", ignoreCase = true)) it else "What if $it" }
        return pipeline.preview(t.id, text)
    }

    /** Head-to-head, deterministic: the engine's compare block for two of the person's moves. No model call, nothing saved. */
    suspend fun compareMoves(projectId: String, moveA: String, moveB: String): JsonObject? =
        engineOnly(projectId, Intent.COMPARE, IntentDetail(moveA = moveA, moveB = moveB), "Compare $moveA and $moveB")

    /** A short lookahead from one move (their likely replies and your best answer to each). No model call, nothing saved. */
    suspend fun simulateMultiStep(projectId: String, move: String): JsonObject? =
        engineOnly(projectId, Intent.PLAN_AHEAD, IntentDetail(move = move), "If I $move, what then?")

    private suspend fun engineOnly(projectId: String, intent: Intent, detail: IntentDetail, text: String): JsonObject? {
        val p = projects.get(projectId) ?: return null
        if (p.scenario.actions.isEmpty()) return null
        val t = threads.list(projectId).lastOrNull()
        return EngineStage.run(p.scenario, p.trust, p.tension, text, text, t?.lastReportJson, intent, detail).directive.toJson()
    }

    /**
     * Structured outcome feedback (SDD §4.1). The priors move deterministically (BayesianUpdater), and the outcome is written to
     * memory with its provenance. [triedMove] may be one of their saved moves (its posture is used) or a posture word.
     */
    suspend fun quickCheck(threadId: String, triedMove: String, counterpartReaction: String, userState: String? = null): QuickCheckResult {
        val t = threads.get(threadId) ?: throw NoSuchElementException("thread $threadId")
        val p = projects.get(t.projectId) ?: throw NoSuchElementException("project ${t.projectId}")
        val s = p.scenario
        val posture = Postures.clean(triedMove)
            ?: s.actions.firstOrNull { it.label.equals(triedMove.trim(), ignoreCase = true) }?.let(Postures::of)
        val reaction = normaliseReaction(counterpartReaction)
        val before = priors(s)
        val after = BayesianUpdater.applyToScenario(s, posture, reaction)
        if (after !== s) projects.saveScenario(p.id, after)
        val other = s.players().firstOrNull { it.id != IntakeChecklist.self(s)?.id }?.name ?: "They"
        memory.ingest(Episode(MemoryGroups.project(p.id), "thread_${t.id}_outcome_${System.currentTimeMillis()}",
            "Outcome check: tried \"$triedMove\"${posture?.let { " ($it)" }.orEmpty()}; $other $counterpartReaction." + (userState?.let { " I feel: $it." } ?: ""),
            "Strategos quick check", System.currentTimeMillis(),
            listOf(FactInput(other, "last reaction", counterpartReaction)) + listOfNotNull(posture?.let { FactInput("You", "last move posture", it) })))
        return QuickCheckResult(before, priors(after), recorded = true)
    }

    // ── memory ──
    suspend fun searchLongTermMemory(query: String, projectId: String? = null, limit: Int = 10): List<MemoryFact> {
        val groups = if (projectId != null) listOf(MemoryGroups.project(projectId))
            else projects.list(includeArchived = true).map { MemoryGroups.project(it.id) } + MemoryGroups.journal(userId)
        return memory.searchFacts(groups, query, limit)
    }

    // ── journal ──
    /** "Recorded privately." goes back at once; the screener runs after, and may add a Do / Don't to the projects it touches. */
    fun saveJournal(content: String, projectId: String? = null): JournalSaved {
        val e = journal.save(projectId, content)
        screening[e.id] = background.launch { runCatching { screener.screen(e.id) } }
        return JournalSaved(e.id, RECORDED)
    }

    suspend fun awaitScreening(entryId: String) { screening[entryId]?.join() }
    fun listJournal() = journal.list()

    private val userId get() = userIdValue
    var userIdValue: String = "local"

    private fun priors(s: com.drkkr.strategos.domain.model.Scenario): Priors? {
        val other = s.players().firstOrNull { it.id != IntakeChecklist.self(s)?.id } ?: return null
        return Priors(other.probSoftensOnRepair ?: BayesianUpdater.DEFAULT_SOFTENS, other.probRespectsBoundary ?: BayesianUpdater.DEFAULT_BOUNDARY,
            other.probEscalatesOnReactive ?: BayesianUpdater.DEFAULT_ESCALATES)
    }

    private fun summary(p: Project) = ProjectSummary(p.id, p.title, p.domainType, p.trust, p.tension, p.createdAt, p.updatedAt, p.archived)

    companion object {
        /** The first sentence, cut on a word near 48 characters: the chat's name until the person renames it. */
        fun chatTitle(message: String): String {
            val first = message.trim().lineSequence().first { it.isNotBlank() }.split(Regex("(?<=[.!?])\\s")).first().trim()
            if (first.length <= 48) return first.trimEnd('.', '!', ' ')
            return first.take(48).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ') + "…"
        }

        const val RECORDED = "Recorded privately."

        /** The words BayesianUpdater knows: "softened", "withdrew_or_escalated", "none". */
        fun normaliseReaction(raw: String): String {
            val r = raw.lowercase()
            return when {
                r == "softened" || r == "withdrew_or_escalated" || r == "none" -> r
                Regex("soft|open|warm|agree|accept|thank|apolog|relax|calm|yes").containsMatchIn(r) -> "softened"
                Regex("withdr|escalat|angry|shout|ignor|silent|quiet|refus|defens|cold|pushed back|threat").containsMatchIn(r) -> "withdrew_or_escalated"
                else -> "none"
            }
        }
    }
}
