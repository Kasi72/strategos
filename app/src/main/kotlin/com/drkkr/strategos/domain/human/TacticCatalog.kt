package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario

/** BENIGN works anywhere; CONTEXT only in work, negotiation or adversarial settings; DECEPTIVE is never offered (I-H2). */
enum class Ethics { BENIGN, CONTEXT, DECEPTIVE }

enum class TacticId {
    DEFAULT_WITH_DEADLINE, TWO_GOOD_OPTIONS, FUTURE_COMMITMENT, WHEN_AND_HOW, MAKE_IT_CONCRETE, THEIR_INTEREST_FRAME,
    PRIVATE_AND_CREDIT, SHOW_DONT_ARGUE, SAY_LESS, STEP_BACK, CONCEDE_SMALL_FIRST, NAME_YOUR_LEVERAGE, POSITIVE_NORM,
    SHARED_OUTSIDE_YARDSTICK, CHANGE_IN_STEPS, PUT_IT_IN_WRITING, ASK_IF_OPEN_FIRST, STOP_AT_THE_MARK, APOLOGISE_FOR_YOUR_PART
}

/**
 * A way of DELIVERING the engine's pick. It is never a new move (I-H1).
 * [publicityPass]: the user could say it out loud to the other person ("I suggested Friday as the default because...") and it
 * would still work and still be fair, and it leaves them a cheap way to say no (I-H3).
 * [adds] / [removes] are the option tags the tactic turns on / off; the planner flips them in memory to see what it would change.
 * [coachHint] is all the coach ever hears about it: plain words, never a law, a book or the tactic's name.
 */
data class Tactic(
    val id: TacticId,
    val source: String,
    val ethics: Ethics,
    val publicityPass: Boolean,
    val domains: Set<RelationshipDomain>,
    val preconditions: (HumanContext, Action, Scenario) -> Boolean,
    val adds: Set<String>,
    val removes: Set<String>,
    val coachHint: String
)

object TacticCatalog {
    private val ALL = RelationshipDomain.values().toSet()
    private val WORK_ETC = setOf(RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.WORK_PEER, RelationshipDomain.NEGOTIATION)
    private val NEVER = { _: HumanContext, _: Action, _: Scenario -> false }          // no signal from Call 1 yet: dormant, never guessed
    private fun tags(a: Action) = DeliveryTags.of(a)
    /** Words that say an agreement or an amount is in play. Read only from the status quo and the saved description. */
    private val AGREEMENT = Regex("(?i)\\b(loan|lend|lent|borrow\\w*|repay\\w*|pay(?:ing)? (?:it )?back|owe\\w*|money|lakh|rupees?|split|equity|shares?|verbal\\w*|promis\\w*|handshake|not written|nothing is written|agreement|contract|deposit|terms)\\b")
    /** The paths actually worth offering: the user's own options minus the tempting reactive one. */
    private fun calmOptions(s: Scenario, a: Action) = s.actionsFor(a.stakeholderId).filter { com.drkkr.strategos.domain.agent.Postures.of(it) != com.drkkr.strategos.domain.agent.Postures.REACTIVE }

