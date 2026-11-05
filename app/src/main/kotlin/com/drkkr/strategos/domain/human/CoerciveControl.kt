package com.drkkr.strategos.domain.human

/**
 * When coercive-control patterns stop advice and start support (I-H5). Decided at the CP-C safety review:
 *  - `isolates_you` and `keeps_you_dependent` route on FIRST mention; `hot_cold` alone needs two turns (or to come with another one);
 *  - only INTIMATE and FAMILY route; in a friendship the same behaviour is an ordinary watch-for after two turns;
 *  - routing is not forever: it expires after [EXPIRES_AFTER] consecutive messages with no coercive tag, and any new mention re-arms it.
 */
object CoerciveControl {
    val TAGS = setOf("isolates_you", "keeps_you_dependent", "hot_cold")
    const val EXPIRES_AFTER = 5
    private val ROUTING_DOMAINS = setOf(RelationshipDomain.INTIMATE, RelationshipDomain.FAMILY)

    /** A coercive pattern has been described and has not yet expired. */
    fun active(ctx: HumanContext?): Boolean =
        ctx != null && ctx.theirTactics.any { it in TAGS } && (ctx.routingQuietTurns ?: 0) < EXPIRES_AFTER

    fun routes(ctx: HumanContext?, domain: RelationshipDomain): Boolean {
        if (ctx == null || domain !in ROUTING_DOMAINS || !active(ctx)) return false
        val present = ctx.theirTactics.filter { it in TAGS }
        if ("isolates_you" in present || "keeps_you_dependent" in present) return true
        return "hot_cold" in present && ((ctx.tacticTurns["hot_cold"] ?: 0) >= 2 || present.size >= 2)
    }

    /** Domains where the three tags are never shown as an ordinary watch-for (they route, or have expired). */
    fun isRoutingDomain(domain: RelationshipDomain): Boolean = domain in ROUTING_DOMAINS
}
