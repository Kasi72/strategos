package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.human.CoerciveControl
import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic

/**
 * Power Mode's gate (blueprint §4). A STRATEGIC (hardball) tactic is allowed only when Power Mode is on and not locked (I-P1, I-P4),
 * the setting is one it lists and is not close (I-H4), it is not a support turn and the other person is not vulnerable (I-H5),
 * and no coercive pattern has been described. MANIPULATIVE is never allowed (I-P2). BENIGN and CONTEXT keep the Human Layer's gate.
 */
object PowerGate {
    fun allowed(t: Tactic, ctx: HumanContext?, domain: RelationshipDomain, powerOn: Boolean, support: Boolean, powerLock: Boolean): Boolean =
        when (t.ethics) {
            Ethics.MANIPULATIVE -> false
            Ethics.BENIGN, Ethics.CONTEXT -> EthicsGate.allowed(t, ctx, domain, support)
            Ethics.STRATEGIC -> powerOn && !powerLock && domain in t.domains && !domain.isClose && !support &&
                (ctx?.theirVulnerable == null || ctx.theirVulnerable == "none") &&
                ctx?.theirTactics.orEmpty().none { it in CoerciveControl.TAGS }
        }
}
