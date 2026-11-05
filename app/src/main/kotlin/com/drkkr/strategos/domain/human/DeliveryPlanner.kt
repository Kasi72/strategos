package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario

/** When to say it. NOW unless [HotStateGate] says to let them cool off (the user) or wait for calm (the other person). */
enum class Timing { NOW, AFTER_COOLING, WHEN_CALM }

/** Where to say it. */
enum class Channel { PRIVATE, WRITTEN }

/** A chosen way of delivering the pick. [hint] is all the coach hears about it; [deltaP] is how much it raises the chance they say yes. */
data class PlannedTactic(val id: TacticKey, val hint: String, val deltaP: Double)

data class Delivery(
    val tactics: List<PlannedTactic> = emptyList(),
    val timing: Timing = Timing.NOW,
    val channel: Channel? = null,
    val sayLess: Boolean = false,
    val defaultOffer: String? = null,
    val optionsToOffer: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = tactics.isEmpty() && channel == null && !sayLess && defaultOffer == null && optionsToOffer.isEmpty() && timing == Timing.NOW
}

/**
 * Keeps the engine's pick (I-H1) and chooses up to two ways of DELIVERING it that raise the chance it lands.
 * For each tactic that passes the ethics gate and its own condition, the recommended move's tags are flipped in memory, the
 * humanised view is rebuilt, and the change in P(they say yes) is the tactic's gain. Nothing is saved.
 */
object DeliveryPlanner {
    /** Pairs that do the same job; only the one with the larger gain is kept. */
    private val CONFLICTS = listOf(
        TacticId.TWO_GOOD_OPTIONS to TacticId.DEFAULT_WITH_DEADLINE,
        TacticId.STEP_BACK to TacticId.DEFAULT_WITH_DEADLINE,
        TacticId.SAY_LESS to TacticId.MAKE_IT_CONCRETE
    )

    /** Tactics that cannot be measured by the model: they enter on their own condition, below any tactic with a gain. */
    private val NO_GAIN_PRIORITY = listOf(TacticId.PUT_IT_IN_WRITING, TacticId.ASK_IF_OPEN_FIRST, TacticId.APOLOGISE_FOR_YOUR_PART, TacticId.STOP_AT_THE_MARK)

    /**
     * What a person does once tends to be what they do next. The habits in the user's described last attempt (the same tag words) are
     * inherited by the move about to be delivered, so the planner can see and replace them: a gratitude appeal becomes "lead with what
     * they gain", a public correction becomes "say it in private". Only habits carry over, not everything they did.
     */
    private val HABIT = setOf("public", "corrects_them", "argues", "appeals_to_fairness_or_gratitude", "open_ended", "asks_them_to_give_up", "immediate")

    internal fun carrying(hc: HumanContext, recommended: Action): Action =
        hc.lastAttemptTags.filter { it in HABIT }.toSet().let { if (it.isEmpty()) recommended else DeliveryTags.with(recommended, it, emptySet()) }

    private fun conflicts(a: TacticKey, b: TacticKey) = CONFLICTS.any { (x, y) -> (a == x && b == y) || (a == y && b == x) }

    /** [raw] is the analysis view BEFORE the humanised view; [second] is the other calm move (complement, else fallback). */
    fun plan(
        raw: Scenario, hc: HumanContext, recommended: Action, second: Action? = null, support: Boolean = false,
        beta: Double = 1.0, c: HumanConstants = HumanConstants.DEFAULT, userSaysNow: Boolean = false
    ): Delivery {
        val domain = RelationshipDomains.of(hc.relationship, null)
        if (EthicsGate.nothingAllowed(hc, domain, support)) return Delivery()
        val scored = candidates(raw, hc, recommended, support, beta)
        val chosen = resolve(scored, c)
        return assemble(raw, hc, recommended, second, chosen, support, c, userSaysNow)
    }

    private fun gainOf(withCtx: Scenario, recommended: Action, t: Tactic, beta: Double, baseP: Double?): Double =
        if (t.id in NO_GAIN_PRIORITY || baseP == null) 0.0 else {
            val flipped = DeliveryTags.with(recommended, t.adds, t.removes)
            val view = HumanizedView.apply(withCtx.copy(actions = withCtx.actions.map { if (it.id == recommended.id) flipped else it }))
            ((ResponseModel.receptiveProbability(view, recommended.stakeholderId, recommended.id, beta) ?: baseP) - baseP) *
                TacticLearning.multiplier(withCtx.humanContext?.tacticSuccess.orEmpty(), t.id.name)         // what worked with THIS person
        }

