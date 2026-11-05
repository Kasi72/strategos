package com.drkkr.strategos.domain.human

/**
 * The closed vocabularies Call 1 may use for the Human Layer (I-H7: the model only classifies, Kotlin decides).
 * Anything outside a list is dropped, never guessed; same idea as `Postures.clean`.
 */
object HumanVocab {
    val RELATIONSHIP = setOf(
        "spouse", "partner", "parent", "child", "sibling", "relative", "friend", "manager", "report",
        "peer", "cofounder", "client", "vendor", "landlord", "opponent", "other"
    )
    val POWER_RELATIVE = setOf("they_higher", "equal", "you_higher")
    val DEPENDENCE = setOf("none", "some", "high")
    val ALTERNATIVE = setOf("none", "weak", "strong")
    val SETTING = setOf("private", "semi_public", "public")
    val STATUS_QUO_FOR_THEM = setOf("keeps_something", "loses_something", "neutral")
    val DEADLINE = setOf("none", "soft", "hard")
    val VULNERABLE = setOf("none", "child", "elder_in_care", "unwell", "in_crisis")
    val YOUR_STATE = setOf("calm", "tense", "upset", "furious")
    val THEIR_STATE = YOUR_STATE + "unknown"
    val GOAL_IS = setOf("break_from_group", "lasting_commitment", "other")

    /** How a move of "You" would be carried out. Stored on the action as `[f:tag,tag]` (see [DeliveryTags]). */
    val OPTION_TAGS = setOf(
        "public", "corrects_them", "credits_them", "argues", "demonstrates", "asks_them_to_give_up", "future_commitment",
        "sets_default", "two_choices", "open_ended", "their_interest", "appeals_to_fairness_or_gratitude", "gives_first",
        "withdraws", "immediate", "written", "irreversible"
    )

    /** Behaviour the user DESCRIBED in the other person (actions, never intentions). "none" means nothing to report. */
    val THEIR_TACTICS = setOf(
        "isolates_you", "keeps_you_dependent", "gives_then_demands", "controls_the_options", "guilt_or_gratitude",
        "silent_treatment", "public_undermining", "moves_goalposts", "flattery_before_ask", "creates_chaos",
        "artificial_deadline", "extreme_anchor", "self_serving_default", "adds_friction_to_your_exit",
        "reaches_you_through_others", "hot_cold", "none"
    )

    /** Smoothed-state scale: 0 = calm … 3 = furious. */
    fun stateLevel(state: String?): Double? = when (state) {
        "calm" -> 0.0; "tense" -> 1.0; "upset" -> 2.0; "furious" -> 3.0
        else -> null
    }

    /** Lowercases, trims and maps spaces and hyphens to `_`; null when the result is not in [vocab]. */
    fun clean(raw: String?, vocab: Set<String>): String? =
        raw?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_")?.takeIf { it in vocab }
}
