package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario

/**
 * Puts a benign delivery tactic on the same scale as a hardball move's EV (blueprint §4, planner rule 2): its Δp (how much more
 * likely the receptive reply becomes) times the spread between the user's own score (0–10) when the other side is receptive and
 * when they are defensive.
 */
object BenignEquivalent {
    /** The user's score with a receptive reply minus with a defensive one, for [actionId]; 0 when it can't be read. */
    fun spread(s: Scenario, actionId: String): Double {
        val owner = IntakeChecklist.self(s) ?: return 0.0
        val other = s.players().firstOrNull { it.id != owner.id } ?: return 0.0
        val theirs = s.actionsFor(other.id)
        val rec = theirs.firstOrNull { Postures.of(it) == Postures.RECEPTIVE } ?: return 0.0
        val def = theirs.firstOrNull { Postures.of(it) == Postures.DEFENSIVE } ?: return 0.0
        fun score(reply: String) = s.payoffs.firstOrNull {
            it.stakeholderId == owner.id && it.actionProfile[owner.id] == actionId && it.actionProfile[other.id] == reply
        }?.let { scale(s, it) }
        val r = score(rec.id) ?: return 0.0
        val d = score(def.id) ?: return 0.0
        return (r - d).coerceAtLeast(0.0)
    }

    fun of(deltaP: Double, spread: Double): Double = deltaP * spread

    private fun scale(s: Scenario, e: PayoffEntry): Double {
        val w = e.payoff.values.keys.sumOf { s.weights[it] ?: 0.0 }
        val total = e.payoff.weighted(s.weights)
        return if (w > 0.0) total / w else total
    }
}
