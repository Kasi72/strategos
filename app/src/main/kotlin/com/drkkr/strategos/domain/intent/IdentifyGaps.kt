package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.IntakeGap
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.agent.StressResult
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.DealNumbers

/** One plain-words question for the end of the reply (or, by the D3 rule, instead of it). [id] names the gap. */
data class NextQuestion(val id: String, val text: String)

/**
 * Intent Dispatch ID2.4 (BLUEPRINT §4.4): at most one question per turn (I-D6), a fixed template per gap. Priority: a gap
 * whose answer would flip the pick ("One thing would change my advice: …"), then the walk-away in a negotiation, then who
 * decides, then the rest. A question already asked in this thread is never asked again. Pure.
 */
object IdentifyGaps {
    const val FLIP_LEAD = "One thing would change my advice: "

    /** The gaps Kotlin can test both ways (ID1.6), and the stress test's flip on the other person's reply. */
    const val YOUR_FALLBACK = "your_fallback"
    const val THEIR_FALLBACK = "their_fallback"
    const val THEIR_REPLY = "their_reply"
    const val WALK_AWAY = "walk_away"
    const val DECIDER = "decider"
    const val LIMITS = "limits"

    /** Every template, for the banned-words test. `{name}` is the other person's name as the user gave it. */
    val TEMPLATES: Map<String, String> = linkedMapOf(
        YOUR_FALLBACK to FLIP_LEAD + "if this falls through, what would you do instead?",
        THEIR_FALLBACK to FLIP_LEAD + "if you two don't agree, what could {name} do instead?",
        THEIR_REPLY to FLIP_LEAD + "when you push on something like this, does {name} usually engage or dig in?",
        WALK_AWAY to "Where's the point you'd say no and walk away?",
        DECIDER to "Does {name} make the final call, or does someone else decide?",
        "wants_fears" to "What does {name} want most here, and what worries them?",
        "their_options" to "How might {name} respond?",
        "my_options" to "What else could you do here, including waiting?",
        "goal" to "What would a good outcome look like for you?",
        LIMITS to "Is anything off the table for you here?"
    )

    /** Every limits question carries this, so it is never asked twice even when the options named in it change. */
    const val LIMITS_MARK = "off the table for you"

    /** Intake gaps that become a question, in priority order after the flip, walk-away and decider gaps. */
    private val REST = listOf("wants_fears", "their_options", "my_options", "goal", LIMITS)

    /** An option named in the limits question: its main clause, short enough to read in one breath. */
    private const val MAX_NAMED_OPTION_CHARS = 80
    /** Two named options only while the whole question stays readable on a phone; otherwise the firmest one alone. */
    private const val MAX_TWO_OPTION_CHARS = 150
    /** Where an option's main clause ends: an aside, an example, an alternative ("…, or snap at him"). */
    private val CLAUSE_END = Regex("""\s+\(|,\s|\s[—–-]\s|;\s|\s+or\s|\s+e\.g\.""")

    /**
     * The limits question in the user's own situation: names one or two of their options, firm before open (the moves people
     * most often rule out), e.g. "Is either of these off the table for you: tell Arjun plainly that it can't keep happening,
     * or propose a weeknight quiet-hours rule?". The reactive move is never named (it is never advised). The short generic
     * template when no option's main clause is plain enough (no figure, no question, no banned word). [advised] are the moves
     * this reply recommends: never named, or the question would undercut its own advice (seen live, 8 Nov).
     */
    fun limitsQuestion(view: Scenario, advised: Set<String> = emptySet()): NextQuestion {
        val skip = advised.map { it.trim().lowercase() }.toSet()
        val you = IntakeChecklist.self(view)
        val order = listOf(Postures.BOUNDARY, Postures.REPAIR, null)
        val named = you?.let { view.actionsFor(it.id) }.orEmpty()
            .filter { Postures.of(it) != Postures.REACTIVE && it.label.trim().lowercase() !in skip }
            .sortedBy { order.indexOf(Postures.of(it)).let { i -> if (i < 0) order.size else i } }
            .map { mainClause(it.label) }
            .filter { it.isNotEmpty() && it.length <= MAX_NAMED_OPTION_CHARS && it.none { c -> c.isDigit() || c == '?' } && !BannedWords.containedIn(it) }
            .distinctBy { it.lowercase() }
            .take(2)
            .map(::asClause)
        val two = if (named.size == 2) "Is either of these $LIMITS_MARK: ${named[0]}, or ${named[1]}?" else null
        val text = when {
            two != null && two.length <= MAX_TWO_OPTION_CHARS -> two
            named.isNotEmpty() -> "Is this $LIMITS_MARK: ${named[0]}?"
            else -> TEMPLATES.getValue(LIMITS)
        }
        return NextQuestion(LIMITS, text)
    }

