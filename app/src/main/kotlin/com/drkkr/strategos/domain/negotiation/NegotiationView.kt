package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.ConstraintAssessment
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

/**
 * Negotiation Numbers (PLAN §3.3): the walk-away as a real limit inside the vote. It adds one user limit and marks every one of
 * the user's options whose single figure is on the wrong side of it as breaking that limit, so `RecommendationEngine` excludes
 * it by the same path as every other limit. The analysis view only: nothing here is ever saved, and the outcome scores are
 * never touched. Without a frame (or without a walk-away) the same instance comes back (I-N1).
 */
object NegotiationView {
    const val CONSTRAINT_ID = "negotiation-walk-away"

    /**
     * The kinds `RecommendationEngine` treats as hard (only a hard limit excludes an option). Kept in step with its
     * `hardConstraintKinds`. PLAN §3.3 asks for POLICY, which is not one of them; USER_HARD is the user's own hard limit.
     */
    private val HARD = setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD)

    fun apply(view: Scenario, frame: DealFrame?): Scenario {
        val walk = frame?.walkAway ?: return view
        val owner = IntakeChecklist.self(view) ?: return view
        val limit = Constraint(
            id = CONSTRAINT_ID, label = "Walk-away: ${DealNumbers.format(walk)}", kind = Constraint.Kind.USER_HARD,
            factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "Your walk-away, for this analysis only (never saved)"
        )
        val hardIds = view.constraints.filter { it.kind in HARD }.map { it.id }.toSet()

        fun crosses(a: Action): Boolean = a.stakeholderId == owner.id && crosses(a.label, frame)

        // Every other option gets the status that leaves its own summary exactly as it was before this limit existed:
        // with no assessment at all it would read UNKNOWN as soon as any hard limit exists (RecommendationEngine).
        fun neutral(a: Action): ConstraintStatus {
            val mine = view.constraintAssessments.filter { it.actionId == a.id }
            val hard = mine.filter { it.constraintId in hardIds }
            return when {
                hard.isEmpty() && hardIds.isNotEmpty() && mine.isEmpty() -> ConstraintStatus.UNKNOWN
                hard.isNotEmpty() && hard.all { it.status == ConstraintStatus.SATISFIED } -> ConstraintStatus.SATISFIED
                else -> ConstraintStatus.NOT_APPLICABLE
            }
        }

        val assessments = view.actions.map { a ->
            val bad = crosses(a)
            ConstraintAssessment(
                id = "$CONSTRAINT_ID:${a.id}", constraintId = CONSTRAINT_ID, actionId = a.id,
                status = if (bad) ConstraintStatus.VIOLATED else neutral(a),
                rationale = if (bad) "Its figure is on the wrong side of your walk-away" else "",
                assessedBy = ConstraintAssessment.AssessedBy.RULE, timestamp = 0L, assessedAt = 0L
            )
        }
        return view.copy(constraints = view.constraints + limit, constraintAssessments = view.constraintAssessments + assessments)
    }

    /** True when [label] carries exactly one figure, in the frame's unit, on the wrong side of the walk-away. */
    fun crosses(label: String, frame: DealFrame): Boolean {
        val walk = frame.walkAway ?: return false
        val figure = DealNumbers.parse(label) ?: return false
        return frame.compatible(figure) && frame.worse(figure.value, walk.value)
    }

    /**
     * The saved analysis with every option that crosses the walk-away marked excluded, for the engine's own fallback text: when the
     * coach's reply is not used, that text must not offer what the vote ruled out. Null frame = the same instance (I-N1).
     */
    fun withinWalkAway(pr: PipelineResult, frame: DealFrame?): PipelineResult {
        val report = pr.report ?: return pr
        if (frame?.walkAway == null) return pr
        val labels = report.scenario.actions.associate { it.id to it.label }
        val owner = IntakeChecklist.self(report.scenario)?.id
        val pathways = report.pathways.map { p ->
            val mine = p.actionId != null && report.scenario.actions.firstOrNull { it.id == p.actionId }?.stakeholderId == owner
            if (!p.excluded && mine && crosses(labels[p.actionId] ?: p.action, frame)) p.copy(excluded = true) else p
        }
        return pr.copy(report = report.copy(pathways = pathways))
    }
}
