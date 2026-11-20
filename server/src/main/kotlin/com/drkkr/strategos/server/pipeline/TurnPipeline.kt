package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.BayesianUpdater
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.PlanAheadGuard
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.server.llm.ChatMessage
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.memory.Episode
import com.drkkr.strategos.server.memory.FactInput
import com.drkkr.strategos.server.memory.MemoryContext
import com.drkkr.strategos.server.memory.MemoryGroups
import com.drkkr.strategos.server.memory.MemoryService
import com.drkkr.strategos.server.persistence.JournalRepository
import com.drkkr.strategos.server.persistence.Project
import com.drkkr.strategos.server.persistence.ProjectRepository
import com.drkkr.strategos.server.persistence.ThreadRepository
import com.drkkr.strategos.server.persistence.TopicThread
import com.drkkr.strategos.server.persistence.TurnRecord
import com.drkkr.strategos.server.persistence.TurnRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Serializable
data class DirectiveSummary(
    val ready: Boolean,
    val recommendedMove: String? = null,
    val moveToImplement: String? = null,
    val safeFallbackMove: String? = null,
    val blockedOrDominatedMoves: List<String> = emptyList(),
    val stressVerdict: String? = null,
    val pattern: String? = null,
    val confidence: String? = null,
)

@Serializable
data class TurnResponse(
    val turnIndex: Int,
    val reply: String,
    val intent: String,
    val directive: DirectiveSummary? = null,
    /** The engine's full directive (the X-Ray drawer reads it). Walk-away figures are masked by the engine itself. */
    val directiveJson: JsonObject? = null,
    val trustScore: Double,
    val tensionScore: Double,
    val memoryBackend: String,
    val coachUsed: Boolean = false,
    val savedChanges: Int = 0,
)

class TurnFailed(message: String, val retryable: Boolean) : Exception(message)

/**
 * The strict three-stage turn (SDD §1). Call 1 extracts and routes and never advises; Stage 2 is Kotlin only; Call 2 words the
 * engine's pick with memory. The LLM never edits the scenario: Call 1's sketch becomes ChangeOps that go through the same
 * validator and applier as the app, and agent output is never CONFIRMED (ChangeSetApplier).
 */
