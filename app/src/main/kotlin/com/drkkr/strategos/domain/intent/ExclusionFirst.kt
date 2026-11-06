package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.NegotiationView

/**
 * ID3.7: the user's moves that cross the walk-away, and their reactive moves, are set aside BEFORE dominance is pruned, so a
 * valid move dominated only by an excluded one is not dropped with it. Pure; the engine's own pipeline is not changed.
 */
object ExclusionFirst {
    /** The user's moves that are never offered: past the walk-away (the walk-away view marks them) or reactive. */
    fun excluded(view: Scenario): Set<String> {
        val owner = IntakeChecklist.self(view) ?: return emptySet()
        val crossing = view.constraintAssessments.filter { it.constraintId == NegotiationView.CONSTRAINT_ID && it.status == ConstraintStatus.VIOLATED }.map { it.actionId }
        val reactive = view.actionsFor(owner.id).filter { Postures.of(it) == Postures.REACTIVE }.map { it.id }
        return (crossing + reactive).toSet()
    }

    /** [view] without the [excluded] moves and every cell and assessment that names them. The same instance when none. */
    fun filtered(view: Scenario): Scenario {
        val out = excluded(view)
        if (out.isEmpty()) return view
        // keep at least one move for the user, or there is no game left to prune
        val owner = IntakeChecklist.self(view) ?: return view
        if (view.actionsFor(owner.id).all { it.id in out }) return view
        return view.copy(
            actions = view.actions.filter { it.id !in out },
            payoffs = view.payoffs.filter { p -> p.actionProfile.values.none { it in out } },
            constraintAssessments = view.constraintAssessments.filter { it.actionId !in out }
        )
    }

    /** The user's moves that are dominated among the moves left after the exclusion (action ids). */
    fun dominated(view: Scenario, contextText: String = ""): Set<String> {
        val ownerId = IntakeChecklist.self(view)?.id ?: return emptySet()
        return runCatching { GameModelService.analyzeWithContext(filtered(view), contextText = contextText).pruneResult?.eliminated?.get(ownerId).orEmpty().toSet() }
            .getOrDefault(emptySet())
    }
}
