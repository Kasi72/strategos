package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.domain.negotiation.WalkAwayMask

/**
 * The main turn's steps after the analysis view, in one place, so an intent's variant goes through exactly what the turn did
 * (I-D4): the walk-away view and mask, the fallback shift, the Human Layer; then the directive's own guards for the pick.
 */
object TurnChain {
    /** The walk-away view, masked (what LabelChecks and the fallback shift read). */
    fun negotiated(analysisView: Scenario, deal: DealFrame?): Scenario = WalkAwayMask.apply(NegotiationView.apply(analysisView, deal), deal)

    fun prepare(deal: DealFrame?, sides: FallbackShift.Sides): (Scenario) -> Scenario = { v ->
        HumanizedView.apply(FallbackShift.apply(negotiated(v, deal), sides))
    }

    /** The engine's pick on a prepared view, as the directive builder would give it (walk-away, dominance and stated-floor guards). */
    fun decide(deal: DealFrame?, statedFloor: Double?): (Scenario) -> String? = { v ->
        EngineDirectiveBuilder.build(v, AnalysisPipeline().run(v), null, statedFloor = statedFloor, deal = deal).takeIf { it.ready }?.recommendedMove
    }
}
