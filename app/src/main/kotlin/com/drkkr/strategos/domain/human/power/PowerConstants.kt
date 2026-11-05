package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.human.RelationshipDomain

/**
 * Power Mode's numbers (blueprint §4). Every one is a named, bounded constant with a source note, like `HumanConstants` (I-H6).
 * These are engineering estimates for how likely a hardball move is to be seen through; none comes from a measured study.
 */
data class PowerConstants(
    /**
     * Base chance the other side sees through a hardball move, by setting. Lowest in a one-off negotiation (expected posturing),
     * highest up a hierarchy (a manager watches you over time). Blueprint §4; engineering estimate.
     */
    val qBase: Map<RelationshipDomain, Double> = mapOf(
        RelationshipDomain.NEGOTIATION to 0.15, RelationshipDomain.ADVERSARIAL to 0.20,
        RelationshipDomain.WORK_PEER to 0.25, RelationshipDomain.WORK_HIERARCHY to 0.30
    ),
    /** Added when the relationship continues after this deal: more rounds, more chances to be found out (48 Laws L5, reputation). */
    val qContinuing: Double = 0.15,
    /** Added when the counterpart probes your limits or doubts your word (radar `probes_you`, or credibility <= 3; L14 defensive). */
    val qSuspicious: Double = 0.15,
    /** Added (a reduction) when the counterpart is new to the user: a vendor, landlord or opponent knows little about them. */
    val qNewCounterpart: Double = -0.10,
    /** Floor: some chance of exposure always remains. Blueprint §4. */
    val qMin: Double = 0.05,
    /** Ceiling: exposure is never treated as certain. Blueprint §4. */
    val qMax: Double = 0.8,
    /** Moves shown on the Power Read card (blueprint §4, planner rule 2). */
    val maxCardMoves: Int = 3,
    /** Hardball hints passed to the coach in `delivery` (blueprint §4, planner rule 2). */
    val maxDeliveryMoves: Int = 2,
    /** q is halved and doubled by this factor to find a "close call" (the InferredStressTest idea, blueprint §4). */
    val closeCallFactor: Double = 2.0
) {
    companion object {
        val DEFAULT = PowerConstants()
    }
}
