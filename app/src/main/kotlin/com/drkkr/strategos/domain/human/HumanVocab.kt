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

    /** Tags only the planner uses (never asked of Call 1): what a delivery tactic adds so the humanised view can measure it. */
    val PLANNER_TAGS = setOf("outside_yardstick", "names_leverage")

    /** Behaviour the user DESCRIBED in the other person (actions, never intentions). "none" means nothing to report. */
    val THEIR_TACTICS = setOf(
        "isolates_you", "keeps_you_dependent", "gives_then_demands", "controls_the_options", "guilt_or_gratitude",
        "silent_treatment", "public_undermining", "moves_goalposts", "flattery_before_ask", "creates_chaos",
        "artificial_deadline", "extreme_anchor", "self_serving_default", "adds_friction_to_your_exit",
        "reaches_you_through_others", "hot_cold", "none"
    )

    /**
     * Power Mode's addition to [THEIR_TACTICS]: `probes_you` ("unusually curious about your plans or limits", 48 Laws L14, defensive).
     * Kept as its own set so the Human Layer's vocabulary (and its counts, radar and prompt tests) stays exactly as it was; Call 1's
     * reader accepts both.
     */
    val POWER_THEIR_TACTICS = setOf("probes_you")

    /** Power Mode: the manipulative help a user may ask for. Never planned; each gets a fixed decline note (`DeclineNotes`). */
    /** Intent Dispatch ID2.1: what the user asks for this turn (BLUEPRINT §4.1). */
    val INTENTS = setOf("standard", "what_if", "compare", "top_options", "their_reaction", "plan_ahead")
    val USER_ASKS_FOR = setOf("none", "false_facts", "exploit_weakness", "isolate", "make_dependent", "fake_friend", "scapegoat")

    /** Smoothed-state scale: 0 = calm … 3 = furious. */
    fun stateLevel(state: String?): Double? = when (state) {
        "calm" -> 0.0; "tense" -> 1.0; "upset" -> 2.0; "furious" -> 3.0
        else -> null
    }

    /** Lowercases, trims and maps spaces and hyphens to `_`; null when the result is not in [vocab]. */
    fun clean(raw: String?, vocab: Set<String>): String? =
        raw?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_")?.takeIf { it in vocab }
}