    /** Every tactic that passes the gate and its condition, with the gain it would give (0 for the ones the model cannot measure). */
    fun candidates(raw: Scenario, hc: HumanContext, recommended: Action, support: Boolean, beta: Double = 1.0): List<Pair<Tactic, Double>> {
        val domain = RelationshipDomains.of(hc.relationship, null)
        val moved = carrying(hc, recommended)
        val withCtx = raw.copy(humanContext = hc, actions = raw.actions.map { if (it.id == recommended.id) moved else it })
        val baseP = ResponseModel.receptiveProbability(HumanizedView.apply(withCtx), recommended.stakeholderId, recommended.id, beta)
        return TacticCatalog.offered
            .filter { EthicsGate.allowed(it, hc, domain, support) && it.preconditions(hc, moved, withCtx) }
            .filter { it.id != TacticId.SAY_LESS }                                    // a flag, see [assemble]
            .map { t -> t to gainOf(withCtx, moved, t, beta, baseP) }
    }

    /** One row of the diagnostic table: why a tactic was or was not offered. [deltaP] is null for a precondition-only tactic or a flag. */
    data class Diagnosis(val id: TacticKey, val gatePass: Boolean, val preconditionPass: Boolean, val deltaP: Double?, val preconditionOnly: Boolean)

    /** For the live replay and for tuning: every tactic, whether it passed the ethics gate and its own condition, and its gain. */
    fun diagnose(raw: Scenario, hc: HumanContext, recommended: Action, support: Boolean, beta: Double = 1.0): List<Diagnosis> {
        val domain = RelationshipDomains.of(hc.relationship, null)
        val moved = carrying(hc, recommended)
        val withCtx = raw.copy(humanContext = hc, actions = raw.actions.map { if (it.id == recommended.id) moved else it })
        val baseP = ResponseModel.receptiveProbability(HumanizedView.apply(withCtx), recommended.stakeholderId, recommended.id, beta)
        return TacticCatalog.offered.map { t ->
            val gate = EthicsGate.allowed(t, hc, domain, support)
            val pre = t.preconditions(hc, moved, withCtx)
            val noGain = t.id in NO_GAIN_PRIORITY || t.id == TacticId.SAY_LESS
            Diagnosis(t.id, gate, pre, if (gate && pre && !noGain) gainOf(withCtx, moved, t, beta, baseP) else null, noGain)
        }
    }

    /** Picks at most [HumanConstants.maxTactics]: the biggest gains first, no conflicting pair, the unmeasurable ones last. */
    fun resolve(scored: List<Pair<Tactic, Double>>, c: HumanConstants = HumanConstants.DEFAULT): List<Pair<Tactic, Double>> {
        val picked = mutableListOf<Pair<Tactic, Double>>()
        val withGain = scored.filter { it.first.id !in NO_GAIN_PRIORITY && it.second >= c.deltaPMin }
            .sortedByDescending { it.second }                                         // stable: ties keep catalog order
        for (s in withGain) {
            if (picked.size >= c.maxTactics) break
            if (picked.none { conflicts(it.first.id, s.first.id) }) picked += s
        }
        for (id in NO_GAIN_PRIORITY) {
            if (picked.size >= c.maxTactics) break
            scored.firstOrNull { it.first.id == id }?.let { if (picked.none { p -> conflicts(p.first.id, id) }) picked += it }
        }
        return picked
    }

    /** Turns the chosen tactics into the delivery block: channel, offers, and the say-less flag. */
    fun assemble(
        raw: Scenario, hc: HumanContext, recommended: Action, second: Action?, chosen: List<Pair<Tactic, Double>>,
        support: Boolean = false, c: HumanConstants = HumanConstants.DEFAULT, userSaysNow: Boolean = false
    ): Delivery {
        val domain = RelationshipDomains.of(hc.relationship, null)
        if (EthicsGate.nothingAllowed(hc, domain, support)) return Delivery()
        val ids = chosen.map { it.first.id }
        val tags = DeliveryTags.of(carrying(hc, recommended))
        val faceCost = "corrects_them" in tags && hc.theirPower == "they_higher" && "credits_them" !in tags
        val numbers = raw.negotiationContext?.hasContent() == true
        val channel = when {
            (hc.theirStateLevel ?: 0.0) >= 2.0 -> Channel.WRITTEN                    // a hot counterpart: put it in writing, not face to face
            faceCost -> Channel.PRIVATE
            numbers -> Channel.WRITTEN
            else -> null
        }
        val sayLess = TacticCatalog.offered.single { it.id == TacticId.SAY_LESS }.let {
            EthicsGate.allowed(it, hc, domain, support) && it.preconditions(hc, carrying(hc, recommended), raw.copy(humanContext = hc))
        } && TacticId.MAKE_IT_CONCRETE !in ids
        return Delivery(
            tactics = chosen.map { (t, gain) -> PlannedTactic(t.id, t.coachHint, gain) },
            timing = HotStateGate.timing(hc, recommended, support, userSaysNow), channel = channel, sayLess = sayLess,
            defaultOffer = recommended.label.takeIf { TacticId.DEFAULT_WITH_DEADLINE in ids },
            optionsToOffer = if (TacticId.TWO_GOOD_OPTIONS in ids) listOfNotNull(recommended.label, second?.label) else emptyList()
        )
    }
}
