package com.drkkr.strategos.ui.calm.overview

import com.drkkr.strategos.domain.agent.BrainFact
import com.drkkr.strategos.domain.agent.InfluenceGraph
import com.drkkr.strategos.domain.agent.Pattern
import com.drkkr.strategos.domain.agent.PersonMemory
import com.drkkr.strategos.domain.agent.StandingGuidance
import com.drkkr.strategos.domain.analysis.MomentumEvent
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.domain.analysis.PendingCheck

/** The momentum chart data: past (solid) and two projections (dashed) for trust and tension. */
data class ChartUi(
    val trustPast: List<Float>,
    val tensionPast: List<Float>,
    val trustHold: List<Float>,
    val tensionHold: List<Float>,
    val trustRevert: List<Float>,
    val tensionRevert: List<Float>
) {
    val hasData: Boolean get() = trustPast.size >= 2
}

/** One standing Do/Don't line, flattened for the list with its source caption. */
data class GuidanceRowUi(val text: String, val isDo: Boolean, val source: String, val refId: String?)

/** A pending journal takeaway shown in "From your journal". */
data class JournalTeaserUi(val postNoteId: String, val headline: String, val takeaway: String, val whenLabel: String)

/** The Overview "dashboard" state (board Hub). Sections with no data use their own empty variant. */
data class OverviewUiState(
    val loading: Boolean = true,
    val projectName: String = "",
    val subtitle: String = "",
    val linkedJournalCount: Int = 0,
    val status: MomentumStatus = MomentumStatus.STEADY,
    val chart: ChartUi = ChartUi(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    val momentumBlurb: String = "",
    val events: List<MomentumEvent> = emptyList(),
    val pendingCheck: PendingCheck? = null,
    val guidance: List<GuidanceRowUi> = emptyList(),
    val pattern: Pattern? = null,
    val threads: List<ThreadRowUi> = emptyList(),
    val chatLabel: String = "Start chat",
    val people: List<PersonMemory> = emptyList(),
    val limits: List<BrainFact> = emptyList(),
    val inferredCount: Int = 0,
    val reviewedCount: Int = 0,
    val inferredTotal: Int = 0,
    val modelStrip: String = "",
    val influence: InfluenceGraph? = null,
    val journalTeasers: List<JournalTeaserUi> = emptyList(),
    val reviewItems: List<com.drkkr.strategos.domain.agent.ReviewItem> = emptyList()
)

/** A conversation row for the Conversations section. */
data class ThreadRowUi(val id: String, val title: String, val lastLine: String, val whenLabel: String, val isOpenNow: Boolean)

data class OverviewActions(
    val onBack: () -> Unit = {},
    val onOpenChat: () -> Unit = {},
    val onOpenThread: (String) -> Unit = {},
    val onNewTopic: () -> Unit = {},
    val onSaveQuickCheck: (did: String, landed: String) -> Unit = { _, _ -> },
    val onOpenReply: (String?) -> Unit = {},
    val onReadMore: (String) -> Unit = {},
    val onOpenXRay: () -> Unit = {},
    val onReview: () -> Unit = {},
    val onAddFact: () -> Unit = {},
    val onApplyJournal: (String) -> Unit = {},
    val onDismissJournal: (String) -> Unit = {},
    val onOpenJournalTab: () -> Unit = {}
)

/** Pure chart helpers (no LLM, no android): project each series a few steps for the dashed lines. */
object OverviewChart {
    const val STEPS = 3

    /** Continue the recent slope ("if you hold this approach"), clamped 0..100. */
    fun hold(series: List<Float>, steps: Int = STEPS): List<Float> {
        if (series.size < 2) return emptyList()
        val slope = (series.last() - series[series.size - 2])
        return (1..steps).map { (series.last() + slope * it).coerceIn(0f, 100f) }
    }

    /** Drift back toward the neutral middle ("if the old pattern repeats"). */
    fun revert(series: List<Float>, steps: Int = STEPS, toward: Float = 50f): List<Float> {
        if (series.size < 2) return emptyList()
        var v = series.last()
        return (1..steps).map { v += (toward - v) * 0.35f; v.coerceIn(0f, 100f) }
    }
}
