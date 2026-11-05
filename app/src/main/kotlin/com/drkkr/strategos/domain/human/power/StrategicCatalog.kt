package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.TacticKey
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.DealNumbers

/** Power Mode's hardball tactics. Kept apart from `TacticId` so the Human Layer's catalog and its counts never change. */
enum class StrategicId : TacticKey {
    HOLD_YOUR_NUMBER, FIRM_POSTURE, AGGRESSIVE_ANCHOR, LET_THEM_MOVE_FIRST, STRATEGIC_SILENCE, COLOR_THE_CHOICES, REAL_COMPETITION,
    CREDIBLE_WALKAWAY, CONTROL_THE_CLOCK, DONT_SHOW_YOUR_HAND, GO_TO_THE_DECIDER, BUILD_INDISPENSABILITY, ALLIES_FIRST, STAY_UNREADABLE,
    DISDAIN_THE_JAB
}

/**
 * A STRATEGIC tactic (blueprint §3): the Human Layer's [Tactic] with `ethics = STRATEGIC`, plus [gain] (expected improvement on the
 * 0–10 scale for the user's weighted outcome, a bounded constant) and [exposureLoss] (what being seen through costs in trust and
 * reputation). Instead of the publicity test (I-H3) each one passes the two Power Mode checks: no false statement of material fact,
 * and the other side keeps a real way to say no or walk away. Statements about your OWN intentions or limits are posture, not fact.
 */
data class StrategicTactic(
    val tactic: Tactic, val gain: Double, val exposureLoss: Double,
    /** A move that answers probing (hold your number, silence, don't show your hand): no suspicion term in q; a gain bonus once probing is confirmed. */
    val defensive: Boolean = false
) {
    val id: StrategicId get() = tactic.id as StrategicId

    /** The hint the coach gets. The anchor's carries the opening figure from the deal frame (never the walk-away). */
    fun hint(s: Scenario, ctx: HumanContext?): String =
        if (OPEN_AT in tactic.coachHint) tactic.coachHint.replace(OPEN_AT, StrategicCatalog.frame(s, ctx)?.openAt?.let(DealNumbers::format) ?: "your opening")
        else tactic.coachHint

    companion object { const val OPEN_AT = "{open_at}" }
}

/**
 * The 15 STRATEGIC tactics (blueprint §3). Each precondition reads only `HumanContext`, `DeliveryTags`, the scenario and its
 * `negotiationContext` (through the [DealFrame]). A precondition with no signal from Call 1 is false: nothing is guessed.
 * None of these tells the user to state a false fact, invent an offer or evidence, pose as a friend, exploit a weakness, isolate
 * someone, make them dependent, scapegoat anyone or stage legitimacy: those exist only in the radar and in `DeclineNotes`.
 */
object StrategicCatalog {
    private val N = RelationshipDomain.NEGOTIATION
    private val A = RelationshipDomain.ADVERSARIAL
    private val WORK = setOf(RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.WORK_PEER)

    /** The deal frame read from the saved figures (the message is not needed for a precondition). */
    fun frame(s: Scenario, c: HumanContext?): DealFrame? = DealFrame.build(s.negotiationContext, c, "")

    private fun theirFigureOnTable(s: Scenario) = s.negotiationContext?.counterpartReservation?.let { DealNumbers.parse(it) } != null
    private val OFFER_WORDS = Regex("(?i)\\b(offer\\w*|quot\\w*|price\\w*|proposal|terms|bid)\\b")
    private fun offerOnTable(s: Scenario, c: HumanContext) =
        theirFigureOnTable(s) || s.negotiationContext?.openingAnchor?.isNotBlank() == true || OFFER_WORDS.containsMatchIn(c.statusQuo.orEmpty())
    private fun realFallback(s: Scenario, c: HumanContext) = c.yourAlternative == "strong" || frame(s, c)?.walkAwayCredible == true
    private fun counterpart(s: Scenario) = IntakeChecklist.self(s)?.let { o -> s.players().firstOrNull { it.id != o.id } }
    private fun calmOptions(s: Scenario, a: Action) = s.actionsFor(a.stakeholderId).filter { Postures.of(it) != Postures.REACTIVE }

    /** Someone other than the person in front of the user decides: a higher-authority stakeholder, or a named approver. */
    private fun deciderElsewhere(s: Scenario): Boolean {
        val owner = IntakeChecklist.self(s) ?: return false
        val them = counterpart(s) ?: return false
        val higher = s.stakeholders.any { it.id != owner.id && it.id != them.id && it.authority > them.authority }
        val approver = them.approvalRole.isNotBlank() && !Regex("(?i)final|decid|sign").containsMatchIn(them.approvalRole)
        return higher || approver
    }

    /** A zero-sum contest: across the outcomes the user and the other side move in opposite directions (correlation <= -0.5). */
    private fun zeroSum(s: Scenario): Boolean {
        val owner = IntakeChecklist.self(s) ?: return false
        val them = counterpart(s) ?: return false
        val pairs = s.payoffs.filter { it.stakeholderId == owner.id }.mapNotNull { mine ->
            s.payoffs.firstOrNull { it.stakeholderId == them.id && it.actionProfile == mine.actionProfile }
                ?.let { mine.payoff.weighted(s.weights) to it.payoff.weighted(s.weights) }
        }
        if (pairs.size < 3) return false
        val mx = pairs.map { it.first }.average(); val my = pairs.map { it.second }.average()
        val cov = pairs.sumOf { (x, y) -> (x - mx) * (y - my) }
        val vx = pairs.sumOf { (x, _) -> (x - mx) * (x - mx) }; val vy = pairs.sumOf { (_, y) -> (y - my) * (y - my) }
        return vx > 0 && vy > 0 && cov / Math.sqrt(vx * vy) <= -0.5
    }

