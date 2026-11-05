package com.drkkr.strategos.domain.human

/**
 * The ethics gate. Its test is the publicity principle from Nudge (Ch.17): would the move still be fine, and still work, if the
 * other person knew exactly what you were doing and why? Invariants I-H2 to I-H5:
 *  - I-H2: a DECEPTIVE tactic is never offered, anywhere.
 *  - I-H3: a tactic that fails the publicity test is never offered.
 *  - I-H4: in a close relationship only BENIGN tactics; CONTEXT tactics need work, negotiation or an adversarial setting;
 *    and a tactic is only ever offered in a domain it lists.
 *  - I-H5: safety first. A safety need, a vulnerable counterpart, or coercive behaviour in a close relationship means NOTHING is
 *    offered (the user gets support, not tactics).
 */
object EthicsGate {
    /** Patterns that, in a close relationship, are signs of coercive control rather than ordinary power dynamics. */
    val COERCIVE = setOf("isolates_you", "keeps_you_dependent", "hot_cold")

    /** True when no tactic at all may be offered. */
    fun nothingAllowed(ctx: HumanContext?, domain: RelationshipDomain, support: Boolean): Boolean {
        if (support) return true
        val vulnerable = ctx?.theirVulnerable
        if (vulnerable != null && vulnerable != "none") return true
        return domain.isClose && ctx?.theirTactics.orEmpty().any { it in COERCIVE }
    }

    fun allowed(t: Tactic, ctx: HumanContext?, domain: RelationshipDomain, support: Boolean): Boolean {
        if (nothingAllowed(ctx, domain, support)) return false
        if (t.ethics == Ethics.DECEPTIVE || !t.publicityPass) return false
        if (t.ethics == Ethics.CONTEXT && domain.isClose) return false
        return domain in t.domains
    }
}
