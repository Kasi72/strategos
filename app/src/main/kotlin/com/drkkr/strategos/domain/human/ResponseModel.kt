package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.exp

/**
 * "How will they react?" as a probability, not as the single highest-scoring reply (Nudge: people are noisy, not Econs).
 *
 * A noisy best reply (quantal response): P(receptive) = 1 / (1 + exp(-beta * (U_receptive - U_defensive))), with U the
 * counterpart's own score in the humanised view, normalised to the 0-10 scale so beta means the same thing in every scenario.
 * As beta grows this is exactly today's "best reply". Once a prior for the move's posture has been LEARNED (non-null on the
 * counterpart), it is blended in 50/50; the default priors are not. The result stays inside [0.05, 0.95].
 */
object ResponseModel {
    private const val FLOOR = 0.05
    private const val CEILING = 0.95

    /** How sharply they pick their best reply. Low noise (epsilon) means a high beta; 1.0 when nothing is known. */
    fun betaFrom(opponent: OpponentModel?): Double = when {
        opponent == null -> 1.0
        opponent.epsilon < 0.15 -> 3.0
        opponent.epsilon < 0.35 -> 1.5
        opponent.epsilon < 0.60 -> 1.0
        else -> 0.5
    }

    /** What the user's move leads to, as the likelier reply and how sure we are. */
    data class Likely(val replyLabel: String, val pReceptive: Double) {
        val couldGoEitherWay: Boolean get() = pReceptive in 0.4..0.6
    }

    private class Pair(val otherName: String, val receptiveId: String, val defensiveId: String)

    private fun pair(s: Scenario, ownerId: String, actionId: String): Pair? {
        val other = s.players().firstOrNull { it.id != ownerId } ?: return null
        val theirs = s.actionsFor(other.id)
        val rec = theirs.firstOrNull { Postures.of(it) == Postures.RECEPTIVE } ?: return null
        val def = theirs.firstOrNull { Postures.of(it) == Postures.DEFENSIVE } ?: return null
        if (s.actions.none { it.id == actionId && it.stakeholderId == ownerId }) return null
        return Pair(other.name, rec.id, def.id)
    }

    private fun entry(s: Scenario, who: String, ownerId: String, otherId: String, actionId: String, replyId: String) =
        s.payoffs.firstOrNull { it.stakeholderId == who && it.actionProfile[ownerId] == actionId && it.actionProfile[otherId] == replyId }

    /** The counterpart's score on a 0-10 scale (their weighted outcome divided by the weights it uses). */
    private fun feel(s: Scenario, e: com.drkkr.strategos.domain.model.PayoffEntry): Double {
        val w = e.payoff.values.keys.sumOf { (s.weights[it] ?: 0.0) }
        val total = e.payoff.weighted(s.weights)
        return if (w > 0.0) total / w else total
    }

    /** P(they take the receptive reply to [actionId]), or null when their two kinds of reply cannot be told apart. */
    fun receptiveProbability(s: Scenario, ownerId: String, actionId: String, beta: Double): Double? {
        val pair = pair(s, ownerId, actionId) ?: return null
        val other = s.players().first { it.id != ownerId }
        val r = entry(s, other.id, ownerId, other.id, actionId, pair.receptiveId) ?: return null
        val d = entry(s, other.id, ownerId, other.id, actionId, pair.defensiveId) ?: return null
        val x = (beta * (feel(s, r) - feel(s, d))).coerceIn(-700.0, 700.0)
        val logit = 1.0 / (1.0 + exp(-x))
        val action = s.actions.first { it.id == actionId }
        val learned = when (Postures.of(action)) {
            Postures.REPAIR -> other.probSoftensOnRepair
            Postures.BOUNDARY -> other.probRespectsBoundary
            Postures.REACTIVE -> other.probEscalatesOnReactive?.let { 1.0 - it }
            else -> null
        }
        val p = if (learned != null) 0.5 * logit + 0.5 * learned else logit
        return p.coerceIn(FLOOR, CEILING)
    }

    /** What the user's move is worth to them: p of the good outcome plus (1 - p) of the other, in the user's own weighted score. */
    fun expectedValue(s: Scenario, ownerId: String, actionId: String, beta: Double): Double? {
        val pair = pair(s, ownerId, actionId) ?: return null
        val other = s.players().first { it.id != ownerId }
        val p = receptiveProbability(s, ownerId, actionId, beta) ?: return null
        val good = entry(s, ownerId, ownerId, other.id, actionId, pair.receptiveId)?.payoff?.weighted(s.weights) ?: return null
        val bad = entry(s, ownerId, ownerId, other.id, actionId, pair.defensiveId)?.payoff?.weighted(s.weights) ?: return null
        return p * good + (1 - p) * bad
    }

    /** The more likely reply to [actionId] and how sure we are, for the "if you do this, they would most likely..." line. */
    fun likelyReply(s: Scenario, ownerId: String, actionId: String, beta: Double): Likely? {
        val pair = pair(s, ownerId, actionId) ?: return null
        val p = receptiveProbability(s, ownerId, actionId, beta) ?: return null
        val label = s.actions.firstOrNull { it.id == (if (p >= 0.5) pair.receptiveId else pair.defensiveId) }?.label ?: return null
        return Likely(label, p)
    }

    /**
     * ID4.1: the counterpart's replies to [actionId], most likely first: their own options only, never an invented reply. The same
     * noisy best reply over every option they have (a softmax of beta × their 0-10 score); with exactly a receptive and a
     * defensive reply, the order is [receptiveProbability]'s (learned priors included). Options with no cell are left out.
     */
    fun replies(s: Scenario, ownerId: String, actionId: String, beta: Double): List<com.drkkr.strategos.domain.model.Action> {
        val other = s.players().firstOrNull { it.id != ownerId } ?: return emptyList()
        if (s.actions.none { it.id == actionId && it.stakeholderId == ownerId }) return emptyList()
        val theirs = s.actionsFor(other.id).mapNotNull { r -> entry(s, other.id, ownerId, other.id, actionId, r.id)?.let { r to feel(s, it) } }
        val pair = pair(s, ownerId, actionId)
        if (theirs.size == 2 && pair != null && theirs.map { it.first.id }.toSet() == setOf(pair.receptiveId, pair.defensiveId)) {
            val p = receptiveProbability(s, ownerId, actionId, beta)
            if (p != null) return theirs.map { it.first }.sortedByDescending { if (it.id == pair.receptiveId) p else 1 - p }
        }
        // exp(beta × score) is monotone in the score, so the order is the scores' order (stable on ties: their option order)
        return theirs.sortedByDescending { beta * it.second }.map { it.first }
    }

    /** Their name, for the sentence. */
    fun otherName(s: Scenario, ownerId: String): String? = s.players().firstOrNull { it.id != ownerId }?.name
}