    private fun mainClause(label: String): String =
        label.trim().let { l -> CLAUSE_END.find(l)?.let { l.substring(0, it.range.first) } ?: l }.trim().trimEnd('.', '!', ',', ':')

    /**
     * With no confident pick, whether [q] is asked instead of the advice. The limits question never is: it is the last, most
     * generic gap, and asking it in place of an answer read as a form (8 Nov, user). Then the coach reflects on the message.
     */
    fun asksInsteadOfAdvice(q: NextQuestion?): Boolean = q != null && q.id != LIMITS

    /** "Threaten to leave" reads as "threaten to leave" mid-sentence; a name or "I" at the start keeps its capital. */
    private fun asClause(label: String): String {
        val first = label.substringBefore(' ')
        val keep = first == "I" || first.drop(1).any { it.isUpperCase() }
        return if (keep) label else label.replaceFirstChar { it.lowercase() }
    }

    /** A negotiation: the deal frame exists, the relationship is one, or the user gave negotiation facts. */
    fun negotiation(view: Scenario, deal: DealFrame?): Boolean {
        val domain = RelationshipDomains.of(view.humanContext?.relationship, null, view.negotiationContext)
        if (view.humanContext?.relationship != null && domain.isClose) return false     // a loan to a brother is not a deal to walk away from (CP-I2)
        return deal != null || domain == RelationshipDomain.NEGOTIATION || view.negotiationContext?.hasContent() == true
    }

    /** The walk-away is known: the frame read one, or the user wrote one. */
    private fun walkAwayKnown(view: Scenario, deal: DealFrame?) = deal?.walkAway != null || view.negotiationContext?.reservationPoint?.isNotBlank() == true

    /**
     * The D3 rule: ask first instead of advising only in a negotiation whose recommended move carries a figure while no
     * walk-away is known (today's 4 Nov behaviour for numbers). Every other turn advises and may add [next] at the end.
     */
    fun askFirst(view: Scenario, deal: DealFrame?, pickLabel: String?): Boolean =
        pickLabel != null && negotiation(view, deal) && !walkAwayKnown(view, deal) && DealNumbers.figures(pickLabel).isNotEmpty()

    fun walkAwayQuestion(): NextQuestion = NextQuestion(WALK_AWAY, TEMPLATES.getValue(WALK_AWAY))

    /**
     * The one question for this turn, or null. [flips] are the gap ids whose answer would change the pick, computed by the caller
     * ([YOUR_FALLBACK], [THEIR_FALLBACK] via [com.drkkr.strategos.domain.human.FallbackShift]); the stress test adds [THEIR_REPLY]
     * when one of its flips is on the other person's side. [alreadyAsked] is the assistant's earlier text in this thread.
     */
    fun next(
        view: Scenario, gaps: List<IntakeGap>, stress: StressResult?, deal: DealFrame?,
        flips: Set<String> = emptySet(), alreadyAsked: List<String> = emptyList(), advised: Set<String> = emptySet()
    ): NextQuestion? {
        val you = IntakeChecklist.self(view)
        val other = view.players().firstOrNull { it.id != you?.id }
        val name = other?.name?.takeIf { it.isNotBlank() }
        fun q(id: String): NextQuestion? {
            if (id == LIMITS) return limitsQuestion(view, advised)
            val t = TEMPLATES.getValue(id)
            if ("{name}" in t && name == null) return null
            return NextQuestion(id, t.replace("{name}", name.orEmpty()))
        }
        val theirStressFlip = other != null && stress?.flips.orEmpty().any { it.stakeholder == other.name || it.stakeholder == other.id }
        val candidates = buildList {
            if (YOUR_FALLBACK in flips) add(YOUR_FALLBACK)
            if (THEIR_FALLBACK in flips) add(THEIR_FALLBACK)
            if (theirStressFlip) add(THEIR_REPLY)
            if (negotiation(view, deal) && !walkAwayKnown(view, deal)) add(WALK_AWAY)
            // who decides matters at work and in deals; in a close relationship the other person is the one deciding with you
            val close = RelationshipDomains.of(view.humanContext?.relationship, null, view.negotiationContext).isClose
            if (other != null && !close && gaps.any { it.id == "power" && it.about == other.name }) add(DECIDER)
            for (id in REST) if (gaps.any { it.id == id && (it.about == null || it.about == other?.name) }) add(id)
        }.distinct()
        return candidates.asSequence().mapNotNull(::q).firstOrNull { c ->
            alreadyAsked.none { it.contains(c.text) || (c.id == LIMITS && it.contains(LIMITS_MARK)) }
        }
    }
}
