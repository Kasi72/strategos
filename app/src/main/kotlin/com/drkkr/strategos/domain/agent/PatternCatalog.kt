package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario

/** One plain-English variable in a pattern's "What matters in your case" list. */
data class PatternVariable(val label: String, val value: String)

/** The learnable pattern for a project: a plain name, a short explainer, the case specifics, and a link. */
data class Pattern(
    val archetype: String,
    val name: String,
    val summary: String,
    val variables: List<PatternVariable>,
    val wikiUrl: String
)

/**
 * Turns the engine's behavioral-module id into something a person can learn from: a plain name, a
 * three-sentence explainer in the coach's voice, the variables that matter filled from their own
 * scenario, and a "read more" Wikipedia link. Shared by the Overview pattern card and the X-ray's
 * "Why this works" so the two never say different things. Pure; no android, no persistence.
 */
object PatternCatalog {

    private data class Entry(val name: String, val summary: String, val wikiUrl: String)

    private const val WIKI = "https://en.wikipedia.org/wiki/"
    private const val GENERIC = "pure-nash"

    // Keyed by the behavioral module id (EngineDirective.dynamicArchetype).
    private val ENTRIES: Map<String, Entry> = mapOf(
        "prisoners-dilemma" to Entry(
            "Trust trap",
            "You both gain if this works, but it only works if each of you trusts the other to carry their share. " +
                "Being wrong is costly, so the careful choice starts to look wiser than the generous one. " +
                "That pull toward playing safe is the trap — for both of you. " +
                "The dominant strategy here is to defect, yet mutual defection leaves you both worse off than if you had both cooperated — " +
                "that tension is the heart of the dilemma. Breaking it requires a commitment device, repeated interaction, or changed incentives.",
            WIKI + "Prisoner%27s_dilemma"
        ),
        "stag-hunt" to Entry(
            "Mutual trust needed",
            "You can both reach the better outcome, but only by going for it together. " +
                "If either of you plays it safe, the safe move wins and the bigger prize is lost. " +
                "The whole thing turns on whether each of you believes the other will commit. " +
                "Unlike the trust trap, there is no dominant strategy pulling you apart — only uncertainty about the other's resolve.",
            WIKI + "Stag_hunt"
        ),
        "chicken-game" to Entry(
            "Standoff over who gives way",
            "Neither of you wants to be the one who backs down. " +
                "If you both hold firm you both get hurt; if one yields, the other comes out ahead. " +
                "The risk is that each of you is betting the other will blink first. " +
                "A credible commitment to not yielding — made publicly and irreversibly — shifts the pressure entirely onto the other side. " +
                "Without that, the best available move is to randomise so the other side cannot predict and exploit you.",
            WIKI + "Chicken_(game)"
        ),
        "relational-repair" to Entry(
            "Relationship repair",
            "The bond matters more than winning this one exchange. " +
                "A small, genuine gesture can reset the tone, but only if it reads as sincere. " +
                "Leaving the distance too long lets it harden.",
            WIKI + "Reciprocity_(social_psychology)"
        ),
        "stakeholder-trust-dynamics" to Entry(
            "Trust building over time",
            "Trust here is built slowly and spent quickly. " +
                "Each promise you keep raises what you can ask for next time; each one you break costs more than it gained. " +
                "Playing the long game beats winning any single round.",
            WIKI + "Repeated_game"
        ),
        "ultimatum" to Entry(
            "Take it or leave it",
            "One of you sets the terms and the other can only accept or walk away. " +
                "An offer that feels unfair often gets refused even when refusing costs the refuser. " +
                "So the split that looks fair usually does better than the greedy one.",
            WIKI + "Ultimatum_game"
        ),
        "bayesian-game" to Entry(
            "Private information game",
            "One or more parties have private information — a type, a cost, a true preference — that the others can only guess. " +
                "Every move is a signal as well as an action: it reveals something about what you know, " +
                "and the other side will update their beliefs accordingly. " +
                "Strategy here means deciding not just what to do, but what to reveal, what to conceal, " +
                "and how to interpret what you observe. " +
                "Equilibrium is Bayesian: each party plays optimally given their beliefs about the others' private information.",
            WIKI + "Bayesian_game"
        ),
        "repeated-infinite" to Entry(
            "Long shadow of the future",
            "This interaction will repeat indefinitely, and both parties know it. " +
                "That changes everything: defecting today risks triggering retaliation across all future rounds, " +
                "making short-term gain potentially very costly. " +
                "The folk theorem says that if each party cares enough about the future — " +
                "the discount factor is above a critical threshold — then cooperation can be sustained as a stable equilibrium. " +
                "The shadow of the future is the enforcement mechanism: " +
                "it replaces the need for external contracts or courts.",
            WIKI + "Folk_theorem_(game_theory)"
        ),
        "negotiation" to Entry(
            "Deal zone",
            "There is a range of outcomes where both of you do better than walking away — the question is where in that range you end up. " +
                "The first number on the table anchors the whole conversation, so opening well matters more than it looks. " +
                "Your real power comes from your best outside option: know it cold, because it sets your floor.",
            WIKI + "Negotiation"
        ),
        "principal-agent" to Entry(
            "Delegation risk",
            "You are asking someone to act on your behalf, but their incentives are not perfectly aligned with yours. " +
                "They know more about what they are actually doing than you do, which gives them room to serve themselves. " +
                "The fix is to design the arrangement so doing the right thing for you is also the easy, rewarding choice for them.",
            WIKI + "Principal%E2%80%93agent_problem"
        ),
        "coordination" to Entry(
            "Find the obvious meeting point",
            "There is no real conflict here — both of you want to end up doing the same thing. " +
                "The only problem is picking which option becomes the shared one. " +
                "Whoever names a concrete option first usually sets the standard, because switching away from any agreed point costs both sides.",
            WIKI + "Coordination_game"
        ),
        "commitment-problem" to Entry(
            "Making your word stick",
            "One party's promise is not believed, because nothing stops them from changing course once circumstances shift. " +
                "The fix is a pre-commitment: a step taken now that makes backing out costly or impossible. " +
                "Paradoxically, limiting your own future freedom here is what makes the other side trust you enough to move.",
            WIKI + "Commitment_device"
        ),
        GENERIC to Entry(
            "A stable pattern",
            "There is a steady balance here that neither side has much reason to break on their own. " +
                "Each of you is doing the best you can given what the other is doing. " +
                "Changing the outcome usually means changing what's at stake, not just trying harder.",
            WIKI + "Nash_equilibrium"
        )
    )

