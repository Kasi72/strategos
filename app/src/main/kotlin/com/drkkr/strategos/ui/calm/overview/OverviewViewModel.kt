package com.drkkr.strategos.ui.calm.overview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.GuidanceRepository
import com.drkkr.strategos.domain.agent.GuidanceKind
import com.drkkr.strategos.domain.agent.PatternCatalog
import com.drkkr.strategos.domain.agent.ReviewAction
import com.drkkr.strategos.domain.agent.ReviewItem
import com.drkkr.strategos.domain.agent.ProjectBrain
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.ui.calm.projects.ProjectsUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Assembles the Project Overview (dashboard) from the redesign's backend repositories. */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModel(app: Application, private val projectId: String) : AndroidViewModel(app) {

    private val container = (app as StrategosApplication).container
    private val scenarios = container.scenarioRepository
    private val trajectory = container.trajectoryRepository
    private val brainRepo = container.projectBrainRepository
    private val guidanceRepo = container.guidanceRepository
    private val journal = container.journalRepository
    private val db = container.database
    private val conversations = db?.let { ConversationRepository(it) }

    private val points = trajectory?.observePoints(projectId) ?: flowOf(emptyList())
    private val brainFlow = brainRepo?.observe(projectId) ?: flowOf<ProjectBrain?>(null)
    private val postNotes = journal?.observeActivePostNotes(projectId) ?: flowOf(emptyList())

    val state: StateFlow<OverviewUiState> =
        combine(scenarios.scenarios, points, brainFlow, postNotes) { all, pts, brain, notes ->
            Quad(all, pts, brain, notes)
        }.mapLatest { (all, pts, brain, notes) ->
            val s = all.firstOrNull { it.id == projectId } ?: return@mapLatest OverviewUiState(loading = true)
            val threads = conversations?.threadSummaries(projectId).orEmpty()
            val msgCount = conversations?.messageCount(projectId) ?: 0
            val trust = pts.map { it.trustScore.toFloat() }
            val tension = pts.map { it.tensionScore.toFloat() }
            val status = MomentumStatus.of(pts.map { it.trustScore }, pts.map { it.tensionScore })
            val archetype = trajectory?.current(projectId)?.activeArchetype
            val standing = guidanceRepo?.standing(projectId)
            val now = System.currentTimeMillis()

            OverviewUiState(
                loading = false,
                projectName = ProjectsUi.displayName(s.title),
                subtitle = subtitle(threads.size, pts.lastOrNull()?.timestamp ?: s.updatedAt, now),
                linkedJournalCount = journal?.let { countLinked(projectId) } ?: 0,
                status = status,
                chart = ChartUi(
                    trustPast = trust, tensionPast = tension,
                    trustHold = OverviewChart.hold(trust), tensionHold = OverviewChart.hold(tension),
                    trustRevert = OverviewChart.revert(trust), tensionRevert = OverviewChart.revert(tension)
                ),
                momentumBlurb = blurb(status),
                events = trajectory?.events(projectId).orEmpty(),
                pendingCheck = trajectory?.pendingCheck(projectId),
                guidance = standing?.let { g ->
                    g.dos.map { GuidanceRowUi(it.text, true, "From ${it.sourceLabel}", it.refId) } +
                        g.donts.map { GuidanceRowUi(it.text, false, "From ${it.sourceLabel}", it.refId) }
                }.orEmpty(),
                pattern = PatternCatalog.of(archetype, s),
                threads = threads.map { ThreadRowUi(it.id, it.title, it.lastLine, relative(it.lastAt, now), it.isOpenNow) },
                chatLabel = if (msgCount == 0) "Start chat" else "Continue chat",
                people = brain?.people.orEmpty(),
                limits = brain?.limits.orEmpty(),
                inferredCount = brain?.inferredCount ?: 0,
                reviewedCount = brain?.reviewedCount ?: 0,
                inferredTotal = brain?.inferredTotal ?: 0,
                modelStrip = brain?.counts?.let { "${it.people} people · ${it.options} options · ${it.outcomesFilled}/${it.outcomesExpected} outcomes" } ?: "",
                influence = brainRepo?.influence(projectId),
                reviewItems = brainRepo?.reviewItems(projectId).orEmpty(),
                journalTeasers = notes.filter { !it.applied }.map {
                    JournalTeaserUi(it.id, it.insightHeadline, "Suggested takeaway: ${it.doThis}".trimEnd(), relative(it.createdAt, now))
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState())

    init { viewModelScope.launch { scenarios.load() } }

    fun rename(name: String) = viewModelScope.launch {
        scenarios.find(projectId)?.let { s -> scenarios.save(s.copy(title = name.trim().ifEmpty { s.title })) }
    }

    fun saveQuickCheck(did: String, landed: String) = viewModelScope.launch {
        trajectory?.submitQuickCheck(projectId, null, "", did, landed, "GROUNDED")
    }

    fun review(postNoteId: String, apply: Boolean) = viewModelScope.launch {
        if (apply) journal?.applyPostNote(postNoteId) else journal?.dismissPostNote(postNoteId)
    }

    /** "Add something the app should know": a free fact, the user's own. */
    fun addFact(text: String) = viewModelScope.launch { if (text.isNotBlank()) brainRepo?.addFact(projectId, text) }

    fun review(item: ReviewItem, action: ReviewAction) = viewModelScope.launch { brainRepo?.review(projectId, item, action) }

    /** "Accept all as my estimates": keep every inferred value as the user's own. */
    fun acceptAll() = viewModelScope.launch {
        brainRepo?.reviewItems(projectId)?.forEach { brainRepo.review(projectId, it, ReviewAction.KEEP) }
    }

    private suspend fun countLinked(id: String): Int = journal?.linkSuggestions(id)?.size ?: 0

    private fun subtitle(topics: Int, lastAt: Long, now: Long): String {
        val t = if (topics == 1) "1 topic" else "$topics topics"
        return "$t · active ${ProjectsUi.activityLabel(lastAt, now).lowercase()}"
    }

    private fun blurb(status: MomentumStatus) = when (status) {
        MomentumStatus.IMPROVING -> "Trust has been climbing. Keep doing what's working."
        MomentumStatus.TENSE -> "Tension has been rising. The next few moves matter."
        MomentumStatus.STEADY -> "Things are holding steady for now."
    }

    private fun relative(at: Long, now: Long): String {
        val mins = (now - at) / 60000
        return when {
            at == 0L -> ""
            mins < 60 -> "${mins.coerceAtLeast(1)}m"
            mins < 1440 -> "${mins / 60}h"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at))
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
