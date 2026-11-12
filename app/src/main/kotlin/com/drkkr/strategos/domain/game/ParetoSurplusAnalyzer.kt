package com.drkkr.strategos.domain.game

/** E8: Pareto surplus map — gap between Nash joint utility and the socially optimal (Pareto-maximal) joint utility. */
object ParetoSurplusAnalyzer {

    data class SurplusResult(
        /** Sum of all players' utilities at the Nash equilibrium profile. */
        val nashJointUtility: Double,
        /** Maximum achievable sum of utilities across all profiles (Pareto optimum). */
        val paretoJointUtility: Double,
        /** paretoJointUtility - nashJointUtility; ≥ 0 always. */
        val surplusGap: Double,
        /** True when the equilibrium is already Pareto-optimal (gap < 0.01). */
        val isSociallyOptimal: Boolean,
        val note: String
    )

    fun analyze(ir: GameIR, focalEquilibrium: EquilibriumProfile?): SurplusResult? {
        val pureProfile = focalEquilibrium?.pureProfile ?: return null
        val nashProfileIdx = ir.profileIndex(pureProfile.toIntArray())
        val nashJoint = (0 until ir.playerCount).sumOf { p -> ir.utility[p][nashProfileIdx] }
        val paretoJoint = (0 until ir.profileCount).maxOfOrNull { k ->
            (0 until ir.playerCount).sumOf { p -> ir.utility[p][k] }
        } ?: nashJoint
        val gap = paretoJoint - nashJoint
        val isSocOpt = gap < 0.01
        val note = if (isSocOpt)
            "Nash equilibrium is socially optimal — no joint improvement available"
        else
            "Joint surplus shortfall: ${String.format("%.2f", gap)} utility units left on the table vs. the best feasible outcome"
        return SurplusResult(nashJoint, paretoJoint, gap.coerceAtLeast(0.0), isSocOpt, note)
    }
}