    private fun t(id: StrategicId, source: String, domains: Set<RelationshipDomain>, gain: Double, loss: Double, hint: String,
                  defensive: Boolean = false, pre: (HumanContext, Action, Scenario) -> Boolean) =
        StrategicTactic(Tactic(id, source, Ethics.STRATEGIC, publicityPass = true, domains = domains, preconditions = pre,
            adds = emptySet(), removes = emptySet(), coachHint = hint), gain, loss, defensive)

    /** Moves of the same family: the coach gets only the stronger of the two (the card may show both). */
    val SAME_FAMILY: Set<Set<StrategicId>> = setOf(setOf(StrategicId.REAL_COMPETITION, StrategicId.CREDIBLE_WALKAWAY))

    val tactics: List<StrategicTactic> = listOf(
        t(StrategicId.HOLD_YOUR_NUMBER, "48 Laws L3, L4", setOf(N, A) + WORK, 0.8, 0.2,
            "Don't state your bottom line; keep your walk-away number to yourself.", defensive = true) { c, _, s -> frame(s, c)?.walkAway != null },
        t(StrategicId.FIRM_POSTURE, "48 Laws L28; negotiation norm", setOf(N, A), 0.7, 0.5,
            "Present your position as firm, then concede slowly and in shrinking steps, if at all.") { c, _, s -> offerOnTable(s, c) && realFallback(s, c) },
        t(StrategicId.AGGRESSIVE_ANCHOR, "48 Laws L28; Nudge Ch.1 (anchoring)", setOf(N), 1.0, 0.4,
            "Open at ${StrategicTactic.OPEN_AT}, the top of a range you can justify with real data.") { c, _, s -> !theirFigureOnTable(s) && frame(s, c)?.openAt != null },
        t(StrategicId.LET_THEM_MOVE_FIRST, "48 Laws L8", setOf(N) + WORK, 0.6, 0.1,
            "Ask them to name their number or proposal first, before you give yours.") { _, a, s -> !theirFigureOnTable(s) || "gives_first" in DeliveryTags.of(a) },
        t(StrategicId.STRATEGIC_SILENCE, "48 Laws L4", setOf(N, A) + WORK, 0.5, 0.2,
            "Answer in one short line, then stop and let the silence do the work.", defensive = true) { c, a, _ -> "probes_you" in c.theirTactics || "argues" in DeliveryTags.of(a) },
        t(StrategicId.COLOR_THE_CHOICES, "48 Laws L31 (colour the choices; real options only)", setOf(N) + WORK, 0.6, 0.4,
            "Lay out the real options in an order that makes yours the sensible middle choice.") { _, a, s -> calmOptions(s, a).size >= 3 },
        t(StrategicId.REAL_COMPETITION, "48 Laws L20", setOf(N), 1.2, 0.3,
            "Line up real alternatives and say truthfully that you have other real options.") { c, _, s -> realFallback(s, c) },
        t(StrategicId.CREDIBLE_WALKAWAY, "48 Laws L28, L22 (reversed); Nudge Ch.15", setOf(N, A), 1.0, 0.3,
            "Get your real fallback ready, and let them see you could genuinely walk away.") { c, _, s ->
            when (frame(s, c)?.walkAwayCredible) { true -> true; false -> false; null -> c.yourAlternative == "strong" }
        },
        t(StrategicId.CONTROL_THE_CLOCK, "48 Laws L35", setOf(N, A) + WORK, 0.7, 0.3,
            "If time is on your side, slow down; if not, set a real, specific deadline.") { c, _, _ -> c.deadline == "soft" || c.deadline == "hard" || "artificial_deadline" in c.theirTactics },
        t(StrategicId.DONT_SHOW_YOUR_HAND, "48 Laws L30, L4", setOf(N) + WORK, 0.5, 0.2,
            "Keep how much you need this, your urgency and your method to yourself.", defensive = true) { c, _, _ -> c.youDependOnThem == "some" || c.youDependOnThem == "high" || "probes_you" in c.theirTactics },
        t(StrategicId.GO_TO_THE_DECIDER, "48 Laws L23, L42 (benign half), L34", setOf(N) + WORK, 0.8, 0.5,
            "Take it to the person who actually decides, with your manager's knowledge if you have one.") { _, _, s -> deciderElsewhere(s) },
        t(StrategicId.BUILD_INDISPENSABILITY, "48 Laws L11 (legitimate form)", WORK, 0.9, 0.2,
            "Before the ask, own something critical they'd struggle to replace, like a key system.") { c, _, _ -> c.theyDependOnYou == "none" || c.theyDependOnYou == "some" },
        t(StrategicId.ALLIES_FIRST, "48 Laws L18, L42, L43", setOf(A) + WORK, 0.8, 0.2,
            "Before the meeting, line up one or two supporters who already agree with you.") { _, _, s -> s.players().size >= 3 || s.stakeholders.any { it.gameRole == GameRole.INFLUENCER } },
        t(StrategicId.STAY_UNREADABLE, "48 Laws L17, L48 (zero-sum only)", setOf(A), 0.5, 0.4,
            "Vary your approach and don't telegraph your next move.") { _, _, s -> zeroSum(s) },
        t(StrategicId.DISDAIN_THE_JAB, "48 Laws L36, L39 (calm half)", setOf(A) + WORK, 0.5, 0.1,
            "Don't answer the jab; respond calmly to the substance later.") { c, _, _ ->
            c.theirTactics.any { it == "public_undermining" || it == "creates_chaos" } || (c.theirStateLevel ?: 0.0) >= 2.0
        }
    )
}
