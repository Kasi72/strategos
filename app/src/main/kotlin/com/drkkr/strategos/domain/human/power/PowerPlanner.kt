package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.human.CoerciveControl
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario

/** One hardball move for the Power Read card: its EV after exposure, and whether the lead would change if q were halved or doubled. */
data class RankedMove(val tactic: StrategicTactic, val ev: Double, val closeCall: Boolean = false)

/**
 * What Power Mode adds to a turn (blueprint §4). [cardMoves]: up to 3 moves with EV > 0, best first, for the Power Read card.
 * [deliveryMoves]: up to 2 of those whose EV beats the best benign move, for the coach. [noHardballReason]: why there are none.
 * [closeDomainNote]: the line shown instead of moves in a close relationship. [q]: the exposure chance used.
 */
data class PowerPlan(
    val cardMoves: List<RankedMove> = emptyList(),
    val deliveryMoves: List<StrategicTactic> = emptyList(),
    val noHardballReason: String? = null,
    val closeDomainNote: String? = null,
    val q: Double? = null
)

/**
 * Power Mode's planner (I-P3). Ranks the STRATEGIC tactics that pass [PowerGate] and their preconditions by EV after the cost of
 * being found out. A hardball move is offered only when its EV is above zero, and reaches the coach only when it also beats the
 * best benign move; otherwise the card says why hardball doesn't pay. It never changes the engine's pick (I-H1).
 */
object PowerPlanner {
    const val CLOSE_DOMAIN_NOTE = "Hardball moves are for work and negotiations — here's how the dynamics look."
    const val REASON_EXPOSED = "a high chance they'd see through it"
    const val REASON_CONTINUES = "this relationship continues after the deal"
    const val REASON_SMALL = "the gain is small here"
    /** At or above this q, being seen through is the main reason hardball doesn't pay (STEPS PM2.3). */
    const val HIGH_Q = 0.45
    private const val EPS = 1e-9

    fun plan(
        view: Scenario, domain: RelationshipDomain, powerOn: Boolean, support: Boolean, powerLock: Boolean, trustLevel: Double?,
        benignBest: Double, c: PowerConstants = PowerConstants.DEFAULT, tactics: List<StrategicTactic> = StrategicCatalog.tactics,
        /** The move the tactics would deliver; defaults to the user's first calm option. */
        move: Action? = null
    ): PowerPlan {
        if (!powerOn || powerLock || support) return PowerPlan()
        val ctx = view.humanContext ?: HumanContext()
        // Safety first (I-H5): a vulnerable counterpart or a coercive pattern means no hardball and no "why not" either.
        if ((ctx.theirVulnerable ?: "none") != "none" || ctx.theirTactics.any { it in CoerciveControl.TAGS }) return PowerPlan()
        if (domain.isClose) return PowerPlan(closeDomainNote = CLOSE_DOMAIN_NOTE)

        val owner = IntakeChecklist.self(view)
        val counterpart = owner?.let { o -> view.players().firstOrNull { it.id != o.id } }
        // The habits in the user's recent attempts (union of the last 3 turns) ride on the move, as in the Human Layer planner.
        val action = (move ?: owner?.let { o -> view.actionsFor(o.id).firstOrNull { Postures.of(it) != Postures.REACTIVE } ?: view.actionsFor(o.id).firstOrNull() })
            ?.let { DeliveryPlanner.carrying(ctx, it) }
        val candidates = if (action == null) emptyList() else tactics.filter { t ->
            PowerGate.allowed(t.tactic, ctx, domain, powerOn, support, powerLock) && runCatching { t.tactic.preconditions(ctx, action, view) }.getOrDefault(false)
        }
        val q = Exposure.q(ctx, domain, counterpart, c)
        val qDefensive = Exposure.q(ctx, domain, counterpart, c, suspicion = false)
        val probing = Exposure.PROBES in ctx.confirmedTactics()
        fun ev(t: StrategicTactic, scale: Double): Double {
            val boosted = if (t.defensive && probing) t.copy(gain = t.gain + c.defensiveProbeGain) else t
            return Exposure.ev(boosted, ((if (t.defensive) qDefensive else q) * scale).coerceIn(0.0, 1.0), trustLevel, c)
        }
        fun ranked(scale: Double) = candidates.map { it to ev(it, scale) }.sortedByDescending { it.second }

        val atQ = ranked(1.0)
        val positive = atQ.filter { it.second >= c.minEv - EPS }
        if (positive.isEmpty()) {
            val reason = when {
                q >= HIGH_Q - EPS -> REASON_EXPOSED                // 0.30 + 0.15 is 0.4499… in floating point
                Exposure.continuing(ctx) -> REASON_CONTINUES
                else -> REASON_SMALL
            }
            return PowerPlan(noHardballReason = reason, q = q)
        }
        // A close call: the lead changes when q is halved or doubled (the InferredStressTest idea).
        val leader = positive.first().first.id
        val closeCall = listOf(c.closeCallFactor, 1 / c.closeCallFactor).any { ranked(it).firstOrNull()?.first?.id != leader }
        val card = positive.take(c.maxCardMoves).mapIndexed { i, (t, ev) -> RankedMove(t, ev, closeCall = i == 0 && closeCall) }
        // The coach gets up to 2 moves that beat the best benign one, never two of the same family (the next distinct move fills in).
        val delivery = mutableListOf<StrategicTactic>()
        for ((t, ev) in positive) {
            if (delivery.size >= c.maxDeliveryMoves || ev <= benignBest) break
            if (delivery.any { d -> StrategicCatalog.SAME_FAMILY.any { f -> d.id in f && t.id in f } }) continue
            delivery += t
        }
        return PowerPlan(cardMoves = card, deliveryMoves = delivery, q = q)
    }
}