    val offered: List<Tactic> = listOf(
        Tactic(TacticId.DEFAULT_WITH_DEADLINE, "Nudge Ch.5 (defaults); Ch.1 (\"yeah, whatever\")", Ethics.BENIGN, true, ALL,
            { _, a, _ -> "open_ended" in tags(a) && "sets_default" !in tags(a) },
            setOf("sets_default"), setOf("open_ended"),
            "Say what you'll do and by when, unless they'd prefer something else; make objecting easy."),
        Tactic(TacticId.TWO_GOOD_OPTIONS, "Nudge Ch.5/7/9 (structured choices); the open inverse of 48 Laws L31", Ethics.BENIGN, true, ALL,
            { _, a, s -> calmOptions(s, a).size > 2 && "two_choices" !in tags(a) },
            setOf("two_choices"), emptySet(),
            "Offer two options you'd both be happy with, and say they're welcome to suggest a third."),
        Tactic(TacticId.FUTURE_COMMITMENT, "Nudge Ch.6 (Save More Tomorrow)", Ethics.BENIGN, true, ALL,
            { _, a, _ -> "asks_them_to_give_up" in tags(a) && "future_commitment" !in tags(a) },
            setOf("future_commitment"), emptySet(),
            "Ask them to agree now to a change that starts later, or when something specific happens."),
        Tactic(TacticId.WHEN_AND_HOW, "Nudge Ch.3 (channel factors)", Ethics.BENIGN, true, ALL,
            { c, _, _ -> (c.stepsForThemToSayYes ?: 0) >= 2 },
            emptySet(), emptySet(),
            "Agree a specific day and way to do it, and remove the first small obstacle."),
        Tactic(TacticId.MAKE_IT_CONCRETE, "Nudge Ch.5 (mappings, salience)", Ethics.BENIGN, true, ALL, NEVER,
            emptySet(), emptySet(),
            "Put the quietly growing cost into numbers or units they care about."),
        Tactic(TacticId.THEIR_INTEREST_FRAME, "48 Laws L13; L43", Ethics.BENIGN, true, ALL,
            { _, a, _ -> "appeals_to_fairness_or_gratitude" in tags(a) },
            setOf("their_interest"), setOf("appeals_to_fairness_or_gratitude"),
            "Lead with what they gain or protect, not with what they owe you."),
        Tactic(TacticId.PRIVATE_AND_CREDIT, "48 Laws L1, L24", Ethics.BENIGN, true, ALL,
            { c, a, _ -> "corrects_them" in tags(a) && c.theirPower == "they_higher" },
            setOf("credits_them"), setOf("public"),
            "Say it in private, and credit their thinking first."),
        Tactic(TacticId.SHOW_DONT_ARGUE, "48 Laws L9; L37", Ethics.BENIGN, true, ALL,
            { _, a, s -> "argues" in tags(a) || s.players().firstOrNull { it.id != a.stakeholderId }?.probSoftensOnRepair?.let { it < 0.45 } == true },
            setOf("demonstrates"), setOf("argues"),
            "Do the visible thing, or offer a small proof, instead of making the case again."),
        Tactic(TacticId.SAY_LESS, "48 Laws L4", Ethics.BENIGN, true, ALL,
            { _, a, _ -> "argues" in tags(a) },
            emptySet(), emptySet(),
            "Say one calm sentence, then stop; don't keep justifying yourself."),
        Tactic(TacticId.STEP_BACK, "48 Laws L16, L8", Ethics.CONTEXT, true, setOf(RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.WORK_PEER, RelationshipDomain.NEGOTIATION, RelationshipDomain.ADVERSARIAL),
            { c, _, _ -> "silent_treatment" in c.confirmedTactics() },
            setOf("withdraws"), emptySet(),
            "Stop chasing, say when you're available, and let them come to you."),
        Tactic(TacticId.CONCEDE_SMALL_FIRST, "Reciprocity; 48 Laws L43 (L12 is the deceptive form and is not used)", Ethics.CONTEXT, true, WORK_ETC,
            { c, a, _ -> (c.theirStateLevel ?: 0.0) >= 2.0 && "gives_first" !in tags(a) },
            setOf("gives_first"), emptySet(),
            "Give one real, low-cost concession before you make the firm ask."),
        Tactic(TacticId.NAME_YOUR_LEVERAGE, "48 Laws L11 (awareness form)", Ethics.CONTEXT, true, WORK_ETC,
            { c, a, _ -> c.theyDependOnYou == "high" && "appeals_to_fairness_or_gratitude" in tags(a) },
            emptySet(), emptySet(),
            "Frame the ask around what your role makes possible, never as a threat."),
        Tactic(TacticId.POSITIVE_NORM, "Nudge Ch.3 (social norms: tax letters, energy study)", Ethics.BENIGN, true, ALL, NEVER,
            emptySet(), emptySet(),
            "Point to what most people in their position really do, and say it with approval."),
        Tactic(TacticId.SHARED_OUTSIDE_YARDSTICK, "Nudge Ch.15 (self-serving bias in bargaining)", Ethics.BENIGN, true, ALL,
            { c, _, _ -> c.confirmedTactics().any { it == "extreme_anchor" || it == "moves_goalposts" } },
            emptySet(), emptySet(),
            "Suggest an outside reference you can both check, like a market rate or a mediator's range."),
        Tactic(TacticId.CHANGE_IN_STEPS, "48 Laws L45; Nudge Ch.6", Ethics.BENIGN, true, ALL,
            { c, a, _ -> "asks_them_to_give_up" in tags(a) && (c.stepsForThemToSayYes ?: 0) >= 3 },
            emptySet(), emptySet(),
            "Start with a smaller first step, and agree the next step in advance."),
        Tactic(TacticId.PUT_IT_IN_WRITING, "Nudge Ch.5 (expect error), Ch.15; 48 Laws L40", Ethics.BENIGN, true, ALL,
            { c, a, s ->
                // A family loan, a co-founder split, a verbal agreement, a plan with a forgettable last step. Read from the
                // relationship, any negotiation figures and the status quo; no money dimension needed (the live model often draws none).
                val domain = RelationshipDomains.of(c.relationship, null)
                val inScope = domain in setOf(RelationshipDomain.FAMILY, RelationshipDomain.FRIENDSHIP, RelationshipDomain.WORK_PEER, RelationshipDomain.NEGOTIATION)
                val agreement = c.relationship == "cofounder" || s.negotiationContext?.hasContent() == true ||
                    AGREEMENT.containsMatchIn(listOfNotNull(c.statusQuo, s.stakes, s.description).joinToString(" "))
                "written" !in tags(a) && (c.relationship == "cofounder" || inScope) && agreement
            },
            setOf("written"), emptySet(),
            "Send a one-line written summary with the terms and a date to review it."),
        Tactic(TacticId.ASK_IF_OPEN_FIRST, "Nudge Ch.3 (mere measurement)", Ethics.BENIGN, true, ALL,
            { c, a, _ -> "asks_them_to_give_up" in tags(a) && c.deadline != "hard" },
            emptySet(), emptySet(),
            "Ask first whether they'd be open to looking at it, before the details."),
        Tactic(TacticId.STOP_AT_THE_MARK, "48 Laws L47, L29", Ethics.BENIGN, true, ALL, NEVER,
            emptySet(), emptySet(),
            "Lock in what you got, thank them, and stop there."),
        Tactic(TacticId.APOLOGISE_FOR_YOUR_PART, "Nudge Ch.14 (apologies); relational repair", Ethics.BENIGN, true,
            ALL - RelationshipDomain.ADVERSARIAL, NEVER,
            emptySet(), emptySet(),
            "Apologise briefly for your own part, specifically, with no 'but'.")
    )

    /**
     * Law numbers (48 Laws of Power) that are deception or manipulation. They are NEVER offered; they exist only so the radar
     * can recognise them when someone does them to the user (Appendix A). L15 is excluded entirely.
     */
    val defenceOnly: Set<String> = setOf("L3", "L7", "L12", "L14", "L21", "L26", "L27", "L31", "L32", "L33", "L39", "L44")
}
