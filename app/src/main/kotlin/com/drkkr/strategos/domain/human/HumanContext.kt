package com.drkkr.strategos.domain.human

import kotlinx.serialization.Serializable

/**
 * What the model has inferred about the human side of the situation (power, status quo, emotional state, how the
 * other person is behaving). Stored inside the scenario JSON, so there is no database migration. Everything in it is
 * AI-inferred until the user corrects it (I-H8). The words come from the closed vocabularies in [HumanVocab].
 */
@Serializable
data class HumanContext(
    val relationship: String? = null,
    /** The counterpart compared with "You": they_higher | equal | you_higher. */
    val theirPower: String? = null,
    val theyDependOnYou: String? = null,
    val youDependOnThem: String? = null,
    val yourAlternative: String? = null,
    val theirAlternative: String? = null,
    /** Where the move would happen: private | semi_public | public. */
    val setting: String? = null,
    /** What continues if nobody acts, in the user's words (at most [STATUS_QUO_MAX] characters). */
    val statusQuo: String? = null,
    val statusQuoForThem: String? = null,
    val deadline: String? = null,
    val theirVulnerable: String? = null,
    /** Smoothed emotional level, 0 = calm … 3 = furious. One hot message does not make a hot person. */
    val yourStateLevel: Double? = null,
    val theirStateLevel: Double? = null,
    /** The latest raw reading, for this turn only. */
    val yourStateRaw: String? = null,
    val theirStateRaw: String? = null,
    /** Things the user described the other person doing, newest first, at most [TACTICS_MAX]. */
    val theirTactics: List<String> = emptyList(),
    val stepsForThemToSayYes: Int? = null,
    val goalIs: String? = null,
    /** Per delivery tactic: Beta(alpha, beta) of how often it worked with this person. Used from HL6. */
    val tacticSuccess: Map<String, List<Double>> = emptyMap()
) {
    /**
     * Folds a newer reading into this one. A non-null newer value replaces the old; a null never erases it. The two
     * state levels are smoothed (half new, half old). Tactics are the union, newest first. Learned priors are kept.
     */
    fun merge(newer: HumanContext): HumanContext = HumanContext(
        relationship = newer.relationship ?: relationship,
        theirPower = newer.theirPower ?: theirPower,
        theyDependOnYou = newer.theyDependOnYou ?: theyDependOnYou,
        youDependOnThem = newer.youDependOnThem ?: youDependOnThem,
        yourAlternative = newer.yourAlternative ?: yourAlternative,
        theirAlternative = newer.theirAlternative ?: theirAlternative,
        setting = newer.setting ?: setting,
        statusQuo = (newer.statusQuo ?: statusQuo)?.take(STATUS_QUO_MAX),
        statusQuoForThem = newer.statusQuoForThem ?: statusQuoForThem,
        deadline = newer.deadline ?: deadline,
        theirVulnerable = newer.theirVulnerable ?: theirVulnerable,
        yourStateLevel = smooth(yourStateLevel, newer.yourStateLevel),
        theirStateLevel = smooth(theirStateLevel, newer.theirStateLevel),
        yourStateRaw = newer.yourStateRaw ?: yourStateRaw,
        theirStateRaw = newer.theirStateRaw ?: theirStateRaw,
        theirTactics = (newer.theirTactics + theirTactics).distinct().take(TACTICS_MAX),
        stepsForThemToSayYes = newer.stepsForThemToSayYes ?: stepsForThemToSayYes,
        goalIs = newer.goalIs ?: goalIs,
        tacticSuccess = tacticSuccess + newer.tacticSuccess
    )

    private fun smooth(old: Double?, new: Double?): Double? = when {
        new == null -> old
        old == null -> new
        else -> 0.5 * new + 0.5 * old
    }

    companion object {
        const val STATUS_QUO_MAX = 200
        const val TACTICS_MAX = 6
    }
}
