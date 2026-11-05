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
    val tacticSuccess: Map<String, List<Double>> = emptyMap(),
    /**
     * Readings of the soft fields (and of a relationship in a new domain) that have been seen once and are waiting for a
     * second turn to repeat them, keyed by field name. See [merge].
     */
    val pending: Map<String, String> = emptyMap(),
    /**
     * The move that was held back for the user to cool off ([Timing.AFTER_COOLING]). The next turns ask "did you send it?" until
     * they report how it went. An empty string in a patch clears it.
     */
    val pendingCooling: String? = null,
    /** How many turns each of [theirTactics] was described on. A pattern is only shown to the user once it has been seen twice. */
    val tacticTurns: Map<String, Int> = emptyMap(),
    /** Messages in a row with no coercive tag since one was last described; routing to support expires at [CoerciveControl.EXPIRES_AFTER]. Null = not counting. */
    val routingQuietTurns: Int? = null,
    /** How the user already tried (or is about to), in the same words as an option's tags. Habits in it steer the delivery of the next move. */
    val lastAttemptTags: List<String> = emptyList(),
    /** The last three turns' readings of [lastAttemptTags], newest first. Habits older than three turns drop out. */
    val attemptHistory: List<List<String>> = emptyList(),
    /** The reply whose outcome was last counted into [tacticSuccess], so one reply is never counted twice. */
    val learnedFromMessage: String? = null,
    /** Fields the user has kept or fixed in the Review sheet. The model never overwrites them again (I-H8: the user's word wins). */
    val userFixed: Set<String> = emptySet(),
    /** Power Mode: what the user asked the app to help them do this turn, when manipulative. Per turn: never carried forward. */
    val userAsksFor: String? = null
) {
    /** Tactics described on at least [minTurns] turns, newest first. */
    fun confirmedTactics(minTurns: Int = 2): List<String> = theirTactics.filter { (tacticTurns[it] ?: 0) >= minTurns }

    /**
     * Folds a newer reading into this one. A non-null newer value replaces the old; a null never erases it. The two
     * state levels are smoothed (half new, half old). Tactics are the union, newest first. Learned priors are kept.
     *
     * Two kinds of value are slower to change, because a single message is a weak signal (seen live: a sibling read as a
     * friend on one turn; alternatives and setting guessed):
     *  - `relationship`: the saved one is kept. A value in a DIFFERENT [RelationshipDomain] replaces it only when it
     *    arrives on two turns in a row; drift inside one domain (spouse, partner) never does. "other" says nothing.
     *  - setting, dependencies, alternatives: a new value is held in [pending] and becomes the saved one only when the next
     *    turn repeats it; a different value restarts the wait. A turn that omits the field neither confirms nor resets.
     */
    fun merge(incoming: HumanContext): HumanContext {
        // The user's own word wins: a field they kept or fixed ignores what the model reads from now on.
        val fixed = userFixed + incoming.userFixed
        val newer = if (fixed.isEmpty()) incoming else incoming.copy(
            relationship = incoming.relationship.takeUnless { "relationship" in fixed }, theirPower = incoming.theirPower.takeUnless { "theirPower" in fixed },
            setting = incoming.setting.takeUnless { "setting" in fixed }, theyDependOnYou = incoming.theyDependOnYou.takeUnless { "theyDependOnYou" in fixed },
            youDependOnThem = incoming.youDependOnThem.takeUnless { "youDependOnThem" in fixed }, yourAlternative = incoming.yourAlternative.takeUnless { "yourAlternative" in fixed },
            theirAlternative = incoming.theirAlternative.takeUnless { "theirAlternative" in fixed }, userFixed = fixed
        )
        val pend = pending.toMutableMap()
        fixed.forEach { pend.remove(it) }
        val rel = mergeRelationship(relationship, newer.relationship, pend)
        val setting = soft("setting", setting, newer.setting, pend)
        val theyDepend = soft("theyDependOnYou", theyDependOnYou, newer.theyDependOnYou, pend)
        val youDepend = soft("youDependOnThem", youDependOnThem, newer.youDependOnThem, pend)
        val yourAlt = soft("yourAlternative", yourAlternative, newer.yourAlternative, pend)
        val theirAlt = soft("theirAlternative", theirAlternative, newer.theirAlternative, pend)
        return mergeRest(newer, rel, setting, theyDepend, youDepend, yourAlt, theirAlt, pend)
    }

    private fun mergeRest(
        newer: HumanContext, rel: String?, setting: String?, theyDepend: String?, youDepend: String?,
        yourAlt: String?, theirAlt: String?, pend: Map<String, String>
    ): HumanContext = HumanContext(
        relationship = rel,
        theirPower = newer.theirPower ?: theirPower,
        theyDependOnYou = theyDepend,
        youDependOnThem = youDepend,
        yourAlternative = yourAlt,
        theirAlternative = theirAlt,
        setting = setting,
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
        tacticSuccess = tacticSuccess + newer.tacticSuccess,
        pending = pend,
        pendingCooling = if (newer.pendingCooling == "") null else newer.pendingCooling ?: pendingCooling,
        tacticTurns = tacticTurns + newer.theirTactics.distinct().associateWith { (tacticTurns[it] ?: 0) + 1 },
        routingQuietTurns = newer.routingQuietTurns ?: routingQuietTurns,
        // A real turn carries the whole history (so an empty union can legitimately replace an old one); a bare patch only adds.
        lastAttemptTags = if (newer.attemptHistory.isNotEmpty()) newer.lastAttemptTags else newer.lastAttemptTags.ifEmpty { lastAttemptTags },
        attemptHistory = newer.attemptHistory.ifEmpty { attemptHistory },
        learnedFromMessage = newer.learnedFromMessage ?: learnedFromMessage,
        userFixed = userFixed + newer.userFixed,
        userAsksFor = newer.userAsksFor
    )

    private fun soft(key: String, old: String?, new: String?, pend: MutableMap<String, String>): String? = when {
        new == null -> old
        new == old -> { pend.remove(key); old }
        pend[key] == new -> { pend.remove(key); new }
        else -> { pend[key] = new; old }
    }

    private fun mergeRelationship(old: String?, new: String?, pend: MutableMap<String, String>): String? {
        val key = "relationship"
        if (new == null) return old
        if (old == null || old == "other") { pend.remove(key); return new }      // nothing real saved yet
        if (new == "other") return old
        val newDomain = RelationshipDomains.of(new, null)
        if (newDomain == RelationshipDomains.of(old, null)) { pend.remove(key); return old }
        val waiting = pend[key]?.let { RelationshipDomains.of(it, null) }
        return if (waiting == newDomain) { pend.remove(key); new } else { pend[key] = new; old }
    }

    private fun smooth(old: Double?, new: Double?): Double? = when {
        new == null -> old
        old == null -> new
        else -> 0.5 * new + 0.5 * old
    }

    companion object {
        const val STATUS_QUO_MAX = 200
        const val TACTICS_MAX = 6
    }

    /** The Review sheet's Keep / Fix: the user takes the field as theirs ([clear] also empties it). */
    fun fix(key: String, clear: Boolean): HumanContext {
        val base = copy(userFixed = userFixed + key)
        if (!clear) return base
        return when (key) {
            "relationship" -> base.copy(relationship = null); "theirPower" -> base.copy(theirPower = null); "setting" -> base.copy(setting = null)
            "theyDependOnYou" -> base.copy(theyDependOnYou = null); "youDependOnThem" -> base.copy(youDependOnThem = null)
            "yourAlternative" -> base.copy(yourAlternative = null); "theirAlternative" -> base.copy(theirAlternative = null)
            else -> base
        }
    }
}
