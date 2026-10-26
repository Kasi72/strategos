package com.drkkr.strategos.ui.calm.xray

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.domain.agent.NotEnoughPrompt
import com.drkkr.strategos.domain.agent.Pattern
import com.drkkr.strategos.domain.agent.PatternCatalog
import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.agent.XRayGrid
import com.drkkr.strategos.domain.agent.XRayGridBuilder
import com.drkkr.strategos.domain.agent.XRayNotEnough
import com.drkkr.strategos.domain.agent.XRayPriors
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.InferredStressTest
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class XRayUiState(
    val loading: Boolean = true,
    val grid: XRayGrid? = null,
    val notEnough: NotEnoughPrompt? = null,
    val odds: Float = 0.5f,
    val coachOdds: Float = 0.5f,
    val coachBestLabel: String = "",
    val oddsLabel: String = "",
    val pattern: Pattern? = null,
    val modelStrip: String = "",
    /** The engine's own verdict at the coach's odds, so the sheet never disagrees with the chat. */
    val engineMove: String? = null,
    val engineRobust: Boolean? = null
) {
    val changed: Boolean get() = kotlin.math.abs(odds - coachOdds) > 0.004f
    val flipped: Boolean get() = grid != null && changed && grid.best.label != coachBestLabel
}

/**
 * X-ray sheet state (board 07). The slider is private to this view model and is NOT persisted until
 * "Keep as my estimate"; every drag just recomputes the pure grid, so it can run per frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XRayViewModel(app: Application, private val projectId: String) : AndroidViewModel(app) {
    private val container = (app as StrategosApplication).container
    private val scenarios = container.scenarioRepository
    private val xray = container.xRayRepository
    private val trajectory = container.trajectoryRepository
    private val dragged = MutableStateFlow<Float?>(null)

    /** Runs the existing engine once per saved scenario version (never per slider frame). */
    private val engine = scenarios.scenarios
        .map { l -> l.firstOrNull { it.id == projectId } }
        .distinctUntilChangedBy { it?.version }
        .mapLatest { s -> s?.let { withContext(Dispatchers.Default) { directiveFor(it) } } }

    private suspend fun directiveFor(s: Scenario): EngineDirective? = runCatching {
        val snap = trajectory?.current(projectId)
        val view = EngineDirectiveBuilder.analysisView(s, snap?.trustScore, snap?.tensionScore)
        val pipeline = AnalysisPipeline()
        val result = pipeline.run(view)
        val stress = runCatching { InferredStressTest.run(view, pipeline = pipeline) }.getOrNull()
        EngineDirectiveBuilder.build(view, result, stress, null)
    }.getOrNull()?.takeIf { it.ready }

    val state: StateFlow<XRayUiState> = combine(scenarios.scenarios, dragged, engine) { all, d, directive ->
        val s = all.firstOrNull { it.id == projectId } ?: return@combine XRayUiState(loading = true)
        val prior = XRayPriors.of(s).firstOrNull { it.id == PriorId.TAKES_LIMIT_WELL }
        val coach = (prior?.value ?: XRayPriors.COACH_DEFAULT).toFloat()
        val odds = d ?: coach
        val unchanged = kotlin.math.abs(odds - coach) <= 0.004f
        val pinned = directive?.moveToImplement
        val grid = XRayGridBuilder.build(s, odds.toDouble(), pinned.takeIf { unchanged })
        val coachBest = pinned ?: XRayGridBuilder.build(s, coach.toDouble())?.best?.label.orEmpty()
        val archetype = trajectory?.current(projectId)?.activeArchetype
        val counts = container.projectBrainRepository?.brain(projectId)?.let {
            "${it.counts.people} people · ${it.counts.options} options · ${it.counts.outcomesFilled}/${it.counts.outcomesExpected} outcomes"
        }.orEmpty()
        XRayUiState(
            loading = false, grid = grid,
            notEnough = if (grid == null) XRayNotEnough.from(true, false) else null,
            odds = odds, coachOdds = coach, coachBestLabel = coachBest,
            oddsLabel = prior?.label ?: "It goes well",
            pattern = PatternCatalog.of(archetype, s), modelStrip = counts,
            engineMove = pinned, engineRobust = directive?.let { it.stressVerdict == "ROBUST" }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), XRayUiState())

    fun setOdds(v: Float) { dragged.value = v.coerceIn(0f, 1f) }
    fun reset() { dragged.value = null }

    /** "Keep as my estimate": persist the dragged odds as the user's own, then drop the local drag. */
    fun keep() = viewModelScope.launch {
        dragged.value?.let { xray.keepAsMine(projectId, mapOf(PriorId.TAKES_LIMIT_WELL to it.toDouble())) }
        dragged.value = null
    }
}