class TurnPipeline(
    private val llm: LlmProvider,
    private val memory: MemoryService,
    private val projects: ProjectRepository,
    private val threads: ThreadRepository,
    private val turns: TurnRepository,
    private val journal: JournalRepository,
    private val summaries: RollingSummaryService,
    /** Where memory writes and summaries run after the reply has gone back (never blocking a turn). */
    private val background: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val coach = CoachStage(llm)
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** The last background job per thread, so tests (and shutdown) can wait for memory writes to land. */
    private val pending = ConcurrentHashMap<String, Job>()

    suspend fun awaitBackground(threadId: String) { pending[threadId]?.join() }

    /** One turn at a time per project: two threads of the same project share one scenario. */
    suspend fun run(threadId: String, message: String): TurnResponse {
        val thread = threads.get(threadId) ?: throw NoSuchElementException("thread $threadId")
        return locks.getOrPut(thread.projectId) { Mutex() }.withLock { turn(thread, message.trim(), preview = false) }
    }

    /** The full three stages on a copy: nothing is saved (scenario, trajectory, turn or memory). For what-if tools (I-D3). */
    suspend fun preview(threadId: String, message: String): TurnResponse {
        val thread = threads.get(threadId) ?: throw NoSuchElementException("thread $threadId")
        return locks.getOrPut(thread.projectId) { Mutex() }.withLock { turn(thread, message.trim(), preview = true) }
    }

    private suspend fun turn(thread: TopicThread, text: String, preview: Boolean): TurnResponse {
        require(text.isNotEmpty()) { "message is empty" }
        val project = projects.get(thread.projectId) ?: throw NoSuchElementException("project ${thread.projectId}")
        val loaded = project.scenario
        val history = turns.forThread(thread.id)
        val turnIndex = (history.lastOrNull()?.turnIndex ?: -1) + 1
        val groups = listOf(MemoryGroups.project(project.id))
        val people = loaded.stakeholders.map { it.name }.filter { it.isNotBlank() }
        val mem = runCatching { memory.retrieveContext(groups, text, people) }.getOrDefault(MemoryContext(backend = memory.backend))
        val earlierSaid = everythingSaid(history.map { it.userMessage })
        val ctx = CoachContext(mem.render(), thread.rollingSummary, earlierSaid)

        // ── Stage 1: extraction and intent routing (no advice) ──
        val sketch = extract(project, thread, history, text, mem)?.let(PlanAheadGuard::realIntent)
            ?: throw TurnFailed("I couldn't read that just now. Try again in a moment.", retryable = true)

        val priorReply = history.lastOrNull()?.assistantReply
        val recent = (history.takeLast(5).map { it.userMessage } + text).joinToString("\n")
        if (sketch.messageKind == "followup_question" && sketch.intentKind == Intent.STANDARD && !priorReply.isNullOrBlank()) {
            val deal = DealFrame.build(loaded.negotiationContext, loaded.humanContext, text, recent = recent)
            coach.answerFollowUp(text, priorReply, thread.lastReportJson, ctx, deal)?.let { answer ->
                return finish(project, thread, turnIndex, text, sketch, null, answer, project.trust, project.tension, mem, coachUsed = true, saved = 0, preview = preview)
            }
        }

        val hypothetical = sketch.intentKind in HYPOTHETICAL && loaded.actions.isNotEmpty()
        val holdCourse = !hypothetical && loaded.actions.isNotEmpty() && sketch.messageKind == "thinking_aloud" && sketch.intentKind == Intent.STANDARD
        // Trajectory: Call 1's deltas, EMA-smoothed and capped per turn exactly as the app does. A hypothetical moves nothing.
        val (trust, tension) = if (hypothetical || holdCourse || (sketch.trustDelta == 0.0 && sketch.tensionDelta == 0.0)) project.trust to project.tension
            else smooth(project.trust, sketch.trustDelta, sketch.backstoryOnly) to smooth(project.tension, sketch.tensionDelta, sketch.backstoryOnly)
        // Bayesian priors update before the ops are built, so the converter and the engine see the same numbers (app Phase F.6).
        val scenario = if (holdCourse) loaded else BayesianUpdater.applyToScenario(loaded, sketch.userLastMovePosture, sketch.counterpartObservedReaction)

        val converted = SketchConverter.toOps(sketch, scenario)
        val hypotheticalOps = if (!hypothetical) emptyList()
            else sketch.whatIf?.let { w -> runCatching { SketchConverter.toOps(w, scenario).ops }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: converted.ops
        val userTexts = history.map { it.userMessage } + text
        var current = scenario
        var turnOps = emptyList<ChangeOp>()
        val understood = mutableListOf<String>()
        if (!hypothetical && !holdCourse) {
            if (converted.ops.isEmpty() && (converted.ask != null || scenario.actions.isEmpty())) {
                val ask = converted.ask ?: COULD_NOT_READ
                if (scenario !== loaded && !preview) projects.saveScenario(project.id, scenario)
                return finish(project, thread, turnIndex, text, sketch, null, ask, trust, tension, mem, saved = 0, preview = preview)
            }
            applyOps(scenario, converted.ops, userTexts)?.let { (after, accepted) ->
                understood += PlainResult.understood(accepted, scenario)
                turnOps = accepted
                current = after
            }
        }
        if (current !== loaded && !preview) projects.saveScenario(project.id, current)
        if ((trust != project.trust || tension != project.tension) && !preview) projects.saveTrajectory(project.id, trust, tension)

        if (converted.support) {
            return finish(project, thread, turnIndex, text, sketch, null, coach.safetyReply(text), trust, tension, mem, saved = turnOps.size, preview = preview)
        }
        if (current.actions.isEmpty()) {
            return finish(project, thread, turnIndex, text, sketch, null, converted.ask ?: COULD_NOT_READ, trust, tension, mem, saved = turnOps.size, preview = preview)
        }

        // ── Stage 2: the deterministic engine ──
        val outcome = EngineStage.run(
            saved = current, trust = trust, tension = tension, text = text, recentUserText = recent,
            previousReportJson = thread.lastReportJson, intent = sketch.intentKind, intentDetail = sketch.intentDetail,
            whatIfOps = hypotheticalOps, turnOps = turnOps, turnBefore = scenario, sketch = sketch,
            holdCourse = holdCourse, earlierReplies = history.map { it.assistantReply },
        )
        val digest = outcome.savedResult.report?.let { ReportDigest.build(outcome.savedResult, current).toString() }

        // ── Stage 3: the memory-aware companion ──
        val result = coach.reply(current, outcome, text, understood, ctx)
        val reply = if (converted.ask != null && !result.usedCoach) "${result.text}\n\n${converted.ask}" else result.text
        return finish(project, thread, turnIndex, text, sketch, outcome, reply, trust, tension, mem,
            coachUsed = result.usedCoach, saved = turnOps.size, reportJson = digest, preview = preview)
    }

    private suspend fun extract(project: Project, thread: TopicThread, history: List<TurnRecord>, text: String, mem: MemoryContext): ModelSketch? {
        val s = project.scenario
        val context = buildJsonObject {
            put("now", java.time.LocalDate.now().toString())
            put("domain", project.domainType.name)
            put("scenario", if (s.stakeholders.isEmpty()) JsonNull else LiveDigest.build(s))
            putJsonObject("trajectory") { put("trust", project.trust); put("tension", project.tension) }
            putJsonArray("active_journal_post_notes") {
                journal.postNotes(project.id).take(3).forEach { n ->
                    addJsonObject { put("habit_or_trigger", n.habitOrTrigger); put("do_this", n.doThis); put("dont_do_this", n.dontDoThis) }
                }
            }
        }
        val messages = buildList {
            add(ChatMessage("system", ArgonPrompts.EXTRACTION_PROMPT))
            add(ChatMessage("system", "$CONTEXT_HEADER\n```json\n$context\n```"))
            if (mem.render().isNotBlank()) add(ChatMessage("system", mem.render()))
            if (thread.rollingSummary.isNotBlank()) add(ChatMessage("system", "Summary of earlier conversation:\n${thread.rollingSummary}"))
            history.takeLast(RECENT_TURNS).forEach { t ->
                add(ChatMessage("user", t.userMessage))
                add(ChatMessage("assistant", t.assistantReply.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(300)))
            }
            add(ChatMessage("user", text))
        }
        repeat(2) {
            val r = llm.chat(ChatRequest(messages, jsonMode = true, maxTokens = 3_000, temperature = 0.1, purpose = "EXTRACT"))
            when (r) {
                is ChatResult.Ok -> SketchParser.tryParse(r.text).getOrNull()?.let { return it }
                is ChatResult.Failed -> if (!r.retryable) throw TurnFailed(r.message, retryable = false)
            }
        }
        return null
    }

    /** The app's change-set path: normalise, validate, then apply with every op accepted. Null when nothing could be applied. */
    private fun applyOps(s: Scenario, raw: List<ChangeOp>, userTexts: List<String>): Pair<Scenario, List<ChangeOp>>? {
        if (raw.isEmpty()) return null
        val validated = ChangeSetValidator.validate(s, OpNormalizer.normalize(raw, s))
        if (validated.accepted.isEmpty()) return null
        val ops = validated.accepted.map { it.op }
        val applied = ChangeSetApplier.apply(s, ops, ops.indices.map { OpDecision(it, Decision.ACCEPT) }, validated.refIds, userTexts, "From your message")
        return if (applied.applied == 0) null else applied.scenario to ops
    }

    private fun finish(
        project: Project, thread: TopicThread, turnIndex: Int, text: String, sketch: ModelSketch, outcome: EngineOutcome?,
        reply: String, trust: Double, tension: Double, mem: MemoryContext, coachUsed: Boolean = false, saved: Int,
        reportJson: String? = null, preview: Boolean = false,
    ): TurnResponse {
        val now = clock()
        val d = outcome?.directive
        val directiveJson = d?.toJson()
        if (!preview) persist(project, thread, turnIndex, text, sketch, d?.moveToImplement, directiveJson, reply, trust, tension, now, reportJson)
        return TurnResponse(
            turnIndex = turnIndex, reply = reply, intent = sketch.intentKind.word,
            directive = d?.let {
                DirectiveSummary(it.ready, it.recommendedMove, it.moveToImplement, it.safeFallbackMove,
                    it.blockedOrDominatedMoves.map { b -> b.move }, it.stressVerdict, it.gameTheoreticPattern, it.confidence?.tier?.name)
            },
            directiveJson = directiveJson, trustScore = trust, tensionScore = tension, memoryBackend = mem.backend,
            coachUsed = coachUsed, savedChanges = if (preview) 0 else saved,
        )
    }

    private fun persist(
        project: Project, thread: TopicThread, turnIndex: Int, text: String, sketch: ModelSketch, advised: String?,
        directiveJson: JsonObject?, reply: String, trust: Double, tension: Double, now: Long, reportJson: String?,
    ) {
        turns.append(TurnRecord(UUID.randomUUID().toString(), thread.id, project.id, turnIndex, text,
            runCatching { SketchJson.encodeToString(ModelSketch.serializer(), sketch) }.getOrNull(), directiveJson?.toString(), reply, trust, tension, now))
        threads.touch(thread.id, turnIndex + 1, reportJson)
        val episode = Episode(
            groupId = MemoryGroups.project(project.id),
            name = "thread_${thread.id}_turn_$turnIndex",
            body = buildString {
                append("User: ").append(text.take(4_000))
                advised?.let { append("\nStrategos advised: ").append(it) }
            },
            sourceDescription = "Strategos thread \"${thread.title}\"",
            referenceTime = now,
            facts = factsFrom(sketch, project.scenario, advised),
        )
        pending[thread.id] = background.launch {
            runCatching { memory.ingest(episode) }
            if ((turnIndex + 1) % RollingSummaryService.EVERY_TURNS == 0) runCatching { summaries.update(thread.id) }
        }
    }

    companion object {
        const val RECENT_TURNS = 4
        const val SAID_BUDGET = 10_000
        const val CONTEXT_HEADER = "STRATEGOS APP CONTEXT (generated by the app, authoritative, not written by the user)"
        const val COULD_NOT_READ = "I couldn't turn that into options. Try telling me who is involved and what you could do."
        val HYPOTHETICAL = setOf(Intent.WHAT_IF, Intent.COMPARE, Intent.THEIR_REACTION, Intent.PLAN_AHEAD)
        private val SketchJson = Json { encodeDefaults = false; explicitNulls = false }

        /** The app's trajectory smoothing (TrajectoryRepository.recordTurn): EMA 0.25, at most 5 points a turn (2 for backstory). */
        fun smooth(prev: Double, delta: Double, backstoryOnly: Boolean): Double {
            val cap = if (backstoryOnly) 2.0 else 5.0
            val raw = (prev + delta.coerceIn(-20.0, 20.0)).coerceIn(0.0, 100.0)
            return (prev * 0.75 + raw * 0.25).roundToInt().toDouble().coerceIn(prev - cap, prev + cap).coerceIn(0.0, 100.0)
        }

        /** Everything said earlier, in full, newest kept first when over [SAID_BUDGET]; returned oldest first. */
        fun everythingSaid(texts: List<String>, budget: Int = SAID_BUDGET): List<String> {
            val out = ArrayDeque<String>(); var used = 0
            for (t in texts.asReversed().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (used + t.length > budget) break
                out.addFirst(t); used += t.length
            }
            return out.toList()
        }

        /** The structured facts this turn adds to the temporal ledger: what each person wants and fears, how they reacted, what was advised. */
        fun factsFrom(sketch: ModelSketch, before: Scenario, advised: String?): List<FactInput> = buildList {
            val self = IntakeChecklist.self(before)?.name ?: sketch.people.firstOrNull()?.name
            sketch.people.filter { it.name.isNotBlank() && !it.name.equals(self, true) && !it.name.equals("You", true) }.forEach { p ->
                p.wants?.takeIf { it.isNotBlank() }?.let { add(FactInput(p.name, "wants", it)) }
                p.fears?.takeIf { it.isNotBlank() }?.let { add(FactInput(p.name, "fears", it)) }
                p.goals?.takeIf { it.isNotBlank() }?.let { add(FactInput(p.name, "goals", it)) }
            }
            val other = before.players().firstOrNull { it.name != self }?.name ?: sketch.people.firstOrNull { !it.name.equals(self, true) }?.name
            sketch.counterpartObservedReaction?.takeIf { it.isNotBlank() && other != null }?.let { add(FactInput(other!!, "last reaction", it)) }
            sketch.userLastMovePosture?.takeIf { it.isNotBlank() }?.let { add(FactInput("You", "last move posture", it)) }
            advised?.takeIf { it.isNotBlank() }?.let { add(FactInput("Strategos", "advised", it)) }
        }
    }
}
