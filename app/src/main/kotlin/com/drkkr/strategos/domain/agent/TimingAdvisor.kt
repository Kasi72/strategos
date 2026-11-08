package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.model.Scenario

/**
 * Advises whether to act now, set a deadline, or defer, based on discount factor and scenario constraints.
 */
object TimingAdvisor {

    enum class TimingAdvice { WAIT, SET_DEADLINE, NO_GUIDANCE }

    /**
     * @param scenario the current scenario (checks for time constraints)
     * @param opponentModel optional opponent priors; discountFactor drives patience estimates
     * @return [TimingAdvice.WAIT] when patience pays off, [TimingAdvice.SET_DEADLINE] when the opponent is impatient
     *         or the scenario has a binding deadline, [TimingAdvice.NO_GUIDANCE] when insufficient information
     */
    fun advise(
        scenario: Scenario,
        opponentModel: OpponentModel? = null
    ): TimingAdvice {
        val df = opponentModel?.discountFactor ?: scenario.discountFactor

        // Binding time constraint in the scenario
        val hasDeadlineConstraint = scenario.constraints.any { c ->
            c.kind == com.drkkr.strategos.domain.model.Constraint.Kind.POLICY ||
                    c.kind == com.drkkr.strategos.domain.model.Constraint.Kind.RESOURCE
        }

        if (df == null) {
            // No discount data — set a deadline if there is a binding constraint, else no guidance
            return if (hasDeadlineConstraint) TimingAdvice.SET_DEADLINE else TimingAdvice.NO_GUIDANCE
        }

        return when {
            // Very patient opponent (high discount factor) — waiting costs them little, so we wait too
            df >= 0.7 -> TimingAdvice.WAIT
            // Impatient opponent or binding constraint — a deadline forces action
            df <= 0.3 || hasDeadlineConstraint -> TimingAdvice.SET_DEADLINE
            // Moderate patience
            else -> TimingAdvice.NO_GUIDANCE
        }
    }

    /** Plain-English note for the coach prompt. */
    fun note(advice: TimingAdvice, opponentModel: OpponentModel? = null): String? = when (advice) {
        TimingAdvice.WAIT ->
            "The other side is patient (high discount factor). There is no urgency to move first; " +
                    "waiting preserves your options without material cost."
        TimingAdvice.SET_DEADLINE ->
            "The other side is impatient or a binding constraint applies. " +
                    "Setting a clear deadline shifts pressure to them and prevents indefinite delay."
        TimingAdvice.NO_GUIDANCE -> null
    }
}
