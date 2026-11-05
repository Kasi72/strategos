package com.drkkr.strategos.domain.human

/**
 * What worked with THIS person. Per delivery tactic a Beta(alpha, beta) starting at (1, 1): a reaction of "softened" after a reply
 * that used it adds to alpha, "withdrew_or_escalated" adds to beta, anything else changes nothing. The planner scales a tactic's
 * gain by how well it has worked, so a person who ignores defaults stops getting them. Stored in [HumanContext.tacticSuccess].
 */
object TacticLearning {
    /** What a reaction taught us, and from which reply, so the same reply is never counted twice. */
    data class Learned(val messageId: String, val success: Map<String, List<Double>>)

    /** Only the entries that changed (a patch). Empty when the reaction says nothing or no tactic was used. */
    fun update(success: Map<String, List<Double>>, used: Collection<String>, reaction: String?): Map<String, List<Double>> {
        val (a, b) = when (reaction) {
            "softened" -> 1.0 to 0.0
            "withdrew_or_escalated" -> 0.0 to 1.0
            else -> return emptyMap()
        }
        return used.distinct().associateWith { id ->
            val old = success[id] ?: listOf(1.0, 1.0)
            listOf(old[0] + a, old[1] + b)
        }
    }

    /** Posterior mean of "it worked", 0.5 when nothing is known. */
    fun mean(success: Map<String, List<Double>>, id: String): Double {
        val ab = success[id] ?: return 0.5
        return ab[0] / (ab[0] + ab[1])
    }

    /** How much to trust a tactic's measured gain: mean / 0.5, kept between half and one and a half. */
    fun multiplier(success: Map<String, List<Double>>, id: String): Double = (mean(success, id) / 0.5).coerceIn(0.5, 1.5)
}
