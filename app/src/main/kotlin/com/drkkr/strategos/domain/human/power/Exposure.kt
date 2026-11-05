package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.model.Stakeholder

/** Power Mode's exposure model (blueprint §4): how likely a hardball move is to be seen through, and what it is worth after that. */
object Exposure {
    /** Relationships that go on after this deal: more rounds, more chances to be found out. */
    val CONTINUING = setOf("manager", "report", "peer", "cofounder", "client")
    /** Close relationships always continue (CP-P2); Power Mode never plays hardball in them, but the card names what is at stake. */
    val CLOSE = setOf("spouse", "partner", "parent", "child", "sibling", "relative", "friend")
    /** Counterparts new to the user: they know little about how the user usually behaves. */
    val NEW_COUNTERPART = setOf("vendor", "landlord", "opponent")
    const val PROBES = "probes_you"
    /** Credibility at or below this (0-10) reads as the counterpart doubting the user's word. */
    const val LOW_CREDIBILITY = 3

    /** The chance q the other side sees through a hardball move, bounded to [PowerConstants.qMin, PowerConstants.qMax]. */
    /** [suspicion] false for a DEFENSIVE move: being seen holding back costs almost nothing, so probing doesn't raise its q (CP-P1). */
    fun q(ctx: HumanContext?, domain: RelationshipDomain, counterpart: Stakeholder?, c: PowerConstants, suspicion: Boolean = true): Double {
        var q = c.qBase[domain] ?: c.qMax                       // a domain with no base (a close one) is treated as the most exposed
        if (continuing(ctx)) q += c.qContinuing                 // the analysis view is always a repeated game, so only the relationship decides
        if (suspicion && suspicious(ctx, counterpart)) q += c.qSuspicious
        if (ctx?.relationship in NEW_COUNTERPART) q += c.qNewCounterpart
        return q.coerceIn(c.qMin, c.qMax)
    }

    fun continuing(ctx: HumanContext?): Boolean = ctx?.relationship in CONTINUING || ctx?.relationship in CLOSE
    fun suspicious(ctx: HumanContext?, counterpart: Stakeholder?): Boolean =
        PROBES in ctx?.theirTactics.orEmpty() || (counterpart != null && counterpart.credibility <= LOW_CREDIBILITY)

    /** EV = (1 − q)·gain − q·exposureLoss·(1 + trust/100). More trust means more to lose; trust defaults to 50. */
    fun ev(t: StrategicTactic, q: Double, trustLevel: Double?, c: PowerConstants): Double =
        (1 - q) * t.gain - q * t.exposureLoss * (1 + (trustLevel ?: DEFAULT_TRUST) / 100.0)

    const val DEFAULT_TRUST = 50.0
}