    /** Trajectory stores archetypes as UPPER_SNAKE (e.g. CHICKEN_GAME); fold those onto the module ids. */
    private fun normalize(archetype: String): String =
        if (archetype in ENTRIES) archetype else archetype.trim().lowercase().replace('_', '-')

    /** The pattern for an archetype id, with variables filled from [scenario] when given. Null for an unknown/absent id. */
    fun of(archetype: String?, scenario: Scenario? = null): Pattern? {
        val key = archetype?.let(::normalize) ?: return null
        val e = ENTRIES[key] ?: return null
        return Pattern(key, e.name, e.summary, scenario?.let(::variables).orEmpty(), e.wikiUrl)
    }

    /** Convenience: read the directive's top archetype. */
    fun forDirective(directive: EngineDirective, scenario: Scenario? = null): Pattern? =
        of(directive.dynamicArchetype, scenario)

    /**
     * The handful of plain-English specifics the card shows: the user's limit, the other person's
     * main need, any third party who can sway it, and the doubt driving the tension. All optional —
     * only what the scenario actually records is shown.
     */
    fun variables(s: Scenario): List<PatternVariable> {
        val self = IntakeChecklist.self(s)
        val other = s.players().firstOrNull { it.id != self?.id }
        return buildList {
            (s.constraints.firstOrNull { it.kind == Constraint.Kind.USER_HARD } ?: s.constraints.firstOrNull())
                ?.label?.takeIf { it.isNotBlank() }?.let { add(PatternVariable("Your limit", cap(it))) }
            other?.goals?.firstOrNull { it.isNotBlank() }?.let { add(PatternVariable("${possessive(other.name)} need", cap(it))) }
            s.stakeholders.firstOrNull { it.gameRole == GameRole.INFLUENCER }
                ?.let { add(PatternVariable(it.name, cap(it.role.ifBlank { "can tip it either way" }))) }
            other?.fears?.firstOrNull { it.isNotBlank() }?.let { add(PatternVariable("The doubt", cap(it))) }
        }
    }

    private fun cap(t: String) = t.trim().replaceFirstChar { it.uppercase() }

    private fun possessive(name: String): String = when {
        name.equals("You", ignoreCase = true) -> "Your"
        name.endsWith("s", ignoreCase = true) -> "$name'"
        else -> "$name's"
    }
}
